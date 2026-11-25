package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.auth.HcError
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.dmsdp.DmsdpAction
import io.github.gcbond.openhc.core.dmsdp.DmsdpChannel
import io.github.gcbond.openhc.core.dmsdp.DmsdpEvent
import io.github.gcbond.openhc.core.dmsdp.KeepAliveMode
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightChannel
import io.github.gcbond.openhc.core.hisight.HiSightEndReason
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.HiSightOutput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 用假的层驱动编排器：验证层间交接的顺序（与参考实现一致）和各种异常路径。 */
class ReceiverSessionTest {

    /** 记下动作里出现的 id 与通道，把输出描述成便于比较的短文本。 */
    private class Driver(
        config: ReceiverConfig = Synthetic.config(),
        sink: AccessUnitSink = AccessUnitSink { },
    ) {
        val layers = FakeLayers()
        val session = ReceiverSession(config, sink, layers)
        var now = 1_000L
        val listens = HashMap<SessionChannel, SessionAction.Listen>()
        val connects = HashMap<SessionChannel, SessionAction.Connect>()
        var udp: SessionAction.OpenUdp? = null
        private val names = HashMap<Int, String>()

        init {
            layers.nearby.onFeed = { text ->
                when (text) {
                    "auth?" -> listOf(NearbyOutput.HandshakeCompleted(true), Synthetic.authRequest())
                    "auth-again?" -> listOf(Synthetic.authRequest())
                    "dmsdp?" -> listOf(Synthetic.dmsdpRequest())
                    "other?" -> listOf(NearbyOutput.SocketRequest(99, 6, "other", null))
                    else -> emptyList()
                }
            }
        }

        fun record(list: List<SessionOutput>): List<String> {
            for (o in list) {
                when (o) {
                    is SessionAction.Listen -> {
                        listens[o.channel] = o
                        names[o.id] = "${o.channel}.listener"
                        names[o.acceptId] = o.channel.name
                    }
                    is SessionAction.Connect -> {
                        connects[o.channel] = o
                        names[o.id] = o.channel.name
                    }
                    is SessionAction.OpenUdp -> {
                        udp = o
                        names[o.id] = o.channel.name
                    }
                    else -> Unit
                }
            }
            return list.map { describe(it) }
        }

        fun describe(o: SessionOutput): String = when (o) {
            is SessionAction.Listen -> "listen ${o.channel} ${o.host}:${o.port}" + (o.requireLocalHost?.let { " only $it" } ?: "")
            is SessionAction.Connect -> "connect ${o.channel} ${o.host}:${o.port}"
            is SessionAction.Write -> "write ${names[o.id]} ${o.bytes.text()}"
            is SessionAction.Close -> "close ${names[o.id] ?: o.id}"
            is SessionAction.OpenUdp -> "udp ${o.localHost} -> ${o.remoteHost}:${o.remotePort}"
            is SessionAction.BindUdp -> "bind-udp ${o.channel} ${o.localHost}:${o.localPort}"
            is SessionAction.SendDatagram -> "datagram ${o.bytes.text()}"
            is SessionEvent.Diagnostic -> "diag"
            is SessionEvent -> "event $o"
        }

        fun idOf(channel: SessionChannel): Int = listens[channel]?.acceptId ?: connects[channel]?.id ?: udp!!.id

        fun start() = record(session.start(now))
        fun listening(channel: SessionChannel, port: Int) = record(session.onListening(listens.getValue(channel).id, port, now))
        fun accepted(channel: SessionChannel, remote: String = Synthetic.PHONE_IP) =
            record(session.onAccepted(listens.getValue(channel).acceptId, remote, now))
        fun connected(channel: SessionChannel) = record(session.onConnected(connects.getValue(channel).id, now))
        fun data(channel: SessionChannel, text: String): List<String> {
            val bytes = "xx$text".bytes()
            // 故意带偏移，确认编排器按 offset/length 传给各层。
            return record(session.onData(idOf(channel), bytes, 2, bytes.size - 2, now))
        }
        fun closed(channel: SessionChannel) = record(session.onClosed(idOf(channel), now))
        fun ioError(channel: SessionChannel) = record(session.onIoError(idOf(channel), "测试错误", now))
        fun tick(at: Long) = record(session.onTick(at))
        fun stop() = record(session.stop(now))

        fun toControl() {
            start()
            listening(SessionChannel.CONTROL, Synthetic.PORT)
            accepted(SessionChannel.CONTROL)
        }

        fun toAuthListening() {
            toControl()
            data(SessionChannel.CONTROL, "auth?")
            listening(SessionChannel.AUTH, 40001)
        }

        fun toAuthStarted() {
            toAuthListening()
            accepted(SessionChannel.AUTH)
            data(SessionChannel.AUTH, "hello")
        }

        fun toAuthenticated() {
            toAuthStarted()
            data(SessionChannel.AUTH, "done")
        }

        fun toDmsdp() {
            toAuthenticated()
            data(SessionChannel.CONTROL, "dmsdp?")
            listening(SessionChannel.DMSDP_SESSION, 40002)
            accepted(SessionChannel.DMSDP_SESSION)
        }

        fun toDeviceReady() {
            toDmsdp()
            data(SessionChannel.DMSDP_SESSION, "nego")
            listening(SessionChannel.DMSDP_CONTROL, 40003)
            accepted(SessionChannel.DMSDP_CONTROL)
            data(SessionChannel.DMSDP_CONTROL, "crypto")
        }

        fun toProjection() {
            toDeviceReady()
            data(SessionChannel.DMSDP_CONTROL, "display")
            connected(SessionChannel.HISIGHT_CONTROL)
        }

        fun toPlaying() {
            toProjection()
            data(SessionChannel.HISIGHT_CONTROL, "setup")
            connected(SessionChannel.VIDEO)
            connected(SessionChannel.REMOTE_CONTROL)
            data(SessionChannel.HISIGHT_CONTROL, "play-ok")
        }
    }

