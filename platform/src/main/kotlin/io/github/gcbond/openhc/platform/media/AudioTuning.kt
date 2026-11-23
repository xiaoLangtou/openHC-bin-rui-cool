package io.github.gcbond.openhc.platform.media

/**
 * 播放缓冲的参数（纯逻辑，可在电脑上测）：毫秒数，以及按采样率换算出的帧数。
 *
 * 默认按有线投屏取低值：攒约 80ms 起播，AudioTrack 自己的缓冲约 40ms（不小于系统最小值），队列长期高于 100ms 就削峰。
 * （起播 60ms、削峰 80ms 在真机上会偶尔放空，所以各加了 20ms。）
 * 「声音延后」[extraDelayMillis] 加在起播门槛、削峰目标和硬上限上：在最低配置之上多攒这么多，声音整体往后挪。
 * 无线垫底 [cushionMillis] 同样加在这三处，见 [withCushion]。
 * 另外带着媒体、导航两路各放到哪个声道（[AudioStreamChoice]，默认自动），建音轨时用（[streamFor]）。
 */
class AudioTuning(
    /** 攒够这么多才开始放（欠载后重新起播也用它）。 */
    val startMillis: Int = 80,
    /** 稳态削峰的目标：每次取走一块后队列仍高于它、持续 [trimHoldMillis]，就逐块丢最旧的压回来。 */
    val targetMillis: Int = 100,
    /** 硬上限：一下子积压超过它（例如网络卡住后一口气补发）就立刻丢到 [targetMillis]。 */
    val maxMillis: Int = 300,
    /** 队列高于目标要持续这么久才开始削峰。 */
    val trimHoldMillis: Int = 2_000,
    /** 两次削峰至少隔这么久，免得听起来断一大截。 */
    val trimSpacingMillis: Int = 200,
    /** 每次削峰最多丢约这么多（至少一块：AAC 一块约 21ms，PCM 实测一包约 5ms）。 */
    val trimStepMillis: Int = 20,
    /** 起播（含放空后重新起播）后这么久之内，队列高于削峰目标就直接压回目标：手机开播时常一口气补发一批。 */
    val startWindowMillis: Int = 1_000,
    /** AudioTrack 自己的缓冲，实际不小于系统给的最小值。 */
    val trackMillis: Int = 40,
    /** 声音延后：起播门槛、削峰目标和硬上限都加这么多。 */
    val extraDelayMillis: Int = 0,
    /** 无线垫底：和声音延后一样加在三处，由连接方式决定，不归设置管（[withCushion]）。 */
    val cushionMillis: Int = 0,
    /** 安卓 8.0 起向系统要低延迟的输出路径（更低版本没有这个选项，照常建）。 */
    val lowLatency: Boolean = true,
    /** 媒体那一路放到哪个声道（[AudioStreamChoice]）。 */
    val mediaStream: Int = AudioStreamChoice.AUTO,
    /** 导航播报那一路放到哪个声道（[AudioStreamChoice]）。 */
    val navigationStream: Int = AudioStreamChoice.AUTO,
) {
    init {
        require(startMillis > 0 && startMillis <= targetMillis && targetMillis <= maxMillis) { "要求 0 < 起播 ≤ 削峰目标 ≤ 上限" }
        require(trimHoldMillis >= 0 && trimSpacingMillis >= 0 && startWindowMillis >= 0) { "削峰的时间不能为负" }
        require(trimStepMillis > 0) { "每次削峰的时长必须为正" }
        require(trackMillis > 0) { "音轨缓冲必须为正" }
        require(extraDelayMillis in 0..MAX_EXTRA_DELAY_MILLIS) { "声音延后超出范围: $extraDelayMillis" }
        require(cushionMillis in 0..MAX_EXTRA_DELAY_MILLIS) { "无线垫底超出范围: $cushionMillis" }
        require(AudioStreamChoice.normalize(mediaStream) == mediaStream) { "音乐声道超出范围: $mediaStream" }
        require(AudioStreamChoice.normalize(navigationStream) == navigationStream) { "导航声道超出范围: $navigationStream" }
    }

    /** 这一路放到哪个声道：只有媒体和导航能指定，提示音、通话、铃声一律自动。 */
    fun streamFor(usage: AudioUsage): Int = when (usage) {
        AudioUsage.MEDIA -> mediaStream
        AudioUsage.NAVIGATION -> navigationStream
        AudioUsage.ASSISTANT, AudioUsage.CALL, AudioUsage.RINGTONE -> AudioStreamChoice.AUTO
    }

    fun startFrames(sampleRate: Int): Int = framesFor(sampleRate, startMillis + added)

    fun targetFrames(sampleRate: Int): Int = framesFor(sampleRate, targetMillis + added)

    fun maxFrames(sampleRate: Int): Int = framesFor(sampleRate, maxMillis + added)

    private val added: Int get() = extraDelayMillis + cushionMillis

    /**
     * 同样的参数再垫 [millis]。无线投屏时车机 WiFi 扫描要离开投屏所在的信道，一次停 0.1～0.3 秒（2026-11 比亚迪实车，
     * 系统应用要定位时触发）；声音走 TCP，数据不丢、只是晚到后一口气补来，多攒这么多就能扛过去不放空。
     */
    fun withCushion(millis: Int): AudioTuning = AudioTuning(
        startMillis, targetMillis, maxMillis, trimHoldMillis, trimSpacingMillis, trimStepMillis, startWindowMillis,
        trackMillis, extraDelayMillis, millis, lowLatency, mediaStream, navigationStream,
    )

    fun trimStepFrames(sampleRate: Int): Int = framesFor(sampleRate, trimStepMillis)

    /** 向系统申请的 AudioTrack 缓冲字节数：约 [trackMillis]，不小于系统最小值 [minimumBytes]。 */
    fun trackBytes(sampleRate: Int, frameBytes: Int, minimumBytes: Int): Int =
        maxOf(minimumBytes, framesFor(sampleRate, trackMillis) * frameBytes)

    override fun toString() = "起播 ${startMillis + added}ms、削峰目标 ${targetMillis + added}ms、" +
        "上限 ${maxMillis + added}ms、音轨约 ${trackMillis}ms、声音延后 +${extraDelayMillis}ms、" +
        (if (cushionMillis > 0) "无线垫底 +${cushionMillis}ms、" else "") +
        "低延迟输出${if (lowLatency) "开" else "关"}、音乐声道 ${AudioStreamChoice.label(mediaStream)}、" +
        "导航声道 ${AudioStreamChoice.label(navigationStream)}"

    companion object {
        const val MAX_EXTRA_DELAY_MILLIS = 1_000

        /** 毫秒换算成帧（向下取整，至少 1 帧）。 */
        fun framesFor(sampleRate: Int, millis: Int): Int = maxOf(1, (sampleRate.toLong() * millis / 1000).toInt())

        /** 帧换算成毫秒（向下取整）。 */
        fun millisFor(sampleRate: Int, frames: Long): Long = frames * 1000 / sampleRate
    }
}

