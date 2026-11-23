package io.github.gcbond.openhc.demo

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.SystemClock

/**
 * 仅调试构建：等车机 WiFi 断开（手机关热点时车机会掉线）再做事，用来在 adb 会断的场景下先布置好试验。
 * 只用清单里已有的 ACCESS_WIFI_STATE；读不到 WiFi 状态时退成固定等 60 秒。最多等 30 分钟。
 */
internal object DebugWifiWait {
    fun afterWifiOff(context: Context, handler: Handler, afterSeconds: Int, report: (String) -> Unit, then: () -> Unit) {
        poll(context.applicationContext, handler, SystemClock.uptimeMillis(), afterSeconds, report, then)
    }

    private fun poll(context: Context, handler: Handler, startedAt: Long, afterSeconds: Int, report: (String) -> Unit, then: () -> Unit) {
        val wifiUp = try {
            @Suppress("DEPRECATION")
            val info = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            info != null && info.networkId != -1 && info.supplicantState == SupplicantState.COMPLETED
        } catch (e: SecurityException) {
            report("读不到 WiFi 状态（${e.javaClass.simpleName}），改为 60 秒后执行")
            handler.postDelayed(then, 60_000)
            return
        }
        when {
            !wifiUp -> {
                report("车机 WiFi 已断开，$afterSeconds 秒后执行")
                handler.postDelayed(then, afterSeconds * 1_000L)
            }
            SystemClock.uptimeMillis() - startedAt > 30 * 60_000L -> report("等了 30 分钟 WiFi 还没断，放弃")
            else -> handler.postDelayed({ poll(context, handler, startedAt, afterSeconds, report, then) }, 1_000)
        }
    }
}