    private fun List<String>.endReason(): String = single { it.startsWith("event Ended") }

    // ---------------- 完整顺序 ----------------

    @Test
    fun wiredFlowFollowsPrototypeOrder() {
        val frames = ArrayList<AccessUnit>()
        val d = Driver(sink = AccessUnitSink { frames += it })
        val ip = Synthetic.LOCAL_IP
        val phone = Synthetic.PHONE_IP

        // 先开控制监听（通配地址，只认连到车机 IP 的连接），开好才通知可以拉起。
        assertEquals(listOf("listen CONTROL 0.0.0.0:${Synthetic.PORT} only $ip"), d.start())
        assertEquals(listOf("event ControlListening(${Synthetic.PORT})"), d.listening(SessionChannel.CONTROL, Synthetic.PORT))
        assertEquals(listOf("event PhoneConnected($phone)", "write CONTROL connect-id"), d.accepted(SessionChannel.CONTROL))

        // 业务 19：先在本机地址开认证监听，开好后才把端口回给手机。
        assertEquals(
            listOf("event ControlHandshakeCompleted(加密=true)", "listen AUTH $ip:0"),
            d.data(SessionChannel.CONTROL, "auth?"),
        )
        assertEquals(listOf("write CONTROL accept:19:40001"), d.listening(SessionChannel.AUTH, 40001))
        assertEquals(emptyList(), d.accepted(SessionChannel.AUTH))
        assertEquals(
            listOf("write AUTH hello-reply", "event AuthStarted(PIN_PAIRING)", "event ShowPinCode(内容不显示)"),
            d.data(SessionChannel.AUTH, "hello"),
        )

        // 业务 20 在认证完成前到达：先留着，认证成功后再开监听、回端口。
        assertEquals(listOf("diag"), d.data(SessionChannel.CONTROL, "dmsdp?"))
        assertEquals(
            listOf("write AUTH done-reply", "event Authenticated(PIN_PAIRING)", "listen DMSDP_SESSION $ip:0"),
            d.data(SessionChannel.AUTH, "done"),
        )
        assertEquals(listOf("write CONTROL accept:20:40002"), d.listening(SessionChannel.DMSDP_SESSION, 40002))
        assertEquals(emptyList(), d.accepted(SessionChannel.DMSDP_SESSION))

        // 首轮协商要求另开控制监听，开好后把端口写回业务 20。
        assertEquals(listOf("listen DMSDP_CONTROL $ip:0"), d.data(SessionChannel.DMSDP_SESSION, "nego"))
        assertEquals(listOf("write DMSDP_SESSION control-port:40003"), d.listening(SessionChannel.DMSDP_CONTROL, 40003))
        assertEquals(listOf("write DMSDP_CONTROL OPTIONS"), d.accepted(SessionChannel.DMSDP_CONTROL))
        // 手机选 UDP 保活：绑本机地址，连设备控制连接的对端。
        assertEquals(
            listOf("write DMSDP_CONTROL crypto-ok", "udp $ip -> $phone:50000", "event DeviceSessionReady(UDP)"),
            d.data(SessionChannel.DMSDP_CONTROL, "crypto"),
        )

        // 投屏端口：车机去连业务 20 连接的对端。
        assertEquals(
            listOf("event ProjectionRequested(7000)", "connect HISIGHT_CONTROL $phone:7000"),
            d.data(SessionChannel.DMSDP_CONTROL, "display"),
        )
        assertEquals(listOf("write HISIGHT_CONTROL ANNOUNCE"), d.connected(SessionChannel.HISIGHT_CONTROL))

        // SETUP 之后先只连视频；视频连上再连回控；两条都连上才发 PLAY。
        assertEquals(
            listOf("write HISIGHT_CONTROL setup-ok", "event VideoNegotiated(1280x720@30，触摸关，按键关)", "connect VIDEO $phone:7001"),
            d.data(SessionChannel.HISIGHT_CONTROL, "setup"),
        )
        assertEquals(listOf("connect REMOTE_CONTROL $phone:7002"), d.connected(SessionChannel.VIDEO))
        assertEquals(listOf("write HISIGHT_CONTROL PLAY"), d.connected(SessionChannel.REMOTE_CONTROL))
        assertEquals(listOf("event PlayAccepted"), d.data(SessionChannel.HISIGHT_CONTROL, "play-ok"))

        // 视频帧原样交给回调（同一个对象，不复制），第一帧时报开始出画面。
        assertEquals(listOf("event VideoStarted(1280x720@30，触摸关，按键关)"), d.data(SessionChannel.VIDEO, "frame"))
        assertEquals(emptyList(), d.data(SessionChannel.VIDEO, "frame"))
        assertEquals(2, frames.size)
        assertSame(d.layers.hisight.unit, frames[0])
        assertEquals(2L, d.session.videoFrames)

        assertEquals(
            listOf(
                "nearby.create", "nearby.start", "nearby.feed(auth?)", "nearby.accept(19,40001)", "auth.create",
                "auth.feed(hello)", "nearby.feed(dmsdp?)", "auth.feed(done)", "nearby.accept(20,40002)",
                "dmsdp.create($phone)", "dmsdp.onConnected(SESSION)", "dmsdp.feed(SESSION,nego)",
                "dmsdp.controlListenerReady(40003)", "dmsdp.onConnected(CONTROL)", "dmsdp.feed(CONTROL,crypto)",
                "dmsdp.feed(CONTROL,display)", "hisight.create", "hisight.start", "hisight.feedControl(setup)",
                "hisight.onVideoConnected", "hisight.onRemoteControlConnected", "hisight.feedControl(play-ok)",
                "hisight.feedVideo(5)", "hisight.feedVideo(5)",
            ),
            d.layers.calls,
        )
    }

