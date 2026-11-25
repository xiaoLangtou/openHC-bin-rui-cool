package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** module 3 设备与服务命令：101/102/103/104/105。 */
class DeviceMessageHandlerTest {

    private class Result {
        val replies = ArrayList<ByteArray>()
        val events = ArrayList<DmsdpEvent>()
    }

    private fun DeviceMessageHandler.run(data: ByteArray): Result {
        val result = Result()
        handle(data, { result.replies += it }, { result.events += it })
        return result
    }

    private fun phone101(vararg changes: Pair<String, String>): ByteArray {
        val fields = linkedMapOf(
            "deviceId" to "\"${Synthetic.PEER_ID}\"",
            "deviceType" to "1",
            "deviceName" to "\"合成手机\"",
            "properties" to "{\"5001\": true}",
        )
        for ((k, v) in changes) fields[k] = v
        val json = fields.entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${it.value}" }
        return hex("e5") + json.toByteArray()
    }

    @Test
    fun localDeviceInfoMatchesCrossVectors() {
        assertEquals(CrossVectors.DEVICE_101_REPLY_DISPLAY, DeviceMessageHandler(Synthetic.config()).run(hex(CrossVectors.PHONE_101)).replies.single().toHex())
        assertEquals(
            CrossVectors.DEVICE_101_REPLY_PLAIN,
            DeviceMessageHandler(Synthetic.config(display = null, name = "OpenHC")).run(hex(CrossVectors.PHONE_101)).replies.single().toHex(),
        )
        val minimal = DmsdpConfig.Display(width = 800, height = 480, density = 1.0, dpi = 160, fps = 60)
        val exchange = DeviceMessageHandler(Synthetic.config(display = minimal, name = "OpenHC"))
        assertEquals(CrossVectors.DEVICE_101_REPLY_MIN, exchange.run(hex(CrossVectors.PHONE_101)).replies.single().toHex())
        assertEquals(CrossVectors.DEVICE_102_DISPLAY_MIN, exchange.run(hex("e80027ffff")).replies.single().toHex())
        assertEquals(CrossVectors.DEVICE_102_DISPLAY, DeviceMessageHandler(Synthetic.config()).run(hex("e800000008")).replies.single().toHex())
    }

    @Test
    fun localDeviceJsonTypes() {
        val reply = DeviceMessageHandler(Synthetic.config()).run(hex(CrossVectors.PHONE_101)).replies.single()
        val json = Json.parseToJsonElement(String(reply.copyOfRange(1, reply.size))) as JsonObject
        assertEquals("4", json["deviceType"].toString())
        assertEquals("-1", json["port"].toString())
        assertEquals("1", json["businessId"].toString())
        assertEquals("\"ABCDEF01\"", (json["properties"] as JsonObject)["2003"].toString(), "2003 是完整 8 位车型编号")
        val service = DeviceMessageHandler(Synthetic.config()).run(hex("e800000008")).replies.single()
        val properties = (Json.parseToJsonElement(String(service.copyOfRange(1, service.size))) as JsonObject)["properties"] as JsonObject
        assertEquals("1.5", properties["4001"].toString(), "缩放比例是浮点数")
        assertEquals("1920", properties["1009"].toString())
        val integral = DmsdpConfig.Display(width = 800, height = 480, density = 2.0, dpi = 320, fps = 30)
        val integralReply = DeviceMessageHandler(Synthetic.config(display = integral)).run(hex("e800000008")).replies.single()
        assertTrue("\"4001\":2.0" in String(integralReply), "整数值的缩放比例也带小数点")
    }

