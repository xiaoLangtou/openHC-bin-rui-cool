package io.github.gcbond.openhc.connection

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference

/**
 * 回车机桌面：用户在投屏界面上点「车机」图标时，手机暂停投屏显示服务（[io.github.gcbond.openhc.core.session.SessionEvent.ProjectionPaused]），
 * 车机应该回自己的桌面，连接、声音、方向盘按键照常（之后从桌面点 OpenHC 回到投屏）。有线、无线共用。
 *
 * - 只在 OpenHC 的界面在前台时处理（[attach] / [detach] 由界面在 onResume / onPause 调）；界面不在前台就已经在车机桌面了。
 * - 启动竞态：刚连上时手机会在服务开始后约 0.7 秒紧跟一次暂停（实车），这时并不是用户点的。设备会话就绪后
 *   [STARTUP_WINDOW_MS] 内的第一次暂停忽略，之后的照常处理。
 * - 回桌面用标准的「主屏幕」意图，从前台界面发出，不受后台启动界面的限制。
 */
internal object CarHome {
    private const val TAG = "OpenHC-CarHome"

    /** 设备会话就绪后这么久之内的第一次暂停当成启动竞态。 */
    private const val STARTUP_WINDOW_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())

    // 以下只在主线程上访问。
    private var activity: WeakReference<Activity>? = null
    private var sessionReadyAt = 0L
    private var startupPauseSeen = false

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    /** 设备会话就绪（每次连接一次）：重新开始算启动竞态的窗口。任意线程。 */
    fun onDeviceSessionReady() {
        val now = SystemClock.elapsedRealtime()
        main.post {
            sessionReadyAt = now
            startupPauseSeen = false
        }
    }

    /** 手机暂停了投屏显示服务。[label] 是日志里的连接标识。任意线程。 */
    fun onProjectionPaused(label: String) {
        val now = SystemClock.elapsedRealtime()
        main.post {
            val age = now - sessionReadyAt
            if (!startupPauseSeen && sessionReadyAt != 0L && age in 0..STARTUP_WINDOW_MS) {
                startupPauseSeen = true
                Log.i(TAG, "（$label）设备会话就绪后 ${age}ms 手机暂停投屏，按启动竞态忽略，不回车机桌面")
                return@post
            }
            startupPauseSeen = true
            val front = activity?.get()
            if (front == null || front.isFinishing) {
                Log.i(TAG, "（$label）手机暂停投屏（点了「车机」图标）：OpenHC 不在前台，不用处理")
                return@post
            }
            try {
                front.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i(TAG, "（$label）手机暂停投屏（点了「车机」图标）：回车机桌面，连接保持")
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "（$label）回车机桌面失败：找不到桌面（${e.message}），改为把 OpenHC 退到后台")
                front.moveTaskToBack(true)
            }
        }
    }
}
