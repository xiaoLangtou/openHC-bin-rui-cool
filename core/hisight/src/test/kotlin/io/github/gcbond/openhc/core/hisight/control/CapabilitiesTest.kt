package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.HiSightConfig
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.ascii
import io.github.gcbond.openhc.core.hisight.bool
import io.github.gcbond.openhc.core.hisight.configFrom
import io.github.gcbond.openhc.core.hisight.hex
import io.github.gcbond.openhc.core.hisight.int
import io.github.gcbond.openhc.core.hisight.obj
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 能力协商与 Transport 解析，对照参考实现在同样输入上的结果。 */
class CapabilitiesTest {
    private val section = Vectors.section("capabilities").jsonObject
    private val configs: Map<String, HiSightConfig> =
        section.obj("configs").mapValues { configFrom(it.value.jsonObject) }

    @Test
    fun getParameterResponsesMatchPrototype() {
        for (element in section.arr("get")) {
            val case = element.jsonObject
            val query = case.getValue("query").hex()
            for ((name, expected) in case.obj("results")) {
                val actual = try {
                    Capabilities(configs.getValue(name)).responseBody(query)
                } catch (e: MalformedMessageException) {
                    null
                }
                val want = (expected as? JsonPrimitive)?.takeUnless { expected is JsonNull }?.content
                assertEquals(want, actual, "配置 $name 查询 ${case.str("query")}")
            }
        }
    }

    @Test
    fun setParameterSelectionsMatchPrototype() {
        for (element in section.arr("set")) {
            val case = element.jsonObject
            val body = case.getValue("body").hex()
            for ((name, expected) in case.obj("results")) {
                val label = "配置 $name 正文 ${case.str("body")}"
                val caps = Capabilities(configs.getValue(name))
                val fields = try {
                    ParameterText.parameters(body)
                } catch (e: MalformedMessageException) {
                    assertEquals("parse-error", (expected as? JsonPrimitive)?.content, label)
                    continue
                }
                if (ParameterText.TRIGGER in fields) {
                    assertEquals("trigger", (expected as? JsonPrimitive)?.content, label)
                    continue
                }
                val notes = try {
                    caps.select(fields)
                } catch (e: MalformedMessageException) {
                    assertEquals("rejected", (expected as? JsonPrimitive)?.content, label)
                    continue
                }
                if (expected is JsonPrimitive && expected.content == "rejected") {
                    // 有意的差异：参考实现拒绝手机选的音频、没声明的输入事件和重复项，本实现照常接受并写说明
                    // （鸿蒙 3.0 / 4.x 的手机会这样选）。走到这里说明其他字段都通过了，被拒只因为这几样。
                    assertTrue(notes.any { "没声明" in it || "重复项" in it }, label)
                    continue
                }
                val result = expected as? JsonObject ?: throw AssertionError("$label：参考实现拒绝，本实现却接受了")
                val selected = result["selected"]
                if (selected is JsonObject) {
                    val s = caps.selection!!
                    assertEquals(selected.int("width"), s.width, label)
                    assertEquals(selected.int("height"), s.height, label)
                    assertEquals(selected.int("fps"), s.fps, label)
                } else {
                    assertNull(caps.selection, label)
                }
                val setup = try {
                    caps.mediaSetup(12345, 12346)
                } catch (e: MalformedMessageException) {
                    null
                }
                val expectedSetup = result["setup"]
                if (expectedSetup is JsonObject) {
                    requireNotNull(setup) { "$label：参考实现能完成 SETUP，本实现不能" }
                    assertEquals(expectedSetup.arr("events").map { it.jsonPrimitive.content }, setup.inputEvents, label)
                    assertEquals(expectedSetup.arr("vendors").map { it.jsonPrimitive.content }, setup.inputVendors, label)
                    assertEquals(expectedSetup["source"]?.jsonPrimitive?.intOrNull, setup.sourceDeviceType, label)
                } else {
                    assertNull(setup, label)
                }
                val noRemote = try {
                    caps.mediaSetup(12345, -1)
                    true
                } catch (e: MalformedMessageException) {
                    false
                }
                assertEquals(result.bool("setupNoRemote"), noRemote, label)
            }
        }
    }

    @Test
    fun transportMatchesPrototype() {
        for (element in section.arr("transport")) {
            val case = element.jsonObject
            val value = case.str("value")
            val actual = try {
                parseTransport(value)
            } catch (e: MalformedMessageException) {
                null
            }
            if (case.bool("ok")) {
                requireNotNull(actual) { "Transport 应当接受：$value" }
                assertEquals(case.int("server"), actual.serverPort, value)
                assertEquals(case.int("remote"), actual.remoteControlPort, value)
            } else {
                assertNull(actual, "Transport 应当拒绝：$value")
            }
        }
    }

