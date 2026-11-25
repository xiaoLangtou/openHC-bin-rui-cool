package io.github.gcbond.openhc.core.dmsdp.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手机音频焦点消息（module 4）的解析、用途到出声通道的归类、路号。数据全部是合成的。 */
class AudioFocusMessagesTest {

    private fun parse(type: Int, text: String) = PhoneAudioFocus.parse(type, text.toByteArray(Charsets.UTF_8))

    @Test
    fun legacyRequestsCarryTheFocusTypeAndUsage() {
        for (type in 1..4) {
            val message = parse(type, "1")
            assertEquals(PhoneAudioFocus.Action.REQUEST, message.action)
            assertEquals(type, message.focusType)
            assertEquals(1, message.usage)
            assertEquals(AudioRoute.MEDIA, message.route)
            assertFalse(message.split)
            assertNull(message.problem)
        }
        val abandon = parse(5, "12")
        assertEquals(PhoneAudioFocus.Action.ABANDON, abandon.action)
        assertNull(abandon.focusType)
        assertEquals(AudioRoute.NAVIGATION, abandon.route)
    }

    @Test
    fun legacyDataThatIsNotADecimalUsageIsKeptAsUnknown() {
        for (bad in listOf("", "x", "-1", "12345", "1 ")) {
            val message = parse(1, bad)
            assertEquals(PhoneAudioFocus.Action.REQUEST, message.action, "「$bad」")
            assertNull(message.usage, "「$bad」")
            assertNotNull(message.problem, "「$bad」")
        }
        // 原始字节进日志只露前 16 个。
        val long = PhoneAudioFocus.parse(1, ByteArray(40) { 0x41 })
        assertTrue(long.toString().contains("40 字节：" + "41".repeat(16) + "…"), long.toString())
    }

    @Test
    fun splitFocusMessagesAreJson() {
        val start = parse(14, "{\"contentType\":1,\"focusType\":3,\"usage\":12}")
        assertEquals(PhoneAudioFocus.Action.REQUEST, start.action)
        assertTrue(start.split)
        assertEquals(3, start.focusType)
        assertEquals(12, start.usage)
        assertEquals(1, start.contentType)
        assertEquals(AudioRoute.NAVIGATION, start.route)
        assertTrue("压低别人" in start.toString() && "导航" in start.toString(), start.toString())

        val end = parse(16, "{\"focusType\":\"5\",\"usage\":\"12\"}")
        assertEquals(PhoneAudioFocus.Action.ABANDON, end.action, "数字写成字符串也认")
        assertEquals(12, end.usage)
        assertNull(end.contentType)

        val odd = parse(14, "{\"focusType\":9,\"usage\":12}")
        assertEquals(PhoneAudioFocus.Action.OTHER, odd.action)
        assertEquals(12, odd.usage)
        assertNotNull(odd.problem)

        for (bad in listOf("not json", "[1,2]", "{\"focusType\":3}", "{\"focusType\":3,\"usage\":true}")) {
            val message = parse(14, bad)
            assertEquals(PhoneAudioFocus.Action.OTHER, message.action, bad)
            assertTrue(message.split, bad)
            assertNotNull(message.problem, bad)
        }
    }

    @Test
    fun policyAbandonAllAndOthers() {
        assertTrue(parse(15, "{\"multiPolicy\":\"8:1\"}").multiPolicy)
        assertTrue(parse(15, "{\"multiPolicy\":{\"a\":1}}").multiPolicy)
        assertFalse(parse(15, "{\"multiPolicy\":\"\"}").multiPolicy)
        assertFalse(parse(15, "{\"multiPolicy\":null}").multiPolicy)
        assertFalse(parse(15, "{}").multiPolicy)
        assertEquals(PhoneAudioFocus.Action.POLICY, parse(15, "{}").action)
        assertEquals(PhoneAudioFocus.Action.OTHER, parse(15, "oops").action)
        assertEquals(PhoneAudioFocus.Action.ABANDON_ALL, PhoneAudioFocus.parse(57, ByteArray(0)).action)
        for (type in listOf(0, 6, 11, 12, 13, -1, 1000)) {
            assertEquals(PhoneAudioFocus.Action.OTHER, PhoneAudioFocus.parse(type, byteArrayOf(1)).action, "type $type")
        }
    }

    @Test
    fun usagesMapToRoutes() {
        assertEquals(AudioRoute.MEDIA, AudioRoute.ofUsage(1))
        assertEquals(AudioRoute.MEDIA, AudioRoute.ofUsage(14))
        assertEquals(AudioRoute.NAVIGATION, AudioRoute.ofUsage(12))
        assertEquals(AudioRoute.CALL, AudioRoute.ofUsage(2))
        assertEquals(AudioRoute.CALL, AudioRoute.ofUsage(3))
        assertEquals(AudioRoute.RINGTONE, AudioRoute.ofUsage(6))
        for (usage in listOf(4, 5, 7, 10, 11, 13, 16, 17, 18)) assertEquals(AudioRoute.VOICE, AudioRoute.ofUsage(usage), "用途 $usage")
        for (usage in listOf(0, 15, 19, -1, 1000)) assertNull(AudioRoute.ofUsage(usage), "用途 $usage")
    }

