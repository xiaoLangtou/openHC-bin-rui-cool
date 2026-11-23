package io.github.gcbond.openhc.core.dmsdp.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 分流判定与各路去向（[AudioRouter]）、PCM 能不能出声的判定（[PcmCheck]）、手机编号。 */
class AudioRoutingTest {

    private fun focus(type: Int, text: String) = PhoneAudioFocus.parse(type, text.toByteArray())
    private fun split(focusType: Int, usage: Int) = focus(14, "{\"contentType\":1,\"focusType\":$focusType,\"usage\":$usage}")

    @Test
    fun everythingIsMediaUntilSplittingIsSeen() {
        val router = AudioRouter()
        for (bus in listOf(0, 3, 8, AudioBus.UNKNOWN)) assertEquals(AudioRoute.MEDIA, router.route(bus), "第 $bus 路")
        // 老式焦点消息（不分流的手机播报时也发 type 3）不算分流的证据。
        assertNull(router.onFocus(focus(3, "1")))
        assertNull(router.onFocus(focus(5, "1")))
        assertFalse(router.splitting)
        assertEquals(AudioRoute.MEDIA, router.route(0))
    }

    @Test
    fun splitFocusMessageTurnsBusZeroIntoItsOwnRoute() {
        val router = AudioRouter()
        assertNotNull(router.onFocus(split(3, 12)))
        assertTrue(router.splitting)
        assertEquals(AudioRoute.NAVIGATION, router.route(AudioBus.SPLIT))
        assertEquals(AudioRoute.MEDIA, router.route(AudioBus.MUSIC))
        assertEquals(AudioRoute.MEDIA, router.route(5), "没见过的路号当媒体")
        assertEquals(AudioRoute.MEDIA, router.route(AudioBus.UNKNOWN))

        // 播完（focusType 5）不改去向：播报的尾巴可能更晚到。
        assertNull(router.onFocus(split(5, 12)))
        assertEquals(AudioRoute.NAVIGATION, router.route(0))
        // 语音助手要出声：第 0 路改放到提示与语音。
        assertNotNull(router.onFocus(split(3, 16)))
        assertEquals(AudioRoute.VOICE, router.route(0))
        // 媒体用途的分流消息说的是音乐那一路，不动第 0 路。
        assertNull(router.onFocus(split(1, 1)))
        assertEquals(AudioRoute.VOICE, router.route(0))
        // 用途不认识的也不动。
        assertNull(router.onFocus(split(3, 0)))
        assertEquals(AudioRoute.VOICE, router.route(0))
        assertNotNull(router.onFocus(split(2, 12)))
        assertEquals(AudioRoute.NAVIGATION, router.route(0))
    }

    /** SSRC 带的用途：通话、铃声总是单独放；导航、提示与语音在声明了多总线时单独放；媒体或不认识的按路号规则。 */
    @Test
    fun usageInTheSsrcPicksTheRoute() {
        val router = AudioRouter()
        assertEquals(AudioRoute.CALL, router.route(2, usage = 2))
        assertEquals(AudioRoute.CALL, router.route(AudioBus.UNKNOWN, usage = 3))
        assertEquals(AudioRoute.RINGTONE, router.route(0, usage = 6))
        assertEquals(AudioRoute.NAVIGATION, router.route(5, usage = 12))
        assertEquals(AudioRoute.VOICE, router.route(5, usage = 16))
        assertEquals(AudioRoute.MEDIA, router.route(0, usage = 1), "没看出分流：媒体用途的第 0 路照旧当媒体")
        assertEquals(AudioRoute.MEDIA, router.route(0, usage = 999), "不认识的用途按媒体放")
        router.onFocus(split(3, 12))
        assertEquals(AudioRoute.NAVIGATION, router.route(0, usage = 1), "分流后第 0 路按分流消息走（手机给两路都标媒体用途）")
        assertEquals(AudioRoute.MEDIA, router.route(8, usage = 1))

        val off = AudioRouter(multiBus = false)
        assertEquals(AudioRoute.CALL, off.route(2, usage = 2), "关掉多总线也不把通话混进媒体")
        assertEquals(AudioRoute.RINGTONE, off.route(0, usage = 6))
        assertEquals(AudioRoute.MEDIA, off.route(0, usage = 12), "关掉多总线时导航当媒体")
    }

    @Test
    fun withoutMultiBusEverythingStaysMedia() {
        val router = AudioRouter(multiBus = false)
        assertNull(router.onFocus(split(3, 12)))
        assertNull(router.onBus(AudioBus.MUSIC))
        assertFalse(router.splitting)
        for (bus in listOf(0, 8, AudioBus.UNKNOWN)) assertEquals(AudioRoute.MEDIA, router.route(bus), "第 $bus 路")
    }

