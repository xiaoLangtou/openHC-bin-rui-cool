package io.github.gcbond.openhc.platform.media

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * 播放手机投过来的一路声音：PCM 直接写 AudioTrack；AAC 先经 MediaCodec 解成 PCM 再写。
 *
 * - **线程**：[queuePcm] / [queueAac] 可以在任意线程调用（通常是协议线程），只复制数据、入队，不阻塞；
 *   解码和阻塞的 AudioTrack.write 都在本类自己的线程上做，[Listener] 也在那个线程上回调。
 * - **缓冲**（参数见 [AudioTuning]）：先攒够起播门槛再开始放；队列长期高于削峰目标就逐块丢最旧的，突发积压超过上限
 *   一次丢回目标；输出快放空（欠载，包括手机暂停播放）时暂停，重新攒够再放。策略在 [AudioJitterQueue]。
 * - **输出**：按 [usage] 设音轨的用途（媒体、导航、提示与语音、通话，见 [AudioUsage.attributes]），音量跟系统对应的那一档走；
 *   设置里给媒体、导航指定了声道号的按号建（[AudioTuning.streamFor]，建音轨见 [PcmTrack]）；
 *   安卓 8.0 起按 [AudioTuning.lowLatency] 向系统要低延迟的输出路径。本类不申请音频焦点（见 [AudioFocusController]），
 *   失去焦点时由调用方用 [setVolume] 压低或静音。
 * - **延迟**：播放线程每隔一会儿算一次 [latency]（队列、解码、音轨已写未播、硬件四段），日志用。
 * - 一个对象对应一次数据会话里的一路：会话结束就 [close]，之后不再出声，线程退出、AudioTrack 和解码器释放。
 */
