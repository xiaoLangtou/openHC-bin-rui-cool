package io.github.gcbond.openhc.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 开关车机热点的小程序（[HotspotSwitchMain]）的命令行和输出。 */
object HotspotSwitchCommand {
    /** 小程序的入口类（安装包里的 [HotspotSwitchMain]）。 */
    const val MAIN_CLASS = "io.github.gcbond.openhc.wifi.HotspotSwitchMain"

    val helper = AdbHelper(
        MAIN_CLASS,
        HotspotSwitchMain.HEADER,
        setOf(HotspotSwitchMain.START, HotspotSwitchMain.STOP, HotspotSwitchMain.CHECK),
    )
}

/**
 * 「自动打开车机热点」（可选，默认关）。无线方式选「车机自带热点」时，手机认证通过、要加入热点那一刻，热点没开着就经车机本机的
 * 网络 ADB 打开（用车机设置里保存的热点名称、密码、频段），这次连接结束时关掉。
 *
 * 只做能还原的改动，规则：
 * - 打开前先在磁盘上记下「待关闭」，再去开；进程中途没了，下次启动、开机或点火、开始无线连接时（[recover]）按记号关掉；
 * - 热点本来就开着（车主自己开的）就不动它，结束时也不关；只关 OpenHC 这次打开的（[close] 带连接编号，别的连接关不了）；
 * - 关不成（例如 ADB 一时连不上）每 [RETRY_MILLIS] 重试，最多 [RETRY_LIMIT] 次，记号留着等下次 [recover]；
 * - 没打开成（ADB 没开、没授权、系统不支持）就什么都没改，记号清掉；结果不明（超时）时按打开了处理，结束时照样关；
 * - 上次留下的记号还在、热点又正开着：多半是上次没关成的那个，这次接手、用完关掉；
 * - 打开、关闭、补关都排在同一个后台线程上按顺序跑：上一次连接排着的关闭先跑完，下一次连接才打开，不会把新开的关掉；
 * - 后台一律不弹授权框；只有车主在设置里打开时（[check]）才请求授权。
 */
