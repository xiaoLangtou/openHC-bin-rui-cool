package io.github.gcbond.openhc.platform.media

import java.util.Locale
import kotlin.math.abs

/**
 * 解码统计，一个统计窗口（约 1 秒）一份。纯数据，不引用安卓类。
 *
 * 帧率按窗口实际时长折算成每秒。[renderedFps] 是系统回报的上屏帧数（安卓 6.0 起解码器的上屏回调），
 * 部分车机不回报，这时为 -1；它和 [decodedFps] 的比值就是上屏率。上屏回调只是参考：系统文档说明它可能成批、
 * 推迟送达，个别帧显示了也可能没有回调。按开源框架的实现看，OMX 解码器要等帧显示过、从屏幕上换下来才回调，
 * 被合成器挤掉的帧没有回调；有的解码器在送去显示时就回调，这时上屏数反映不出合成器丢帧。
 * 换了 Surface 以后重放缓存的帧不算进输入、解码帧率和积压，单独计在 [replayed] 里。
 *
 * 后半部分是上屏节拍（[FramePacer]）和到达抖动的统计，`toString` 里单独一行：
 * - [sameLatch]：和上一帧出帧时落在同一次合成取帧的帧数。立即送显（节拍关）时按推算每一次都有一帧被合成器挤掉，
 *   所以它应当约等于「解码 − 上屏」；节拍开时这些帧（以及跟在后面的帧）被顺延，不再被挤掉。
 * - 「出帧到合成」是出帧到被合成器取走的推算时间，之后系统还要固定的一两个刷新周期才显示到屏幕上，两种做法一样；
 *   两种做法的差值就是节拍多出来的延迟，也直接给在 [avgExtraDelayMs]。
 * - 到达抖动：两帧到达间隔与手机时间戳间隔之差，量的是从手机交出到我们收齐这一路加进来的抖动；
 *   手机时间戳间隔本身（[minPhoneIntervalMs]～[maxPhoneIntervalMs]）看手机出帧匀不匀。
 */
