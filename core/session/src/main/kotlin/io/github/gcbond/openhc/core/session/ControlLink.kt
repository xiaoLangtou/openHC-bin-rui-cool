package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.nearby.NearbySession

/**
 * [ReceiverSession] 的控制通道从哪来、认证是否已经做完。
 *
 * - 有线（[wired]）：控制监听开在 carInfo 里的端口（通道 6），手机在控制通道上请求业务 19 做认证。
 * - 无线的 WiFi 阶段（[wifi]）：控制监听开在车机 WiFi 地址的随机端口（通道 3），端口由 [SessionEvent.ControlListening]
 *   交出、写进连接串；认证已经在蓝牙上做完，会话密钥直接交给编排器，手机不会再请求业务 19。
 */
internal class ControlLink private constructor(
    /** 控制通道的通道号，也是业务 20 请求里应有的通道号。 */
    val channel: Int,
    val bindHost: String,
    /** 0 表示由系统分配。 */
    val port: Int,
    /** 见 [SessionAction.Listen.requireLocalHost]。 */
    val requireLocalHost: String?,
    sessionKey: ByteArray?,
    /** 无线时蓝牙认证里手机报的 HiCar 版本（有线时由编排器自己的认证得到，这里为 null）。 */
    val phoneVersion: String? = null,
) {
    private var key: ByteArray? = sessionKey?.copyOf()

    /** 认证已在别处（蓝牙上）做完。 */
    val preAuthenticated: Boolean = sessionKey != null

    /** 取走会话密钥（只能取一次，取走后这里不再留副本）；有线返回 null。 */
    fun takeSessionKey(): ByteArray? {
        val k = key ?: return null
        key = null
        return k
    }

    override fun toString() = "ControlLink(通道 $channel, $bindHost:$port)"

    companion object {
        fun wired(config: ReceiverConfig): ControlLink {
            // 通配监听时只接受连到车机 IP 的连接（经 USB 网卡进来的）；绑了具体地址就不用再筛。
            val filter = config.controlBindHost == ReceiverConfig.WILDCARD && config.controlLocalFilter
            return ControlLink(
                NearbySession.CHANNEL_USB, config.controlBindHost, config.controlPort,
                if (filter) config.localIp else null, null,
            )
        }

        /** [sessionKey] 会被复制，调用方自己的那份由调用方清零。 */
        fun wifi(localIp: String, sessionKey: ByteArray, phoneVersion: String? = null): ControlLink =
            ControlLink(NearbySession.CHANNEL_WIFI, localIp, 0, null, sessionKey, phoneVersion)
    }
}

/** 编排器给监听、连接等资源分配 id。无线编排器和它内嵌的有线编排器共用一个，id 不会重复。 */
internal class IdSource {
    private var next = 1

    fun next(): Int = next++
}
