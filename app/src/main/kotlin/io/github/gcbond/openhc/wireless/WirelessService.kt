package io.github.gcbond.openhc.wireless

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.AppIntents
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.platform.log.Log

/**
 * 无线投屏的前台服务：有连接期间（等扫码、连接、投屏）留在前台，切到后台也不断。连接本身由 [WirelessController] 调度，
 * 这里只负责前台状态和通知。写法与有线的前台服务相同，分开是为了两边各自演进、互不牵连。
 *
 * 类型是 connectedDevice。安卓 14 起这个类型要满足运行时前置条件之一：本应用有清单里的 CHANGE_NETWORK_STATE，
 * 无线开始前也已拿到蓝牙运行时权限（安卓 12 起）。
 */
class WirelessService : Service() {

    private lateinit var controller: WirelessController
    private var inForeground = false
    private var lastStartId = 0
    private var leaving = false
    private var lastNotification: Content? = null

    private val listener = WirelessController.HostListener { onControllerChanged() }

    /**
     * 车机熄屏广播（`ecarx.intent.action.DISPLAY_OFF`，吉利原厂 DisplayStateChangedReceiver 同款，需求文档第 8 项）：
     * 熄火锁车时主动优雅断开无线会话、释放热点/直连与 EAS 状态，避免手机一直连着车机热点耗电。
     * 只在前台服务运行期间动态注册（onCreate 注册、onDestroy 注销）。
     */
    private val displayOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DISPLAY_OFF_ACTION) return
            Log.i(TAG, "收到车机熄屏广播，优雅断开无线投屏会话")
            runCatching { controller.stop() }.onFailure { Log.w(TAG, "熄屏断连失败：${it.message}") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = (application as OpenHcApp).wireless
        controller.addHostListener(listener)
        createChannel()
        registerDisplayOffReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        leaving = false
        // 用 startForegroundService 起的服务必须尽快进前台，哪怕马上就要退出。
        if (!enterForeground()) return START_NOT_STICKY
        if (!controller.wantsForeground) leave()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.removeHostListener(listener)
        if (inForeground) controller.serviceInForeground = false
        if (!leaving) controller.onServiceDestroyed()
        unregisterDisplayOffReceiver()
        super.onDestroy()
    }

    /** 动态注册熄屏广播（ECARX 系统广播，manifest 里不声明）。
     *  广播来自系统/其他应用，必须 RECEIVER_EXPORTED 才收得到；安卓 13 以下 ContextCompat 自动忽略该 flag。 */
    private fun registerDisplayOffReceiver() {
        runCatching {
            ContextCompat.registerReceiver(this, displayOffReceiver, IntentFilter(DISPLAY_OFF_ACTION),
                ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "注册熄屏广播失败（${it.javaClass.simpleName}），熄屏不自动断连") }
    }

    private fun unregisterDisplayOffReceiver() {
        runCatching { unregisterReceiver(displayOffReceiver) }
    }

    private fun enterForeground(): Boolean {
        val content = Content.of(controller.state.value, controller.autoRetryPending)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(content), foregroundType())
            inForeground = true
            controller.serviceInForeground = true
            lastNotification = content
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "进不了前台", e)
            controller.onServiceStartFailed("${e.javaClass.simpleName}：${e.message}")
            leaving = true
            stopSelf(lastStartId)
            false
        }
    }

    private fun onControllerChanged() {
        if (!controller.wantsForeground) {
            leave()
            return
        }
        if (!inForeground) return
        val content = Content.of(controller.state.value, controller.autoRetryPending)
        if (content == lastNotification) return
        lastNotification = content
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(content))
            } catch (e: SecurityException) {
                Log.w(TAG, "更新通知失败：${e.message}")
            }
        }
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            controller.serviceInForeground = false
        }
        stopSelf(lastStartId)
    }

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_name))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun build(content: Content): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(content.title))
            .setContentText(getString(content.text))
            .setContentIntent(AppIntents.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    /** 通知的两行文字（字符串资源 id）。 */
    private data class Content(val title: Int, val text: Int) {
        companion object {
            /** [autoRetryPending]：两次等待之间（自动回连排着下一次）。 */
            fun of(state: WirelessUiState, autoRetryPending: Boolean): Content = when (state.phase) {
                WirelessPhase.PROJECTING -> Content(R.string.notification_projecting, R.string.notification_projecting_text)
                WirelessPhase.CONNECTING -> Content(R.string.wireless_notification_connecting, R.string.notification_connecting_text)
                WirelessPhase.STOPPING -> Content(R.string.wireless_notification_waiting, R.string.notification_stopping_text)
                WirelessPhase.IDLE, WirelessPhase.PREPARING, WirelessPhase.WAITING_FOR_SCAN ->
                    if (state.autoReconnect || autoRetryPending) {
                        Content(R.string.wireless_notification_waiting, R.string.wireless_auto_notification_text)
                    } else {
                        Content(R.string.wireless_notification_waiting, R.string.wireless_notification_scan_text)
                    }
            }
        }
    }

    private companion object {
        const val TAG = "OpenHC-Wireless"

        /** 与有线共用一个通知渠道（同一类通知）。 */
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 2

        /** 车机熄屏广播（吉利/ECARX 系统广播，原厂 DisplayStateChangedReceiver 同款）。 */
        const val DISPLAY_OFF_ACTION = "ecarx.intent.action.DISPLAY_OFF"
    }
}