    @Test
    fun localStopTearsDownTopLayerFirst() {
        val d = Driver()
        d.toPlaying()
        assertEquals(
            listOf(
                "write HISIGHT_CONTROL TEARDOWN",
                "close HISIGHT_CONTROL", "close VIDEO", "close REMOTE_CONTROL",
                "close KEEPALIVE", "close DMSDP_CONTROL", "close DMSDP_CONTROL.listener",
                "close DMSDP_SESSION", "close DMSDP_SESSION.listener",
                "close AUTH", "close AUTH.listener",
                "close CONTROL", "close CONTROL.listener",
                "event Ended(本机停止)",
            ),
            d.stop(),
        )
        assertTrue(d.layers.calls.containsAll(listOf("hisight.shutdown", "dmsdp.close")))
        assertTrue(d.session.isEnded)
        assertNull(d.session.nextTickAtMillis)
        // 结束之后什么都不再做。
        assertEquals(emptyList(), d.data(SessionChannel.VIDEO, "frame"))
        assertEquals(emptyList(), d.stop())
        assertEquals(emptyList(), d.tick(1_000_000))
    }

    @Test
    fun controlListenerBoundToLocalIpHasNoFilter() {
        val d = Driver(Synthetic.config(controlBindHost = Synthetic.LOCAL_IP))
        assertEquals(listOf("listen CONTROL ${Synthetic.LOCAL_IP}:${Synthetic.PORT}"), d.start())
    }

