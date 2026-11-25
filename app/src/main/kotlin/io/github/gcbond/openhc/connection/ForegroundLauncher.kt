package io.github.gcbond.openhc.connection

import android.content.Context
import android.os.SystemClock
import io.github.gcbond.openhc.AppIntents
import io.github.gcbond.openhc.platform.log.Log

/**
 * 手机要求恢复投屏时把 OpenHC 拉回前台（原厂 HiCar 对齐，需求文档第 7 项；对应原厂 HiCarService2 的
 * onDeviceServiceChange 203/204/207 → startHiCarMainActivity）。
 *
 * - 防抖：[MIN_INTERVAL_MS] 内不重复拉起（原厂 HiCarLaunchGuard 同款，默认 1.5 秒），避免手机连续
 *   发状态通知时把启动请求排满、界面来回闪。
 * - OpenHC 界面已经在前台时不拉（[CarHome.isActivityFront]）；拉起失败（系统限制后台启动等）只记日志。
 */
internal object ForegroundLauncher {
    private const val TAG = "OpenHC-Foreground"

    /** 两次自动拉起的最小间隔（毫秒）。 */
    private const val MIN_INTERVAL_MS = 1_500L

    // 只在主线程上访问（事件回调经控制器 post 到主线程）。
    private var lastLaunchAt = 0L

    /** [reason] 是日志里的触发说明（如 "action=203"）。 */
    fun launch(context: Context, reason: String) {
        if (CarHome.isActivityFront()) {
            Log.i(TAG, "（$reason）OpenHC 已在前台，不用拉起")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastLaunchAt != 0L && now - lastLaunchAt < MIN_INTERVAL_MS) {
            Log.i(TAG, "（$reason）距上次自动拉起不足 ${MIN_INTERVAL_MS}ms，防抖跳过")
            return
        }
        lastLaunchAt = now
        runCatching {
            AppIntents.openApp(context).send()
            Log.i(TAG, "（$reason）已把 OpenHC 拉回前台")
        }.onFailure {
            Log.w(TAG, "（$reason）拉回前台失败：${it.message}")
        }
    }
}
