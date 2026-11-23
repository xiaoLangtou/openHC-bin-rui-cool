package io.github.gcbond.openhc.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {

    @Test
    fun fileNameCarriesTheTime() {
        assertEquals("openhc-diagnostics-20261122-201530.txt", DiagnosticsReport.fileName("20261122-201530"))
    }

    @Test
    fun headerListsEveryFactAndSaysWhatIsLeftOut() {
        val header = DiagnosticsReport.header(listOf("OpenHC" to "0.2.0（200，release）", "固件" to ""))
        assertTrue(header.startsWith("OpenHC 诊断日志\n"))
        assertTrue("不含连接密钥和 WiFi 密码" in header)
        assertTrue("OpenHC：0.2.0（200，release）\n" in header)
        assertTrue("固件：（空）\n" in header, "空值写明是空的，免得以为漏了")
    }

    @Test
    fun fullAddressesKeepOnlyTheEnds() {
        assertEquals(
            "BluetoothSocket: connect to 3C:**:**:**:9A:0B, ok",
            DiagnosticsReport.redact("BluetoothSocket: connect to 3C:4D:5E:6F:9A:0B, ok"),
        )
        assertEquals("device 3c:**:**:**:9a:0b: bonded", DiagnosticsReport.redact("device 3c:4d:5e:6f:9a:0b: bonded"))
        assertEquals("a=10:**:**:**:50:60 b=aa:**:**:**:ee:ff", DiagnosticsReport.redact("a=10:20:30:40:50:60 b=aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun placeholdersMaskedAddressesAndOtherNumbersStay() {
        val untouched = listOf(
            "BSSID 02:00:00:00:00:00（占位）",
            "BSSID 0a:**:**:**:4e:5f",
            "01-02 03:04:05.678  4321  4400 I OpenHC-Wireless: [1] 192.168.0.1，2437 MHz",
            "fe80::1:2:3:4:5:6",
            "1a:2b:3c:4d:5e:6f:70",
        )
        for (line in untouched) assertEquals(line, DiagnosticsReport.redact(line))
    }

    @Test
    fun onlyLinesOfOurOwnProcessAreKept() {
        assertTrue(DiagnosticsReport.fromProcess("01-02 03:04:05.678  4321  4400 I OpenHC-Wireless: [1] 开始", "4321"))
        assertFalse(DiagnosticsReport.fromProcess("01-02 03:04:05.678  1234  1250 D SomeOtherApp: 别人的日志", "4321"))
        assertFalse(DiagnosticsReport.fromProcess("01-02 03:04:05.678 43210  4400 I X: 进程号只是开头相同", "4321"))
        assertTrue(DiagnosticsReport.fromProcess("--------- beginning of main", "4321"))
    }

    @Test
    fun ownLogLinesLookLikeLogcatThreadtime() {
        assertEquals(
            listOf("01-02 03:04:05.678  4321  4400 I OpenHC-Wireless: 状态 IDLE/null → PREPARING/null"),
            DiagnosticsReport.ownLines("01-02 03:04:05.678", 4321, 4400, 4, "OpenHC-Wireless", "状态 IDLE/null → PREPARING/null", null),
        )
        // 自己写的行和读 logcat 记下的行用同一套规则认进程。
        val line = DiagnosticsReport.ownLines("01-02 03:04:05.678", 4321, 4400, 5, "OpenHC-App", "x", null).single()
        assertTrue(DiagnosticsReport.fromProcess(line, "4321"))
        assertTrue(DiagnosticsReport.writtenDirectly(line))
    }

    @Test
    fun multiLineMessagesAndStacksRepeatThePrefix() {
        val lines = DiagnosticsReport.ownLines(
            "01-02 03:04:05.678", 12, 34567, 6, "OpenHC-Audio", "第一行\r\n第二行", "java.io.IOException: 断了\n\tat a.b(c.kt:1)\n",
        )
        assertEquals(
            listOf(
                "01-02 03:04:05.678    12 34567 E OpenHC-Audio: 第一行",
                "01-02 03:04:05.678    12 34567 E OpenHC-Audio: 第二行",
                "01-02 03:04:05.678    12 34567 E OpenHC-Audio: java.io.IOException: 断了",
                "01-02 03:04:05.678    12 34567 E OpenHC-Audio: \tat a.b(c.kt:1)",
            ),
            lines,
        )
    }

    @Test
    fun logcatSkipsOnlyWhatWasWrittenDirectly() {
        assertTrue(DiagnosticsReport.writtenDirectly("01-02 03:04:05.678  4321  4400 I OpenHC-Wireless: 蓝牙连上：启用"))
        assertTrue(DiagnosticsReport.writtenDirectly("01-02 03:04:05.678  4321  4400 W OpenHC  : 短标签补了空格"))
        assertTrue(DiagnosticsReport.writtenDirectly("01-02 03:04:05.678  4321  4400 E OpenHC-App:"))
        // 系统在本进程里打的、标签只是开头像的、内核拿线程名当标签的权限拒绝记录：照记。
        val kept = listOf(
            "01-02 03:04:05.678  4321  4410 D BluetoothSocket: close() channel: 5",
            "01-02 03:04:05.678  4321  4411 I GlDriver: build info",
            "01-02 03:04:05.678  4321  4400 I OpenHCX: 不是自己的标签",
            "01-02 03:04:05.678  4321  4321 I OpenHC-Hotspot: type=1400 audit(0.0:100): avc:  denied  { read }",
            "--------- beginning of main",
            "===== 01-02 03:04:05.678 OpenHC 诊断记录：进程 4321 开始记录 =====",
        )
        for (line in kept) assertFalse(DiagnosticsReport.writtenDirectly(line), line)
    }

    @Test
    fun sectionTitlesStandOut() {
        assertEquals("\n===== 运行日志 =====\n", DiagnosticsReport.section("运行日志"))
    }
}
