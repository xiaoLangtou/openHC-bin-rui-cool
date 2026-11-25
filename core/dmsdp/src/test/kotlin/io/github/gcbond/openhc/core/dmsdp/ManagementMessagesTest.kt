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
}
