package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AccessoryAuthenticatorFactory
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.auth.HiCarAuthSession
import io.github.gcbond.openhc.core.auth.KotlinAccessoryAuthenticator
import io.github.gcbond.openhc.core.dmsdp.DmsdpChannel
import io.github.gcbond.openhc.core.dmsdp.DmsdpOutput
import io.github.gcbond.openhc.core.dmsdp.DmsdpSession
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.HiSightOutput
import io.github.gcbond.openhc.core.hisight.HiSightReceiver
import io.github.gcbond.openhc.core.nearby.NearbyAction
import io.github.gcbond.openhc.core.nearby.NearbyEvent
import io.github.gcbond.openhc.core.nearby.NearbySession

/*
 * 编排器看到的各层接口。正式实现原样转给各层的会话对象；单元测试换成假的层，
 * 只验证编排器的交接顺序和异常路径，不依赖各层内部的加密细节。
 * auth、dmsdp、hisight 的输出类型可以直接构造，假层原样复用；nearby 的事件只能由它自己构造，
 * 所以这里转成 [NearbyOutput]。
 */

internal interface Layers {
    /** 手机连上控制通道时创建。 */
    fun nearby(): NearbyLayer

    /** 手机连上认证端口时创建。 */
    fun auth(): AuthLayer

    /** 手机连上业务 20 时创建；[phoneIp] 是这条连接的对端地址。 */
    /** [phoneVersion]：认证时手机报的 HiCar 版本（没有为 null），扬声器按手机记的传输方式时一起比对。 */
    fun dmsdp(sessionKey: ByteArray, phoneIp: String, phoneVersion: String?): DmsdpLayer

    /** 手机通告投屏端口时创建；视频帧同步交给 [sink]。 */
    fun hisight(sessionKey: ByteArray, sink: AccessUnitSink): HiSightLayer
}

internal sealed class NearbyOutput {
    class Send(val bytes: ByteArray) : NearbyOutput()
    class HandshakeCompleted(val encrypted: Boolean) : NearbyOutput()
    object HandshakeTimedOut : NearbyOutput()

    /** 手机请求为某项业务新开 TCP；[handle] 是正式实现里原来的事件，回应时原样交回。 */
    class SocketRequest(val businessId: Int, val channel: Int, val tag: String, val handle: Any?) : NearbyOutput() {
        override fun toString() = "SocketRequest(业务=$businessId, 通道=$channel, 标签=$tag)"
    }

    /** 不影响流程的事件，[text] 可以直接写日志。 */
    class Note(val text: String) : NearbyOutput()
}

internal interface NearbyLayer {
    val nextTickAtMillis: Long?
    fun start(nowMillis: Long): List<NearbyOutput>
    fun feed(data: ByteArray, offset: Int, length: Int): List<NearbyOutput>
    fun onTick(nowMillis: Long): List<NearbyOutput>
    fun accept(request: NearbyOutput.SocketRequest, port: Int): List<NearbyOutput>
    fun reject(request: NearbyOutput.SocketRequest): List<NearbyOutput>
}

internal interface AuthLayer {
    fun feed(data: ByteArray, offset: Int, length: Int): List<HiCarAuthAction>
    fun onConnectionClosed(): List<HiCarAuthAction>
}