class VideoStats(
    /** 统计窗口时长（毫秒）。 */
    val windowMs: Long,
    /** 收到的访问单元（帧）每秒个数，含丢掉的。 */
    val inputFps: Float,
    /** 解码器每秒出帧数（不含重放的帧）。 */
    val decodedFps: Float,
    /** 每秒实际上屏帧数；系统没有回报为 -1。 */
    val renderedFps: Float,
    /** 本窗口丢掉的访问单元个数（等关键帧、输入缓冲超时、出错等）。 */
    val dropped: Int,
    /** 本窗口出帧的平均解码延迟（送进解码器 → 解码器交出，毫秒）；没有出帧为 -1。 */
    val avgDecodeLatencyMs: Float,
    /** 本窗口出帧的最大解码延迟（毫秒）；没有出帧为 -1。 */
    val maxDecodeLatencyMs: Float,
    /** 已送进解码器、还没出帧的帧数（窗口结束时）。 */
    val pendingFrames: Int,
    val totalInput: Long,
    val totalDecoded: Long,
    val totalDropped: Long,
    /** 当前画面尺寸，还不知道为 0。 */
    val width: Int,
    val height: Int,
    /** 当前解码器名字，没有解码器为 null。 */
    val codecName: String?,
    /** 正在丢帧等关键帧。 */
    val waitingForKeyFrame: Boolean,
    /** 本窗口解出来的重放帧数（不上屏，最后一帧除外）。 */
    val replayed: Int = 0,
    /** 在缓存里等输入缓冲的访问单元个数（窗口结束时，含要重放的）。 */
    val waitingForInput: Int = 0,
    /** 上屏节拍打开着（见 [FramePacer]）。 */
    val framePacing: Boolean = false,
    /** 推算出的屏幕刷新周期（毫秒），还没有为 -1。 */
    val refreshPeriodMs: Float = -1f,
    /** 本窗口和上一帧出帧时落在同一次合成取帧的帧数。 */
    val sameLatch: Int = 0,
    /** 本窗口因为节拍晚一个刷新周期送出的帧数。 */
    val deferred: Int = 0,
    /** 本窗口扣着时被更新的帧顶替、不再显示的帧数。 */
    val replaced: Int = 0,
    /** 本窗口还没有刷新网格、没按节拍直接送显的帧数。 */
    val unpaced: Int = 0,
    /** 本窗口送显的帧从出帧到被合成器取走的平均、最大时间（毫秒，推算），没有为 -1。 */
    val avgLatchDelayMs: Float = -1f,
    val maxLatchDelayMs: Float = -1f,
    /** 其中因为节拍多等的时间，按送显的帧平均（毫秒），没有为 -1。 */
    val avgExtraDelayMs: Float = -1f,
    /** 本窗口到达间隔与手机时间戳间隔之差的平均、最大值（毫秒），没有为 -1。 */
    val avgArrivalJitterMs: Float = -1f,
    val maxArrivalJitterMs: Float = -1f,
    /** 本窗口手机时间戳间隔的最小、最大值（毫秒），没有为 -1。 */
    val minPhoneIntervalMs: Float = -1f,
    val maxPhoneIntervalMs: Float = -1f,
    /** 本窗口收到的码流字节折算的码率（Mbps，收到就计，含丢掉的）；没有收到为 -1。 */
    val inputMbps: Float = -1f,
) {
    /** 两行：第一行是解码与上屏，第二行是上屏节拍和到达抖动。 */
    override fun toString(): String = String.format(
        Locale.ROOT,
        "输入 %.1f 解码 %.1f 上屏 %s 丢 %d（累计 %d）延迟 均 %s 最大 %s ms 积压 %d%s%s 码率 %s %dx%d %s%s",
        inputFps,
        decodedFps,
        if (renderedFps < 0) "-" else String.format(Locale.ROOT, "%.1f", renderedFps),
        dropped,
        totalDropped,
        ms(avgDecodeLatencyMs),
        ms(maxDecodeLatencyMs),
        pendingFrames,
        if (waitingForInput > 0) " 待送 $waitingForInput" else "",
        if (replayed > 0) " 重放 $replayed" else "",
        if (inputMbps < 0) "-" else String.format(Locale.ROOT, "%.1f Mbps", inputMbps),
        width,
        height,
        codecName ?: "无解码器",
        if (waitingForKeyFrame) " 等关键帧" else "",
    ) + "\n" + pacingLine()

    /** 例：`上屏节拍 开（刷新 16.67 ms）：两帧同刷新 12 顺延 14 顶替 0；出帧到合成 均 11.2 最大 24.9 ms，节拍多等 均 3.9 ms；…` */
    fun pacingLine(): String = buildString {
        append("上屏节拍 ").append(if (framePacing) "开" else "关")
        if (refreshPeriodMs > 0) {
            append(String.format(Locale.ROOT, "（刷新 %.2f ms）", refreshPeriodMs))
        } else {
            append("（还没拿到刷新时刻）")
        }
        append("：两帧同刷新 ").append(sameLatch)
        if (framePacing) append(" 顺延 ").append(deferred).append(" 顶替 ").append(replaced)
        if (unpaced > 0) append(" 未对齐 ").append(unpaced)
        if (avgLatchDelayMs >= 0) {
            append("；出帧到合成 均 ").append(ms(avgLatchDelayMs)).append(" 最大 ").append(ms(maxLatchDelayMs)).append(" ms")
            if (framePacing) append("，节拍多等 均 ").append(ms(avgExtraDelayMs)).append(" ms")
        }
        if (avgArrivalJitterMs >= 0) {
            append("；到达抖动 均 ").append(ms(avgArrivalJitterMs)).append(" 最大 ").append(ms(maxArrivalJitterMs)).append(" ms")
            append("，手机间隔 ").append(ms(minPhoneIntervalMs)).append("～").append(ms(maxPhoneIntervalMs)).append(" ms")
        }
    }

    private fun ms(value: Float): String = if (value < 0) "-" else String.format(Locale.ROOT, "%.1f", value)
}

/**
 * [VideoStats] 的计数器：送帧线程、解码线程同时更新，所以方法都加锁（每帧几次，开销可以忽略）。
 * 时间由调用方传入（窗口用毫秒，节拍和到达抖动用 System.nanoTime 的纳秒），方便测试。
 */
internal class VideoStatsCounter {
    private var windowStartMs = -1L
    private var input = 0
    private var decoded = 0
    private var rendered = 0
    private var dropped = 0
    private var latencySumUs = 0L
    private var latencyMaxUs = -1L
    private var latencySamples = 0
    private var renderReported = false
    private var replayed = 0

    private var totalInput = 0L
    private var totalDecoded = 0L
    private var totalDropped = 0L
    private var pending = 0

    // 收流字节（码率）：收到就计（含后来丢掉的），按窗口清零，累计保留。
    private var inputBytes = 0L
    private var totalInputBytes = 0L

    // 上屏节拍（见 FramePacer），按窗口清零。
    private var sameLatch = 0
    private var deferred = 0
    private var replaced = 0
    private var unpaced = 0
    private var latchDelaySumNs = 0L
    private var latchDelayMaxNs = -1L
    private var extraSumNs = 0L
    private var shown = 0

