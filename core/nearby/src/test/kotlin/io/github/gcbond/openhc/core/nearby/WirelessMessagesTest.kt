package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 无线新增的消息：数据通道协商（资源层命令 1、2、7）、WiFi 组网层的 MAC、带 uuid 的建 socket 应答。全部用合成值。 */
class WirelessMessagesTest {

    /** 合成能力串：组主、组成员、2.4G、5G 都支持，双频 0，tag 12 = 300，信道 36/149/165，当前 5180 MHz，另有 tag 9、17。 */
    private val capability = ByteWriter()
        .ea(1).ea(1).ea(2).ea(1).ea(3).ea(1).ea(4).ea(1).ea(6).ea(0).ea(12).ea(300)
        .ea(5).bytes(hex("1182"))
        .ea(7).ea(5180).ea(9).ea(0).ea(17).ea(1)
        .toByteArray()

    @Test
    fun capabilityStringIsParsed() {
        val cap = DataChannelMessages.parseCapability(capability)
        assertEquals(true, cap.canBeGroupOwner)
        assertEquals(true, cap.canBeGroupClient)
        assertEquals(true, cap.supports2g)
        assertEquals(true, cap.supports5g)
        assertEquals(false, cap.dualBand)
        assertEquals(273L, cap.channelBitmap)
        assertEquals(setOf(36, 149, 165), cap.channels5g)
        assertEquals(5180, cap.connectedFrequencyMhz)
        assertEquals(mapOf(12 to 300L, 9 to 0L, 17 to 1L), cap.otherFields)
        assertNull(cap.unparsedFrom)
        assertNull(cap.incompleteRaw)
        // 没出现的 tag 为 null。
        val empty = DataChannelMessages.parseCapability(ByteArray(0))
        assertNull(empty.supports5g)
        assertNull(empty.channels5g)
        assertNull(empty.connectedFrequencyMhz)
    }

    @Test
    fun channelBitmapIsEaShapedButMayBeLonger() {
        // 9 个已知信道全有 = 0x1FF，写出来就是 EA 的 7F 83。
        assertEquals("7f83", DataChannelMessages.writeBitmap(ByteWriter(), 0x1FF).toByteArray().toHex())
        assertEquals(WifiChannels.CHANNELS_5G.toSet(), WifiChannels.channelsOf(DataChannelMessages.readBitmap(ByteReader(hex("7f83")))))
        // 超过 4 字节（EA 读不了）也能读；更高的位含义未知，不算进信道。
        val long = DataChannelMessages.readBitmap(ByteReader(hex("7f7f7f7f81")))
        assertEquals(0x1FFFFFFFL, long)
        assertEquals(9, WifiChannels.channelsOf(long).size)
        assertFailsWith<MalformedMessageException> { DataChannelMessages.readBitmap(ByteReader(ByteArray(9))) }
        assertFailsWith<MalformedMessageException> { DataChannelMessages.readBitmap(ByteReader(hex("7f7f"))) }
        for (bits in listOf(0L, 1L, 273L, 0x1FFL, 1L shl 40)) {
            val w = DataChannelMessages.writeBitmap(ByteWriter(), bits).toByteArray()
            assertEquals(bits, DataChannelMessages.readBitmap(ByteReader(w)))
        }
    }

    @Test
    fun negotiationRequestNeedsCapabilityTlv() {
        val req = DataChannelMessages.parseRequest(DataChannelMessages.requestValue(3, capability))
        assertEquals(3, req.dataChannel)
        assertEquals(setOf(36, 149, 165), req.capability.channels5g)
        // 能力串后面多出的别的 TLV 忽略。
        val withExtra = DataChannelMessages.requestValue(3, capability) + encodeTlv(9, byteArrayOf(1))
        assertEquals(3, DataChannelMessages.parseRequest(withExtra).dataChannel)
        assertFailsWith<MalformedMessageException> { DataChannelMessages.parseRequest(hex("83")) }
        assertFailsWith<MalformedMessageException> { DataChannelMessages.parseRequest(hex("838385")) }
        // 能力串内容解不了不算整条请求错：只用解开的部分（这里一个字段都没解开）。
        val unreadable = DataChannelMessages.parseRequest(DataChannelMessages.requestValue(3, hex("85000000000000000000")))
        assertEquals(0, unreadable.capability.unparsedFrom)
        assertNull(unreadable.capability.channelBitmap)
    }

    @Test
    fun unknownFieldsLongerThanEaKeepTheRestAligned() {
        // 不认识的 tag 20 带一个 5 字节的值（EA 最多 4 字节），后面的字段照样解得开。
        val cap = DataChannelMessages.parseCapability(
            ByteWriter().ea(3).ea(1).ea(20).bytes(hex("0102030481")).ea(4).ea(1).ea(7).ea(2437).toByteArray(),
        )
        assertEquals(true, cap.supports2g)
        assertEquals(true, cap.supports5g)
        assertEquals(2437, cap.connectedFrequencyMhz)
        assertEquals(setOf(20), cap.otherFields.keys)
        assertNull(cap.unparsedFrom)
    }

