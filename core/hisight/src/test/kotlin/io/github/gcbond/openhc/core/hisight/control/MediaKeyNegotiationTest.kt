package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.HiSightConfig
import io.github.gcbond.openhc.core.hisight.ascii
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 只为媒体键声明的按键事件：声明与 keyInput 相同，但手机不选回、没选源设备类型 7 都不让协商失败。 */
class MediaKeyNegotiationTest {
    private val touchAndMedia = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, mediaKeyInput = true)
    private val mediaOnly = HiSightConfig(640, 480, 640, 480, 30, 160, mediaKeyInput = true)
    private val touch = listOf("left_point_down", "left_point_up", "left_move")
    private val keys = listOf("left_key_down", "left_key_up")

    private fun select(caps: Capabilities, uibc: String?, device: String? = null): List<String> {
        var body = "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n"
        if (uibc != null) body += "his_uibc_capability: $uibc\r\n"
        if (device != null) body += "his_device_type: $device\r\n"
        return caps.select(ParameterText.parameters(ascii(body)))
    }

    /** 输入能力用不了：选择照常通过，写了「不能触控」的说明，SETUP 后没有输入。 */
    private fun assertInputOff(caps: Capabilities, notes: List<String>) {
        assertTrue(notes.any { "不能触控" in it }, notes.toString())
        val setup = caps.mediaSetup(5000, 5001, ArrayList())
        assertTrue(setup.inputEvents.isEmpty() && setup.inputVendors.isEmpty())
    }

    private fun generic(events: List<String>) = "input_category_list=GENERIC;generic_cap_list=${events.joinToString(", ")};"

    @Test
    fun advertisedLikeKeyInput() {
        val query = ascii("his_version\r\nhis_video_formats\r\nhis_uibc_capability\r\nhis_device_type\r\n")
        val strict = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, keyInput = true)
        val body = Capabilities(touchAndMedia).responseBody(query)
        assertEquals(Capabilities(strict).responseBody(query), body)
        assertTrue(body.contains("his_uibc_capability: ${generic(touch + keys)}\r\n"))
    }

    @Test
    fun acceptedWithOrWithoutSource7() {
        for (device in listOf("device_type 4;subtype 0;", "source_device_type 7;source_subtype 0;device_type 4;subtype 0;", null)) {
            val caps = Capabilities(touchAndMedia)
            select(caps, generic(keys + touch), device)
            val setup = caps.mediaSetup(5000, 5001, ArrayList())
            assertEquals((touch + keys).toSet(), setup.inputEvents.toSet(), "his_device_type: $device")
            assertEquals(if (device?.startsWith("source") == true) 7 else null, setup.sourceDeviceType)
        }
    }

    @Test
    fun keysNotChosenOrHalfChosenLeaveTouchOnly() {
        for (chosen in listOf(touch, touch + "left_key_down", touch + "left_key_up")) {
            val caps = Capabilities(touchAndMedia)
            select(caps, generic(chosen), "device_type 4;subtype 0;")
            assertEquals(touch.toSet(), caps.mediaSetup(5000, 5001, ArrayList()).inputEvents.toSet(), "手机选的是 $chosen")
        }
    }

    @Test
    fun touchIsStillMandatory() {
        val caps = Capabilities(touchAndMedia)
        // 触摸没选全、没选输入能力：不断开，这次不能触控。
        assertInputOff(caps, select(caps, generic(keys)))
        val fresh = Capabilities(touchAndMedia)
        assertInputOff(fresh, select(fresh, null))
        // 声明了触摸就必须有回控端口。
        select(caps, generic(touch + keys))
        ArrayList<String>().let { notes ->
            assertTrue(caps.mediaSetup(5000, -1, notes).inputEvents.isEmpty())
            assertTrue(notes.isNotEmpty(), "没给回控端口要降级并写说明")
        }
    }

    @Test
    fun strictKeyInputStillRequiresSource7() {
        val both = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, keyInput = true, mediaKeyInput = true)
        // 没选源设备类型 7：照常继续，按键只发基础键。
        val noSource = Capabilities(both)
        assertTrue(select(noSource, generic(touch + keys), "device_type 4;subtype 0;").any { "源设备类型 7" in it })
        assertEquals((touch + keys).toSet(), noSource.mediaSetup(5000, 5001, ArrayList()).inputEvents.toSet())
        // 必须的按键没选：这次不能触控。
        val noKeys = Capabilities(both)
        assertInputOff(noKeys, select(noKeys, generic(touch), "source_device_type 7;device_type 4;subtype 0;"))
    }

    @Test
    fun mediaOnlyNeverFailsNegotiation() {
        for (uibc in listOf(null, "none", generic(emptyList()), generic(keys))) {
            val caps = Capabilities(mediaOnly)
            select(caps, uibc)
            val expected = if (uibc == generic(keys)) keys.toSet() else emptySet()
            assertEquals(expected, caps.mediaSetup(5000, 5001, ArrayList()).inputEvents.toSet(), "手机选的是 $uibc")
            // 没有回控端口：这次没有按键，不算失败。
            assertTrue(caps.mediaSetup(5000, -1, ArrayList()).inputEvents.isEmpty())
        }
        // 选了没声明的事件（鸿蒙 3.0 会把自己支持的全列上、还重复）：丢掉没声明的和重复的，只用声明过的。
        val extra = Capabilities(mediaOnly)
        select(extra, generic(touch + keys + listOf("mouse_down", "mouse_up", "mouse_move") + touch + keys))
        assertEquals(keys.toSet(), extra.mediaSetup(5000, 5001, ArrayList()).inputEvents.toSet())
        val onlyTouch = Capabilities(mediaOnly)
        select(onlyTouch, generic(touch))
        assertTrue(onlyTouch.mediaSetup(5000, 5001, ArrayList()).inputEvents.isEmpty())
    }

    /** 没声明的事件和重复项写进说明；必须的触摸事件没选上仍然拒绝。 */
    @Test
    fun extraEventsAreDroppedWithANoteButRequiredOnesStillCount() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true, mediaKeyInput = true)
        val caps = Capabilities(config)
        val notes = caps.select(
            ParameterText.parameters(
                ("his_video_formats: codecs 1;width 640;height 480;fps 30\r\nhis_uibc_capability: " +
                    generic(touch + keys + listOf("mouse_down") + touch) + "\r\n").toByteArray(Charsets.US_ASCII),
            ),
        )
        assertEquals((touch + keys).toSet(), caps.mediaSetup(5000, 5001, ArrayList()).inputEvents.toSet())
        val note = notes.single()
        assertTrue("mouse_down" in note && "重复项" in note, note)
        val missing = Capabilities(config)
        assertInputOff(
            missing,
            missing.select(
                ParameterText.parameters(
                    ("his_video_formats: codecs 1;width 640;height 480;fps 30\r\nhis_uibc_capability: " +
                        generic(keys + listOf("mouse_down")) + "\r\n").toByteArray(Charsets.US_ASCII),
                ),
            ),
        )
    }
}
