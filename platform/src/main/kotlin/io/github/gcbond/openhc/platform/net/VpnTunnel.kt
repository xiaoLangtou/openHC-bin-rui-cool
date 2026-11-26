package io.github.gcbond.openhc.platform.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.gcbond.openhc.platform.log.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次会话用的 TUN：绑定 [WiredVpnService]、建 TUN，[close] 时关 TUN 并解除绑定。
 *
 * 关闭顺序：先停掉用 [tunnel] 的线程，再 [close]。
 */
internal class VpnTunnel private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val service: WiredVpnService,
    val tunnel: TunInterface,
    private val onRevoked: () -> Unit,
) : Closeable {

    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (service.revokeListener === onRevoked) service.revokeListener = null
        tunnel.close()
        unbindQuietly(context, connection)
    }

    companion object {
        /**
         * 绑定服务并建 TUN，阻塞到建好、失败、超时或 [cancelled] 返回 true 为止。
         * 绑定回调在主线程上，所以不能在主线程调用。失败抛 [IOException]，消息说明原因。
         */
        fun open(
            context: Context,
            spec: TunSpec,
            timeoutMs: Long,
            cancelled: () -> Boolean,
            onRevoked: () -> Unit,
        ): VpnTunnel {
            check(Looper.myLooper() != Looper.getMainLooper()) { "不能在主线程建 TUN" }
            val app = context.applicationContext ?: context
            val connected = CountDownLatch(1)
            val serviceRef = AtomicReference<WiredVpnService?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    serviceRef.set((binder as? WiredVpnService.LocalBinder)?.service)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 服务被系统回收时 TUN 一并失效，搬运线程会读到隧道关闭并收尾。
                }
            }
            val intent = Intent(app, WiredVpnService::class.java).setAction(WiredVpnService.ACTION_LOCAL_BIND)
            val bound = try {
                app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: RuntimeException) {
                unbindQuietly(app, connection)
                throw IOException("绑定 VPN 服务异常: ${e.javaClass.simpleName}", e)
            }
            if (!bound) {
                unbindQuietly(app, connection)
                throw IOException("绑定 VPN 服务失败（清单里没有声明服务？）")
            }
            try {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (!connected.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    if (cancelled()) throw IOException("已取消")
                    if (System.nanoTime() > deadline) throw IOException("等 VPN 服务超时（$timeoutMs 毫秒）")
                }
                val service = serviceRef.get() ?: throw IOException("绑定到的不是本模块的 VPN 服务")
                service.revokeListener = onRevoked
                val descriptor = establishTunWithReclaim(service, spec, app)
                val tun = try {
                    TunInterface(descriptor)
                } catch (e: IOException) {
                    descriptor.close()
                    throw e
                }
                return VpnTunnel(app, connection, service, tun, onRevoked)
            } catch (e: IOException) {
                serviceRef.get()?.let { if (it.revokeListener === onRevoked) it.revokeListener = null }
                unbindQuietly(app, connection)
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                serviceRef.get()?.let { if (it.revokeListener === onRevoked) it.revokeListener = null }
                unbindQuietly(app, connection)
                throw IOException("等 VPN 服务时被中断", e)
            }
        }

        private fun unbindQuietly(context: Context, connection: ServiceConnection) {
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // 没绑上或已经解绑过。
            }
        }

        /**
         * 建 TUN：首次返回 null（安卓 9 上 uid 1000 共享 android.uid.system 与开机默认的
         * LEGACY_VPN「mOwnerUID=1000」撞号——prepare 被 1000==1000 短路，mPackage 卡在
         * 「[Legacy VPN]」，establish 必败；appop 已持久化时 [VpnConsent.check] 还看不出来）
         * 时，两步换手夺回归属后立即重试一次：插线建 TUN 的瞬间自愈，不浪费已消耗的 carInfo。
         * 普通签名版没有系统身份，reclaim 直接返回 false，照旧抛原错误走弹窗授权流程。
         */
        private fun establishTunWithReclaim(
            service: WiredVpnService,
            spec: TunSpec,
            app: Context,
        ): ParcelFileDescriptor {
            tryEstablish(service, spec)?.let { return it }
            Log.i(TAG, "首次建 TUN 返回 null，尝试系统身份两步换手夺回 VPN 归属后重试")
            VpnConsent.reclaimSystemVpnOwner(app)
            tryEstablish(service, spec)?.let { return it }
            throw IOException("系统拒绝建 TUN：没有 VPN 授权，或授权已被别的 VPN 应用取代")
        }

        /** 单次建 TUN；系统侧异常（地址/路由被拒、锁定模式、设备策略等）转 [IOException]。 */
        private fun tryEstablish(service: WiredVpnService, spec: TunSpec): ParcelFileDescriptor? = try {
            service.establishTun(spec)
        } catch (e: RuntimeException) {
            throw IOException("系统拒绝建 TUN: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        private const val TAG = "OpenHC-VpnTunnel"
        private const val WAIT_SLICE_MS = 100L
    }
}