    @Test
    fun localFilterCanBeTurnedOff() {
        val d = Driver(Synthetic.config(controlLocalFilter = false))
        assertEquals(listOf("listen CONTROL 0.0.0.0:${Synthetic.PORT}"), d.start())
    }

    @Test
    fun startTwiceIsRejected() {
        val d = Driver()
        d.start()
        assertFailsWith<IllegalStateException> { d.start() }
    }

    // ---------------- 建 socket 请求 ----------------

    @Test
    fun repeatedAuthRequestsGetTheSamePort() {
        val d = Driver()
        d.toControl()
        d.data(SessionChannel.CONTROL, "auth?")
        // 监听还没开好时又来一次：两次都等开好后回同一个端口。
        assertEquals(emptyList(), d.data(SessionChannel.CONTROL, "auth-again?"))
        assertEquals(
            listOf("write CONTROL accept:19:40001", "write CONTROL accept:19:40001"),
            d.listening(SessionChannel.AUTH, 40001),
        )
        // 开好之后的重复请求立刻回同一个端口，不再开新监听。
        assertEquals(listOf("write CONTROL accept:19:40001"), d.data(SessionChannel.CONTROL, "auth-again?"))
    }

    @Test
    fun dmsdpRequestBeforeAuthHandshakeIsRejected() {
        val d = Driver()
        d.toAuthListening()
        d.accepted(SessionChannel.AUTH)
        assertEquals(listOf("diag", "write CONTROL reject:20"), d.data(SessionChannel.CONTROL, "dmsdp?"))
    }

    @Test
    fun dmsdpRequestsDuringAuthAreAnsweredAfterSuccess() {
        val d = Driver()
        d.toAuthStarted()
        d.data(SessionChannel.CONTROL, "dmsdp?")
        d.data(SessionChannel.CONTROL, "dmsdp?")
        val afterSuccess = d.data(SessionChannel.AUTH, "done")
        assertEquals(1, afterSuccess.count { it.startsWith("listen DMSDP_SESSION") })
        assertEquals(
            listOf("write CONTROL accept:20:40002", "write CONTROL accept:20:40002"),
            d.listening(SessionChannel.DMSDP_SESSION, 40002),
        )
        assertEquals(listOf("write CONTROL accept:20:40002"), d.data(SessionChannel.CONTROL, "dmsdp?"))
    }

    @Test
    fun unknownBusinessIsRejected() {
        val d = Driver()
        d.toControl()
        assertEquals(listOf("diag", "write CONTROL reject:99"), d.data(SessionChannel.CONTROL, "other?"))
    }

    // ---------------- 认证 ----------------

    @Test
    fun authFailureEndsAndClosesEverything() {
        val d = Driver()
        d.toAuthStarted()
        d.layers.auth.onFeed = { listOf(HiCarAuthAction.Failed(AuthFailure.Local(HcError.VERIFY_PROOF_FAILED))) }
        assertEquals(
            listOf("close AUTH", "close AUTH.listener", "close CONTROL", "close CONTROL.listener", "event Ended(认证失败（Local(VERIFY_PROOF_FAILED=17)）)"),
            d.data(SessionChannel.AUTH, "x"),
        )
    }

