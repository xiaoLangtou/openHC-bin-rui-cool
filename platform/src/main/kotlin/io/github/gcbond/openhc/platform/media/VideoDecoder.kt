package io.github.gcbond.openhc.platform.media

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.platform.log.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * H.264 硬解上屏：接 [AccessUnitSink] 回调，每帧先复制进缓存（[FrameCache]），再按顺序送进 MediaCodec 的空闲输入缓冲，
 * 解出来的帧按屏幕刷新的节拍送显（见「上屏节拍」）。用完必须 [close]。
 *
 * ## 线程
 * - **送帧线程**（调用 [onAccessUnit] / [queueAccessUnit] 的线程，一般是会话的协议线程，它还要处理控制消息、保活和触摸）：
 *   拆 NAL、判断能不能送、必要时当场建解码器（首个关键帧、尺寸变化、出错后的下一个关键帧），把整帧复制进缓存，
 *   有空闲输入缓冲就当场送进去。**不等输入缓冲**：一时没有空闲缓冲的帧留在缓存里，解码线程拿到缓冲时按顺序补送。
 *   帧数据只在回调期间有效，所以复制在回调里同步做完。送帧必须串行（同一时间只有一个线程送）。
 * - **解码线程**（本类自己的 HandlerThread）：收 MediaCodec 的异步回调。拿到空闲输入缓冲就从缓存补送；出帧按节拍
 *   `releaseOutputBuffer`（重放的帧例外，见下）；收 Choreographer 的刷新回调；处理格式变化、解码器报错；执行重放；
 *   按 [Options.statsIntervalMs] 做统计和卡死检查。所有 [Listener] 回调都在这个线程上。
 * - **界面线程**：[setSurface]（在 Surface 创建、销毁的回调里同步切换输出）。
 *
 * 解码器的创建、配置、切换输出、flush、释放，以及缓存和送帧，都在同一把锁里做。出帧回调不拿锁：按「是不是当前解码器」
 * 过滤，碰上别的线程正好释放解码器，只会收到 IllegalStateException，接住即可。不在解码器自己的回调里释放它：
 * 要换掉时先标记、不再往里送，释放（或重建）排到回调返回之后。
 *
 * ## 缓存
 * 从最近一个能开始解码的帧（含 IDR，或者全是帧内条带，见 [AccessUnitScan.isSyncPoint]）起，送进解码器的每个访问单元
 * （含中途单独发来的参数集）都复制一份按顺序留着，段首另存一份当时的参数集；下一个能开始解码的帧到达时整段换掉，
 * 还没送进去的旧帧作废（算丢帧）。上限见 [Options.cacheMaxBytes]、[Options.cacheMaxFrames]：超过时丢掉已经送过的部分，
 * 到下一个关键帧之前不能重放（打日志），还没送的照样留着。缓存有两个用处：
 * - **等输入缓冲**：刚建好的解码器第一个输入缓冲可能来得晚，关键帧又大、解得慢，紧跟着的帧一时拿不到缓冲。
 *   这些帧留在缓存里按顺序补送，不丢；丢了就得等手机的下一个关键帧，手机不按固定间隔发关键帧时可能一直黑屏。
 *   解码器超过 [Options.stallTimeoutMs] 一个缓冲都不给，才判为卡死。
 * - **重放**：输出换到新的视图 Surface 后（切后台再回来、视图重建），新 Surface 上什么都没有，手机画面不变时又不发新帧，
 *   会一直黑屏。所以在解码线程上 flush 解码器，再从段首起把缓存重送一遍：重放的帧不上屏，只上屏最后一帧。
 *   flush 之后就和新解码器从关键帧开始解一样，不靠解码器对重复帧的容错。
 *   - 重放期间新到的帧排在缓存后面，重放完接着送，顺序不乱；重放中途来了新的关键帧，剩下的重放直接作废。
 *   - 切回前手机还在出帧时先等一小会儿（[DecoderTiming.replayDelayMs]），等来新画面就不重放。
 *   - 解码器可能扣着最后一帧、或者改了时间戳认不出最后一帧：手里总扣着最近交出的一个重放帧，
 *     一段时间没有新的就把它上屏（[ReplayPresenter]）。
 *   - 重放的帧不算进输入、解码帧率，单独计数（[VideoStats.replayed]）。
 *   - 解码器换过（释放后在新关键帧上重建）时，缓存也是从新关键帧起的，重放的只会是新解码器自己的帧；
 *     缓存超过上限作废了、或者解码器已经释放，就不重放，等手机的新帧。
 *
 * ## 丢帧与恢复
 * 一旦丢了帧或者换了解码器，之后的 P 帧引用不到参考帧，所以统一丢到下一个能开始解码的帧为止，
 * 开始等的时候回调一次 [Listener.onKeyFrameNeeded]：
 * - 解码器迟迟不收帧，等着送的帧超出缓存上限：丢这一帧，等关键帧。
 * - 一帧比输入缓冲还大：按更大的缓冲重建解码器，从缓存段首起补送（缓存超过上限丢过时只能等关键帧）。
 * - 解码器报错（暂时性错误除外）、配置失败、卡死（见上）：释放解码器，下一个关键帧重建。
 * - SPS 里的尺寸、profile 等变了：在这个关键帧上当场换新解码器，不丢帧。
 * - Surface 没了（切后台、视图销毁）：默认把输出切到一个看不见的占位 Surface（setOutputSurface），解码不停、
 *   参考帧不断，Surface 回来时切回去并重放；切不过去才释放解码器、等关键帧。
 *
 * 释放解码器一律 stop + release，异常都接住；[close] 之后不再持有任何 MediaCodec。
 *
 * ## 上屏节拍
 * 合成器每次刷新前取一次帧，两次取帧之间送来的帧只显示最后一帧，前面的被挤掉（系统文档对 releaseOutputBuffer
 * 的说明：同一次 VSYNC 送了几帧，只显示最后一帧）。出帧立即送显时，手机发帧、传输、解码的抖动会让两帧落进
 * 同一次取帧：平板真机 60 帧时上屏回调只有 44～49 帧/秒，30 帧时几乎不少。所以默认（[Options.framePacing]）
 * 按 [FramePacer] 送显：多数帧照样马上送；和上一个送出的帧落在同一次取帧的，扣到下一次取帧再送；每帧最多晚
 * 一个刷新周期（60Hz 屏 16.7ms，55Hz 屏 18.2ms），同一时刻最多扣着两帧。平均多等多少取决于抖动的样子，
 * 在 0 到一个周期之间，统计第二行的「节拍多等」就是它（细节见 [FramePacer]）。取帧时刻由 Choreographer 的刷新回调
 * （注册在解码线程上，有帧要显示时才开、空闲 [VSYNC_IDLE_NS] 后停）和屏幕给的时序（[setDisplayTiming]）推算，
 * 见 [VsyncGrid]、[DisplayTiming]。关掉节拍就是原来的立即送显；统计第二行在两种做法下都推算「两帧同刷新」，用来对照。
 *
 * ## 低延迟
 * - 送显一律 `releaseOutputBuffer(index, true)`（不管马上送还是扣过的帧），时间戳总在过去：合成器下一次取帧就拿走，
 *   不按时间戳排队（实测按时间戳排队上屏率只有一半左右）。扣帧的时机由本类掌握，不靠给合成器一个将来的时间戳：
 *   那样要知道帧具体在哪一次刷新显示，而屏幕报的送显提前量可能少整数个周期（见 [DisplayTiming]），
 *   算错一拍帧就晚到、照样被挤掉；扣帧只依赖取帧的相位，不受影响。
 * - 送进解码器的时间戳默认用本机单调时钟（入队时刻），不用手机的 PTS。依据是系统合成器公开的调度方式：
 *   缓冲的时间戳被当作期望显示时间，入队时刻总在过去，所以下一次刷新就显示，同一次刷新来了几帧只显示最新的一帧；
 *   手机的 PTS 和本机时钟无关，可能被当成「一秒内的将来再显示」或者「无效时间戳、一帧不丢地排队」。
 *   平板真机上「上屏」少于「解码」、帧率越高少得越多，与「只显示最新的一帧」相符（推断，没有逐帧核对）。
 *   [Options.useStreamTimestamps] 可以切回手机的 PTS 做对照（重放的帧仍用入队时刻）。入队时刻顺带用来算解码延迟
 *   （出帧时刻 − 入队时刻）。
 * - 安卓 11 起解码器支持时打开标准低延迟模式（KEY_LOW_LATENCY），另外按厂商加私有的低延迟键（[VendorLowLatency]），
 *   并设实时优先级；配置失败就逐级去掉这些键重试，还不行换下一个解码器。
 *
 * ## 安卓版本
 * 用到的异步回调、setOutputSurface、异步模式下 flush 后重新 start、上屏回调、私有格式的 ImageReader、
 * 非主线程上的 Choreographer、屏幕的帧时间偏移和送显提前量都是安卓 6.0（API 23）就有的，minSdk 下全部可用。
 * 标准低延迟模式要安卓 11（API 30），更早的系统只靠厂商键和实时优先级；判断解码器是不是硬件、
 * 跳过别名在安卓 10（API 29）以下按名字判断。
 */
