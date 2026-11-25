package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit
import io.github.gcbond.openhc.connection.DisplayPlan

/**
 * 应用设置（SharedPreferences）。改动在下一次连接时生效。任意线程可读写。
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 声明给手机的帧率；0 表示按屏幕刷新率。 */
    var fps: Int
        get() = prefs.getInt(KEY_FPS, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_FPS, value.coerceAtLeast(0)) }

    /** 「界面大小」：声明给手机的 DPI 按这个百分比缩小，只认设置页列出的档位，别的值按默认。下次连接生效。 */
    var uiScalePercent: Int
        get() = prefs.getInt(KEY_UI_SCALE, DisplayPlan.DEFAULT_UI_SCALE).takeIf { it in DisplayPlan.UI_SCALE_CHOICES }
            ?: DisplayPlan.DEFAULT_UI_SCALE
        set(value) = prefs.edit { putInt(KEY_UI_SCALE, value) }

    /** 用户手填的车机蓝牙地址（原样保存，用的时候再校验）；没填为 null。 */
    var manualBluetoothAddress: String?
        get() = prefs.getString(KEY_MANUAL_BT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_MANUAL_BT) else putString(KEY_MANUAL_BT, value.trim())
        }

    /**
     * 应用被「设备接入」拉起过：用户在系统框里同意过用本应用打开手机（勾了「默认」之后系统会自动授权）。
     * 这之后（「插线时不再弹窗」开着时）有线会话先等一会儿看系统会不会自动授权，再自己弹授权框，免掉一个多余的框。
     */
    var usbAttachAccepted: Boolean
        get() = prefs.getBoolean(KEY_USB_ATTACH_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_USB_ATTACH_ACCEPTED, value) }

    /**
     * 「全屏显示」：开（默认）时投屏藏起状态栏和底部导航栏、铺满整屏；关时保留底部导航栏，画面让出它的位置，
     * 声明给手机的尺寸也随之变小。下次连接生效。
     */
    var fullScreen: Boolean
        get() = prefs.getBoolean(KEY_FULL_SCREEN, true)
        set(value) = prefs.edit { putBoolean(KEY_FULL_SCREEN, value) }

    /** 安卓 13 起的通知权限问过一次了（拒绝了也不再追问）。 */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, value) }

    /**
     * 诊断日志上传的日志服务器：填到端口即可（例如 http://121.36.29.74:3800），上报时拼 /logs。
     * 代码里带默认值（自己部署的日志服务器），清空保存即恢复默认。
     */
    var logServerUrl: String
        get() = prefs.getString(KEY_LOG_SERVER_URL, null)?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_LOG_SERVER_URL
        set(value) = prefs.edit { putString(KEY_LOG_SERVER_URL, value.trim()) }

    /** 日志服务器的上报令牌（X-Report-Token 头）；没填则不带头上报。 */
    var logServerToken: String
        get() = prefs.getString(KEY_LOG_SERVER_TOKEN, null)?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_LOG_SERVER_TOKEN
        set(value) = prefs.edit { putString(KEY_LOG_SERVER_TOKEN, value.trim()) }

    private companion object {
        const val FILE = "openhc-settings"
        const val KEY_FPS = "fps"
        const val KEY_UI_SCALE = "ui_scale_percent"
        const val KEY_MANUAL_BT = "manual_bluetooth_address"
        const val KEY_USB_ATTACH_ACCEPTED = "usb_attach_accepted"
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        const val KEY_FULL_SCREEN = "full_screen"
        const val KEY_LOG_SERVER_URL = "log_server_url"
        const val KEY_LOG_SERVER_TOKEN = "log_server_token"
        const val DEFAULT_LOG_SERVER_URL = "http://121.36.29.74:3800"
        const val DEFAULT_LOG_SERVER_TOKEN = "a7b140f74313db0fbf09001528b48c65"
    }
}