/**
 * 我们这边「从排进队列到真正从输出端出去」的延迟拆分（毫秒），由播放线程每隔一会儿算一次（纯逻辑，可在电脑上测）：
 *
 * - **队列**：已经收到、还没写进 AudioTrack 的；
 * - **解码**：已经送进 AAC 解码器、还没解出来的（PCM 为 0）；
 * - **音轨已写未播**：写进 AudioTrack、播放头还没走到的（写入帧数 − 播放头位置）；
 * - **硬件**：播放头走过、还没真正从输出端出去的（播放头位置 − 由 `getTimestamp` 推算的此刻正在输出的帧）。
 *   拿不到时间戳、或时间戳太旧（推算出的位置超过播放头 10ms 以上，例如刚从暂停恢复）时为 null（未知），不估。
 *
 * 用户感到的音画差减去 [totalMillis]，就是手机那边加传输占的部分。
 */
class AudioLatency(
    val queueMillis: Long,
    val decoderMillis: Long,
    val trackMillis: Long,
    val hardwareMillis: Long?,
) {
    /** 我们这边的总延迟；硬件部分未知时为 null。 */
    val totalMillis: Long? get() = hardwareMillis?.let { queueMillis + decoderMillis + trackMillis + it }

    override fun toString(): String {
        val parts = "队列 ${queueMillis}ms + 解码 ${decoderMillis}ms + 音轨已写未播 ${trackMillis}ms"
        val total = totalMillis
        return if (total != null) {
            "我们这边总延迟 ${total}ms = $parts + 硬件 ${hardwareMillis}ms（getTimestamp 推算）"
        } else {
            "我们这边总延迟至少 ${queueMillis + decoderMillis + trackMillis}ms = $parts + 硬件部分未知（拿不到时间戳）"
        }
    }

    companion object {
        /**
         * 由播放线程手上的计数算出拆分。
         *
         * @param framesWritten 这个 AudioTrack 建好以来写进去的帧数。
         * @param headPosition 播放头位置（`getPlaybackHeadPosition`，32 位，按无符号传入）。
         * @param timestampFrame `getTimestamp` 给出的「[timestampNanos] 那一刻正在输出的帧」；拿不到或没在播放时传 null。
         * 位置是 32 位的，会回绕：差值按 32 位有符号数解释（真实差值远小于 2^31 帧）。
         */
        fun estimate(
            sampleRate: Int,
            queueFrames: Long,
            decoderFrames: Long,
            framesWritten: Long,
            headPosition: Long,
            timestampFrame: Long?,
            timestampNanos: Long,
            nowNanos: Long,
        ): AudioLatency {
            require(sampleRate > 0) { "采样率必须为正" }
            val pending = signed32(framesWritten - headPosition).coerceAtLeast(0)
            val hardware = timestampFrame?.let { frame ->
                val elapsed = (nowNanos - timestampNanos).coerceAtLeast(0)
                val presentedNow = frame + elapsed * sampleRate / NANOS_PER_SECOND
                val behind = signed32(headPosition - presentedNow)
                // 推算出的位置略超过播放头（取整误差）按 0；超过太多说明时间戳太旧，记为未知。
                if (behind < -(sampleRate / STALE_TOLERANCE_DIVISOR).toLong()) null else behind.coerceAtLeast(0)
            }
            fun millis(frames: Long) = AudioTuning.millisFor(sampleRate, frames)
            return AudioLatency(
                queueMillis = millis(queueFrames.coerceAtLeast(0)),
                decoderMillis = millis(decoderFrames.coerceAtLeast(0)),
                trackMillis = millis(pending),
                hardwareMillis = hardware?.let { millis(it) },
            )
        }

        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** 容差 = 采样率 / 100，即 10ms。 */
        private const val STALE_TOLERANCE_DIVISOR = 100

        private fun signed32(value: Long): Long = value.toInt().toLong()
    }
}
