package io.github.gcbond.openhc.core.dmsdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 管理通道（module 1）JSON 消息的解析：线上样本 + 车辆磁贴触发识别。 */
class ManagementMessagesTest {

    @Test
    fun knownTypesGetStructuredSummaries() {
        val appStatus = ManagementMessages.parse(533, """{"status":1,"appType":"video","appName":"com.huawei.hmos.photos"}""".toByteArray())
        assertTrue(appStatus != null && !appStatus.carDesktop)
        assertTrue(appStatus != null && "com.huawei.hmos.photos" in appStatus.summary, appStatus?.summary)

        val capability = ManagementMessages.parse(500, """{"VehicleControlCapability":{}}""".toByteArray())
        assertTrue(capability != null && !capability.carDesktop)
        capability?.let { assertTrue("VehicleControlCapability" in it.summary, it.summary) }

        val navFocus = ManagementMessages.parse(503, """{"navFocus":"HICAR_STOP","navPackageName":"com.huawei.hmos.maps.app"}""".toByteArray())
        assertTrue(navFocus != null && "HICAR_STOP" in navFocus.summary)
        navFocus?.let { assertTrue("com.huawei.hmos.maps.app" in it.summary, it.summary) }
    }

    @Test
    fun appOperation529CarTileTriggersThePipeline() {
        // 车辆磁贴点击：start + HiCar 车机包名（逆向 ec/d CarAppStartMgr 确认的协议）。
        val start = ManagementMessages.parse(
            529,
            """{"RequestApp":{"Action":"start","AppPackage":"com.huawei.hicar"}}""".toByteArray(),
        )
        assertTrue(start != null && start.carDesktop, start?.summary ?: "null")
        assertTrue("start" in start!!.summary, start.summary)

        // pause 只重置 dock，不是退出投屏。
        val pause = ManagementMessages.parse(
            529,
            """{"RequestApp":{"Action":"pause","AppPackage":"com.huawei.hicar"}}""".toByteArray(),
        )
        assertTrue(pause != null && !pause.carDesktop, pause?.summary ?: "null")

        // start 其他应用（手机要求车机开某个应用）不是退出投屏。
        val otherApp = ManagementMessages.parse(
            529,
            """{"RequestApp":{"Action":"start","AppPackage":"com.example.music"}}""".toByteArray(),
        )
        assertTrue(otherApp != null && !otherApp.carDesktop)

        // 缺 RequestApp 键：官方报 "invalid data struct"，这里不认成磁贴但也不崩。
        val malformed = ManagementMessages.parse(529, """{"foo":1}""".toByteArray())
        assertTrue(malformed != null && !malformed.carDesktop)
        assertTrue("RequestApp" in malformed.summary, malformed.summary)
    }

    @Test
    fun carDesktopKeysTriggerThePipeline() {
        val parsed = ManagementMessages.parse(600, """{"exitProjection":1}""".toByteArray())
        assertTrue(parsed != null && parsed.carDesktop, parsed?.summary ?: "null")
    }

    @Test
    fun unknownJsonAndBinaryFallBackCleanly() {
        // JSON 对象但类型未知：给字段摘要，不认成车辆磁贴。
        val unknown = ManagementMessages.parse(600, """{"foo":1}""".toByteArray())
        assertTrue(unknown != null && !unknown.carDesktop)
        assertTrue("foo" in unknown.summary, unknown.summary)
        // 不是 JSON 对象（数组、二进制）：返回 null，调用方照旧走 UnhandledDeviceData。
        assertNull(ManagementMessages.parse(600, "[1]".toByteArray()))
        assertNull(ManagementMessages.parse(600, byteArrayOf(0, 1, 2)))
    }

    @Test
    fun fileShareManagementTypeIsNotAccepted() {
        assertFalse(ManagementMessages.accepts(17))
        assertTrue(ManagementMessages.accepts(500))
    }

    @Test
    fun mediaData515FullAndElapsedTimeTickAreExtracted() {
        val full = ManagementMessages.parse(
            515,
            """{"MediaData":{"Name":"晴天","Artist":"周杰伦","AlbumName":"叶惠美","TotalTime":269,"ElapsedTime":15,"Status":3,"AlbumArtIcon":[1,2,-1]}}""".toByteArray(),
        )
        assertTrue(full != null && full.mediaData != null && full.reply != null)
        val info = ManagementMessages.extractMediaData(full!!.mediaData!!)
        assertEquals("晴天", info.title)
        assertEquals("周杰伦", info.artist)
        assertEquals("叶惠美", info.album)
        assertEquals(269, info.totalTimeSec)
        assertEquals(15, info.elapsedTimeSec)
        assertEquals(3, info.status)
        assertTrue(info.albumArtBytes.contentEquals(byteArrayOf(1, 2, -1)))

        val tick = ManagementMessages.parse(515, """{"ElapsedTime":42}""".toByteArray())
        assertTrue(tick != null && tick.mediaData != null && tick.reply != null)
        val tickInfo = ManagementMessages.extractMediaData(tick!!.mediaData!!)
        assertNull(tickInfo.title)
        assertEquals(42, tickInfo.elapsedTimeSec)
    }