class AudioPlayer(
    val format: Format,
    private val listener: Listener = Listener { },
    val tuning: AudioTuning = AudioTuning(),
    val usage: AudioUsage = AudioUsage.MEDIA,
) : Closeable {

    enum class Encoding { PCM_16, AAC }

    /** 输入的格式。PCM 是 16 位小端交错（大端的由 [queuePcm] 转换）；AAC 是逐帧的 AAC-LC（可以带 ADTS 头）。 */
    class Format(val sampleRate: Int, val channels: Int, val encoding: Encoding) {
        init {
            require(sampleRate in 8_000..192_000) { "采样率超出范围: $sampleRate" }
            require(channels in 1..2) { "只支持单声道和双声道: $channels" }
        }

        val frameBytes: Int get() = 2 * channels

        override fun toString() = "${sampleRate}Hz/${channels}声道/$encoding"
    }

    sealed class Event {
        /**
         * AudioTrack 建好了：系统最小缓冲、申请的和实际拿到的缓冲（帧数来自 `getBufferSizeInFrames`），
         * 安卓 7.0 起的缓冲容量、安卓 8.0 起实际拿到的性能模式（拿不到的为 null）。
         */
        class TrackReady(
            val sampleRate: Int,
            val frameBytes: Int,
            val minimumBytes: Int,
            val requestedBytes: Int,
            val bufferFrames: Int,
            val capacityFrames: Int?,
            val lowLatencyRequested: Boolean,
            val performanceMode: Int?,
            /** 这一路实际走了哪条路（指定的声道号、导航那一路的属性，见 [PcmTrack.Built.route]），普通情况为空。 */
            val attributesNote: String = "",
        ) : Event() {
            private fun millis(frames: Long) = AudioTuning.millisFor(sampleRate, frames)

            override fun toString(): String {
                val capacity = capacityFrames?.let { "，容量 $it 帧（${millis(it.toLong())}ms）" } ?: ""
                val mode = when (performanceMode) {
                    null -> "系统低于安卓 8.0，没有性能模式"
                    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "低延迟"
                    AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "省电"
                    AudioTrack.PERFORMANCE_MODE_NONE -> "普通"
                    else -> "未知（$performanceMode）"
                }
                return "音轨就绪：系统最小缓冲 $minimumBytes 字节（${millis(minimumBytes.toLong() / frameBytes)}ms），" +
                    "申请 $requestedBytes 字节（${millis(requestedBytes.toLong() / frameBytes)}ms），" +
                    "实际 $bufferFrames 帧 = ${bufferFrames.toLong() * frameBytes} 字节（${millis(bufferFrames.toLong())}ms）$capacity；" +
                    "性能模式 $mode（${if (lowLatencyRequested) "申请了低延迟" else "没申请低延迟"}）" +
                    (if (attributesNote.isNotEmpty()) "；音轨属性 $attributesNote" else "")
            }
        }


        /** 第一次开始出声：起播时攒了多少毫秒。 */
        class Started(val bufferedMillis: Long) : Event() {
            override fun toString() = "开始出声（起播缓冲 ${bufferedMillis}ms）"
        }

        /** 输出放空，暂停并重新攒数据（手机暂停播放或数据断流）。[count] 是累计次数。 */
        class Starved(val count: Int) : Event() {
            override fun toString() = "缓冲放空，暂停并重新攒数据（累计 $count 次）"
        }

        /** 放空之后重新攒够、又开始出声。 */
        class Resumed(val bufferedMillis: Long) : Event() {
            override fun toString() = "重新开始出声（起播缓冲 ${bufferedMillis}ms）"
        }

        /** 积压超限丢了数据；[totalFrames] 是累计丢掉的帧数。 */
        class Dropped(val totalFrames: Long, val totalMillis: Long) : Event() {
            override fun toString() = "积压超限丢掉旧数据（累计 $totalFrames 帧，约 ${totalMillis}ms）"
        }

        /** 解码器报告的输出格式。 */
        class OutputFormat(val sampleRate: Int, val channels: Int) : Event() {
            override fun toString() = "解码输出 ${sampleRate}Hz/$channels 声道"
        }

        /** 出错：AudioTrack 或解码器建不起来、写失败等。[fatal] 为 true 时之后不再出声。 */
        class Failed(val detail: String, val fatal: Boolean) : Event() {
            override fun toString() = (if (fatal) "播放失败，之后不再出声：" else "播放出错：") + detail
        }
    }

    fun interface Listener {
        fun onAudioPlayerEvent(event: Event)
    }

    private val queue = AudioJitterQueue(
        startFrames = tuning.startFrames(format.sampleRate),
        targetFrames = tuning.targetFrames(format.sampleRate),
        maxFrames = tuning.maxFrames(format.sampleRate),
        trimHoldMillis = tuning.trimHoldMillis.toLong(),
        trimSpacingMillis = tuning.trimSpacingMillis.toLong(),
        trimStepFrames = tuning.trimStepFrames(format.sampleRate),
        startWindowMillis = tuning.startWindowMillis.toLong(),
    )

    @Volatile
    private var closed = false

    private val thread = Thread({ run() }, "OpenHC-AudioPlayer-${usage.name.lowercase()}")

    /** 调用方要的音量（0～1），播放线程发现变了就设到音轨上。 */
    @Volatile
    private var requestedVolume = 1f

    // ---------------- 以下只在播放线程上访问 ----------------
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0
    private var trackFrameBytes = 4
    private var framesWritten = 0L
    private var playing = false
    private var started = false
    private var dead = false
    private var decoder: MediaCodec? = null
    private var decoderErrors = 0
    private var presentationUs = 0L
    private var pcm = ByteArray(PCM_SCRATCH)
    private val info = MediaCodec.BufferInfo()
    private var reportedDropped = 0L
    private var appliedVolume = 1f
    private val timestamp = AudioTimestamp()
    private var latencyAt = 0L

    /** 送进解码器、解出来的帧数：差值就是还在解码器里的。 */
    private var decoderInFrames = 0L
    private var decoderOutFrames = 0L

    /** 最近一次算出的延迟拆分（播放线程约每 250ms 更新一次）；还没开始放时为 null。 */
    @Volatile
    var latency: AudioLatency? = null
        private set

    /** AudioTrack 自己统计的欠载次数（安卓 7.0 起才有，之前为 null）。 */
    @Volatile
    var trackUnderruns: Int? = null
        private set

    init {
        thread.isDaemon = true
        thread.start()
    }

    /** 已经入队、还没写进 AudioTrack 的毫秒数（粗略，给日志用）。 */
    val queuedMillis: Long get() = queue.bufferedFrames * 1000 / format.sampleRate

    /** 累计放空次数。 */
    val starvations: Int get() = queue.starvations

    /** 稳态削峰丢掉的块数和毫秒数（累计）。 */
    val trimmedChunks: Int get() = queue.trimmedChunks
    val trimmedMillis: Long get() = queue.trimmedFrames * 1000 / format.sampleRate

    /** 起播窗口里压回目标丢掉的毫秒数（累计）。 */
    val startTrimmedMillis: Long get() = queue.startTrimmedFrames * 1000 / format.sampleRate

    /** 突发积压超过上限时丢掉的毫秒数（累计）。 */
    val droppedMillis: Long get() = queue.droppedFrames * 1000 / format.sampleRate

    /** 放一段 PCM（[bigEndian] 为 true 时按大端的 16 位采样读）。复制后入队，不阻塞。尾部不足一帧的字节丢掉。 */
    fun queuePcm(data: ByteArray, offset: Int, length: Int, bigEndian: Boolean) {
        check(format.encoding == Encoding.PCM_16) { "这个播放器不是 PCM 的" }
        if (closed) return
        val copy = PcmBytes.copyLittleEndian(data, offset, length, format.frameBytes, bigEndian)
        if (copy.isEmpty()) return
        queue.offer(copy, copy.size / format.frameBytes)
    }

    /** 放一个 AAC 帧（一个 RTP 包的载荷，可以带 ADTS 头）。复制后入队，不阻塞。 */
    fun queueAac(data: ByteArray, offset: Int, length: Int) {
        check(format.encoding == Encoding.AAC) { "这个播放器不是 AAC 的" }
        if (closed || length <= 0) return
        queue.offer(data.copyOfRange(offset, offset + length), AacConfig.SAMPLES_PER_FRAME)
    }

    /**
     * 这一路的音量（0 静音、1 原样），用来在失去音频焦点时压低或静音。任意线程调用，不阻塞；
     * 播放线程在下一块数据前设到音轨上（音轨重建后也沿用）。
     */
    fun setVolume(volume: Float) {
        requestedVolume = volume.coerceIn(0f, 1f)
    }

    /**
     * 停止：丢掉还没写进 AudioTrack 的数据，播放线程随即停下、释放 AudioTrack 和解码器。不阻塞（可以在协议线程上调），
     * 可以重复调用。要等资源真正释放完用 [awaitReleased]。
     */
    override fun close() {
        if (closed) return
        closed = true
        queue.close()
    }

    /** 等播放线程退出（资源已释放），最多 [timeoutMillis]。返回是否已经退出。 */
    fun awaitReleased(timeoutMillis: Long): Boolean {
        if (Thread.currentThread() === thread) return false
        try {
            thread.join(timeoutMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    // ---------------- 播放线程 ----------------

    private fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (e: RuntimeException) {
            // 拿不到高优先级也照样放。
        }
        try {
            ensureTrack(format.sampleRate, format.channels)
            while (!closed && !dead) {
                val chunk = queue.take(POLL_MILLIS)
                applyVolume(false)
                reportDrops()
                if (chunk == null) {
                    // 正在放时把解码器里晚出来的帧也写进去；放空暂停期间不取，免得提前把音轨拉起来。
                    if (playing) decoder?.let { drainQuietly(it, 0L) }
                    checkStarved()
                } else {
                    when (format.encoding) {
                        Encoding.PCM_16 -> writePcm(chunk.data, 0, chunk.data.size)
                        Encoding.AAC -> decode(chunk.data)
                    }
                }
                // 这一块处理完再算：它已经在解码器或音轨里，不会漏算。
                refreshLatency()
            }
        } catch (e: Exception) {
            emit(Event.Failed("${e.javaClass.simpleName}：${e.message}", fatal = true))
        } finally {
            release()
        }
    }

    private fun ensureTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val current = track
        if (current != null && trackRate == sampleRate && trackChannels == channels) return current
        releaseTrack()
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = 2 * channels
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            fatal("系统不支持 ${sampleRate}Hz/$channels 声道的 16 位 PCM（getMinBufferSize=$minimum）")
            return null
        }
        val requested = tuning.trackBytes(sampleRate, frameBytes, minimum)
        val built = try {
            PcmTrack.build(usage, tuning.streamFor(usage), sampleRate, mask, requested, tuning.lowLatency)
        } catch (e: Exception) {
            fatal("建不起 AudioTrack：${e.javaClass.simpleName}：${e.message}")
            return null
        }
        val created = built.track
        track = created
        trackRate = sampleRate
        trackChannels = channels
        trackFrameBytes = frameBytes
        framesWritten = 0
        playing = false
        latency = null
        applyVolume(true)
        emit(
            Event.TrackReady(
                sampleRate = sampleRate,
                frameBytes = frameBytes,
                minimumBytes = minimum,
                requestedBytes = requested,
                bufferFrames = created.bufferSizeInFrames,
                capacityFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) created.bufferCapacityInFrames else null,
                lowLatencyRequested = built.lowLatencyRequested,
                performanceMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) created.performanceMode else null,
                attributesNote = built.route,
            ),
        )
        return created
    }

    /**
     * 约每 250ms 算一次延迟拆分。硬件部分用 `getTimestamp`：它给出某一时刻正在输出的帧，按经过的时间推到此刻；
     * 没在播放或拿不到时间戳就记为未知，不估。
     */
    private fun refreshLatency() {
        val now = System.nanoTime()
        if (now - latencyAt < LATENCY_REFRESH_NANOS) return
        latencyAt = now
        val t = track ?: return
        if (!started) return
        val head = t.playbackHeadPosition.toLong() and UINT32
        val stamped = playing && t.getTimestamp(timestamp)
        latency = AudioLatency.estimate(
            sampleRate = trackRate,
            queueFrames = queuedFramesAtTrackRate(),
            decoderFrames = decoderInFrames - decoderOutFrames,
            framesWritten = framesWritten,
            headPosition = head,
            timestampFrame = if (stamped) timestamp.framePosition else null,
            timestampNanos = if (stamped) timestamp.nanoTime else 0L,
            nowNanos = System.nanoTime(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) trackUnderruns = t.underrunCount
    }

    /** 队列里的帧按输入采样率计；解码输出的采样率和输入不同时换算到音轨的采样率。 */
    private fun queuedFramesAtTrackRate(): Long {
        val frames = queue.bufferedFrames
        return if (trackRate == format.sampleRate) frames else frames * trackRate / format.sampleRate
    }

    /** 写一段 16 位小端 PCM；没在放就先开始放（数据已经攒够了才会到这里）。 */
    private fun writePcm(data: ByteArray, offset: Int, length: Int) {
        val t = track ?: return
        if (!playing) {
            t.play()
            playing = true
            val buffered = (queue.bufferedFrames + length / trackFrameBytes) * 1000 / trackRate
            if (!started) {
                started = true
                emit(Event.Started(buffered))
            } else {
                emit(Event.Resumed(buffered))
            }
        }
        var done = 0
        while (done < length && !closed) {
            val n = t.write(data, offset + done, length - done, AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                fatal("AudioTrack.write 返回 $n")
                return
            }
            if (n == 0) break
            done += n
        }
        framesWritten += done / trackFrameBytes
    }

    /** 调用方要的音量变了（或刚建好音轨，[force]）就设到音轨上。 */
    private fun applyVolume(force: Boolean) {
        val t = track ?: return
        val volume = requestedVolume
        if (!force && volume == appliedVolume) return
        try {
            t.setVolume(volume)
            appliedVolume = volume
        } catch (e: IllegalStateException) {
            // 音轨刚好在释放：下一块数据时再设。
        }
    }

    /** 队列空了：AudioTrack 里也快放完时暂停，等重新攒够再放。 */
    private fun checkStarved() {
        val t = track ?: return
        if (!playing || queue.bufferedFrames > 0) return
        val head = t.playbackHeadPosition.toLong() and UINT32
        val left = (framesWritten - head) and UINT32
        if (left > trackRate.toLong() * LOW_WATER_MILLIS / 1000) return
        t.pause()
        playing = false
        queue.onStarved()
        emit(Event.Starved(queue.starvations))
    }

    private fun reportDrops() {
        val dropped = queue.droppedFrames
        if (dropped == reportedDropped) return
        reportedDropped = dropped
        emit(Event.Dropped(dropped, dropped * 1000 / format.sampleRate))
    }

    // ---------------- AAC ----------------

    private fun decode(unit: ByteArray) {
        val adts = AacConfig.parseAdts(unit, 0, unit.size) ?: scanAdts(unit)
        val from = adts?.headerLength ?: 0
        val to = adts?.frameLength ?: unit.size
        if (to <= from) return
        val codec = decoder ?: createDecoder(adts) ?: return
        try {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val input: ByteBuffer = codec.getInputBuffer(index) ?: return
                input.clear()
                input.put(unit, from, to - from)
                codec.queueInputBuffer(index, 0, to - from, presentationUs, 0)
                presentationUs += AacConfig.SAMPLES_PER_FRAME * 1_000_000L / format.sampleRate
                decoderInFrames += AacConfig.SAMPLES_PER_FRAME
            }
            // 刚送进去的这一帧给解码器几毫秒解出来，马上写进音轨；否则它要在解码器里等下一包，起播缓冲白白少一截。
            drain(codec, OUTPUT_WAIT_US)
        } catch (e: Exception) {
            decoderFailed("解码出错：${e.javaClass.simpleName}：${e.message}")
        }
    }

    private fun drainQuietly(codec: MediaCodec, waitUs: Long) {
        try {
            drain(codec, waitUs)
        } catch (e: Exception) {
            decoderFailed("解码出错：${e.javaClass.simpleName}：${e.message}")
        }
    }

    private fun createDecoder(adts: AacConfig.Adts?): MediaCodec? {
        if (decoderErrors >= MAX_DECODER_ERRORS) return null
        val csd = adts?.audioSpecificConfig() ?: AacConfig.audioSpecificConfig(format.sampleRate, format.channels)
        val rate = adts?.sampleRate ?: format.sampleRate
        val channels = if (adts != null && adts.channelConfig in 1..2) adts.channelConfig else format.channels
        return try {
            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
            decoder = codec
            codec
        } catch (e: Exception) {
            decoderFailed("建不起 AAC 解码器：${e.javaClass.simpleName}：${e.message}")
            null
        }
    }

    /** 取出解码器里已经解好的 PCM 写进音轨。第一次最多等 [waitUs]，拿到一块之后不再等。 */
    private fun drain(codec: MediaCodec, waitUs: Long) {
        var wait = waitUs
        while (!closed) {
            val index = codec.dequeueOutputBuffer(info, wait)
            when {
                index >= 0 -> {
                    wait = 0L
                    if (info.size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (pcm.size < info.size) pcm = ByteArray(info.size)
                            output.position(info.offset)
                            output.get(pcm, 0, info.size)
                            decoderOutFrames += info.size / trackFrameBytes
                            writePcm(pcm, 0, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    emit(Event.OutputFormat(rate, channels))
                    if (channels in 1..2) ensureTrack(rate, channels) else fatal("解码输出 $channels 声道，不支持")
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                // 其余（老接口的输出缓冲变化）不用处理。
            }
        }
    }

    private fun decoderFailed(detail: String) {
        decoderErrors++
        releaseDecoder()
        decoderInFrames = 0
        decoderOutFrames = 0
        val fatal = decoderErrors >= MAX_DECODER_ERRORS
        emit(Event.Failed(detail, fatal))
        if (fatal) dead = true
    }

    // ---------------- 收尾 ----------------

    private fun fatal(detail: String) {
        dead = true
        emit(Event.Failed(detail, fatal = true))
    }

    private fun release() {
        releaseDecoder()
        releaseTrack()
    }

    private fun releaseDecoder() {
        val codec = decoder ?: return
        decoder = null
        try {
            codec.stop()
        } catch (e: Exception) {
            // 已经坏掉的解码器 stop 也可能抛，照样释放。
        }
        try {
            codec.release()
        } catch (e: Exception) {
            // 忽略。
        }
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        playing = false
        try {
            t.pause()
            t.flush()
        } catch (e: Exception) {
            // 忽略。
        }
        t.release()
    }

    private fun emit(event: Event) {
        try {
            listener.onAudioPlayerEvent(event)
        } catch (e: Exception) {
            // 回调自己的问题不影响播放。
        }
    }

    private companion object {
        const val POLL_MILLIS = 10L
        const val LATENCY_REFRESH_NANOS = 250_000_000L

        /** AudioTrack 里剩下不到这么多毫秒、队列又空着，就算放空。 */
        const val LOW_WATER_MILLIS = 10L
        const val INPUT_TIMEOUT_US = 10_000L

        /** 送进一帧后等它解出来的上限。 */
        const val OUTPUT_WAIT_US = 5_000L
        const val MAX_DECODER_ERRORS = 5
        const val PCM_SCRATCH = 16 * 1024
        const val UINT32 = 0xFFFF_FFFFL

        /** ADTS 不在载荷开头时最多往前找几个字节：有的封装在 ADTS 前垫了固定长度的头。 */
        const val MAX_ADTS_PREFIX = 16

        /**
         * 载荷开头不是 ADTS 时，在前 [MAX_ADTS_PREFIX] 个字节里找 ADTS 并把它前面那段垫头跳过；
         * 要求找出的帧几乎吃满整个载荷，防止裸 AAC 数据里碰巧出现 0xFFF1 被误认。找不到返回 null（按裸 AAC 放）。
         */
        fun scanAdts(data: ByteArray): AacConfig.Adts? {
            for (p in 1 until minOf(MAX_ADTS_PREFIX, data.size)) {
                val adts = AacConfig.parseAdts(data, p, data.size - p) ?: continue
                if (adts.frameLength >= data.size - p - MAX_ADTS_PREFIX) return adts
            }
            return null
        }
    }
}