class VideoDecoder @JvmOverloads constructor(
    private val listener: Listener = object : Listener {},
    private val options: Options = Options(),
) : AccessUnitSink, Closeable {

    /** 都在解码线程上回调，不要做耗时操作；更新界面请切到主线程。 */
    interface Listener {
        /** 画面尺寸（裁剪后的显示尺寸）定下来或变了。用来摆放视图、换算触摸。 */
        fun onVideoSizeChanged(width: Int, height: Int) {}

        /** 统计，每 [Options.statsIntervalMs] 一次。 */
        fun onStats(stats: VideoStats) {}

        /** 开始丢帧等关键帧。手机支持请求关键帧时可以在这里请求，也可以在界面上提示。 */
        fun onKeyFrameNeeded(reason: ResyncReason) {}

        /** 建不起解码器（没有能用的 H.264 解码器、尺寸不支持等），下一个关键帧会再试。 */
        fun onDecoderUnavailable(detail: String) {}
    }

    class Options(
        /**
         * 对照用：true 时把手机给的 PTS（[AccessUnit.presentationTimeUs]）原样作为解码器时间戳，不再用入队时刻，
         * 这时不统计解码延迟。默认 false，见类注释「低延迟」一节。
         */
        val useStreamTimestamps: Boolean = false,
        /**
         * 没有视图 Surface 时（切后台等）继续解码到看不见的占位 Surface，回到前台时重放缓存、马上有画面；
         * false 则释放解码器，回来后等关键帧。
         */
        val decodeWithoutSurface: Boolean = true,
        /** 是否加厂商私有的低延迟键（见 [VendorLowLatency]）。 */
        val vendorLowLatency: Boolean = true,
        /** 统计间隔（毫秒）。 */
        val statsIntervalMs: Long = 1000,
        /** 每多少个统计间隔往日志里打一行统计，0 不打。 */
        val statsLogEvery: Int = 5,
        /**
         * 卡死判断（毫秒）：送进去的帧这么久不出帧，或者有帧要送、解码器这么久一个输入缓冲都不给。
         * 帧不会因为等输入缓冲被丢，关键帧（包括刚建好解码器后的第一帧）最多等这么久。
         */
        val stallTimeoutMs: Long = 3000,
        /** 缓存（见类注释）最多存多少字节。 */
        val cacheMaxBytes: Int = 16 * 1024 * 1024,
        /** 缓存最多存多少个访问单元。 */
        val cacheMaxFrames: Int = 600,
        /**
         * 按屏幕刷新的节拍送显（类注释「上屏节拍」），false 是立即送显。运行中可以用 [VideoDecoder.framePacing] 切换。
         */
        val framePacing: Boolean = true,
    ) {
        init {
            require(statsIntervalMs > 0) { "statsIntervalMs 必须是正数" }
            require(statsLogEvery >= 0) { "statsLogEvery 不能是负数" }
            require(stallTimeoutMs > 0) { "stallTimeoutMs 必须是正数" }
            require(cacheMaxBytes > 0 && cacheMaxFrames > 0) { "缓存上限必须是正数" }
        }
    }

    /** 为什么要丢帧等关键帧。 */
    enum class ResyncReason {
        /** 没有视图 Surface，且不在后台解码（或者切到占位 Surface 失败），解码器已释放。 */
        SURFACE_LOST,

        /** 解码器迟迟不收帧，等着送的帧超出了缓存上限，丢了一帧。 */
        INPUT_TIMEOUT,

        /** 一帧比输入缓冲还大，已按更大的缓冲重建，但没法从缓存补送（缓存超过上限丢过，或者帧大过缓冲上限）。 */
        INPUT_TOO_LARGE,

        /** 解码器报错或者配置失败，已释放。 */
        DECODER_ERROR,

        /** 解码器收了帧却长时间不出帧，或者有帧要送却长时间不给输入缓冲，判为卡死，已释放。 */
        DECODER_STALLED,

        /** 码流格式变了（分辨率等），或者出现了要重排的 B 帧，要换解码器。 */
        FORMAT_CHANGED,

        /** 帧或参数集格式不对。 */
        BAD_STREAM,

        /**
         * 手机丢了帧（视频断档：RTP 序号跳变，发送端拥塞时会整批丢掉普通帧）：之后的帧缺参考帧，接着解会花屏。
         * 解码器不释放（画面停在最后一帧），等关键帧接上；手机丢完普通帧紧接着就发关键帧。
         */
        STREAM_GAP,
    }

    private val lock = ReentrantLock()

    // ---------------------------------------------------------------- 以下由 lock 保护（带 @Volatile 的可以不拿锁读）

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var codecName: String? = null

    /** 当前输出在占位 Surface 上：出帧不送显，直接还给解码器。 */
    @Volatile
    private var outputOnPlaceholder = false

    @Volatile
    private var waitingForSync = true

    @Volatile
    private var closed = false

    /** 刚 flush 过、还没重新 start：这期间解码器交来的回调都已作废，丢掉。 */
    @Volatile
    private var flushing = false

    private var codecSps: H264Sps? = null
    private var codecDecodeOrder = false

    /** 要换掉的解码器：已经排了释放或重建（解码线程上、回调返回之后做），不再往里送帧。 */
    private var retiringCodec: MediaCodec? = null
    private val freeInputs = ArrayDeque<Int>()
    private var viewSurface: Surface? = null

    /** 视图 Surface 每换一次加一：排着的重放据此判断还算不算数。 */
    private var surfaceGeneration = 0
    private var placeholder: ImageReader? = null
    private var waitNotified = false
    private var maxInputSize = 0
    private var hintFps = 0
    private val params = ParameterSets()
    private var sawBSlice = false
    private var lastPtsUs = 0L
    private val cache = FrameCache(options.cacheMaxBytes, options.cacheMaxFrames)

    // 日志用：刚建好的解码器多久给出第一个输入缓冲、头几帧到达后等了多久才送进去。
    private var installedAtMs = 0L
    private var firstInputSeen = false
    private var freshFedByCodec = 0

    // ---------------------------------------------------------------- 送帧线程独占

    private val scan = AccessUnitScan()

    /** 最近一帧到达的时间（毫秒，任意线程可读），还没收到过为 -1。 */
    @Volatile
    private var lastArrivalMs = -1L

    // ---------------------------------------------------------------- 解码线程独占

    private var notifiedWidth = 0
    private var notifiedHeight = 0
    private var ticksSinceLog = 0

    /** [presenter] 手里的输出序号属于哪个解码器。 */
    private var outputCodec: MediaCodec? = null
    private val presenter = ReplayPresenter { index, render -> releaseOutput(index, render) }
    private var replayStartedMs = 0L
    private var replayFrames = 0

    /** 视图 Surface 上上屏过的非重放帧数：切到新 Surface 后它变了，说明手机已经发来新画面，不用重放。解码线程写。 */
    @Volatile
    private var freshFramesShown = 0L

    private val replays = ReplayTracker()
    private val stats = VideoStatsCounter()

    // ---------------------------------------------------------------- 上屏节拍（解码线程独占，见 FramePacer）

    private val vsyncGrid = VsyncGrid()
    private val pacer = FramePacer()

    /** [pacer] 扣着的输出序号（属于 [outputCodec]），和 pacer 里的队列一一对应、顺序相同。 */
    private val heldIndices = IntArray(FramePacer.MAX_QUEUED)
    private var choreographer: Choreographer? = null
    private var choreographerUnavailable = false
    private var vsyncRequested = false
    private var gridLogged = false

    /** 最近一次有帧要上屏的时刻（System.nanoTime）：空闲久了就不再要刷新回调。 */
    private var lastShowNs = 0L

    /** 视图所在屏幕的刷新时序，任意线程写。 */
    @Volatile
    private var displayTiming: DisplayTiming? = null

    /** 按屏幕刷新的节拍送显（类注释「上屏节拍」），初值取 [Options.framePacing]。任意线程可改，下一帧起生效。 */
    @Volatile
    var framePacing: Boolean = options.framePacing

    /** 最近一次的统计（任意线程可读），还没有统计过为 null。 */
    @Volatile
    var lastStats: VideoStats? = null
        private set

    private val thread = HandlerThread("OpenHC-VideoDecoder").also { it.start() }
    private val handler = Handler(thread.looper)
    private val codecCallback = CodecCallback()
    private val renderListener = MediaCodec.OnFrameRenderedListener { c, _, _ ->
        if (c === codec && !outputOnPlaceholder) stats.onRendered()
    }
    private val holdTimeout = Runnable { onReplayHoldTimeout() }
    private val vsyncCallback = Choreographer.FrameCallback { frameTimeNanos -> onVsync(frameTimeNanos) }
    private val heldDue = Runnable { onHeldDue() }
    private val pacerReset = Runnable { resetPacer() }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            onTick()
            handler.postDelayed(this, options.statsIntervalMs)
        }
    }

    init {
        stats.start(now())
        handler.postDelayed(tick, options.statsIntervalMs)
    }

    /**
     * 协商结果（HiSightOutput.StreamNegotiated）：记下帧率（用作解码器的 operating-rate），并在解码线程上预先查好
     * 解码器清单。解码器仍在首个关键帧到达时按其中的 SPS/PPS 配置：拿真实的参数集配置兼容性最好，
     * 提前建解码器只能省下一次一百毫秒左右的启动时间。任意线程。
     */
    fun prepare(width: Int, height: Int, fps: Int) {
        lock.withLock { if (fps > 0) hintFps = fps }
        if (width > 0 && height > 0) handler.post { DecoderCatalog.candidates(width, height) }
    }

    /**
     * 视图所在屏幕的刷新时序（[DisplayRefreshRate.timingOf]），上屏节拍用它推算合成器取帧的相位。任意线程，
     * 视图拿到 Surface 时给一次即可。不给的话，周期从 Choreographer 的回调间隔量，帧时间当作刷新时刻，
     * 提前量按一个周期估，相位可能偏，节拍的效果打折扣。
     */
    fun setDisplayTiming(timing: DisplayTiming?) {
        if (timing == displayTiming) return
        displayTiming = timing
        handler.post {
            vsyncGrid.setNominalPeriod(timing?.refreshPeriodNs ?: 0)
            Log.i(TAG, "屏幕刷新时序：${timing ?: "不知道"}")
        }
    }

    /**
     * 设置输出 Surface（界面线程，在 SurfaceHolder 的回调里调用）。null 表示 Surface 没了：返回时保证解码器已经
     * 不再往原来的 Surface 上画（切到了占位 Surface，或者解码器已释放）。同一个 Surface 对象销毁后重建，中间要先传过 null。
     * 换到新的视图 Surface 后，在解码线程上重放缓存，把最后一帧画出来（见类注释）。
     * 正在配置解码器时会等配置完成（通常一两百毫秒以内）。
     */
    fun setSurface(surface: Surface?) {
        lock.withLock {
            if (closed) return
            val target = surface?.takeIf { it.isValid }
            if (target === viewSurface) return
            viewSurface = target
            surfaceGeneration++
            // 排在下面的重放前面：重放出来的最后一帧按新 Surface 重新起拍。
            handler.post(pacerReset)
            val c = codec ?: return
            if (target != null) {
                if (switchOutputLocked(c, target)) {
                    outputOnPlaceholder = false
                    closePlaceholderLocked()
                    Log.i(TAG, "输出切回视图 Surface")
                    scheduleReplayLocked(c)
                } else {
                    teardownLocked(ResyncReason.SURFACE_LOST)
                }
                return
            }
            val sps = codecSps
            if (options.decodeWithoutSurface && sps != null) {
                val placeholderSurface = placeholderLocked(sps.width, sps.height)
                if (placeholderSurface != null) {
                    // 先标记再切：切换过程中出的帧直接还给解码器，不往占位 Surface 上送。
                    outputOnPlaceholder = true
                    if (switchOutputLocked(c, placeholderSurface)) {
                        Log.i(TAG, "没有视图 Surface，输出切到占位 Surface，继续解码")
                        return
                    }
                }
            }
            teardownLocked(ResyncReason.SURFACE_LOST)
        }
    }

    /** HiSight 视频回调：在调用 feedVideo 的线程上同步执行，帧数据只在回调期间有效。 */
    override fun onAccessUnit(unit: AccessUnit) {
        queueAccessUnit(unit.data, unit.offset, unit.length, unit.presentationTimeUs, afterGap = unit.gapBefore != null)
    }

    /**
     * 送一帧 Annex-B 数据（[onAccessUnit] 的通用版本）。只在调用期间读 [data]（要留的部分复制进缓存），不保留引用；
     * 返回 true 表示收下了：已经送进解码器，或者在缓存里排队等输入缓冲。不等输入缓冲，除了参数越界不会抛异常：
     * 解码器出的问题都在内部恢复，不打断送帧线程。
     * [streamPtsUs] 是码流里的显示时间戳（微秒），只在 [Options.useStreamTimestamps] 打开时使用，负数表示没有。
     * [afterGap]：这一帧之前码流断过档（[ResyncReason.STREAM_GAP]）。
     */
    @JvmOverloads
    fun queueAccessUnit(data: ByteArray, offset: Int, length: Int, streamPtsUs: Long = -1, afterGap: Boolean = false): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        if (closed) return false
        lastArrivalMs = now()
        stats.onInput()
        scan.scan(data, offset, length)
        if (scan.sliceCount > 0) stats.onArrival(System.nanoTime(), streamPtsUs)
        return try {
            lock.withLock { !closed && feedLocked(data, offset, length, streamPtsUs, afterGap) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "送帧时出现意外异常，释放解码器", e)
            lock.withLock { if (!closed) teardownLocked(ResyncReason.DECODER_ERROR) }
            stats.onDropped()
            false
        }
    }

    /** 换一路新码流（例如重新协商之后）：释放解码器，忘掉记下的参数集，等下一个关键帧。 */
    fun reset() {
        lock.withLock {
            if (closed) return
            teardownLocked(null)
            params.clear()
            sawBSlice = false
            maxInputSize = 0
        }
    }

    /** 释放解码器和线程。可在任意线程重复调用；之后送来的帧直接丢弃。 */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            teardownLocked(null)
            cache.release()
            viewSurface = null
        }
        handler.removeCallbacksAndMessages(null)
        // 刷新回调登记在解码线程的 Choreographer 上，也在那个线程上撤掉（退出前会执行已经到时的消息）。
        handler.post { choreographer?.removeFrameCallback(vsyncCallback) }
        thread.quitSafely()
        Log.i(TAG, "视频解码器已关闭")
    }

    // ---------------------------------------------------------------- 送帧

    private fun feedLocked(data: ByteArray, offset: Int, length: Int, streamPtsUs: Long, afterGap: Boolean): Boolean {
        // 断档后的第一帧不是关键帧就等关键帧（不释放解码器）。放在最前面：这一帧哪怕被下面的提前返回挡掉，也已经开始等了。
        if (afterGap && !scan.isSyncPoint) requireSyncLocked(ResyncReason.STREAM_GAP)
        if (scan.nalCount == 0) return drop(ResyncReason.BAD_STREAM)
        try {
            if (scan.spsStart >= 0) params.updateSps(data, scan.spsStart, scan.spsEnd)
            for (i in 0 until scan.ppsCount) params.updatePps(data, scan.ppsStart(i), scan.ppsEnd(i))
        } catch (e: MalformedMessageException) {
            Log.w(TAG, "参数集格式不对：${e.message}")
            return drop(ResyncReason.BAD_STREAM)
        }
        if (scan.hasBSlice && !sawBSlice) {
            sawBSlice = true
            Log.i(TAG, "码流里有 B 条带")
        }
        // 只有 SEI、AUD 之类的帧不送进解码器。
        if (scan.sliceCount == 0 && !scan.isParameterSetsOnly) return false

        val current = codec
        val known = codecSps
        val latest = params.sps
        if (current != null && known != null && latest != null) {
            val formatChanged = needsNewDecoder(known, latest)
            // 按解码顺序立即输出遇上要重排的 B 帧，显示顺序会乱，得换成正常输出的配置。
            // SPS 已经声明不需要重排时，B 条带也是按显示顺序来的，不用换。
            val reorderUnsafe = codecDecodeOrder && sawBSlice && !known.reorderFree
            if (formatChanged || reorderUnsafe) {
                Log.i(
                    TAG,
                    if (formatChanged) {
                        "码流格式变了：${known.width}x${known.height} → ${latest.width}x${latest.height}"
                    } else {
                        "出现 B 帧，改用按显示顺序输出的配置"
                    },
                )
                // 这一帧就能开始解码时当场重建，不算丢帧，也不通知。
                teardownLocked(if (scan.isSyncPoint) null else ResyncReason.FORMAT_CHANGED)
            }
        }

        if (waitingForSync && !scan.isSyncPoint) {
            // 等关键帧期间单独发来的参数集已经记下了，不算丢帧。
            return if (scan.isParameterSetsOnly) false else drop(null)
        }
        if (codec == null) {
            val sps = params.sps
            if (sps == null || !params.isComplete) {
                Log.w(TAG, "关键帧之前没收到完整的 SPS/PPS，继续等")
                return drop(null)
            }
            if (!configureLocked(sps)) return drop(null)
        }
        waitingForSync = false

        val nowMs = now()
        if (scan.isSyncPoint) startChainLocked(nowMs)
        when (cache.append(data, offset, length, scan.isParameterSetsOnly, nowMs, streamPtsUs)) {
            FrameCache.REJECTED -> {
                // 已经送进去和排在前面的帧照样送：解码器的参考帧还是对的，只是这一帧以后的接不上了。
                Log.w(TAG, "解码器迟迟不收帧，${cache.pendingEntries} 帧在等输入缓冲，缓存放不下了")
                return drop(ResyncReason.INPUT_TIMEOUT)
            }
            FrameCache.APPENDED_HISTORY_DROPPED -> Log.i(
                TAG,
                "缓存超过上限（${options.cacheMaxFrames} 帧 / ${options.cacheMaxBytes / 1024} KiB），作废：" +
                    "到下一个关键帧之前，切回投屏画面时不能重放，只能等手机发新帧",
            )
        }
        pumpLocked()
        return true
    }

    /** 能开始解码的帧到了：缓存从它重新开始，段首先存一份当前的参数集。 */
    private fun startChainLocked(nowMs: Long) {
        val lost = cache.startChain()
        if (lost > 0) {
            stats.onDropped(lost)
            Log.i(TAG, "关键帧到了，还没送进解码器的 $lost 帧作废")
        }
        // 解码器手里已经有这份参数集，所以记为已送，只在重放时先送它：flush 后有的解码器要重新给参数集，
        // 这一段中途换过参数集时也不会拿新的去解前面的帧。
        val config = params.csd0() + params.csd1()
        cache.append(config, 0, config.size, config = true, nowMs = nowMs, delivered = true)
    }

    private fun drop(reason: ResyncReason?): Boolean {
        stats.onDropped()
        requireSyncLocked(reason)
        return false
    }

    /** 按顺序把缓存里要送的条目送进空闲输入缓冲，直到送完、没有空闲缓冲，或者解码器要换掉。 */
    private fun pumpLocked() {
        while (!closed && !flushing) {
            val c = codec ?: return
            if (c === retiringCodec) return
            val seq = cache.nextToFeed()
            if (seq < 0) return
            val index = freeInputs.pollFirst() ?: return
            if (!feedEntryLocked(c, index, seq)) return
        }
    }

    /**
     * 把条目 [seq] 送进输入缓冲 [index]。序号已经失效（例如 flush 前发出、晚到的回调）就扔掉这个序号，条目留着下次送；
     * 解码器出了问题就排上换掉它，返回 false。
     */
    private fun feedEntryLocked(c: MediaCodec, index: Int, seq: Long): Boolean {
        val length = cache.length(seq)
        val config = cache.isConfig(seq)
        val replay = cache.isReplay(seq)
        val buffer: ByteBuffer? = try {
            c.getInputBuffer(index)
        } catch (e: IllegalStateException) {
            null
        }
        if (buffer == null) {
            Log.w(TAG, "输入缓冲 $index 已经失效，跳过")
            return true
        }
        try {
            if (length > buffer.capacity()) {
                growInputLocked(c, length, buffer.capacity())
                return false
            }
            buffer.clear()
            cache.copyTo(seq, buffer)
            val streamPtsUs = cache.streamPtsUs(seq)
            val ptsUs = if (!replay && options.useStreamTimestamps && streamPtsUs >= 0) streamPtsUs else nextPtsUs()
            // 先记下再送：出帧回调最早也在送进去之后。
            if (replay && !config) replays.onQueued(ptsUs, seq == cache.replayLastSeq)
            c.queueInputBuffer(index, 0, length, ptsUs, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "送入解码器失败，换解码器", e)
            retireLocked(c, ResyncReason.DECODER_ERROR)
            return false
        }
        val nowMs = now()
        if (!replay && !config && freshFedByCodec < LOG_FIRST_FEEDS) {
            freshFedByCodec++
            Log.i(TAG, "解码器收下第 $freshFedByCodec 帧：到达后等了 ${nowMs - cache.arrivalMs(seq)}ms")
        }
        cache.markFed(nowMs)
        if (!config) {
            if (replay) stats.onReplayQueued(nowMs) else stats.onQueued(nowMs)
        }
        return true
    }

    private fun nextPtsUs(): Long {
        val now = System.nanoTime() / 1000
        lastPtsUs = if (now > lastPtsUs) now else lastPtsUs + 1
        return lastPtsUs
    }

    // ---------------------------------------------------------------- 重放

    /**
     * 输出刚切到新的视图 Surface，上面还什么都没有：在解码线程上排一次重放。
     * 手机刚才还在出帧时先等一小会儿，等来新画面就不用重放。
     */
    private fun scheduleReplayLocked(c: MediaCodec) {
        val generation = surfaceGeneration
        val shownMark = freshFramesShown
        val delayMs = DecoderTiming.replayDelayMs(now(), lastArrivalMs)
        handler.postDelayed({ lock.withLock { replayLocked(c, generation, shownMark) } }, delayMs)
    }

    /** 解码线程：flush 解码器，从缓存段首起重送，只上屏最后一帧。不能重放就保持原样，等手机的新帧。 */
    private fun replayLocked(c: MediaCodec, generation: Int, shownMark: Long) {
        if (closed || codec !== c || c === retiringCodec || generation != surfaceGeneration || outputOnPlaceholder) return
        // 上一次重放刚 flush、还没重新开始：它的帧接下来就画到现在这个 Surface 上，不用再来一次。
        if (flushing) return
        if (freshFramesShown != shownMark) {
            Log.i(TAG, "切回后手机已经发来新画面，不用重放")
            return
        }
        val frames = cache.replayableFrames
        if (frames <= 0) {
            Log.i(TAG, if (frames < 0) "缓存超过上限已作废，不能重放，等手机发新帧" else "缓存里没有送过的帧，不用重放")
            return
        }
        try {
            c.flush()
        } catch (e: RuntimeException) {
            Log.w(TAG, "重放前 flush 失败，释放解码器", e)
            teardownLocked(ResyncReason.DECODER_ERROR)
            return
        }
        // flush 之后之前拿到的输入、输出序号都作废，解码器要重新 start 才给输入缓冲。
        flushing = true
        freeInputs.clear()
        presenter.drop()
        forgetHeld()
        handler.removeCallbacks(holdTimeout)
        stats.onDecoderReset(now())
        replays.reset()
        cache.rewind(now())
        replayStartedMs = now()
        replayFrames = frames
        Log.i(TAG, "从关键帧起重放缓存里的 $frames 帧，只上屏最后一帧")
        // 排在 flush 之前已经发出来的回调后面：那些回调执行时 flushing 还是 true，会被丢掉。
        handler.post { lock.withLock { resumeAfterFlushLocked(c) } }
    }

    private fun resumeAfterFlushLocked(c: MediaCodec) {
        if (codec !== c || !flushing) return
        flushing = false
        try {
            c.start()
        } catch (e: RuntimeException) {
            Log.w(TAG, "flush 后重新开始失败，释放解码器", e)
            teardownLocked(ResyncReason.DECODER_ERROR)
        }
    }

    /** 解码线程：扣着的重放帧一段时间没等来下一个（解码器可能扣着最后一帧），先把它上屏。 */
    private fun onReplayHoldTimeout() {
        val c = codec
        if (c == null || c !== outputCodec || flushing) {
            presenter.drop()
            return
        }
        if (!presenter.holding) return
        val visible = !outputOnPlaceholder
        Log.i(TAG, "重放的最后一帧 ${DecoderTiming.REPLAY_HOLD_MS}ms 没出来（解码器可能扣着它），先上屏最近交出的一帧")
        presenter.onIdle(visible)
    }

    /** [presenter] 的出口（解码线程）：要上屏的按节拍送，不上屏的直接还给解码器。 */
    private fun releaseOutput(index: Int, render: Boolean) {
        if (render) showOutput(index) else releaseNow(index, false)
    }

    private fun releaseNow(index: Int, render: Boolean) {
        try {
            outputCodec?.releaseOutputBuffer(index, render)
        } catch (e: IllegalStateException) {
            // 解码器刚被别的线程释放。
        }
    }

    // ---------------------------------------------------------------- 上屏节拍（解码线程）

    /** 一帧要上屏（输出在视图 Surface 上）：按节拍马上送，或者扣到分给它的那一拍之前。 */
    private fun showOutput(index: Int) {
        val now = System.nanoTime()
        lastShowNs = now
        requestVsync()
        val paced = framePacing
        // 到点还没送的先送（定时器晚了）；刚把节拍关掉就全部送掉。顺序不乱。
        while (pacer.queued > 0 && (!paced || pacer.nextReleaseAtNs <= now)) releaseHead(now)
        when (pacer.onFrame(now, vsyncGrid, deadlineNs(), paced)) {
            FramePacer.SHOW_NOW -> {
                releaseNow(index, true)
                stats.onPacing(pacer.sameLatch, replaced = false, unpaced = false)
                stats.onShown(pacer.shownLatchNs - pacer.shownOutputNs, pacer.shownExtraNs)
            }
            FramePacer.HOLD -> {
                heldIndices[pacer.queued - 1] = index
                stats.onPacing(pacer.sameLatch, replaced = false, unpaced = false)
                scheduleHeadRelease(now)
            }
            FramePacer.REPLACE -> {
                // 队尾那一帧要晚两拍才轮得到：不再显示，还给解码器，这一帧接替它。
                val tail = pacer.queued - 1
                releaseNow(heldIndices[tail], false)
                heldIndices[tail] = index
                stats.onPacing(pacer.sameLatch, replaced = true, unpaced = false)
            }
            else -> {
                // 还没有刷新网格（刚开始，或者停了一阵）：立即送显，扣着的（一般没有）先送。
                while (pacer.queued > 0) releaseHead(now)
                releaseNow(index, true)
                stats.onPacing(sameLatch = false, replaced = false, unpaced = true)
            }
        }
    }

    private fun onHeldDue() {
        val now = System.nanoTime()
        // Handler 按毫秒计时，可能比约定的早不到 1 毫秒到点。
        while (pacer.queued > 0 && pacer.nextReleaseAtNs <= now + TIMER_SLACK_NS) releaseHead(now)
        scheduleHeadRelease(now)
    }

    private fun scheduleHeadRelease(nowNs: Long) {
        handler.removeCallbacks(heldDue)
        if (pacer.queued > 0) handler.postDelayed(heldDue, ceilMs(pacer.nextReleaseAtNs - nowNs))
    }

    /** 送出队首扣着的帧：输出不在视图 Surface 上就不上屏；解码器已经换了、正在 flush 就只出队、不送。 */
    private fun releaseHead(nowNs: Long) {
        val index = heldIndices[0]
        for (i in 1 until pacer.queued) heldIndices[i - 1] = heldIndices[i]
        pacer.onHeadDue(nowNs, vsyncGrid, deadlineNs())
        val c = outputCodec
        if (c == null || c !== codec || flushing) return
        val visible = !outputOnPlaceholder
        releaseNow(index, visible)
        if (visible) stats.onShown(pacer.shownLatchNs - pacer.shownOutputNs, pacer.shownExtraNs)
    }

    /**
     * 输出换了 Surface：节拍从头来。扣着的帧里最新的一帧，输出在视图 Surface 上时照样上屏，其余的还给解码器：
     * 它出帧时已经算作手机发来的新画面（[freshFramesShown]），切回投屏页的重放会因此跳过，不上屏就会停在旧画面上。
     */
    private fun resetPacer() {
        handler.removeCallbacks(heldDue)
        val c = outputCodec
        if (c != null && c === codec && !flushing) {
            val visible = !outputOnPlaceholder
            val newest = pacer.queued - 1
            for (i in 0..newest) releaseNow(heldIndices[i], visible && i == newest)
        }
        pacer.reset()
    }

    /** 扣着的序号已经失效（flush 了，或者换了解码器）：只忘掉，不还；节拍从头来。 */
    private fun forgetHeld() {
        handler.removeCallbacks(heldDue)
        pacer.reset()
    }

    /** 有帧要显示时要刷新回调（每次只要一次，回调里再要下一次）。 */
    private fun requestVsync() {
        if (vsyncRequested || closed || choreographerUnavailable) return
        val ch = choreographer ?: try {
            Choreographer.getInstance().also { choreographer = it }
        } catch (e: RuntimeException) {
            choreographerUnavailable = true
            Log.w(TAG, "拿不到 Choreographer，不按节拍送显：${e.message}")
            return
        }
        ch.postFrameCallback(vsyncCallback)
        vsyncRequested = true
    }

    /** 一次刷新：帧时间换算成实际刷新时刻记进网格；最近还有帧要显示就接着要下一次。 */
    private fun onVsync(frameTimeNanos: Long) {
        vsyncRequested = false
        if (closed) return
        val offset = displayTiming?.appVsyncOffsetNs?.takeIf { abs(it) < MAX_PLAUSIBLE_TIMING_NS } ?: 0L
        vsyncGrid.onRefresh(frameTimeNanos - offset)
        val now = System.nanoTime()
        if (!gridLogged && vsyncGrid.isUsable(now)) {
            gridLogged = true
            Log.i(TAG, "拿到刷新节拍：周期 ${vsyncGrid.periodNs}ns，送显提前量 ${deadlineNs()}ns，上屏节拍${if (framePacing) "开" else "关"}")
        }
        if (now - lastShowNs < VSYNC_IDLE_NS) requestVsync()
    }

    /** 送显提前量（只用相位）：屏幕给的；不知道就按一个刷新周期估。 */
    private fun deadlineNs(): Long {
        val given = displayTiming?.presentationDeadlineNs ?: 0L
        return if (given in 1 until MAX_PLAUSIBLE_TIMING_NS) given else vsyncGrid.periodNs
    }

    // ---------------------------------------------------------------- 解码器生命周期

    private fun configureLocked(sps: H264Sps): Boolean {
        val surface = outputSurfaceLocked(sps)
        if (surface == null) {
            requireSyncLocked(ResyncReason.SURFACE_LOST)
            return false
        }
        val csd0 = params.csd0()
        val csd1 = params.csd1()
        val decodeOrder = sps.reorderFree || !sawBSlice
        val inputSize = maxOf(maxInputSize, sps.codedWidth * sps.codedHeight, MIN_INPUT_SIZE)
        val candidates: List<DecoderCatalog.Candidate?> =
            DecoderCatalog.candidates(sps.width, sps.height).ifEmpty { listOf(null) }
        for (candidate in candidates) {
            val vendorKeys = if (options.vendorLowLatency && candidate != null) {
                VendorLowLatency.keys(candidate.name, decodeOrder)
            } else {
                emptyList()
            }
            val tiers = if (vendorKeys.isEmpty()) TIERS_WITHOUT_VENDOR else TIERS
            for (tier in tiers) {
                val c = createCodec(candidate) ?: break
                val format = buildFormat(candidate, tier, sps, csd0, csd1, inputSize, vendorKeys)
                if (startCodec(c, format, surface, tier)) {
                    installLocked(c, sps, decodeOrder, surface, tier)
                    return true
                }
                releaseQuietly(c)
            }
        }
        Log.e(TAG, "没有能用的 H.264 解码器（${sps.width}x${sps.height}）")
        if (placeholder?.surface === surface) closePlaceholderLocked()
        val detail = "${sps.width}x${sps.height}"
        notifyListener { onDecoderUnavailable(detail) }
        requireSyncLocked(ResyncReason.DECODER_ERROR)
        return false
    }

    private fun createCodec(candidate: DecoderCatalog.Candidate?): MediaCodec? = try {
        if (candidate != null) {
            MediaCodec.createByCodecName(candidate.name)
        } else {
            MediaCodec.createDecoderByType(DecoderCatalog.MIME)
        }
    } catch (e: IOException) {
        Log.w(TAG, "建不了解码器 ${candidate?.name ?: DecoderCatalog.MIME}：${e.message}")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "建不了解码器 ${candidate?.name ?: DecoderCatalog.MIME}：${e.message}")
        null
    }

    private fun startCodec(c: MediaCodec, format: MediaFormat, surface: Surface, tier: Int): Boolean = try {
        c.setCallback(codecCallback, handler)
        try {
            c.setOnFrameRenderedListener(renderListener, handler)
        } catch (e: RuntimeException) {
            // 上屏回调只用于统计，注册不上就不统计上屏帧数，不影响解码。
            Log.w(TAG, "解码器 ${c.name} 不支持上屏回调：${e.message}")
        }
        c.configure(format, surface, null, 0)
        c.start()
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "解码器 ${c.name} 配置失败（档位 $tier）：${e.message}")
        false
    }

    /**
     * 档位：[TIER_FULL] 标准低延迟设置 + 厂商键，[TIER_STANDARD] 只有标准设置，[TIER_INPUT_SIZE] 只指定输入缓冲大小，
     * [TIER_BARE] 只有尺寸和参数集。
     */
    private fun buildFormat(
        candidate: DecoderCatalog.Candidate?,
        tier: Int,
        sps: H264Sps,
        csd0: ByteArray,
        csd1: ByteArray,
        inputSize: Int,
        vendorKeys: List<Pair<String, Int>>,
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(DecoderCatalog.MIME, sps.width, sps.height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
        if (tier <= TIER_INPUT_SIZE) format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize)
        if (tier <= TIER_STANDARD) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = 实时
            if (hintFps > 0) format.setInteger(MediaFormat.KEY_OPERATING_RATE, hintFps)
            // 不支持低延迟模式的解码器碰上这个键可能直接配置失败，所以只给声明支持的。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && candidate?.lowLatency == true) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        if (tier <= TIER_FULL) {
            for ((key, value) in vendorKeys) format.setInteger(key, value)
        }
        return format
    }

    private fun installLocked(c: MediaCodec, sps: H264Sps, decodeOrder: Boolean, surface: Surface, tier: Int) {
        codec = c
        codecName = c.name
        codecSps = sps
        codecDecodeOrder = decodeOrder
        retiringCodec = null
        flushing = false
        outputOnPlaceholder = surface !== viewSurface
        if (!outputOnPlaceholder) closePlaceholderLocked()
        replays.reset()
        installedAtMs = now()
        firstInputSeen = false
        freshFedByCodec = 0
        stats.onDecoderReset(installedAtMs)
        Log.i(
            TAG,
            "解码器 ${c.name} 已启动：${sps.width}x${sps.height}，profile ${sps.profileIdc}，档位 $tier，" +
                "按解码顺序输出=$decodeOrder，输出到${if (outputOnPlaceholder) "占位 Surface" else "视图 Surface"}，" +
                "上屏节拍${if (framePacing) "开" else "关"}",
        )
        val width = sps.width
        val height = sps.height
        handler.post { updateVideoSize(width, height) }
    }

    /** 释放当前解码器（没有就什么都不做），缓存作废，之后丢帧等关键帧；[reason] 为 null 时不通知。 */
    private fun teardownLocked(reason: ResyncReason?) {
        val released = releaseCodecLocked()
        if (released != null) Log.i(TAG, "解码器已释放" + (reason?.let { "（$it）" } ?: ""))
        val lost = cache.clear()
        if (lost > 0) stats.onDropped(lost)
        requireSyncLocked(reason)
    }

    /** 只释放解码器本身（没有就什么都不做），缓存不动。返回释放掉的解码器。 */
    private fun releaseCodecLocked(): MediaCodec? {
        val c = codec
        codec = null
        codecName = null
        codecSps = null
        codecDecodeOrder = false
        retiringCodec = null
        flushing = false
        freeInputs.clear()
        if (c != null) releaseQuietly(c)
        closePlaceholderLocked()
        outputOnPlaceholder = false
        stats.onDecoderReset(now())
        return c
    }

    /** 当前解码器不能再用了（可能正在它自己的回调里）：先不再往里送，释放排到解码线程上、回调返回之后。 */
    private fun retireLocked(c: MediaCodec, reason: ResyncReason) {
        if (codec !== c || retiringCodec === c) return
        retiringCodec = c
        handler.post { lock.withLock { if (codec === c) teardownLocked(reason) } }
    }

    /**
     * 一帧比输入缓冲还大：按更大的缓冲重建解码器（排到解码线程上），从缓存段首起补送。
     * 已经按够大的值要过缓冲、解码器给的还是不够（例如不认这个设置）时只重建这一次，不再重建，等下一个关键帧。
     */
    private fun growInputLocked(c: MediaCodec, length: Int, capacity: Int) {
        if (codec !== c || retiringCodec === c) return
        if (maxInputSize >= length) {
            Log.w(TAG, "一帧 $length 字节，输入缓冲只有 $capacity 字节，已经要过 $maxInputSize 字节，不再重建")
            retireLocked(c, ResyncReason.INPUT_TOO_LARGE)
            return
        }
        val grown = grownInputSize(length)
        Log.w(TAG, "一帧 $length 字节，超过输入缓冲 $capacity 字节，按 $grown 字节重建解码器")
        maxInputSize = grown
        retiringCodec = c
        handler.post { lock.withLock { if (codec === c) rebuildFromCacheLocked(length) } }
    }

    /** 解码线程：换一个解码器，从缓存段首起补送，不用等手机的下一个关键帧。缓存不完整、或者帧大过缓冲上限时只能等关键帧。 */
    private fun rebuildFromCacheLocked(length: Int) {
        val sps = params.sps
        if (sps == null || length > maxInputSize || !cache.historyComplete) {
            teardownLocked(ResyncReason.INPUT_TOO_LARGE)
            return
        }
        releaseCodecLocked()
        if (!configureLocked(sps)) {
            val lost = cache.clear()
            if (lost > 0) stats.onDropped(lost)
            return
        }
        val frames = cache.rewind(now())
        replayStartedMs = now()
        replayFrames = frames
        Log.i(TAG, "新解码器从关键帧起补送缓存：先重放 $frames 帧（只上屏最后一帧），再接着送 ${cache.pendingEntries} 个条目里剩下的")
    }

    private fun requireSyncLocked(reason: ResyncReason?) {
        if (!waitingForSync) {
            waitingForSync = true
            waitNotified = false
        }
        if (reason != null && !waitNotified) {
            waitNotified = true
            Log.i(TAG, "丢帧等关键帧：$reason")
            notifyListener { onKeyFrameNeeded(reason) }
        }
    }

    private fun outputSurfaceLocked(sps: H264Sps): Surface? {
        viewSurface?.let { if (it.isValid) return it }
        if (!options.decodeWithoutSurface) return null
        return placeholderLocked(sps.width, sps.height)
    }

    /**
     * 占位 Surface：一个私有格式的 ImageReader。在它上面时出帧不送显，解码器只是照常解码、维持参考帧；
     * 万一有帧送过来，取出来直接丢掉，免得占住缓冲。
     */
    private fun placeholderLocked(width: Int, height: Int): Surface? {
        placeholder?.let { return it.surface }
        return try {
            val reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, PLACEHOLDER_IMAGES)
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.close()
                } catch (e: RuntimeException) {
                    // 读者已关闭，或者取图失败：丢掉即可。
                }
            }, handler)
            placeholder = reader
            reader.surface
        } catch (e: RuntimeException) {
            Log.w(TAG, "建不了占位 Surface：${e.message}")
            null
        }
    }

    private fun closePlaceholderLocked() {
        val reader = placeholder ?: return
        placeholder = null
        try {
            reader.close()
        } catch (e: RuntimeException) {
            // 关闭失败也不影响后续。
        }
    }

    private fun switchOutputLocked(c: MediaCodec, target: Surface): Boolean = try {
        c.setOutputSurface(target)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "切换输出 Surface 失败：${e.message}")
        false
    }

    private fun releaseQuietly(c: MediaCodec) {
        try {
            c.stop()
        } catch (e: RuntimeException) {
            // 出错状态下 stop 会抛异常，照样 release。
        }
        try {
            c.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "释放解码器失败：${e.message}")
        }
    }

    /** 输入缓冲按至少多 1/4 的余量翻倍，上限 [MAX_INPUT_SIZE]。 */
    private fun grownInputSize(length: Int): Int {
        var size = maxOf(maxInputSize, MIN_INPUT_SIZE)
        while (size < length + length / 4 && size < MAX_INPUT_SIZE) size *= 2
        return size.coerceAtMost(MAX_INPUT_SIZE)
    }

    private fun needsNewDecoder(old: H264Sps, new: H264Sps): Boolean =
        old.width != new.width || old.height != new.height ||
            old.codedWidth != new.codedWidth || old.codedHeight != new.codedHeight ||
            old.profileIdc != new.profileIdc || old.chromaFormatIdc != new.chromaFormatIdc ||
            old.bitDepthLuma != new.bitDepthLuma || old.bitDepthChroma != new.bitDepthChroma ||
            old.frameMbsOnly != new.frameMbsOnly || old.maxNumRefFrames != new.maxNumRefFrames

    // ---------------------------------------------------------------- 解码线程

    private inner class CodecCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            lock.withLock {
                if (c !== codec || flushing) return
                if (!firstInputSeen) {
                    firstInputSeen = true
                    Log.i(TAG, "解码器启动后 ${now() - installedAtMs}ms 给出第一个输入缓冲")
                }
                freeInputs.addLast(index)
                pumpLocked()
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec || flushing) return
            if (outputCodec !== c) {
                // 扣着的是上一个解码器的输出，序号已经失效。
                presenter.drop()
                forgetHeld()
                outputCodec = c
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                releaseOutput(index, false)
                return
            }
            val visible = !outputOnPlaceholder
            val kind = replays.classify(info.presentationTimeUs)
            handler.removeCallbacks(holdTimeout)
            if (presenter.onOutput(index, kind, visible)) handler.postDelayed(holdTimeout, DecoderTiming.REPLAY_HOLD_MS)
            when (kind) {
                ReplayTracker.FRESH -> {
                    if (visible) freshFramesShown++
                    // 用手机时间戳对照时算不出入队到出帧的时间，延迟记为无效值（统计里不计入）。
                    val latencyUs =
                        if (options.useStreamTimestamps) -1L else System.nanoTime() / 1000 - info.presentationTimeUs
                    stats.onDecoded(latencyUs)
                }
                ReplayTracker.REPLAY_LAST -> {
                    stats.onReplayDecoded()
                    Log.i(
                        TAG,
                        "重放完成：最后一帧${if (visible) "已上屏" else "没上屏（视图 Surface 已经没了）"}，" +
                            "重放 $replayFrames 帧用时 ${now() - replayStartedMs}ms",
                    )
                }
                else -> stats.onReplayDecoded()
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec) return
            // 只认带裁剪信息的尺寸；没有裁剪信息时宽高可能是按宏块对齐的编码尺寸，以 SPS 为准。
            val size = try {
                if (format.containsKey(KEY_CROP_LEFT) && format.containsKey(KEY_CROP_RIGHT) &&
                    format.containsKey(KEY_CROP_TOP) && format.containsKey(KEY_CROP_BOTTOM)
                ) {
                    (format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1) to
                        (format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1)
                } else {
                    null
                }
            } catch (e: RuntimeException) {
                null
            }
            Log.i(TAG, "解码器输出格式：$format")
            if (size != null && size.first > 0 && size.second > 0) updateVideoSize(size.first, size.second)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (e.isTransient) {
                Log.w(TAG, "解码器暂时性错误，继续：${e.diagnosticInfo}")
                return
            }
            Log.w(TAG, "解码器出错（可恢复=${e.isRecoverable}）：${e.diagnosticInfo}")
            // 不在 MediaCodec 的回调里直接释放它，排到回调返回之后。
            lock.withLock { retireLocked(c, ResyncReason.DECODER_ERROR) }
        }
    }

    private fun onTick() {
        val now = now()
        val waitingForInput: Int
        lock.withLock {
            val c = codec
            if (c != null && !flushing && c !== retiringCodec) {
                val inputWaitMs = cache.inputWaitMs(now)
                val inputs = stats.inputsSinceOutput
                val waitStart = stats.outputWaitStartMs
                when (DecoderTiming.stall(now, inputs, waitStart, inputWaitMs, options.stallTimeoutMs)) {
                    DecoderTiming.Stall.NO_OUTPUT -> {
                        Log.w(TAG, "解码器收了 $inputs 帧、${now - waitStart}ms 没有出帧，判为卡死")
                        teardownLocked(ResyncReason.DECODER_STALLED)
                    }
                    DecoderTiming.Stall.NO_INPUT -> {
                        Log.w(TAG, "${cache.pendingEntries} 帧在等输入缓冲，解码器 ${inputWaitMs}ms 一个都没给，判为卡死")
                        teardownLocked(ResyncReason.DECODER_STALLED)
                    }
                    DecoderTiming.Stall.NONE -> Unit
                }
            }
            waitingForInput = cache.pendingEntries
        }
        val snapshot = stats.snapshot(
            now, notifiedWidth, notifiedHeight, codecName, waitingForSync, waitingForInput, framePacing, vsyncGrid.periodNs,
        )
        lastStats = snapshot
        if (options.statsLogEvery > 0 && ++ticksSinceLog >= options.statsLogEvery) {
            ticksSinceLog = 0
            Log.i(TAG, snapshot.toString())
        }
        try {
            listener.onStats(snapshot)
        } catch (e: RuntimeException) {
            Log.e(TAG, "统计回调出错", e)
        }
    }

    private fun updateVideoSize(width: Int, height: Int) {
        if (width == notifiedWidth && height == notifiedHeight) return
        notifiedWidth = width
        notifiedHeight = height
        try {
            listener.onVideoSizeChanged(width, height)
        } catch (e: RuntimeException) {
            Log.e(TAG, "尺寸回调出错", e)
        }
    }

    /** 回调统一排到解码线程上，并接住回调里的异常，免得打断解码线程。 */
    private inline fun notifyListener(crossinline call: Listener.() -> Unit) {
        handler.post {
            try {
                listener.call()
            } catch (e: RuntimeException) {
                Log.e(TAG, "回调出错", e)
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "OpenHC-Video"

        const val TIER_FULL = 0
        const val TIER_STANDARD = 1
        const val TIER_INPUT_SIZE = 2
        const val TIER_BARE = 3
        val TIERS = intArrayOf(TIER_FULL, TIER_STANDARD, TIER_INPUT_SIZE, TIER_BARE)
        val TIERS_WITHOUT_VENDOR = intArrayOf(TIER_STANDARD, TIER_INPUT_SIZE, TIER_BARE)

        /** 输入缓冲至少这么大：画面很小时，关键帧也可能比按像素数估的大。 */
        const val MIN_INPUT_SIZE = 512 * 1024
        const val MAX_INPUT_SIZE = 16 * 1024 * 1024

        const val PLACEHOLDER_IMAGES = 2

        /** 每个新解码器头几帧打一行「到达后等了多久才送进去」。 */
        const val LOG_FIRST_FEEDS = 3

        /** 这么久（1 秒）没有帧要显示，就不再要刷新回调；下一帧来了再要。 */
        const val VSYNC_IDLE_NS = 1_000_000_000L

        /** 屏幕给的帧时间偏移、送显提前量超过这个值（0.1 秒）就当读错了。 */
        const val MAX_PLAUSIBLE_TIMING_NS = 100_000_000L

        /** 送扣着的帧时，约定时刻在这么近（1 毫秒）以内就算到点。 */
        const val TIMER_SLACK_NS = 1_000_000L

        /** 纳秒换成 Handler 用的毫秒，向上取整，不小于 0。 */
        fun ceilMs(ns: Long): Long = if (ns <= 0) 0 else (ns + 999_999) / 1_000_000

        // 输出格式里的裁剪区域（含两端）。安卓 13 才有公开常量，键名本身一直是这几个。
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"
    }
}