class CarHotspotAuto(
    private val runner: AdbHelperRunner,
    private val journal: PendingJournal,
    private val worker: AdbWorker,
    private val log: (String) -> Unit,
) {
    enum class Phase { IDLE, CHECKING, OPENING, OPEN, CLOSING }

    /** 设置页显示用：正在做什么、上一次的结果。 */
    data class Status(val phase: Phase = Phase.IDLE, val last: AdbSwitchResult? = null)

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    // 下面的状态只在 worker 线程上读写。
    private var owner: Int? = null
    private var retries = 0
    private var cancelRetry: (() -> Unit)? = null

    /**
     * 第 [id] 次连接要用热点：没开着就打开，等它开好。阻塞调用方（热点自己的工作线程），最多 [waitMillis]；
     * 等不到算 [AdbSwitchResult.FAILED]：还排着没开始的就不开了（这次连接已经不等它），已经在开的照样跑完，
     * 打开了的话结束时 [close] 照样关。
     */
    fun open(id: Int, waitMillis: Long = OPEN_WAIT_MILLIS): AdbSwitchResult {
        val done = CountDownLatch(1)
        val state = AtomicInteger(QUEUED)
        var result = AdbSwitchResult.FAILED
        worker.execute {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                log("第 $id 次连接已经不等了，不再自动打开车机热点")
                return@execute
            }
            try {
                result = openNow(id)
            } finally {
                done.countDown()
            }
        }
        if (done.await(waitMillis, TimeUnit.MILLISECONDS)) return result
        state.compareAndSet(QUEUED, ABANDONED)
        return AdbSwitchResult.FAILED
    }

    /** 第 [id] 次连接结束（不管打开过没有都可以调）：这次打开的就关掉。 */
    fun close(id: Int) = worker.execute {
        if (owner != id) return@execute
        owner = null
        if (journal.pending) closeNow()
    }

    /**
     * 补关：上次打开了、还没关（应用中途退出，或者关一直没成功）就关掉。进程启动、开机或点火、每次开始无线连接时调。
     * [why] 只用于日志。
     */
    fun recover(why: String) = worker.execute {
        if (journal.pending && owner == null) {
            log("上次自动打开的车机热点还没关，现在关掉（$why）")
            closeNow()
        }
    }

    /** 车主在设置里打开时检查一遍：会请求授权（车机弹框），确认小程序跑得起来。不开热点。 */
    fun check() = worker.execute {
        update(Phase.CHECKING)
        val result = runner.run(HotspotSwitchMain.CHECK, ask = true)
        log("检查「自动打开车机热点」：$result")
        update(if (owner != null) Phase.OPEN else Phase.IDLE, result)
    }

    private fun openNow(id: Int): AdbSwitchResult {
        cancelRetries()
        val hadPending = journal.pending
        journal.pending = true
        update(Phase.OPENING)
        val result = runner.run(HotspotSwitchMain.START, ask = false)
        when (result) {
            AdbSwitchResult.DONE -> {
                owner = id
                log("已自动打开车机热点，这次连接结束时关掉")
            }
            AdbSwitchResult.ALREADY_ON -> if (hadPending) {
                owner = id
                log("车机热点开着，是之前自动打开、还没关的：这次用完关掉")
            } else {
                journal.pending = false
                log("车机热点本来就开着（不是 OpenHC 开的），用完不关")
            }
            AdbSwitchResult.FAILED -> {
                owner = id
                log("自动打开车机热点结果不明（$result），结束时照样关")
            }
            else -> {
                // 什么都没改；之前留下的待关记号保留。
                if (!hadPending) journal.pending = false
                log("没能自动打开车机热点：$result")
            }
        }
        update(if (owner == id) Phase.OPEN else Phase.IDLE, result)
        return result
    }

    private fun closeNow(retry: Boolean = false) {
        cancelRetries()
        if (!retry) retries = 0
        update(Phase.CLOSING)
        val result = runner.run(HotspotSwitchMain.STOP, ask = false)
        when (result) {
            AdbSwitchResult.DONE -> {
                journal.pending = false
                log("已关掉自动打开的车机热点")
            }
            // 系统没有这个接口、或者不让调：之前也不可能打开，不用再试。
            AdbSwitchResult.UNSUPPORTED, AdbSwitchResult.DENIED -> {
                journal.pending = false
                log("关车机热点：$result，不再重试")
            }
            else -> {
                if (retries < RETRY_LIMIT) {
                    retries++
                    log("关车机热点没成功（$result），${RETRY_MILLIS / 1000} 秒后第 $retries 次重试")
                    cancelRetry = worker.schedule(RETRY_MILLIS) {
                        cancelRetry = null
                        if (journal.pending && owner == null) closeNow(retry = true)
                    }
                } else {
                    log("关车机热点一直没成功（$result），下次开始无线连接、点火或打开应用时再试")
                }
            }
        }
        update(Phase.IDLE, result)
    }

    private fun cancelRetries() {
        cancelRetry?.invoke()
        cancelRetry = null
    }

    private fun update(phase: Phase, last: AdbSwitchResult? = mutableStatus.value.last) {
        mutableStatus.value = Status(phase, last)
    }

    companion object {
        const val RETRY_MILLIS = 15_000L
        const val RETRY_LIMIT = 8

        /** 连 ADB（几秒）+ 小程序最多 10 秒，再留一点。 */
        const val OPEN_WAIT_MILLIS = 14_000L

        private const val QUEUED = 0
        private const val RUNNING = 1
        private const val ABANDONED = 2
    }
}

/** 自动打开车机热点没成功时给车主看的原因；打开了、本来就开着时为 null。 */
fun AdbSwitchResult.hotspotOpenFailure(): String? = when (this) {
    AdbSwitchResult.DONE, AdbSwitchResult.READY, AdbSwitchResult.ALREADY_ON -> null
    AdbSwitchResult.ADB_OFF -> "车机的网络 ADB 没打开"
    AdbSwitchResult.NOT_APPROVED -> "车机没有授权 OpenHC 调试"
    AdbSwitchResult.TLS_ONLY -> "车机只支持加密的无线调试"
    AdbSwitchResult.UNSUPPORTED -> "车机系统不支持"
    AdbSwitchResult.DENIED -> "车机系统不让打开"
    AdbSwitchResult.FAILED -> "超时或出错"
}