    @Test
    fun unreadableTailKeepsTheFieldsBeforeIt() {
        // 第 4 字节起是一段认不出的格式（连续 9 字节没有终止位）：前面的 2.4G、5G 照用，记下停在哪、留原文。
        val bytes = ByteWriter().ea(3).ea(1).ea(4).ea(1).ea(21).bytes(ByteArray(9) { 0x11 }).toByteArray()
        val cap = DataChannelMessages.parseCapability(bytes)
        assertEquals(true, cap.supports2g)
        assertEquals(true, cap.supports5g)
        assertEquals(4, cap.unparsedFrom)
        assertContentEquals(bytes, cap.incompleteRaw)
        assertTrue(cap.canUse(2437))
        assertTrue("第 4 字节起解不了，原文 ${bytes.toHex()}" in cap.toString())
    }

    @Test
    fun negotiationResponseBytes() {
        // 角色是裸字节，频率是 EA：5745 = 71 AC，2437 = 05 93。
        assertEquals("828783818383" + "0171ac", DataChannelMessages.acceptPayload(3, 1, 5745).toHex())
        assertEquals("828783818383" + "010593", DataChannelMessages.acceptPayload(3, 1, 2437).toHex())
        assertEquals("82828382", DataChannelMessages.refusePayload(3, DataChannelRefusal.BUSY).toHex())
        assertEquals("828383" + "7f81", DataChannelMessages.refusePayload(3, DataChannelRefusal.NO_CHANNEL).toHex())
    }

    @Test
    fun negotiationResultIsParsed() {
        val ok = DataChannelMessages.parseResult(hex("838183830271ac"))
        assertEquals(listOf(3, 1, 2, 5745), listOf(ok.dataChannel, ok.result, ok.role, ok.frequencyMhz))
        val refused = DataChannelMessages.parseResult(hex("837f81"))
        assertEquals(255, refused.result)
        assertNull(refused.role)
        assertNull(refused.frequencyMhz)
        assertFailsWith<MalformedMessageException> { DataChannelMessages.parseResult(hex("838183840271ac00")) }
        assertFailsWith<MalformedMessageException> { DataChannelMessages.parseResult(hex("83818381")) }
        assertFailsWith<MalformedMessageException> { DataChannelMessages.parseResult(hex("83")) }
    }

    @Test
    fun peerMacIsNormalized() {
        assertEquals("0a:1b:2c:3d:4e:5f", WifiLinkMessages.parsePeerMac("0A:1b:2C:3d:4E:5f".toByteArray()))
        assertNull(WifiLinkMessages.parsePeerMac("busy".toByteArray()))
        for (bad in listOf("0a:1b:2c:3d:4e", "0a-1b-2c-3d-4e-5f", "0a:1b:2c:3d:4e:5g", "BUSY", "")) {
            assertFailsWith<MalformedMessageException>(bad) { WifiLinkMessages.parsePeerMac(bad.toByteArray()) }
        }
        assertFailsWith<MalformedMessageException> { WifiLinkMessages.parsePeerMac(byteArrayOf(0x0a, 0x00)) }
    }

    @Test
    fun socketResponseCarriesUuidForRfcomm() {
        val uuid = hex("0000fe3600001000800000805f9b34fb")
        val payload = SocketMessages.responsePayload(0, 4, 2, 19, "hicarAuth".toByteArray(), 0, uuid)
        assertEquals("84a0" + "8084829389" + "6869636172417574688090" + "0000fe3600001000800000805f9b34fb", payload.toHex())
        val parsed = SocketMessages.parseResponse(Tlv.parseAll(payload).single().value)
        assertEquals(listOf(0, 4, 2, 19, 0), listOf(parsed.status, parsed.port, parsed.channel, parsed.businessId, parsed.protocol))
        assertContentEquals(uuid, parsed.uuid)
        // 不带 uuid 的应答照旧。
        assertNull(SocketMessages.parseResponse(Tlv.parseAll(hex(Vectors.PAYLOAD_AUTH_RESP)).single().value).uuid)
    }

    @Test
    fun rfcommServiceUuidDefaultsToSubchannelUuid() {
        val none = NearbyEvent.SocketRequested(2, 2, 19, "hicarAuth".toByteArray(), 0, null)
        assertEquals(NearbySession.DEFAULT_SUBCHANNEL_UUID, NearbySession.rfcommServiceUuid(none))
        assertEquals("0000fe36-0000-1000-8000-00805f9b34fb", NearbySession.DEFAULT_SUBCHANNEL_UUID.toString())
        assertEquals("0000fe35-0000-1000-8000-00805f9b34fb", NearbySession.BLUETOOTH_SERVICE_UUID.toString())
        val given = NearbyEvent.SocketRequested(2, 2, 19, "hicarAuth".toByteArray(), 0, incrementing(0x10, 16))
        assertEquals("10111213-1415-1617-1819-1a1b1c1d1e1f", NearbySession.rfcommServiceUuid(given).toString())
        val odd = NearbyEvent.SocketRequested(2, 2, 19, "hicarAuth".toByteArray(), 0, byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> { NearbySession.rfcommServiceUuid(odd) }
        assertTrue(NearbySession.MAX_RFCOMM_CHANNEL == 30)
    }
}
