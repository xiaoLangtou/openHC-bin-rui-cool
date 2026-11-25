package io.github.gcbond.openhc.core.hisight

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 控制连接整段对话的逐字节对照：参考实现在合成对话上发出的每一帧（同样的 IV 序列、同样的 Date），
 * 与本实现按同样输入产生的 [HiSightOutput.Send] 完全一致，包括出错时的线上顺序。
 */
class ReceiverVectorTest {
    private val conversations = Vectors.section("control").let { it as kotlinx.serialization.json.JsonArray }

    @Test
    fun conversationsMatchPrototypeByteForByte() {
        assertTrue(conversations.size >= 20)
        for (element in conversations) replay(element.jsonObject)
    }

    private fun replay(conversation: JsonObject) {
        val name = conversation.str("name")
        val random = FixedRandom(conversation.arr("ivs").map { it.jsonPrimitive.content.hexToBytes() })
        val session = HiSightReceiver(
            Vectors.key, configFrom(conversation.obj("config")), random, { },
            TimeZone.getTimeZone("UTC"),
        )
        val wall = conversation.long("wallMillis")
        val expectedWire = ArrayList<String>()
        val actualWire = ArrayList<String>()
        val events = ArrayList<HiSightOutput>()
        var remotePort = -1
        var failedBefore = false

        for ((index, stepElement) in conversation.arr("steps").withIndex()) {
            val step = stepElement.jsonObject
            val label = "$name 第 $index 步 ${step.str("op")}"
            val expected = step.arr("out").map { it.jsonPrimitive.content }
            expectedWire += expected
            val outputs: List<HiSightOutput> = when (step.str("op")) {
                "start" -> session.start()
                "feed" -> {
                    val chunk = step.getValue("in").hex()
                    val referenceError = step.bool("error")
                    try {
                        val out = session.feedControl(chunk, 0, chunk.size, wall)
                        if (referenceError) {
                            // 有意的差异：参考实现在这一步报错结束，本实现回应答、写说明、继续（手机各版本的写法不同，
                            // 见 HiSightReceiverTest）。必须留下说明；这段对话只核对到这一步之前。
                            assertTrue(out.any { it is HiSightOutput.Note }, "$label：参考实现报错，本实现放行却没写说明")
                            return
                        }
                        out
                    } catch (e: MalformedMessageException) {
                        assertEquals(true, referenceError, "$label：本实现报错（${e.message}），参考实现没有")
                        failedBefore = true
                        emptyList()
                    }
                }
                "media_connected" -> {
                    val first = session.onVideoConnected()
                    if (remotePort != -1) {
                        assertTrue(first.isEmpty(), "$label：回控未就绪时不应发 PLAY")
                        session.onRemoteControlConnected()
                    } else {
                        first
                    }
                }
                "shutdown" -> session.shutdown()
                else -> error("未知步骤")
            }
            val sent = outputs.filterIsInstance<HiSightOutput.Send>()
            assertTrue(sent.all { it.channel == HiSightChannel.CONTROL }, label)
            val actual = sent.map { it.bytes.toHex() }
            actualWire += actual
            if (!failedBefore && step.str("op") != "shutdown") assertEquals(expected, actual, label)
            for (output in outputs) {
                if (output is HiSightOutput.Connect && output.channel == HiSightChannel.REMOTE_CONTROL) {
                    remotePort = output.port
                }
            }
            events += outputs.filter { it !is HiSightOutput.Send }
        }
        assertEquals(expectedWire, actualWire, "$name：整段线上字节")
        assertEquals(conversation.int("ivsUsed"), random.used, "$name：IV 用量")

        // 参考实现交给视频层的端口和尺寸，本实现以事件给出。
        val setups = conversation.arr("setup")
        val negotiated = events.filterIsInstance<HiSightOutput.StreamNegotiated>()
        assertEquals(setups.size, negotiated.size, name)
        for ((expected, actual) in setups.map { it.jsonObject }.zip(negotiated)) {
            assertEquals(expected.int("width"), actual.width, name)
            assertEquals(expected.int("height"), actual.height, name)
            assertEquals(expected.int("fps"), actual.fps, name)
            val connects = events.filterIsInstance<HiSightOutput.Connect>()
            assertEquals(expected.int("serverPort"), connects.single { it.channel == HiSightChannel.VIDEO }.port, name)
            val remote = connects.singleOrNull { it.channel == HiSightChannel.REMOTE_CONTROL }?.port ?: -1
            assertEquals(expected.int("remotePort"), remote, name)
        }
        // 只要会话结束，最后两项一定是 CloseAll、Ended。
        val ended = events.filterIsInstance<HiSightOutput.Ended>()
        assertTrue(ended.size <= 1, name)
        if (ended.isNotEmpty()) {
            assertEquals(HiSightOutput.CloseAll, events[events.size - 2], name)
            assertEquals(ended.single(), events.last(), name)
        }
    }

    @Test
    fun peerTeardownEndsWithReason() {
        val full = conversations.map { it.jsonObject }.single { it.str("name") == "full-compact-setup" }
        val random = FixedRandom(full.arr("ivs").map { it.jsonPrimitive.content.hexToBytes() })
        val session = HiSightReceiver(Vectors.key, configFrom(full.obj("config")), random, { }, TimeZone.getTimeZone("UTC"))
        val all = ArrayList<HiSightOutput>()
        for (stepElement in full.arr("steps")) {
            val step = stepElement.jsonObject
            all += when (step.str("op")) {
                "start" -> session.start()
                "feed" -> step.getValue("in").hex().let { session.feedControl(it, 0, it.size, full.long("wallMillis")) }
                "media_connected" -> session.onVideoConnected() + session.onRemoteControlConnected()
                else -> session.shutdown()
            }
        }
        assertEquals(1, all.count { it === HiSightOutput.PlayAccepted })
        val ended = all.filterIsInstance<HiSightOutput.Ended>().single()
        assertEquals(HiSightEndReason.PEER_TEARDOWN, ended.reason)
        // 结束后再调用都不产生动作。
        assertEquals(emptyList(), session.shutdown())
        assertEquals(emptyList(), session.feedControl(ByteArray(4), 0, 4, 0))
    }

}