    @Test
    fun failedSelectionKeepsPreviousResult() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, touchInput = true)
        val caps = Capabilities(config)
        val good = "input_category_list=GENERIC;generic_cap_list=left_move, left_point_up, left_point_down;"
        caps.select(
            ParameterText.parameters(
                ascii("his_video_formats: codecs 1;width 320;height 240;fps 25\r\nhis_uibc_capability: $good\r\n"),
            ),
        )
        assertFailsWith<MalformedMessageException> {
            caps.select(
                ParameterText.parameters(
                    ascii(
                        "his_video_formats: codecs 1;width 640;height 480;fps 30\r\n" +
                            "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=left_move;\r\n",
                    ),
                ),
            )
        }
        val setup = caps.mediaSetup(1000, 1001)
        assertEquals(320, setup.selection.width)
        assertEquals(listOf("left_move", "left_point_up", "left_point_down"), setup.inputEvents)
    }

    @Test
    fun malformedEchoedGopOrBitrateIsIgnored() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160, gop = 60)
        for (echo in listOf("gop -1", "gop abc", "gop", "gop 1 2", "bitrate -5", "gop 60;gop 61")) {
            val caps = Capabilities(config)
            caps.select(ParameterText.parameters(ascii("his_video_formats: codecs 1;$echo;width 320;height 240;fps 25\r\n")))
            val setup = caps.mediaSetup(1000, -1)
            assertEquals(320, setup.selection.width, echo)
            assertEquals(25, setup.selection.fps, echo)
        }
        // 本端要用的字段照旧严格：帧率不合规仍然拒绝。
        assertFailsWith<MalformedMessageException> {
            Capabilities(config).select(
                ParameterText.parameters(ascii("his_video_formats: codecs 1;gop 60;width 320;height 240;fps -1\r\n")),
            )
        }
    }

    /** 车机没声明音频，鸿蒙 4.x 的手机仍会选一种：照常继续，选的值写进说明；选 none 或不选没有说明。 */
    @Test
    fun undeclaredAudioSelectionIsAcceptedWithANote() {
        val config = HiSightConfig(640, 480, 640, 480, 30, 160)
        val caps = Capabilities(config)
        val notes = caps.select(
            ParameterText.parameters(ascii("his_audio_codecs: LPCM 00000002 00\r\nhis_video_formats: codecs 1;width 320;height 240;fps 25\r\n")),
        )
        assertEquals(320, caps.mediaSetup(1000, -1).selection.width)
        val note = notes.single()
        assertTrue("LPCM 00000002 00" in note && "没声明" in note, note)
        for (body in listOf("his_audio_codecs: none\r\n", "")) {
            val quiet = Capabilities(config).select(ParameterText.parameters(ascii(body + "his_video_formats: codecs 1;width 320;height 240;fps 25\r\n")))
            assertEquals(emptyList(), quiet, body)
        }
    }

    @Test
    fun undeclaredMediaCapabilityIsAcceptedWithANote() {
        val caps = Capabilities(HiSightConfig(640, 480, 640, 480, 30, 160))
        val notes = caps.select(
            ParameterText.parameters(ascii("his_media_capability: hdr 1;\r\nhis_video_formats: codecs 1;width 320;height 240;fps 25\r\n")),
        )
        assertEquals(320, caps.mediaSetup(1000, -1).selection.width)
        assertTrue(notes.single().let { "扩展媒体能力" in it && "hdr 1;" in it }, notes.toString())
    }

    @Test
    fun undeclaredInputFeaturesAreAcceptedWithANote() {
        val caps = Capabilities(HiSightConfig(640, 480, 640, 480, 30, 160))
        val notes = caps.select(
            ParameterText.parameters(ascii("his_feature: input_feature_set=1,101;\r\nhis_video_formats: codecs 1;width 320;height 240;fps 25\r\n")),
        )
        assertEquals(320, caps.mediaSetup(1000, -1).selection.width)
        assertTrue(notes.single().let { "输入功能" in it && "input_feature_set=1,101;" in it }, notes.toString())
    }

    @Test
    fun configRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { HiSightConfig(0, 480, 640, 480, 30, 160) }
        assertFailsWith<IllegalArgumentException> { HiSightConfig(640, 480, 640, 480, 30, 160, gop = 0) }
        assertFailsWith<IllegalArgumentException> { HiSightConfig(640, 480, 640, 480, 30, 160, textInput = true) }
        assertFailsWith<IllegalArgumentException> { HiSightConfig(640, 480, 640, 480, 30, 160, deviceType = 6) }
    }
}
