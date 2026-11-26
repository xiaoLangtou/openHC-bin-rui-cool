package io.github.gcbond.openhc.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanPauseTest {
    private class FakeJournal : PendingJournal {
        override var pending = false
    }

    /** 立即执行；延时任务存起来，测试里手动触发。 */
    private class FakeWorker : AdbWorker {
        val delayed = ArrayList<() -> Unit>()
        override fun execute(task: () -> Unit) = task()
        override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
            delayed += task
            return { delayed.remove(task) }
        }

        fun runDelayed() {
            val tasks = delayed.toList()
            delayed.clear()
            tasks.forEach { it() }
        }
    }

    private val journal = FakeJournal()
    private val worker = FakeWorker()
    private val calls = ArrayList<String>()
    private var enabled = true
    private var results = ArrayDeque<AdbSwitchResult>()

    /** 每次跑记下命令和是否请求授权，结果按 [results] 依次给，用完了都给 DONE。 */
    private val pause = ScanPause(
        runner = { command, ask ->
            // 暂停前记号必须已经落盘。
            if (command == ScanSwitchMain.PAUSE) assertTrue(journal.pending, "暂停前先记下待恢复")
            calls += if (ask) "$command+ask" else command
            results.removeFirstOrNull() ?: AdbSwitchResult.DONE
        },
        journal = journal,
        enabled = { enabled },
        worker = worker,
        log = {},
    )

    @Test
    fun pausesForTheSessionAndRestoresWhenItEnds() {
        pause.sessionStarted(1)
        assertEquals(listOf("pause"), calls)
        assertTrue(journal.pending)
        assertEquals(ScanPause.Phase.PAUSED, pause.status.value.phase)
        pause.sessionEnded(1)
        assertEquals(listOf("pause", "restore"), calls)
        assertFalse(journal.pending)
        assertEquals(ScanPause.Phase.IDLE, pause.status.value.phase)
    }

    @Test
    fun nothingHappensWhenTheSettingIsOff() {
        enabled = false
        pause.sessionStarted(1)
        pause.sessionEnded(1)
        assertTrue(calls.isEmpty())
        assertFalse(journal.pending)
    }

    @Test
    fun aFailedPauseChangedNothingSoThereIsNothingToRestore() {
        for (result in listOf(AdbSwitchResult.ADB_OFF, AdbSwitchResult.NOT_APPROVED, AdbSwitchResult.UNSUPPORTED)) {
            calls.clear()
            results.addLast(result)
            pause.sessionStarted(1)
            assertFalse(journal.pending, "$result")
            pause.sessionEnded(1)
            assertEquals(listOf("pause"), calls, "$result")
        }
    }

    @Test
    fun anUnclearPauseIsRestoredAnyway() {
        results.addLast(AdbSwitchResult.FAILED)
        pause.sessionStarted(1)
        assertTrue(journal.pending)
        pause.sessionEnded(1)
        assertEquals(listOf("pause", "restore"), calls)
        assertFalse(journal.pending)
    }

    @Test
    fun aFailedPauseKeepsAnEarlierPendingRestore() {
        journal.pending = true
        results.addLast(AdbSwitchResult.ADB_OFF)
        pause.sessionStarted(1)
        assertTrue(journal.pending, "之前暂停过还没恢复，这次没连上 ADB 也不能丢掉记号")
    }

    @Test
    fun restoreRetriesUntilItWorksThenStops() {
        pause.sessionStarted(1)
        results.addLast(AdbSwitchResult.ADB_OFF)
        results.addLast(AdbSwitchResult.FAILED)
        pause.sessionEnded(1)
        assertTrue(journal.pending)
        assertEquals(1, worker.delayed.size)
        worker.runDelayed()
        assertTrue(journal.pending)
        worker.runDelayed()
        assertFalse(journal.pending)
        assertTrue(worker.delayed.isEmpty())
        assertEquals(listOf("pause", "restore", "restore", "restore"), calls)
    }

    @Test
    fun restoreGivesUpAfterTheLimitButKeepsTheMark() {
        pause.sessionStarted(1)
        repeat(ScanPause.RETRY_LIMIT + 1) { results.addLast(AdbSwitchResult.ADB_OFF) }
        pause.sessionEnded(1)
        repeat(ScanPause.RETRY_LIMIT) { worker.runDelayed() }
        assertTrue(worker.delayed.isEmpty())
        assertTrue(journal.pending, "记号留着，下次启动再恢复")
        assertEquals(1 + 1 + ScanPause.RETRY_LIMIT, calls.size)
        pause.recover("测试")
        assertFalse(journal.pending)
    }

    @Test
    fun aNewSessionCancelsPendingRetries() {
        pause.sessionStarted(1)
        results.addLast(AdbSwitchResult.ADB_OFF)
        pause.sessionEnded(1)
        assertEquals(1, worker.delayed.size)
        pause.sessionStarted(2)
        assertTrue(worker.delayed.isEmpty())
        assertTrue(journal.pending)
    }

    @Test
    fun appStartRestoresALeftoverPause() {
        journal.pending = true
        pause.recover("测试")
        assertEquals(listOf("restore"), calls)
        assertFalse(journal.pending)
        calls.clear()
        pause.recover("测试")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun turningTheSettingOffRestoresRightAway() {
        pause.sessionStarted(1)
        enabled = false
        pause.settingChanged(false)
        assertEquals(listOf("pause", "restore"), calls)
        assertFalse(journal.pending)
        pause.sessionEnded(1)
        assertEquals(2, calls.size, "已经恢复过，结束时不再跑")
    }

    @Test
    fun unsupportedRestoreIsNotRetried() {
        journal.pending = true
        results.addLast(AdbSwitchResult.DENIED)
        pause.recover("测试")
        assertFalse(journal.pending)
        assertTrue(worker.delayed.isEmpty())
    }

    @Test
    fun endingAnotherSessionDoesNotRestore() {
        pause.sessionStarted(1)
        pause.sessionEnded(2)
        assertEquals(listOf("pause"), calls)
        assertTrue(journal.pending)
    }

    @Test
    fun checkAsksForApprovalAndChangesNothing() {
        results.addLast(AdbSwitchResult.READY)
        pause.check()
        assertEquals(listOf("check+ask"), calls)
        assertFalse(journal.pending)
        assertEquals(AdbSwitchResult.READY, pause.status.value.last)
    }

    @Test
    fun commandLineAndOutput() {
        assertEquals(
            "CLASSPATH='/data/app/x-1/base.apk' app_process /system/bin ${ScanSwitchCommand.MAIN_CLASS} pause",
            ScanSwitchCommand.line("/data/app/x-1/base.apk", ScanSwitchMain.PAUSE),
        )
        assertEquals("CLASSPATH='/a'\\''b' app_process /system/bin ${ScanSwitchCommand.MAIN_CLASS} check", ScanSwitchCommand.line("/a'b", "check"))
        assertEquals(ScanSwitchMain::class.java.name, ScanSwitchCommand.MAIN_CLASS)
        assertEquals(AdbSwitchResult.DONE, ScanSwitchCommand.parse("noise\nOPENHC_SCAN_SWITCH|DONE|enableWifiConnectivityManager\n"))
        assertEquals(AdbSwitchResult.READY, ScanSwitchCommand.parse("OPENHC_SCAN_SWITCH|READY|allowAutojoinGlobal\r\n"))
        assertEquals(AdbSwitchResult.UNSUPPORTED, ScanSwitchCommand.parse("OPENHC_SCAN_SWITCH|UNSUPPORTED|-"))
        assertEquals(AdbSwitchResult.FAILED, ScanSwitchCommand.parse("Killed"))
        assertEquals(AdbSwitchResult.FAILED, ScanSwitchCommand.parse("OPENHC_SCAN_SWITCH|WHAT|-"))
    }
}