    @Test
    fun authConnectionClosedBeforeSuccessIsAuthFailure() {
        val d = Driver()
        d.toAuthStarted()
        val out = d.closed(SessionChannel.AUTH)
        assertEquals("close AUTH", out.first())
        assertEquals("event Ended(认证失败（ConnectionClosed(incompleteMessage=false)）)", out.endReason())
        assertTrue("auth.closed" in d.layers.calls)
    }

    @Test
    fun authConnectionClosedAfterSuccessIsNormal() {
        val d = Driver()
        d.toAuthenticated()
        assertEquals(listOf("close AUTH"), d.closed(SessionChannel.AUTH))
        assertTrue("auth.closed" !in d.layers.calls)
    }

    /** 显示服务的暂停（202）报成「手机暂停投屏」，连接照常；别的动作、别的服务只记诊断。 */
    @Test
    fun displayServicePauseIsReportedAndTheSessionGoesOn() {
        val d = Driver()
        d.toPlaying()
        val json = kotlinx.serialization.json.JsonObject(emptyMap())
        d.layers.dmsdp.onFeed = { channel, text ->
            when {
                channel != DmsdpChannel.CONTROL -> emptyList()
                text == "pause-display" -> listOf(DmsdpEvent.ServiceStatusChanged(202, 8L, "display", json))
                text == "pause-speaker" -> listOf(DmsdpEvent.ServiceStatusChanged(202, 4L, "speaker", json))
                text == "other-display" -> listOf(DmsdpEvent.ServiceStatusChanged(204, 8L, "display", json))
                else -> emptyList()
            }
        }
        assertEquals(listOf("diag", "event ProjectionPaused"), d.data(SessionChannel.DMSDP_CONTROL, "pause-display"))
        assertEquals(listOf("diag"), d.data(SessionChannel.DMSDP_CONTROL, "pause-speaker"))
        assertEquals(listOf("diag"), d.data(SessionChannel.DMSDP_CONTROL, "other-display"))
        assertTrue(d.data(SessionChannel.DMSDP_CONTROL, "pause-display").none { it.startsWith("event Ended") })
    }

    @Test
    fun sessionKeyIsHandedToDmsdpAndHiSightThenWiped() {
        val d = Driver()
        d.toProjection()
        assertContentEquals(Synthetic.KEY, d.layers.dmsdpKey)
        assertContentEquals(Synthetic.KEY, d.layers.hisightKey)
        assertEquals(Synthetic.PHONE_IP, d.layers.dmsdpPhoneIp)
        // 认证给出的密钥数组用完就清零。
        assertContentEquals(ByteArray(16), d.layers.auth.lastSessionKey)
    }

    // ---------------- 连接被手机关闭 ----------------

    @Test
    fun controlClosedBeforeAuthEnds() {
        val d = Driver()
        d.toAuthStarted()
        assertEquals("event Ended(手机关闭了控制通道（认证完成前）)", d.closed(SessionChannel.CONTROL).endReason())
    }

    @Test
    fun controlClosedAfterAuthButBeforeDeviceSessionEnds() {
        val d = Driver()
        d.toAuthenticated()
        assertEquals("event Ended(手机关闭了控制通道（设备会话还没开始）)", d.closed(SessionChannel.CONTROL).endReason())
    }

    @Test
    fun controlClosedAfterDeviceSessionStartedContinues() {
        val d = Driver()
        d.toDmsdp()
        d.layers.nearby.nextTickAtMillis = 5_000
        assertEquals(listOf("close CONTROL", "diag"), d.closed(SessionChannel.CONTROL))
        assertNull(d.session.nextTickAtMillis, "控制通道关了就不再推进 nearby 的定时")
        d.data(SessionChannel.DMSDP_SESSION, "nego")
        assertEquals(listOf("write DMSDP_SESSION control-port:40003"), d.listening(SessionChannel.DMSDP_CONTROL, 40003))
    }