    // 到达抖动：上一帧的到达时刻和手机时间戳跨窗口保留，其余按窗口清零。
    private var lastArrivalNs = NONE
    private var lastPhonePtsUs = -1L
    private var jitterSumNs = 0L
    private var jitterMaxNs = -1L
    private var jitterSamples = 0
    private var phoneMinNs = Long.MAX_VALUE
    private var phoneMaxNs = -1L

    /**
     * 卡死判断的计时起点：上次出帧以后第一次送帧的时间（这之后一直没出帧）。不用上次出帧的时间：
     * 画面长时间不变、没送帧也没出帧，再送帧时不能一上来就算作很久没出帧。解码器重建、flush 时重置。
     */
    var outputWaitStartMs = 0L
        @Synchronized get
        private set

    /** 上次出帧以后又送进去的帧数（含重放的）：解码器卡死时会一直涨。 */
    var inputsSinceOutput = 0
        @Synchronized get
        private set

    @Synchronized
    fun start(nowMs: Long) {
        windowStartMs = nowMs
        outputWaitStartMs = nowMs
    }

    @Synchronized
    fun onInput() {
        input++
        totalInput++
    }

    /** 收到一个访问单元的 [bytes] 字节：收流码率统计（收到就计，含丢掉的）。 */
    @Synchronized
    fun onInputBytes(bytes: Long) {
        if (bytes <= 0) return
        inputBytes += bytes
        totalInputBytes += bytes
    }

    @Synchronized
    fun onDropped(count: Int = 1) {
        dropped += count
        totalDropped += count
    }

    @Synchronized
    fun onQueued(nowMs: Long) {
        pending++
        countInput(nowMs)
    }

    /** 送进去一个重放帧：只用于卡死判断，不算积压。 */
    @Synchronized
    fun onReplayQueued(nowMs: Long) {
        countInput(nowMs)
    }

    private fun countInput(nowMs: Long) {
        if (inputsSinceOutput == 0) outputWaitStartMs = nowMs
        inputsSinceOutput++
    }

    @Synchronized
    fun onDecoded(latencyUs: Long) {
        decoded++
        totalDecoded++
        if (pending > 0) pending--
        inputsSinceOutput = 0
        // 时间戳被解码器改过时算出来的值没有意义，不计入。
        if (latencyUs in 0..MAX_PLAUSIBLE_LATENCY_US) {
            latencySumUs += latencyUs
            latencySamples++
            if (latencyUs > latencyMaxUs) latencyMaxUs = latencyUs
        }
    }

    /** 解出一个重放帧：不算进解码帧率。 */
    @Synchronized
    fun onReplayDecoded() {
        replayed++
        inputsSinceOutput = 0
    }

    @Synchronized
    fun onRendered() {
        rendered++
        renderReported = true
    }

    /**
     * 收到一帧（有图像条带的访问单元）：[nowNs] 是到达时刻（System.nanoTime），[phonePtsUs] 是手机给的时间戳（微秒），
     * 没有为负数。和上一帧比：到达间隔与手机时间戳间隔之差算到达抖动。手机时间戳不增、回绕或者两帧隔了 1 秒以上
     * （画面不动时手机不发帧）的那一对不算。
     */
    @Synchronized
    fun onArrival(nowNs: Long, phonePtsUs: Long) {
        val previousArrival = lastArrivalNs
        val previousPts = lastPhonePtsUs
        lastArrivalNs = nowNs
        lastPhonePtsUs = phonePtsUs
        if (previousArrival == NONE || previousPts < 0 || phonePtsUs < 0) return
        val phoneNs = (phonePtsUs - previousPts) * 1000
        if (phoneNs <= 0 || phoneNs > MAX_PAIR_INTERVAL_NS) return
        val jitter = abs(nowNs - previousArrival - phoneNs)
        jitterSumNs += jitter
        jitterSamples++
        if (jitter > jitterMaxNs) jitterMaxNs = jitter
        if (phoneNs < phoneMinNs) phoneMinNs = phoneNs
        if (phoneNs > phoneMaxNs) phoneMaxNs = phoneNs
    }

    /**
     * 一帧要上屏时节拍的决定：[sameLatch] 和上一帧落在同一次合成取帧，[replaced] 顶替了扣着的帧
     * （被顶替的那一帧不再显示），[unpaced] 还没有刷新网格、直接送显。
     */
    @Synchronized
    fun onPacing(sameLatch: Boolean, replaced: Boolean, unpaced: Boolean) {
        if (sameLatch) this.sameLatch++
        if (replaced) this.replaced++
        if (unpaced) this.unpaced++
    }