internal interface DmsdpLayer {
    val nextTickAtMillis: Long?
    fun feed(channel: DmsdpChannel, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput>
    fun controlListenerReady(port: Int): List<DmsdpOutput>
    fun onConnected(channel: DmsdpChannel, nowMillis: Long): List<DmsdpOutput>
    fun onClosed(channel: DmsdpChannel, nowMillis: Long): List<DmsdpOutput>
    fun onDatagram(data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput>
    fun onTick(nowMillis: Long): List<DmsdpOutput>
    fun close(): List<DmsdpOutput>

    /** 音频的数据端口开好了。 */
    fun audioListenersReady(port: Int, nowMillis: Long): List<DmsdpOutput> = emptyList()

    /** 音频端口开不了。 */
    fun audioListenersFailed(reason: String): List<DmsdpOutput> = emptyList()

    /** 麦克风数据端口开好了。 */
    fun micListenerReady(port: Int): List<DmsdpOutput> = emptyList()

    /** 麦克风数据端口开不了。 */
    fun micListenerFailed(reason: String): List<DmsdpOutput> = emptyList()

    /** 送一块车机麦克风的声音。 */
    fun sendMicAudio(data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput> = emptyList()

    /** 把车机这边的音频焦点变化告诉手机。 */
    fun sendAudioFocus(change: CarAudioFocus): List<DmsdpOutput> = emptyList()

    /** 主动给手机发一条设备数据（module/type/原始负载，实验/扩展用；核心流程不经过这里）。 */
    fun sendDeviceData(module: Long, type: Int, data: ByteArray): List<DmsdpOutput> = emptyList()

    /** 把车机日夜模式告诉手机（管理通道 501，吉利原厂对齐）；DMSDP 层还没就绪时为空。 */
    fun sendDayNightMode(night: Boolean): List<DmsdpOutput> = emptyList()

    /** 把驻车/行车状态告诉手机（管理通道 506，吉利原厂对齐）；DMSDP 层还没就绪时为空。 */
    fun sendDrivingMode(driving: Boolean): List<DmsdpOutput> = emptyList()
}

internal interface HiSightLayer {
    fun start(): List<HiSightOutput>
    fun feedControl(data: ByteArray, offset: Int, length: Int, wallClockMillis: Long): List<HiSightOutput>
    fun onControlClosed(): List<HiSightOutput>
    fun onVideoConnected(): List<HiSightOutput>
    fun onRemoteControlConnected(): List<HiSightOutput>
    fun feedVideo(data: ByteArray, offset: Int, length: Int)
    fun onVideoClosed(): List<HiSightOutput>
    fun feedRemoteControl(data: ByteArray, offset: Int, length: Int)
    fun onRemoteControlClosed(): List<HiSightOutput>
    fun sendInput(input: HiSightInput): List<HiSightOutput>
    fun shutdown(): List<HiSightOutput>
}

/** 正式实现：按 [ReceiverConfig] 创建各层的会话对象；音频、麦克风数据会话的出口交给 dmsdp 层。 */
internal class ProductionLayers(
    private val config: ReceiverConfig,
    private val audio: AudioStreamListener = AudioStreamListener.NONE,
    private val mic: MicStreamListener = MicStreamListener.NONE,
) : Layers {
    override fun nearby(): NearbyLayer = NearbyAdapter(
        NearbySession(config.identity.host, config.identity.nearbyKeys, config.bluetoothAddress, config.random),
    )

    override fun auth(): AuthLayer {
        val factory = AccessoryAuthenticatorFactory { KotlinAccessoryAuthenticator(it, config.trustStore, config.random) }
        val info = config.authDeviceInfo()
        val session = HiCarAuthSession(config.authConfig(), factory, config.random) { info }
        return object : AuthLayer {
            override fun feed(data: ByteArray, offset: Int, length: Int) = session.feed(data, offset, length)
            override fun onConnectionClosed() = session.onConnectionClosed()
        }
    }

    override fun dmsdp(sessionKey: ByteArray, phoneIp: String, phoneVersion: String?): DmsdpLayer {
        val session = DmsdpSession(config.dmsdpConfig(phoneIp, phoneVersion), sessionKey, config.random, audio, mic)
        return object : DmsdpLayer {
            override val nextTickAtMillis: Long? get() = session.nextTickAtMillis
            override fun feed(channel: DmsdpChannel, data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
                session.feed(channel, data, offset, length, nowMillis)
            override fun controlListenerReady(port: Int) = session.controlListenerReady(port)
            override fun onConnected(channel: DmsdpChannel, nowMillis: Long) = session.onConnected(channel, nowMillis)
            override fun onClosed(channel: DmsdpChannel, nowMillis: Long) = session.onClosed(channel, nowMillis)
            override fun onDatagram(data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
                session.onDatagram(data, offset, length, nowMillis)
            override fun onTick(nowMillis: Long) = session.onTick(nowMillis)
            override fun close() = session.close()
            override fun audioListenersReady(port: Int, nowMillis: Long) = session.audioListenersReady(port, nowMillis)
            override fun audioListenersFailed(reason: String) = session.audioListenersFailed(reason)
            override fun micListenerReady(port: Int) = session.micListenerReady(port)
            override fun micListenerFailed(reason: String) = session.micListenerFailed(reason)
            override fun sendMicAudio(data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
                session.sendMicAudio(data, offset, length, nowMillis)
            override fun sendAudioFocus(change: CarAudioFocus) = session.sendAudioFocus(change)
            override fun sendDeviceData(module: Long, type: Int, data: ByteArray) =
                session.sendDeviceData(module, type, data)
        }
    }

    override fun hisight(sessionKey: ByteArray, sink: AccessUnitSink): HiSightLayer {
        val receiver = HiSightReceiver(sessionKey, config.hisightConfig(), config.random, sink, config.timeZone)
        return object : HiSightLayer {
            override fun start() = receiver.start()
            override fun feedControl(data: ByteArray, offset: Int, length: Int, wallClockMillis: Long) =
                receiver.feedControl(data, offset, length, wallClockMillis)
            override fun onControlClosed() = receiver.onControlClosed()
            override fun onVideoConnected() = receiver.onVideoConnected()
            override fun onRemoteControlConnected() = receiver.onRemoteControlConnected()
            override fun feedVideo(data: ByteArray, offset: Int, length: Int) = receiver.feedVideo(data, offset, length)
            override fun onVideoClosed() = receiver.onVideoClosed()
            override fun feedRemoteControl(data: ByteArray, offset: Int, length: Int) =
                receiver.feedRemoteControl(data, offset, length)
            override fun onRemoteControlClosed() = receiver.onRemoteControlClosed()
            override fun sendInput(input: HiSightInput) = receiver.sendInput(input)
            override fun shutdown() = receiver.shutdown()
        }
    }
}

/** 把 nearby 层的动作和事件转成 [NearbyOutput]。 */
internal class NearbyAdapter(private val session: NearbySession) : NearbyLayer {
    override val nextTickAtMillis: Long? get() = session.nextTickAtMillis

    override fun start(nowMillis: Long) = convert(session.start(nowMillis))

    override fun feed(data: ByteArray, offset: Int, length: Int) = convert(session.feed(data, offset, length))

    override fun onTick(nowMillis: Long) = convert(session.onTick(nowMillis))

    override fun accept(request: NearbyOutput.SocketRequest, port: Int) =
        convert(session.acceptSocket(request.handle as NearbyEvent.SocketRequested, port))

    override fun reject(request: NearbyOutput.SocketRequest) =
        convert(session.rejectSocket(request.handle as NearbyEvent.SocketRequested))

    private fun convert(actions: List<NearbyAction>): List<NearbyOutput> = actions.map { action ->
        when (action) {
            is NearbyAction.Send -> NearbyOutput.Send(action.frame)
            is NearbyEvent.HandshakeCompleted -> NearbyOutput.HandshakeCompleted(action.encrypted)
            NearbyEvent.HandshakeTimedOut -> NearbyOutput.HandshakeTimedOut
            is NearbyEvent.SocketRequested ->
                NearbyOutput.SocketRequest(action.businessId, action.channel, action.tag, action)
            // 其余事件（对端蓝牙地址、自动拒绝、未处理条目、丢弃的数据）的 toString 都不含设备标识。
            is NearbyEvent -> NearbyOutput.Note(action.toString())
        }
    }
}