    @Test
    fun deviceInfoIsAnsweredOnceAndIdentityIsPinned() {
        val exchange = DeviceMessageHandler(Synthetic.config())
        val first = exchange.run(phone101())
        assertEquals(1, first.replies.size)
        val event = first.events.single() as DmsdpEvent.PeerDeviceInfo
        assertEquals(Synthetic.PEER_ID, event.deviceId)
        assertEquals(1L, event.deviceType)
        assertEquals("true", (event.device["properties"] as JsonObject)["5001"].toString(), "未知属性原样保留")
        assertFalse(event.toString().contains(Synthetic.PEER_ID), "toString 不输出设备标识")

        val again = exchange.run(phone101("deviceName" to "\"改名\""))
        assertTrue(again.replies.isEmpty(), "同一连接只回一次本机 101，避免互相回声")
        assertEquals("改名", (again.events.single() as DmsdpEvent.PeerDeviceInfo).device["deviceName"]!!.jsonPrimitive.content)
        assertFailsWith<MalformedMessageException> { exchange.run(phone101("deviceId" to "\"${Synthetic.SELF_ID}\"")) }
    }

    @Test
    fun serviceRequestAnswersOnlyDeclaredServices() {
        val exchange = DeviceMessageHandler(Synthetic.config())
        assertEquals(listOf("display1"), (exchange.run(hex("e80027ffff")).events.single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
        for (mask in listOf("e800000007", "e800000000")) {
            val result = exchange.run(hex(mask))
            assertTrue(result.replies.isEmpty(), mask)
            assertTrue((result.events.single() as DmsdpEvent.ServicesRequested).answeredServiceIds.isEmpty())
        }
        val noDisplay = DeviceMessageHandler(Synthetic.config(display = null))
        assertTrue(noDisplay.run(hex("e8ffffffff")).replies.isEmpty(), "默认不声明显示")
        assertEquals("{\"2003\":\"ABCDEF01\",\"5007\":true,\"5042\":true,\"5032\":true}", noDisplay.localDeviceObject()["properties"].toString(), "没有显示就不带视频属性（5007 兼容/5042 元数据/5032 文件分享，实车基线）")
    }

    /** 扩展点：显示以外的服务按各自的服务类型位回应，显示在前。 */
    @Test
    fun additionalServicesAreDeclaredByTheirTypeBit() {
        val speaker = DmsdpConfig.Service(
            serviceType = 4,
            serviceId = "speaker1",
            properties = buildJsonObject { put("2003", "") },
        )
        val exchange = DeviceMessageHandler(Synthetic.config(additionalServices = listOf(speaker)))
        val both = exchange.run(hex("e80000000c"))
        assertEquals(listOf("display1", "speaker1"), (both.events.single() as DmsdpEvent.ServicesRequested).answeredServiceIds)
        assertEquals(2, both.replies.size)
        assertEquals(
            "{\"deviceId\":\"${Synthetic.SELF_ID}\",\"deviceName\":\"测试车机 Ünï\",\"serviceType\":4," +
                "\"serviceId\":\"speaker1\",\"properties\":{\"2003\":\"\"},\"status\":{}}",
            String(both.replies[1].copyOfRange(1, both.replies[1].size)),
        )
        assertEquals(1, exchange.run(hex("e800000004")).replies.size)
        assertFailsWith<IllegalArgumentException> {
            Synthetic.config(additionalServices = listOf(DmsdpConfig.Service(8, "display1", JsonObject(emptyMap()))))
        }
    }

    @Test
    fun peerServiceStatusAndDisconnectNotifications() {
        val exchange = DeviceMessageHandler(Synthetic.config())
        val service = "{\"deviceId\":\"${Synthetic.PEER_ID}\",\"serviceType\":8,\"serviceId\":\"remote\",\"properties\":{},\"status\":{}}"
        val info = exchange.run(hex("e6") + service.toByteArray())
        assertTrue(info.replies.isEmpty())
        assertEquals("remote", (info.events.single() as DmsdpEvent.PeerServiceInfo).serviceId)

        val status = exchange.serviceObject(exchange.localServices().single()).toString().toByteArray()
        for (action in listOf(202, -123456)) {
            val body = ByteArray(4).also { putS32Be(it, 0, action) } + status
            val result = exchange.run(hex("e7") + body)
            assertTrue(result.replies.isEmpty(), "103 不应答")
            val event = result.events.single() as DmsdpEvent.ServiceStatusChanged
            assertEquals(action, event.action)
            assertEquals("display1", event.serviceId)
        }
        for (reason in listOf(0, 2, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val body = ByteArray(4).also { putS32Be(it, 0, reason) }
            val result = exchange.run(hex("e9") + body)
            assertTrue(result.replies.isEmpty(), "105 不应答")
            assertEquals(reason, (result.events.single() as DmsdpEvent.DisconnectTypeNotified).disconnectType)
        }
    }

    @Test
    fun unknownCommandsAreReportedNotAnswered() {
        for (command in listOf(106, 110, 0, 0x3FFF)) {
            val result = DeviceMessageHandler(Synthetic.config()).run(Ea.encode(command) + hex("00000000"))
            assertTrue(result.replies.isEmpty())
            val event = result.events.single() as DmsdpEvent.UnsupportedDeviceCommand
            assertEquals(command, event.command)
            assertEquals(4, event.body.size)
        }
    }

    @Test
    fun malformedBodiesAreRejected() {
        val serviceJson = "{\"deviceId\":\"x\",\"serviceId\":\"y\",\"serviceType\":8,\"deviceName\":\"n\"}"
        val invalid = listOf(
            "" to ByteArray(0),
            "未结束的 EA" to hex("65"),
            "4 字节 EA" to hex("01010101"),
            "空对象" to hex("e5") + "{}".toByteArray(),
            "数组" to hex("e5") + "[]".toByteArray(),
            "坏 JSON" to hex("e5") + "{".toByteArray(),
            "坏 UTF-8" to hex("e5") + hex("ff"),
            "deviceType 是布尔" to phone101("deviceType" to "true"),
            "deviceType 是浮点" to phone101("deviceType" to "1.0"),
            "deviceType 是字符串" to phone101("deviceType" to "\"1\""),
            "deviceId 为空" to phone101("deviceId" to "\"\""),
            "properties 是数组" to phone101("properties" to "[]"),
            "104 只有 3 字节" to hex("e8000008"),
            "104 多 1 字节" to hex("e80000000800"),
            "103 没有 JSON" to hex("e7000000ca"),
            "103 截断" to hex("e7000000"),
            "103 缺 deviceName" to hex("e7000000ca") + serviceJson.replace(",\"deviceName\":\"n\"", "").toByteArray(),
            "103 serviceType 是布尔" to hex("e7000000ca") + serviceJson.replace("8", "true").toByteArray(),
            "102 status 是数组" to hex("e6") + serviceJson.replace("}", ",\"status\":[]}").toByteArray(),
            "105 长度不对" to hex("e9000000"),
            "105 多字节" to hex("e90000000000"),
        )
        for ((name, data) in invalid) {
            assertFailsWith<MalformedMessageException>(name) { DeviceMessageHandler(Synthetic.config()).run(data) }
        }
    }

    @Test
    fun parseErrorsDoNotCarryPeerData() {
        val bad = hex("e5") + "{\"deviceId\":\"${Synthetic.PEER_ID}\",\"deviceType\":}".toByteArray()
        val error = assertFailsWith<MalformedMessageException> { DeviceMessageHandler(Synthetic.config()).run(bad) }
        var cause: Throwable? = error
        while (cause != null) {
            assertFalse(cause.toString().contains(Synthetic.PEER_ID.substring(0, 16)), "异常链里不能有手机标识: $cause")
            cause = cause.cause
        }
    }

    /**
     * kotlinx 的树解析遇到重复键时取最后一个、不报错（另一份实现会拒绝）。这里只记录这个差异，
     * 设备 JSON 不靠重复键判断任何东西。
     */
    @Test
    fun duplicateJsonKeysKeepLastValue() {
        val exchange = DeviceMessageHandler(Synthetic.config())
        val json = "{\"deviceId\":\"${Synthetic.PEER_ID}\",\"deviceType\":1,\"deviceType\":2}"
        val event = exchange.run(hex("e5") + json.toByteArray()).events.single() as DmsdpEvent.PeerDeviceInfo
        assertEquals(2L, event.deviceType)
    }
}