    /** 一帧送去显示：出帧到被合成器取走 [latchDelayNs]（推算），其中节拍多等了 [extraNs]。 */
    @Synchronized
    fun onShown(latchDelayNs: Long, extraNs: Long) {
        shown++
        latchDelaySumNs += latchDelayNs
        if (latchDelayNs > latchDelayMaxNs) latchDelayMaxNs = latchDelayNs
        extraSumNs += extraNs
        if (extraNs > 0) deferred++
    }

    /** 换了新解码器或者 flush 过（送进去的帧都不会再出来）：积压清零，卡死判断从现在重新计时。 */
    @Synchronized
    fun onDecoderReset(nowMs: Long) {
        pending = 0
        inputsSinceOutput = 0
        outputWaitStartMs = nowMs
    }

    /** 取一份快照并开始新窗口。[refreshPeriodNs] 是推算出的刷新周期，不知道为 0。 */
    @Synchronized
    fun snapshot(
        nowMs: Long,
        width: Int,
        height: Int,
        codecName: String?,
        waitingForKeyFrame: Boolean,
        waitingForInput: Int = 0,
        framePacing: Boolean = false,
        refreshPeriodNs: Long = 0,
    ): VideoStats {
        if (windowStartMs < 0) windowStartMs = nowMs
        val windowMs = (nowMs - windowStartMs).coerceAtLeast(1)
        val perSecond = 1000f / windowMs
        val stats = VideoStats(
            windowMs = windowMs,
            inputFps = input * perSecond,
            inputMbps = if (inputBytes > 0) inputBytes * 8f * perSecond / 1_000_000f else -1f,
            decodedFps = decoded * perSecond,
            renderedFps = if (renderReported) rendered * perSecond else -1f,
            dropped = dropped,
            avgDecodeLatencyMs = if (latencySamples > 0) latencySumUs / 1000f / latencySamples else -1f,
            maxDecodeLatencyMs = if (latencyMaxUs >= 0) latencyMaxUs / 1000f else -1f,
            pendingFrames = pending,
            totalInput = totalInput,
            totalDecoded = totalDecoded,
            totalDropped = totalDropped,
            width = width,
            height = height,
            codecName = codecName,
            waitingForKeyFrame = waitingForKeyFrame,
            replayed = replayed,
            waitingForInput = waitingForInput,
            framePacing = framePacing,
            refreshPeriodMs = if (refreshPeriodNs > 0) refreshPeriodNs / 1e6f else -1f,
            sameLatch = sameLatch,
            deferred = deferred,
            replaced = replaced,
            unpaced = unpaced,
            avgLatchDelayMs = if (shown > 0) latchDelaySumNs / 1e6f / shown else -1f,
            maxLatchDelayMs = if (shown > 0) latchDelayMaxNs / 1e6f else -1f,
            avgExtraDelayMs = if (shown > 0) extraSumNs / 1e6f / shown else -1f,
            avgArrivalJitterMs = if (jitterSamples > 0) jitterSumNs / 1e6f / jitterSamples else -1f,
            maxArrivalJitterMs = if (jitterSamples > 0) jitterMaxNs / 1e6f else -1f,
            minPhoneIntervalMs = if (jitterSamples > 0) phoneMinNs / 1e6f else -1f,
            maxPhoneIntervalMs = if (jitterSamples > 0) phoneMaxNs / 1e6f else -1f,
        )
        windowStartMs = nowMs
        input = 0
        decoded = 0
        rendered = 0
        dropped = 0
        inputBytes = 0
        latencySumUs = 0
        latencyMaxUs = -1
        latencySamples = 0
        replayed = 0
        sameLatch = 0
        deferred = 0
        replaced = 0
        unpaced = 0
        latchDelaySumNs = 0
        latchDelayMaxNs = -1
        extraSumNs = 0
        shown = 0
        jitterSumNs = 0
        jitterMaxNs = -1
        jitterSamples = 0
        phoneMinNs = Long.MAX_VALUE
        phoneMaxNs = -1
        return stats
    }

    private companion object {
        const val MAX_PLAUSIBLE_LATENCY_US = 10_000_000L
        const val NONE = Long.MIN_VALUE

        /** 两帧手机时间戳隔了这么久以上（1 秒），多半是画面不动时手机停发，不算抖动。 */
        const val MAX_PAIR_INTERVAL_NS = 1_000_000_000L
    }
}
