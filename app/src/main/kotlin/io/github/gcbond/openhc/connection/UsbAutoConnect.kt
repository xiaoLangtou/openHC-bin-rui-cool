package io.github.gcbond.openhc.connection

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.gcbond.openhc.MainActivity
import io.github.gcbond.openhc.platform.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置「插线时不再弹窗」（含插线就连），默认关。开关的状态就是清单里 USB 设备过滤器别名的启用状态，不另存。
 *
 * 安卓的 USB 授权按设备实例记：手机收到 carInfo 后重新枚举成 USB 网卡，算另一台设备，所以每次连接要授权两次，
 * 而且授权只在这次插入内有效。只有应用在清单里给这种设备声明了 `USB_DEVICE_ATTACHED` 过滤器（组件启用着），
 * 系统的授权框和「要用 OpenHC 打开吗」框才带「默认使用」勾选框；勾过以后同样的设备再插上，系统当场授权并拉起
 * [MainActivity]，由它开始连接，不再弹框。
 *
 * - 「默认使用」按设备的完整属性记：手机的普通状态和 USB 网卡状态各要勾一次，换手机要重新勾。
 * - 开关关着时过滤器别名停用：系统不再认为 OpenHC 能处理这种设备，插线不会拉起应用，框里也没有「默认使用」。
 *   停用组件不会清掉系统里已存的「默认使用」（只有卸载会清），再打开开关就恢复零点击，不用重新勾。
 * - 系统先发设备接入广播、再按「默认使用」授权，前后差几十到一百多毫秒：开着时有线会话先等一会儿再自己弹授权框，
 *   见 [UsbAutoConnectPolicy]。
 */
internal object UsbAutoConnect {

    private val mutableChanges = MutableStateFlow(0)

    /** 每切换一次加一：界面上依赖这个开关的说明据此重读 [isEnabled]。 */
    val changes: StateFlow<Int> = mutableChanges.asStateFlow()

    fun isEnabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: RuntimeException) {
        false
    }

    /** 关 = 回到清单里的默认（停用）。立即生效，不用重开应用。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        try {
            context.packageManager.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
            Log.i(TAG, "插线时不再弹窗：${if (enabled) "开（USB 过滤器已启用）" else "关（USB 过滤器已停用）"}")
        } catch (e: RuntimeException) {
            Log.e(TAG, "切换 USB 过滤器失败", e)
        }
        mutableChanges.update { it + 1 }
    }

    /** 清单里挂 USB 过滤器的别名，指向 [MainActivity]。别名的类名按命名空间解析，和 MainActivity 同包。 */
    private fun component(context: Context) =
        ComponentName(context.packageName, MainActivity::class.java.name.substringBeforeLast('.') + ALIAS_SIMPLE_NAME)

    private const val ALIAS_SIMPLE_NAME = ".UsbAttachAlias"
    private const val TAG = "OpenHC-Connection"
}

/** 「插线时不再弹窗」相关的判定。纯逻辑。 */
object UsbAutoConnectPolicy {
    /**
     * 有线会话发现手机还没授权时，先等这么久看系统会不会按「默认使用」自动授权，再自己弹授权框：
     * 系统的自动授权比设备接入广播晚几十到一百多毫秒（实测），等一下能免掉一个多余的框。
     */
    const val PERMISSION_GRACE_MS = 800L

    /**
     * 等系统自动授权的时长。只有开关开着（过滤器启用）、而且应用被插线拉起过（用户在系统框里同意过用 OpenHC 打开手机）
     * 时系统才可能自动授权，否则等也白等。
     */
    fun permissionGraceMs(enabled: Boolean, attachAccepted: Boolean): Long =
        if (enabled && attachAccepted) PERMISSION_GRACE_MS else 0L
}
