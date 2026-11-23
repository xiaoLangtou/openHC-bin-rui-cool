package io.github.gcbond.openhc.connection

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
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
 * 有线投屏的前台服务：用户要求连接期间（等插线、连接、投屏、等拔线重插）留在前台，切到后台也不断连。
 * 连接本身由 [ConnectionController] 调度，这里只负责前台状态和通知。
 *
 * 类型是 connectedDevice（安卓 14 起必须声明类型）。安卓 14 起这个类型还要求满足运行时前置条件之一，
 * 本应用靠清单里的 CHANGE_NETWORK_STATE 满足：开始等插线时手机还没插上，还没有 USB 设备授权可用。
 * 起不来（前置条件不满足、安卓 12 起在后台被拒）时报给调度，连接随之结束。
 */
class ConnectionService : Service() {

    private lateinit var controller: ConnectionController
    private var inForeground = false
    private var lastStartId = 0

    /** 自己要退出（调度不再要求前台）：销毁时不用再通知调度。 */
    private var leaving = false
    private var lastNotification: NotificationContent? = null

    private val listener = ConnectionController.HostListener { onControllerChanged() }

    override fun onCreate() {
        super.onCreate()
        controller = (application as OpenHcApp).connection
        controller.addHostListener(listener)
        createChannel()
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
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val content = NotificationContent.of(controller.state.value)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(content), foregroundType())
            inForeground = true
            controller.serviceInForeground = true
            lastNotification = content
            true
        } catch (e: RuntimeException) {
            // 安卓 12 起后台启动被拒（ForegroundServiceStartNotAllowedException）、安卓 14 起前置条件不满足（SecurityException）。
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
        val content = NotificationContent.of(controller.state.value)
        if (content == lastNotification) return
        lastNotification = content
        // 安卓 13 起没有通知权限时通知不显示，前台服务照常运行。
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
        // 只在没有更新的启动请求时才真的停：调度刚放手又马上要求前台时，新的请求还排在后面。
        stopSelf(lastStartId)
    }

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_name))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun build(content: NotificationContent): Notification =
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

    /** 通知显示的两行文字（字符串资源 id）。 */
    private data class NotificationContent(val title: Int, val text: Int) {
        companion object {
            fun of(state: ConnectionUiState): NotificationContent = when (state.phase) {
                Phase.PROJECTING -> NotificationContent(R.string.notification_projecting, R.string.notification_projecting_text)
                Phase.PAIRING -> NotificationContent(R.string.notification_connecting, R.string.notification_pairing_text)
                Phase.CONFIRM_ON_PHONE, Phase.CONNECTING, Phase.WAITING_USB_PERMISSION, Phase.USB_DEBUGGING_ON ->
                    NotificationContent(R.string.notification_connecting, R.string.notification_connecting_text)
                Phase.REPLUG_REQUIRED -> NotificationContent(R.string.notification_waiting, R.string.notification_replug_text)
                Phase.STOPPING -> NotificationContent(R.string.notification_waiting, R.string.notification_stopping_text)
                Phase.IDLE, Phase.PREPARING, Phase.WAITING_FOR_PHONE ->
                    NotificationContent(R.string.notification_waiting, R.string.notification_waiting_text)
            }
        }
    }

    private companion object {
        const val TAG = "OpenHC-Connection"
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1
    }
}
