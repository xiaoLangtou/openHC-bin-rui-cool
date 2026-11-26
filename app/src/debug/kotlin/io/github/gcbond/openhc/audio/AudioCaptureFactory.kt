package io.github.gcbond.openhc.audio

import android.content.Context
import android.util.Log
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** 调试构建：音频抓包写到应用私有目录 `files/audio-capture/`。 */
internal object AudioCaptureFactory {
    fun create(context: Context, attemptId: Int): AudioCapture =
        FileAudioCapture(File(context.filesDir, FileAudioCapture.DIR_NAME), attemptId)
}

/**
 * 每次数据会话写一份 `.txt`（状态、分帧识别结果、数据端口最早的 32 字节），再写一份 `.bin`
 * （数据端口收到的原始字节，最多 [LIMIT_BYTES]）。没有归属会话的消息（手机的焦点消息、声明、PCM 判定等）
 * 写进这次连接的 `.txt`。文件名：`时间-c连接序号-s数据会话.txt`、`…-data.bin`。目录里只留最新的 [KEEP_FILES] 个文件。
 *
 * 协议线程上只做复制和计数，写盘在自己的线程上。
 */
private class FileAudioCapture(private val dir: File, private val attemptId: Int) : AudioCapture {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenHC-AudioCapture").also { it.isDaemon = true }
    }
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val startNanos = System.nanoTime()

    /** 协议线程上：每个「会话/端口」已经交给写盘线程的字节数。 */
    private val queued = HashMap<String, Long>()

    @Volatile
    private var closed = false

    // 以下只在写盘线程上访问。
    private val texts = HashMap<String, Writer>()
    private val bins = HashMap<String, OutputStream>()
    private var broken = false

    init {
        submit {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("建不了目录 ${dir.path}")
            prune()
        }
    }

    override fun onEvent(event: AudioEvent) {
        if (closed) return
        val stream = streamOf(event)
        val line = "+${elapsedMillis()}ms $event"
        submit { text(stream?.dataSessionId).apply { write(line); write("\n"); flush() } }
    }

    override fun onRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        val key = stream.dataSessionId
        val already = queued[key] ?: 0L
        if (already >= LIMIT_BYTES) return
        val take = minOf(length.toLong(), LIMIT_BYTES - already).toInt()
        queued[key] = already + take
        val copy = data.copyOfRange(offset, offset + take)
        val first = already == 0L
        val full = already + take >= LIMIT_BYTES
        val at = elapsedMillis()
        submit {
            val text = text(stream.dataSessionId)
            if (first) {
                text.write("+${at}ms 数据端口最早的 ${minOf(copy.size, HEAD_BYTES)} 个原始字节：" +
                    copy.copyOf(minOf(copy.size, HEAD_BYTES)).toHex() + "\n")
                text.flush()
            }
            bin(stream.dataSessionId).write(copy)
            if (full) {
                text.write("+${at}ms 数据端口已抓满 ${LIMIT_BYTES / MIB}MiB，之后的字节不再保存\n")
                text.flush()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // 关完文件再置位：关闭前已经过了检查、排在后面的写任务不会再打开新文件。
            executor.execute {
                closeFiles()
                broken = true
            }
        } catch (e: RejectedExecutionException) {
            return
        }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) Log.w(TAG, "抓包没能按时写完")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------- 写盘线程 ----------------

    private fun text(dataSessionId: String?): Writer = texts.getOrPut(dataSessionId ?: "") {
        val file = File(dir, baseName(dataSessionId) + ".txt")
        Log.i(TAG, "[$attemptId] 音频抓包记录写到 ${dir.name}/${file.name}")
        file.bufferedWriter(Charsets.UTF_8)
    }

    private fun bin(dataSessionId: String): OutputStream = bins.getOrPut(dataSessionId) {
        val file = File(dir, "${baseName(dataSessionId)}-data.bin")
        Log.i(TAG, "[$attemptId] 音频原始字节写到 ${dir.name}/${file.name}")
        FileOutputStream(file).buffered()
    }

    private fun baseName(dataSessionId: String?): String =
        "$stamp-c$attemptId" + (dataSessionId?.let { "-s" + it.replace(Regex("[^0-9A-Za-z-]"), "_") } ?: "")

    private fun closeFiles() {
        for (out in bins.values) quietly { out.close() }
        for (out in texts.values) quietly { out.close() }
        bins.clear()
        texts.clear()
    }

    /** 只留最新的一些文件，免得抓包把空间占满。 */
    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        for (old in files.drop(KEEP_FILES)) old.delete()
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                if (broken) return@execute
                try {
                    task()
                } catch (e: IOException) {
                    broken = true
                    Log.w(TAG, "[$attemptId] 音频抓包写不下去了，之后不再抓：${e.message}")
                    closeFiles()
                }
            }
        } catch (e: RejectedExecutionException) {
            // 已经关闭。
        }
    }

    private fun elapsedMillis(): Long = (System.nanoTime() - startNanos) / 1_000_000

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            // 关闭时的错误没有意义。
        }
    }

    private fun streamOf(event: AudioEvent): AudioStreamInfo? = when (event) {
        is AudioEvent.Requested -> event.stream
        is AudioEvent.SetupSent -> event.stream
        is AudioEvent.ListenFailed -> event.stream
        is AudioEvent.SetupAnswered -> event.stream
        is AudioEvent.PeerConnected -> event.stream
        is AudioEvent.PeerClosed -> event.stream
        is AudioEvent.FramingDetected -> event.stream
        is AudioEvent.FramingUnknown -> event.stream
        is AudioEvent.FirstPacket -> event.stream
        is AudioEvent.StreamRouted -> event.stream
        is AudioEvent.Note -> event.stream
        is AudioEvent.Ended -> event.stream
        is AudioEvent.PhoneFocus, is AudioEvent.SpeakerDeclared, is AudioEvent.CarFocusSent, is AudioEvent.CodecVerdict,
        is AudioEvent.TransportFallback,
        is AudioEvent.TransportRecovered,
        is AudioEvent.PhoneCallState, is AudioEvent.PhoneVoiceState -> null
    }

    companion object {
        const val DIR_NAME = "audio-capture"
        const val TAG = "OpenHC-Audio"
        private const val MIB = 1024L * 1024
        const val LIMIT_BYTES = 8 * MIB
        const val KEEP_FILES = 40
        const val HEAD_BYTES = 32
        const val CLOSE_WAIT_SECONDS = 2L
    }
}