    @Test
    fun musicBusOrPolicyAlsoShowSplitting() {
        val byBus = AudioRouter()
        assertNull(byBus.onBus(0))
        assertNull(byBus.onBus(AudioBus.UNKNOWN))
        assertFalse(byBus.splitting)
        assertNotNull(byBus.onBus(AudioBus.MUSIC))
        assertNull(byBus.onBus(AudioBus.MUSIC), "只说一次")
        assertEquals(AudioRoute.NAVIGATION, byBus.route(0), "还没收到分流消息时第 0 路按导航")

        val byPolicy = AudioRouter()
        assertNull(byPolicy.onFocus(focus(15, "{\"multiPolicy\":\"\"}")))
        assertFalse(byPolicy.splitting)
        assertNotNull(byPolicy.onFocus(focus(15, "{\"multiPolicy\":\"8:4096:1\"}")))
        assertTrue(byPolicy.splitting)
    }

    @Test
    fun pcmCheckWorksAfterEnoughPlayablePcm() {
        val check = PcmCheck(declaredPcm = true)
        repeat((PcmCheck.WORKS_PACKETS - 1).toInt()) { assertNull(check.onPacket(AudioCodec.PCM, playable = true)) }
        assertNull(check.onPacket(AudioCodec.PCM, playable = false), "不能放的不算")
        assertNull(check.onPacket(AudioCodec.AAC, playable = true), "手机发的是 AAC 不算")
        assertEquals(PcmVerdict.WORKS, check.onPacket(AudioCodec.PCM, playable = true))
        assertNull(check.onPacket(AudioCodec.PCM, playable = true), "只报一次")
    }

    @Test
    fun pcmCheckNoDataSessionNeedsARequestNoTriggerAndTheWindow() {
        val check = PcmCheck(declaredPcm = true)
        assertNull(check.onClose(100_000), "手机没想出声：不判")
        check.onFocusRequest(1, 1_000)
        check.onFocusRequest(1, 9_000)
        assertNull(check.onClose(1_000 + PcmCheck.WINDOW_MILLIS - 1), "从第一次请求算，不到窗口不判")
        assertEquals(PcmVerdict.NO_DATA_SESSION, check.onClose(1_000 + PcmCheck.WINDOW_MILLIS))

        // 连上时的短暂焦点（提示音）不算想出声；之后的长期焦点从它自己的时间算窗口。
        val prompt = PcmCheck(declaredPcm = true)
        for (focusType in listOf(2, 3, 4, null)) prompt.onFocusRequest(focusType, 1_000)
        assertNull(prompt.onClose(100_000), "只有短暂焦点：不判")
        prompt.onFocusRequest(1, 95_000)
        assertNull(prompt.onClose(95_000 + PcmCheck.WINDOW_MILLIS - 1))
        assertEquals(PcmVerdict.NO_DATA_SESSION, prompt.onClose(95_000 + PcmCheck.WINDOW_MILLIS))

        val triggered = PcmCheck(declaredPcm = true)
        triggered.onFocusRequest(1, 1_000)
        triggered.onTrigger()
        assertNull(triggered.onClose(60_000), "开过数据会话就不算")
    }

    @Test
    fun pcmCheckUnplayableOnceAndNothingWhenAacWasDeclared() {
        val check = PcmCheck(declaredPcm = true)
        assertNull(check.onFraming(AudioCodec.PCM, playable = true))
        assertNull(check.onFraming(AudioCodec.AAC, playable = false))
        assertEquals(PcmVerdict.UNPLAYABLE, check.onFraming(AudioCodec.PCM, playable = false))
        assertNull(check.onFraming(AudioCodec.PCM, playable = false))

        val aac = PcmCheck(declaredPcm = false)
        aac.onFocusRequest(1, 0)
        assertNull(aac.onFraming(AudioCodec.PCM, playable = false))
        repeat(100) { assertNull(aac.onPacket(AudioCodec.PCM, playable = true)) }
        assertNull(aac.onClose(60_000))
    }

    @Test
    fun phoneKeyIsAStableShortHash() {
        // SHA-256("FEDC…3210" 的 UTF-8) 的前 8 字节，用 JDK 另算一遍。
        val id = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210"
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).copyOf(8)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, PhoneRef.keyOf(id))
        assertEquals(PhoneRef.keyOf(id), PhoneRef.keyOf(id))
        val ref = PhoneRef(PhoneRef.keyOf(id), "合成手机")
        assertEquals("手机 编号 ${expected.take(6)}", ref.toString(), "日志里只露前 6 位，不带名字")
    }
}
