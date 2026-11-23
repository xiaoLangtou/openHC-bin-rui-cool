package io.github.gcbond.openhc.platform.net

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import io.github.gcbond.openhc.platform.log.Log
import java.net.Inet4Address

/**
 * 有线连接用的 VpnService。要的不是 VPN 本身，而是一块地址由自己定的 TUN 网卡：
 * 普通应用没有权限给系统网卡配地址，TUN 是唯一的正路。
 *
 * 建出来的 TUN：
 * - 只加手机所在网段这一条路由，不加默认路由，不设 DNS；
 * - 只对本应用生效（`addAllowedApplication`），车机上别的应用和系统自己的网络完全不经过它；
 * - 本应用的 IPv6 照常走原来的网络（`allowFamily`）：只配了 IPv4 的 VPN 默认会把 IPv6 拦掉。
 *
 * 由 [VpnTunnel] 在同进程内绑定拉起，不用 startService：安卓 8.0 起应用在后台时 startService 会被拒，绑定不受限。
 * 解除绑定、关掉 TUN 之后服务随之销毁，不会常驻。
 *
 * 清单里要求 `android.permission.BIND_VPN_SERVICE`，只有系统能绑定它的 VPN 接口；
 * 清单里声明不支持「始终开启的 VPN」，免得系统在没有会话时自己把它拉起来。
 */
class WiredVpnService : VpnService() {

    private val localBinder = LocalBinder()

    /** VPN 授权被撤销（用户在设置里断开，或别的 VPN 应用顶替）时调用。 */
    @Volatile
    internal var revokeListener: (() -> Unit)? = null

    internal inner class LocalBinder : Binder() {
        val service: WiredVpnService get() = this@WiredVpnService
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL_BIND) localBinder else super.onBind(intent)

    override fun onRevoke() {
        Log.i(TAG, "VPN 授权被撤销")
        revokeListener?.invoke()
        super.onRevoke()
    }

    /** 建 TUN。没有 VPN 授权时返回 null；参数被系统拒绝时抛异常。 */
    internal fun establishTun(spec: TunSpec): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(spec.sessionName)
            .setMtu(spec.mtu)
            .addAddress(spec.address, spec.prefixLength)
            .addRoute(spec.network, spec.prefixLength)
            .addAllowedApplication(packageName)
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(false)
        spec.configureIntent?.let { builder.setConfigureIntent(it) }
        return builder.establish()
    }

    internal companion object {
        const val TAG = "OpenHC-Wired"

        /** 同进程绑定用的 action；系统绑定时用的是 [VpnService.SERVICE_INTERFACE]。 */
        const val ACTION_LOCAL_BIND = "io.github.gcbond.openhc.platform.net.BIND_WIRED_VPN"
    }
}

/** 建 TUN 的参数。 */
internal class TunSpec(
    val address: Inet4Address,
    val prefixLength: Int,
    val network: Inet4Address,
    val mtu: Int,
    val sessionName: String,
    val configureIntent: PendingIntent?,
)