    @Test
    fun dmsdpSessionClosedBeforeNegotiationEnds() {
        val d = Driver()
        d.toDmsdp()
        val out = d.closed(SessionChannel.DMSDP_SESSION)
        assertEquals("event Ended(手机关闭了设备会话连接（还没协商出设备控制端口）)", out.endReason())
        assertTrue("dmsdp.onClosed(SESSION)" in d.layers.calls)
    }

    @Test
    fun dmsdpSessionClosedAfterNegotiationContinues() {
        val d = Driver()
        d.toDeviceReady()
        assertEquals(listOf("close DMSDP_SESSION"), d.closed(SessionChannel.DMSDP_SESSION))
    }

    @Test
    fun dmsdpControlClosedWithoutProjectionEnds() {
        val d = Driver()
        d.toDeviceReady()
        assertEquals("event Ended(手机关闭了设备控制连接（投屏还没开始）)", d.closed(SessionChannel.DMSDP_CONTROL).endReason())
    }

    @Test
    fun dmsdpControlClosedDuringProjectionContinues() {
        val d = Driver()
        d.toPlaying()
        assertEquals(listOf("close DMSDP_CONTROL", "diag"), d.closed(SessionChannel.DMSDP_CONTROL))
        assertEquals(emptyList(), d.data(SessionChannel.VIDEO, "frame").filter { it.startsWith("event Ended") })
    }

    @Test
    fun partialFrameOnDmsdpCloseIsProtocolError() {
        val d = Driver()
        d.toDeviceReady()
        d.layers.dmsdp.onClosedHandler = { throw MalformedMessageException("控制连接关闭时留下不完整的帧") }
        assertEquals(
            "event Ended(协议错误（DMSDP：控制连接关闭时留下不完整的帧）)",
            d.closed(SessionChannel.DMSDP_CONTROL).endReason(),
        )
    }

    // ---------------- 投屏 ----------------

    @Test
    fun hiSightControlClosedWhileStreamingKeepsReceivingVideo() {
        var frames = 0
        val d = Driver(sink = AccessUnitSink { frames++ })
        d.toPlaying()
        assertEquals(listOf("close HISIGHT_CONTROL", "diag"), d.closed(SessionChannel.HISIGHT_CONTROL))
        assertTrue("hisight.onControlClosed" !in d.layers.calls)
        d.data(SessionChannel.VIDEO, "frame")
        assertEquals(1, frames)
        // 视频连接结束才收尾；控制连接已经没了，TEARDOWN 不再写。
        val out = d.closed(SessionChannel.VIDEO)
        assertTrue(out.none { it.startsWith("write") }, out.toString())
        assertEquals("event Ended(投屏结束（VIDEO_CLOSED）)", out.endReason())
    }

    @Test
    fun hiSightControlClosedBeforePlayEndsProjection() {
        val d = Driver()
        d.toProjection()
        val out = d.closed(SessionChannel.HISIGHT_CONTROL)
        assertTrue("hisight.onControlClosed" in d.layers.calls)
        assertEquals("event Ended(投屏结束（CONTROL_CLOSED）)", out.endReason())
    }

    @Test
    fun peerTeardownEndsWholeSession() {
        val d = Driver()
        d.toPlaying()
        val out = d.data(SessionChannel.HISIGHT_CONTROL, "teardown")
        assertEquals(
            listOf(
                "write HISIGHT_CONTROL ok", "write HISIGHT_CONTROL TEARDOWN",
                "close HISIGHT_CONTROL", "close VIDEO", "close REMOTE_CONTROL",
                "close KEEPALIVE", "close DMSDP_CONTROL", "close DMSDP_CONTROL.listener",
                "close DMSDP_SESSION", "close DMSDP_SESSION.listener", "close AUTH", "close AUTH.listener",
                "close CONTROL", "close CONTROL.listener", "event Ended(投屏结束（PEER_TEARDOWN）)",
            ),
            out,
        )
        assertTrue("hisight.shutdown" !in d.layers.calls, "投屏层自己结束的，不再调 shutdown")
        assertTrue("dmsdp.close" in d.layers.calls)
    }

