package io.github.gcbond.openhc.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.core.dmsdp.audio.MicCodec
import io.github.gcbond.openhc.core.dmsdp.audio.MicEvent
import io.github.gcbond.openhc.core.dmsdp.audio.MicParameters
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.media.MicAacEncoder
import io.github.gcbond.openhc.platform.media.MicCapture
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 录音权限。 */
internal object MicPermission {
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/**
 * 一次连接的车机麦克风入口：接会话的麦克风回调（在协议线程上），手机连上麦克风数据端口时开 [MicCapture]，
 * 按这次协商的格式交声音（PCM 直接交，AAC 先经 [MicAacEncoder] 编码），会话结束或手机拆除时停。日志标签 `OpenHC-Mic`。
 *
 * 开、关采集在自己的单线程上做（开 AudioRecord 要几十毫秒，不能卡协议线程）；采到的每块声音复制一份，经 [attach]
 * 给的投递函数交到协议线程上送出。协议线程处理不过来时（在途超过 [MAX_IN_FLIGHT] 块）丢最新的块、计数，内存有上限。
 */
internal class MicInput(context: Context, private val attemptId: Int) : MicStreamListener, Closeable {
    private val app: Context = context.applicationContext ?: context
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "OpenHC-Mic-$attemptId") }

    /** 投递函数：把一块声音交到协议线程上送出，送完调第二个参数。 */
    @Volatile
    private var submit: ((ByteArray, () -> Unit) -> Unit)? = null

    @Volatile
    private var closed = false

    // 以下只在 worker 线程上访问。
    private var capture: MicCapture? = null
    private var encoder: MicAacEncoder? = null
    private var current: MicParameters? = null

    private val inFlight = AtomicInteger()
    private val sent = AtomicLong()
    private val dropped = AtomicLong()

    /** 会话建好之后交进投递函数（会话要先拿到本对象当回调，所以分两步）。 */
    fun attach(submit: (ByteArray, () -> Unit) -> Unit) {
        this.submit = submit
    }

    override fun onMicEvent(event: MicEvent) {
        Log.i(TAG, "[$attemptId] $event")
        when (event) {
            is MicEvent.Started -> post { start(event.parameters) }
            is MicEvent.ParametersChanged -> post { if (capture != null) start(event.parameters) }
            is MicEvent.Stopped -> post { stop("麦克风会话结束（${event.reason.label}）") }
            else -> Unit
        }
    }

    /** 连接结束：停采集、关线程。只在调度线程上调用（执行器已经停了，不会再有回调）。 */
    override fun close() {
        if (closed) return
        closed = true
        post { stop("连接结束") }
        worker.shutdown()
        if (!worker.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) Log.w(TAG, "[$attemptId] 麦克风线程没有按时退出")
    }

    private fun post(task: () -> Unit) {
        try {
            worker.execute {
                try {
                    task()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "[$attemptId] 麦克风处理出错：${e.javaClass.simpleName}：${e.message}")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 已经关了。
        }
    }

    private fun start(parameters: MicParameters) {
        stop(null)
        if (closed) return
        if (!MicPermission.granted(app)) {
            Log.w(TAG, "[$attemptId] 没有录音权限，车机麦克风不开（手机那头会听不到）")
            return
        }
        val format = try {
            MicCapture.Format(parameters.sampleRate, parameters.channels)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "[$attemptId] 手机要的麦克风格式开不了：${e.message}")
            return
        }
        val sink: MicCapture.Sink = when (parameters.codec) {
            MicCodec.PCM -> MicCapture.Sink { data, length -> send(data.copyOf(length)) }
            MicCodec.AAC -> {
                val created = try {
                    MicAacEncoder(parameters.sampleRate, parameters.channels) { frame -> send(frame) }
                } catch (e: Exception) {
                    Log.w(TAG, "[$attemptId] 建不了 AAC 编码器（${e.javaClass.simpleName}：${e.message}），车机麦克风不开")
                    return
                }
                encoder = created
                Log.i(TAG, "[$attemptId] 手机要 AAC，建编码器：$created")
                MicCapture.Sink { data, length -> created.encode(data, length) }
            }
        }
        val created = MicCapture(format, sink) { event -> Log.i(TAG, "[$attemptId] $event") }
        if (!created.start()) {
            encoder?.close()
            encoder = null
            return
        }
        capture = created
        current = parameters
        Log.i(TAG, "[$attemptId] 开始采集：$format，交 ${parameters.codec.label}")
    }

    private fun stop(why: String?) {
        val running = capture ?: return
        capture = null
        // 先停采集（等采集线程退出），再关编码器：编码只在采集线程上调用。
        running.close()
        encoder?.let {
            Log.i(TAG, "[$attemptId] AAC 编码：送入 ${it.chunks} 块、丢 ${it.dropped} 块、交出 ${it.frames} 帧")
            it.close()
        }
        encoder = null
        if (why != null) {
            Log.i(TAG, "[$attemptId] 停止采集：$why；共交出 ${sent.get()} 块，协议线程忙丢掉 ${dropped.get()} 块（${current ?: ""}）")
        }
        current = null
    }

    /** 采集线程上：投递到协议线程。在途太多就丢这一块。 */
    private fun send(bytes: ByteArray) {
        val target = submit ?: return
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            if (dropped.incrementAndGet() % DROP_LOG_EVERY == 1L) Log.w(TAG, "[$attemptId] 协议线程处理不过来，丢掉麦克风声音（累计 ${dropped.get()} 块）")
            return
        }
        inFlight.incrementAndGet()
        target(bytes) { inFlight.decrementAndGet() }
        val count = sent.incrementAndGet()
        if (count % STATS_EVERY == 0L) Log.i(TAG, "[$attemptId] 已交出 $count 块麦克风声音（丢 ${dropped.get()}）")
    }

    private companion object {
        const val TAG = "OpenHC-Mic"

        /** 在途上限：25 块约 0.5 秒（PCM 20ms 一块）。 */
        const val MAX_IN_FLIGHT = 25
        const val DROP_LOG_EVERY = 50L

        /** 每这么多块记一行统计（PCM 约 10 秒）。 */
        const val STATS_EVERY = 500L
        const val CLOSE_WAIT_MILLIS = 2_000L
    }
}