    @Test
    fun ssrcCarriesUsageHighAndBusLow() {
        assertEquals(0, AudioBus.of(1000))
        assertEquals(8, AudioBus.of(1008))
        assertEquals(63, AudioBus.of(1063))
        assertEquals(0, AudioBus.usageOf(1008), "只有路号、没带用途")
        // 高 16 位是用途：通话 2、铃声 6、媒体 1。
        assertEquals(2, AudioBus.usageOf(0x0002_03EAL))
        assertEquals(2, AudioBus.of(0x0002_03EAL))
        assertEquals(6, AudioBus.usageOf(0x0006_03E8L))
        assertEquals(0, AudioBus.of(0x0006_03E8L))
        assertEquals(8, AudioBus.of(0x0001_03F0L))
        assertEquals(0xFFFF, AudioBus.usageOf(0xFFFF_FFFFL))
        for (ssrc in listOf(999L, 1064L, 0x11223344L, 0L, 0xFFFFFFFFL)) assertEquals(AudioBus.UNKNOWN, AudioBus.of(ssrc), "SSRC $ssrc")
        // 第 12～15 位是附加标志，不属于路号：1003 和 0x43EB（17387）是同一路。
        assertEquals(3, AudioBus.of(0x0000_43EBL))
        assertEquals(3, AudioBus.of(0x0001_43EBL))
        assertEquals(0, AudioBus.usageOf(0x0000_43EBL), "标志位不算用途")
    }

    @Test
    fun laneIsTheSourceKeyOrTheTrailerBus() {
        assertEquals(1003L, AudioBus.lane(0x0000_03EBL, -1))
        assertEquals(1003L, AudioBus.lane(0x0001_43EBL, -1), "用途、标志位变了还是同一路")
        assertEquals(1008L, AudioBus.lane(0x0001_43EBL, 8), "带包尾路号时按包尾，SSRC 不参与")
        assertEquals(AudioBus.lane(1003, 8), AudioBus.lane(1032, 8), "包尾路号相同就是同一路")
        assertTrue(AudioBus.lane(1003, 8) != AudioBus.lane(1003, 0), "同一个 SSRC、包尾路号不同是两路")
        assertEquals(AudioBus.lane(1008, -1), AudioBus.lane(1003, 8), "编号是同一套：包尾路号 8 和 SSRC 1008 都是第 8 路")
        assertEquals(999L, AudioBus.lane(1003, 0xFFFF_FFFFL), "和 SSRC 一样只留低 12 位")
        assertEquals(AudioBus.lane(0x0001_03E8L, -1), AudioBus.lane(0x0001_03E8L, 4096), "包尾的分流路 4096 和 SSRC 里的第 0 路是同一路")
        assertEquals(AudioBus.SPLIT, AudioBus.ofLane(AudioBus.lane(0x0001_03E8L, 4096)))
        assertEquals(8, AudioBus.ofLane(1008))
        assertEquals(0, AudioBus.ofLane(1000))
        for (lane in listOf(999L, 1064L, 0L, 0xFFFF_FFFFL)) assertEquals(AudioBus.UNKNOWN, AudioBus.ofLane(lane), "编号 $lane")
        assertEquals("第 3 路", AudioBus.laneLabel(AudioBus.lane(0x0001_43EBL, -1)))
        assertEquals("第 8 路", AudioBus.laneLabel(AudioBus.lane(1003, 8)))
        assertEquals("源 999", AudioBus.laneLabel(999))
        assertEquals("源 836", AudioBus.laneLabel(AudioBus.lane(0x11223344L, -1)))
    }

    @Test
    fun callAndVoiceStatesAreParsed() {
        fun parse(type: Int, text: String) = PhoneStateMessages.parse(type, text.toByteArray())
        assertEquals(1, parse(504, "{\"callState\":1}"))
        assertEquals(2, parse(504, "{\"callState\":\"2\"}"), "数字写成字符串也认")
        assertEquals(0, parse(504, "{\"callState\":0,\"other\":true}"))
        assertEquals(1, parse(505, "{\"voiceState\":1}"))
        for (bad in listOf("", "x", "[]", "{}", "{\"voiceState\":1}", "{\"callState\":true}", "{\"callState\":\"a\"}")) {
            assertNull(parse(504, bad), "「$bad」")
        }
        assertEquals(-1, parse(504, "{\"callState\":-1}"), "整数照样交出，取值认不认由上层判断")
        assertNull(PhoneCallPhase.of(-1))
        assertNull(parse(505, "{\"callState\":1}"), "505 只看 voiceState")
        assertEquals(PhoneCallPhase.RINGING, PhoneCallPhase.of(1))
        assertEquals(PhoneCallPhase.ACTIVE, PhoneCallPhase.of(2))
        assertEquals(PhoneCallPhase.IDLE, PhoneCallPhase.of(0))
        assertNull(PhoneCallPhase.of(7))
        assertTrue(PhoneStateMessages.accepts(1, 504) && PhoneStateMessages.accepts(4, 505), "module 1 和 4 都认")
        assertFalse(PhoneStateMessages.accepts(3, 504) || PhoneStateMessages.accepts(1, 17) || PhoneStateMessages.accepts(4, 1))
    }
}