    // ---- 原厂吉利 HiCar 对齐（需求文档模块 A/B）----

    @Test
    fun brandIcon502RequestIsFlaggedForRawByteReply() {
        // 手机发 {"brandIconData":"PNG"} 请求品牌图标；应答是原始 PNG 字节（由 DmsdpSession 发，不是 JSON）。
        val parsed = ManagementMessages.parse(502, """{"brandIconData":"PNG"}""".toByteArray())
        assertTrue(parsed != null && parsed.brandIconRequested, parsed?.summary ?: "null")
        assertTrue("brandIconData" in parsed!!.summary, parsed.summary)
        // 其他类型带同样的键不认成 502 请求（type 限定）。
        val otherType = ManagementMessages.parse(600, """{"brandIconData":"PNG"}""".toByteArray())
        assertTrue(otherType != null && !otherType.brandIconRequested)
    }

    @Test
    fun navFocus503HiCarIsFlagged() {
        val hiCar = ManagementMessages.parse(503, """{"navFocus":"HICAR","navPackageName":"com.autonavi.minimap"}""".toByteArray())
        assertTrue(hiCar != null && hiCar.navFocusHiCar, hiCar?.summary ?: "null")

        // HICAR_STOP（手机导航结束）不是请求焦点，不触发让出原厂导航。
        val stop = ManagementMessages.parse(503, """{"navFocus":"HICAR_STOP"}""".toByteArray())
        assertTrue(stop != null && !stop.navFocusHiCar)

        // NATIVE（原厂车机让出焦点）也不触发。
        val native = ManagementMessages.parse(503, """{"navFocus":"NATIVE"}""".toByteArray())
        assertTrue(native != null && !native.navFocusHiCar)
    }

    @Test
    fun callState504AndVoiceState505AreExtracted() {
        val ringing = ManagementMessages.parse(504, """{"callState":1}""".toByteArray())
        assertTrue(ringing != null && ringing.callState == 1, ringing?.summary ?: "null")
        val idle = ManagementMessages.parse(504, """{"callState":0}""".toByteArray())
        assertTrue(idle != null && idle.callState == 0)
        // 缺键时不给状态（app 层不动作）。
        val missing = ManagementMessages.parse(504, """{"foo":1}""".toByteArray())
        assertTrue(missing != null && missing.callState == null)

        val picking = ManagementMessages.parse(505, """{"voiceState":1}""".toByteArray())
        assertTrue(picking != null && picking.voiceState == 1, picking?.summary ?: "null")
        val done = ManagementMessages.parse(505, """{"voiceState":0}""".toByteArray())
        assertTrue(done != null && done.voiceState == 0)
    }

    @Test
    fun voicePrompt519RequestIsFlaggedAndReplyIsConfigurable() {
        val parsed = ManagementMessages.parse(519, """{"isVoicePromptSupport":1}""".toByteArray())
        assertTrue(parsed != null && parsed.voicePromptRequested, parsed?.summary ?: "null")
        assertTrue("isVoicePromptSupport" in parsed!!.summary, parsed.summary)

        // 应答 JSON 按文档约定 isVoicePromptSupport=0（手机侧播提示音；原厂实测回 2，可配）。
        val reply = ManagementMessages.voicePromptReply(0)
        assertEquals("""{"isVoicePromptSupport":0}""", reply.toString())

        // 519 不带 isVoicePromptSupport 键时不应答。
        val other = ManagementMessages.parse(519, """{"voicePrompt":1}""".toByteArray())
        assertTrue(other != null && !other.voicePromptRequested)
    }

    @Test
    fun dayNightMode501AndDrivingMode506Builders() {
        assertEquals("""{"dayNightMode":"night"}""", ManagementMessages.dayNightMode(true).toString())
        assertEquals("""{"dayNightMode":"day"}""", ManagementMessages.dayNightMode(false).toString())
        // 506：0 = 驻车（允许操作/看视频），1 = 行车限制态。
        assertEquals("""{"drivingMode":"0"}""", ManagementMessages.drivingMode(false).toString())
        assertEquals("""{"drivingMode":"1"}""", ManagementMessages.drivingMode(true).toString())
    }
}