    @Test
    fun withoutRemotePortNoRemoteConnectionIsMade() {
        val d = Driver()
        d.toProjection()
        d.layers.hisight.onFeedControl = {
            listOf(HiSightOutput.Connect(HiSightChannel.VIDEO, 7001))
        }
        assertEquals(listOf("connect VIDEO ${Synthetic.PHONE_IP}:7001"), d.data(SessionChannel.HISIGHT_CONTROL, "setup"))
        d.layers.hisight.onFeedControl = { emptyList() }
        assertEquals(emptyList(), d.connected(SessionChannel.VIDEO))
        assertTrue(d.connects[SessionChannel.REMOTE_CONTROL] == null)
    }

    @Test
    fun repeatedDisplayPortIsIgnoredButADifferentOneIsAnError() {
        val d = Driver()
        d.toProjection()
        assertEquals(listOf("diag"), d.data(SessionChannel.DMSDP_CONTROL, "display"))
        d.layers.dmsdp.onFeed = { _, _ -> listOf(DmsdpEvent.DisplayPortAnnounced(7999)) }
        val out = d.data(SessionChannel.DMSDP_CONTROL, "display")
        assertEquals("write HISIGHT_CONTROL TEARDOWN", out.first())
        assertEquals("event Ended(协议错误（DMSDP：手机重复通告了不同的投屏端口）)", out.endReason())
    }

    @Test
    fun videoConnectFailureEndsSession() {
        val d = Driver()
        d.toProjection()
        d.data(SessionChannel.HISIGHT_CONTROL, "setup")
        val out = d.ioError(SessionChannel.VIDEO)
        assertTrue("hisight.shutdown" in d.layers.calls)
        assertEquals("event Ended(视频连接网络错误（测试错误）)", out.endReason())
    }

    @Test
    fun inboundDataOnRemoteControlIsProtocolError() {
        val d = Driver()
        d.toPlaying()
        assertEquals("event Ended(协议错误（HISIGHT：回控连接收到未定义的入站数据）)", d.data(SessionChannel.REMOTE_CONTROL, "?").endReason())
    }

    @Test
    fun remoteControlClosedEndsProjection() {
        val d = Driver()
        d.toPlaying()
        val out = d.closed(SessionChannel.REMOTE_CONTROL)
        assertEquals("close REMOTE_CONTROL", out.first())
        assertTrue("write HISIGHT_CONTROL TEARDOWN" in out)
        assertEquals("event Ended(投屏结束（REMOTE_CONTROL_CLOSED）)", out.endReason())
    }

    @Test
    fun failingVideoSinkEndsSession() {
        val d = Driver(sink = AccessUnitSink { throw IllegalStateException("磁盘满了") })
        d.toPlaying()
        val out = d.data(SessionChannel.VIDEO, "frame")
        assertEquals("write HISIGHT_CONTROL TEARDOWN", out.first())
        assertEquals("event Ended(视频输出失败（IllegalStateException：磁盘满了）)", out.endReason())
    }

