package io.github.gcbond.openhc.startup

import android.content.BroadcastReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.wireless.WirelessNetworkPlan
import io.github.gcbond.openhc.wireless.WirelessUiState

// 设置「开机静默连接」的安卓接线（判定见 SilentConnectPolicy）：开机广播进来后在后台开始等待（SilentBootWatch），
// 手机连上时把界面带到前台（ProjectionSurfacer）。

/**
 * 静默开机：按 [SilentConnectPolicy.route] 选路，在后台开始等插线或等回连，然后握着开机广播看等待建立起来没有
 * （[SilentConnectPolicy.check]），最多 [SilentConnectPolicy.SETTLE_LIMIT_MS]：两个调度都是投到自己的线程上才起前台服务，
 * 广播放手早了，进程可能在服务进前台之前就被收掉。后台开始不了就改走原来打开界面的路（[BootLaunchWatch]），
 * 由首页说明原因。全在主线程上。
 */
internal class SilentBootWatch(
    private val app: OpenHcApp,
    private val pending: BroadcastReceiver.PendingResult?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val atMillis = System.currentTimeMillis()
    private val sinceBootMillis = SystemClock.elapsedRealtime()
    private val installStamp = BootStart.installedAtMillis(app)
    private var route = BootRoute.OPEN_UI
    private var startedAt = 0L
    private var done = false

    fun start() {
        val kind = if (BootStartPolicy.isFreshBoot(sinceBootMillis)) "系统刚启动" else "系统已运行 ${sinceBootMillis / 60_000} 分钟"
        route = SilentConnectPolicy.route(
            silent = true,
            waitForCable = BootStart.waitForCable(app),
            identityLoaded = IdentityLoader.load(app) is IdentityLoader.Result.Loaded,
            vpnGranted = VpnConsent.isGranted(app),
            wirelessPermissionsGranted = BootStart.wirelessPermissionsGranted(app),
            hotspotSupported = WirelessNetworkPlan.hotspotSupported(Build.VERSION.SDK_INT),
        )
        Log.i(BootStart.TAG, "收到开机广播（$kind），开机静默连接开着：$route，安卓 API ${Build.VERSION.SDK_INT}")
        if (route.opensUi) return openUi("后台等待缺前提（$route）")
        // 先记一笔：进程之后被收掉的话，设置页至少知道开机广播送到了。
        record(BootOutcome.PENDING)
        if (route == BootRoute.SILENT_WIRED) startWired() else app.wireless.onSilentBoot()
        startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(::check, SilentConnectPolicy.POLL_MS)
    }

    /** 和界面上点「开始」一样的顺序：无线在等回连的话先让出，再开始有线。 */
    private fun startWired() {
        app.wireless.yieldToWired()
        app.connection.start()
    }

    private fun check() {
        if (done) return
        val deadline = SystemClock.elapsedRealtime() - startedAt >= SilentConnectPolicy.SETTLE_LIMIT_MS
        val result = SilentConnectPolicy.check(route, snapshot(), deadline)
        when (result) {
            SilentCheck.PENDING -> handler.postDelayed(::check, SilentConnectPolicy.POLL_MS)
            SilentCheck.OPEN_UI -> openUi("后台等待没建立起来（${snapshotText()}）")
            SilentCheck.WIRED_INSTEAD -> {
                route = SilentConnectPolicy.wiredRoute(VpnConsent.isGranted(app))
                if (route.opensUi) return openUi("用不了无线，改等插线也缺前提（$route）")
                Log.i(BootStart.TAG, "开机静默连接：这台车机用不了无线，改在后台等插线")
                startWired()
                startedAt = SystemClock.elapsedRealtime()
                handler.postDelayed(::check, SilentConnectPolicy.POLL_MS)
            }
            SilentCheck.WAITING, SilentCheck.STANDBY -> {
                done = true
                SilentConnectPolicy.outcome(route, result)?.let { record(it) }
                Log.i(BootStart.TAG, "开机静默连接：$result（${snapshotText()}），不打开界面")
                pending?.finish()
            }
        }
    }

    private fun snapshot(): SilentSnapshot {
        val wired = app.connection
        val wiredState = wired.state.value
        val wireless = app.wireless
        return SilentSnapshot(
            wiredWaiting = wired.wantsForeground && wired.serviceInForeground,
            wiredBlocked = !wiredState.active && wiredState.notice != null && !wired.wantsForeground,
            wirelessWaiting = wireless.wantsForeground && wireless.serviceInForeground,
            wirelessHold = wireless.state.value.autoHold,
        )
    }

    private fun snapshotText(): String {
        val s = snapshot()
        return "有线 ${if (s.wiredWaiting) "在等" else if (s.wiredBlocked) "开始不了" else "没在等"}，" +
            "无线 ${if (s.wirelessWaiting) "在等" else "没在等（${s.wirelessHold ?: "还没判断"}）"}"
    }

    /** 改走原来打开界面的路：开机广播交给它接着握。 */
    private fun openUi(why: String) {
        done = true
        handler.removeCallbacksAndMessages(null)
        Log.i(BootStart.TAG, "开机静默连接：$why，改为打开界面")
        BootLaunchWatch(app, pending).start()
    }

    private fun record(outcome: BootOutcome) {
        try {
            BootStart.saveRecord(app, BootRecord(atMillis, sinceBootMillis, outcome, installStamp))
        } catch (e: RuntimeException) {
            Log.w(BootStart.TAG, "记录开机结果失败：${e.message}")
        }
    }
}

/**
 * 开机静默连接开着时，手机连上了、而界面不在前台，就把界面带到前台（规则见 [SilentSurfaceTracker]）。
 * 有线、无线调度的状态一变（它们在主线程上通知前台服务的同时）就看一次。静默关着时什么都不做。
 *
 * 安卓 10 起后台应用打开界面要悬浮窗权限：没有的话请求不报错、界面不出来，这时前台服务的通知还在，点它回到本应用。
 */
internal class ProjectionSurfacer(private val app: OpenHcApp) {
    private val tracker = SilentSurfaceTracker()

    /** 主线程。 */
    fun onStateChanged() {
        val wired = app.connection.state.value
        // 无线调度还没建的话不为看一眼状态去建它（建的时候会注册广播、启停组件）。
        val wireless = app.wirelessIfCreated?.state?.value ?: WirelessUiState()
        val surface = tracker.observe(
            uiVisible = app.uiVisibility.visible,
            wiredPhase = wired.phase,
            wiredAttempt = wired.attempt,
            wirelessPhase = wireless.phase,
            wirelessStep = wireless.step,
            wirelessAttempt = wireless.attempt,
        ) { BootStart.silentEffective(app) } ?: return
        val overlay = !BootStartPolicy.overlayRequired(Build.VERSION.SDK_INT) || BootStart.canDrawOverlays(app)
        Log.i(
            BootStart.TAG,
            "开机静默连接：${if (surface.wireless) "无线" else "有线"}第 ${surface.attempt} 次连接 ${surface.what}，" +
                "界面不在前台，带到前台${if (overlay) "" else "（没有悬浮窗权限，多半被系统拦下）"}",
        )
        try {
            app.startActivity(BootStart.surfaceIntent(app))
        } catch (e: RuntimeException) {
            Log.w(BootStart.TAG, "带不到前台：${e.javaClass.simpleName}：${e.message}")
        }
    }
}