    @Test
    fun inputGoesToRemoteControlConnection() {
        val d = Driver()
        assertEquals(emptyList(), d.session.sendInput(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 4, 0L)))
        d.toPlaying()
        val out = d.record(d.session.sendInput(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 4, 0L)))
        assertEquals(listOf("write REMOTE_CONTROL input"), out)
    }

    // ---------------- 保活与定时 ----------------

    @Test
    fun keepAliveDatagramsAndTimeout() {
        val d = Driver()
        d.toPlaying()
        d.layers.dmsdp.onTickHandler = { listOf(DmsdpAction.SendDatagram("ka".bytes()), DmsdpAction.SendDatagram("ka".bytes())) }
        assertEquals(listOf("datagram ka", "datagram ka"), d.tick(3_000))
        val udpId = d.udp!!.id
        d.record(d.session.onDatagram(udpId, "ka".bytes(), 0, 2, d.now))
        assertTrue("dmsdp.onDatagram(ka)" in d.layers.calls)

        d.layers.dmsdp.onTickHandler = { listOf(DmsdpEvent.KeepAliveTimeout(KeepAliveMode.UDP)) }
        val out = d.tick(200_000)
        assertEquals("write HISIGHT_CONTROL TEARDOWN", out.first())
        assertEquals("event Ended(设备会话保活超时（UDP）)", out.endReason())
    }

    @Test
    fun malformedKeepAliveDatagramEndsSession() {
        val d = Driver()
        d.toDeviceReady()
        d.layers.dmsdp.onDatagramHandler = { throw MalformedMessageException("UDP 保活不是加密帧") }
        val out = d.record(d.session.onDatagram(d.udp!!.id, "bad".bytes(), 0, 3, d.now))
        assertEquals("event Ended(协议错误（DMSDP：UDP 保活不是加密帧）)", out.endReason())
    }

    @Test
    fun nextTickIsTheEarliestLayerDeadline() {
        val d = Driver()
        d.toDmsdp()
        d.layers.nearby.nextTickAtMillis = 6_000
        d.layers.dmsdp.nextTickAtMillis = null
        assertEquals(6_000, d.session.nextTickAtMillis)
        d.layers.dmsdp.nextTickAtMillis = 2_000
        assertEquals(2_000, d.session.nextTickAtMillis)
    }

    @Test
    fun controlHandshakeTimeoutEnds() {
        val d = Driver()
        d.toControl()
        d.layers.nearby.onTickHandler = { listOf(NearbyOutput.HandshakeTimedOut) }
        assertEquals(
            listOf("close CONTROL", "close CONTROL.listener", "event Ended(控制通道握手超时)"),
            d.tick(6_000),
        )
    }

    @Test
    fun networkErrorOnControlListenerEnds() {
        val d = Driver()
        d.start()
        val out = d.record(d.session.onIoError(d.listens.getValue(SessionChannel.CONTROL).id, "地址已被占用", d.now))
        assertEquals("event Ended(控制通道网络错误（地址已被占用）)", out.endReason())
    }

    @Test
    fun unexpectedLayerExceptionEndsWithInternalError() {
        val d = Driver()
        d.toDeviceReady()
        d.layers.dmsdp.onFeed = { _, _ -> throw IllegalStateException("状态不对") }
        val out = d.data(SessionChannel.DMSDP_CONTROL, "x")
        assertEquals("event Ended(内部错误（DMSDP：IllegalStateException：状态不对）)", out.endReason())
    }

    @Test
    fun dataForUnknownOrClosedConnectionsIsIgnored() {
        val d = Driver()
        d.toAuthenticated()
        assertEquals(emptyList(), d.record(d.session.onData(999, "x".bytes(), 0, 1, d.now)))
        d.closed(SessionChannel.AUTH)
        assertEquals(emptyList(), d.data(SessionChannel.AUTH, "late"))
        assertEquals(emptyList(), d.ioError(SessionChannel.AUTH))
        assertIs<ReceiverSession>(d.session)
    }

    @Test
    fun fileShareRequestsAreIgnoredBecauseItIsDisabled() {
        val d = Driver()
        d.toDeviceReady()
        d.layers.dmsdp.onFeed = { _, _ ->
            listOf(DmsdpAction.Connect(DmsdpChannel.FILE_CONTROL, 9000), DmsdpAction.Close(DmsdpChannel.FILE_DATA))
        }
        assertEquals(listOf("diag"), d.data(SessionChannel.DMSDP_CONTROL, "x"))
    }

    @Test
    fun peerTeardownWhenProjectionLayerIsAlreadyEndedDoesNotCallShutdown() {
        val d = Driver()
        d.toPlaying()
        d.layers.hisight.onFeedControl = {
            listOf(HiSightOutput.CloseAll, HiSightOutput.Ended(HiSightEndReason.PEER_TEARDOWN))
        }
        d.data(SessionChannel.HISIGHT_CONTROL, "x")
        assertTrue("hisight.shutdown" !in d.layers.calls)
    }
}
