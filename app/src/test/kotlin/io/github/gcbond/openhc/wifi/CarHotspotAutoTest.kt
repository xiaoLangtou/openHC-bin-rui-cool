package io.github.gcbond.openhc.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarHotspotAutoTest {
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
    private val results = ArrayDeque<AdbSwitchResult>()

    /** 每次跑记下命令和是否请求授权，结果按 [results] 依次给，用完了都给 DONE。 */
    private val auto = CarHotspotAuto(
        runner = { command, ask ->
            // 打开前记号必须已经落盘。
            if (command == HotspotSwitchMain.START) assertTrue(journal.pending, "打开前先记下待关闭")
            calls += if (ask) "$command+ask" else command
            results.removeFirstOrNull() ?: AdbSwitchResult.DONE
        },
        journal = journal,
        worker = worker,
        log = {},
    )

    @Test
    fun opensForTheConnectionAndClosesWhenItEnds() {
        assertEquals(AdbSwitchResult.DONE, auto.open(1))
        assertEquals(listOf("start"), calls)
        assertTrue(journal.pending)
        assertEquals(CarHotspotAuto.Phase.OPEN, auto.status.value.phase)
        auto.close(1)
        assertEquals(listOf("start", "stop"), calls)
        assertFalse(journal.pending)
        assertEquals(CarHotspotAuto.Phase.IDLE, auto.status.value.phase)
    }

    @Test
    fun aHotspotTheOwnerOpenedIsLeftAlone() {
        results.addLast(AdbSwitchResult.ALREADY_ON)
        assertEquals(AdbSwitchResult.ALREADY_ON, auto.open(1))
        assertFalse(journal.pending)
        auto.close(1)
        auto.recover("测试")
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun aHotspotLeftOpenByAnEarlierRunIsTakenOverAndClosed() {
        journal.pending = true
        results.addLast(AdbSwitchResult.ALREADY_ON)
        auto.open(1)
        assertTrue(journal.pending)
        auto.close(1)
        assertEquals(listOf("start", "stop"), calls)
        assertFalse(journal.pending)
    }

    @Test
    fun aFailedOpenChangedNothingSoThereIsNothingToClose() {
        for (result in listOf(AdbSwitchResult.ADB_OFF, AdbSwitchResult.NOT_APPROVED, AdbSwitchResult.UNSUPPORTED, AdbSwitchResult.DENIED)) {
            calls.clear()
            results.addLast(result)
            assertEquals(result, auto.open(1))
            assertFalse(journal.pending, "$result")
            auto.close(1)
            assertEquals(listOf("start"), calls, "$result")
        }
    }

    @Test
    fun anUnclearOpenIsClosedAnyway() {
        results.addLast(AdbSwitchResult.FAILED)
        auto.open(1)
        assertTrue(journal.pending)
        auto.close(1)
        assertEquals(listOf("start", "stop"), calls)
        assertFalse(journal.pending)
    }

    @Test
    fun aFailedOpenKeepsAnEarlierPendingClose() {
        journal.pending = true
        results.addLast(AdbSwitchResult.ADB_OFF)
        auto.open(1)
        assertTrue(journal.pending)
        // 这次没打开，结束时不关；留下的记号等补关。
        auto.close(1)
        assertEquals(listOf("start"), calls)
        auto.recover("测试")
        assertEquals(listOf("start", "stop"), calls)
        assertFalse(journal.pending)
    }

    @Test
    fun anotherConnectionCannotCloseIt() {
        auto.open(1)
        auto.close(2)
        auto.recover("测试")
        assertEquals(listOf("start"), calls)
        assertTrue(journal.pending)
    }

    @Test
    fun startupClosesALeftoverHotspot() {
        journal.pending = true
        auto.recover("应用启动")
        assertEquals(listOf("stop"), calls)
        assertFalse(journal.pending)
        auto.recover("应用启动")
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun closeRetriesUntilItWorksThenStops() {
        auto.open(1)
        results.addLast(AdbSwitchResult.ADB_OFF)
        auto.close(1)
        assertTrue(journal.pending)
        assertEquals(1, worker.delayed.size)
        worker.runDelayed()
        assertEquals(listOf("start", "stop", "stop"), calls)
        assertFalse(journal.pending)
        assertTrue(worker.delayed.isEmpty())
    }

    @Test
    fun closeGivesUpAfterTheLimitButKeepsTheMark() {
        auto.open(1)
        repeat(CarHotspotAuto.RETRY_LIMIT + 1) { results.addLast(AdbSwitchResult.FAILED) }
        auto.close(1)
        repeat(CarHotspotAuto.RETRY_LIMIT) { worker.runDelayed() }
        assertEquals(1 + CarHotspotAuto.RETRY_LIMIT + 1, calls.size)
        assertTrue(worker.delayed.isEmpty())
        assertTrue(journal.pending)
    }

    @Test
    fun theNextConnectionCancelsPendingRetries() {
        auto.open(1)
        results.addLast(AdbSwitchResult.ADB_OFF)
        auto.close(1)
        assertEquals(1, worker.delayed.size)
        results.addLast(AdbSwitchResult.ALREADY_ON)
        auto.open(2)
        assertTrue(worker.delayed.isEmpty())
        // 还没关掉的那个热点由这次连接接手，结束时关。
        auto.close(2)
        assertEquals(listOf("start", "stop", "start", "stop"), calls)
        assertFalse(journal.pending)
    }

    @Test
    fun unsupportedCloseIsNotRetried() {
        journal.pending = true
        results.addLast(AdbSwitchResult.UNSUPPORTED)
        auto.recover("测试")
        assertFalse(journal.pending)
        assertTrue(worker.delayed.isEmpty())
    }

    @Test
    fun anOpenStillQueuedWhenTheConnectionGaveUpIsSkipped() {
        val queued = ArrayList<() -> Unit>()
        val slow = CarHotspotAuto(
            runner = { command, _ -> calls += command; AdbSwitchResult.DONE },
            journal = journal,
            worker = object : AdbWorker {
                override fun execute(task: () -> Unit) {
                    queued += task
                }
                override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit = {}
            },
            log = {},
        )
        assertEquals(AdbSwitchResult.FAILED, slow.open(1, waitMillis = 0))
        queued.forEach { it() }
        assertTrue(calls.isEmpty())
        assertFalse(journal.pending)
    }

    @Test
    fun checkAsksForApprovalAndOpensNothing() {
        auto.check()
        assertEquals(listOf("check+ask"), calls)
        assertFalse(journal.pending)
        assertEquals(AdbSwitchResult.DONE, auto.status.value.last)
    }

    @Test
    fun failureTextOnlyWhenNotOpen() {
        assertNull(AdbSwitchResult.DONE.hotspotOpenFailure())
        assertNull(AdbSwitchResult.ALREADY_ON.hotspotOpenFailure())
        for (result in AdbSwitchResult.values().filter { it != AdbSwitchResult.DONE && it != AdbSwitchResult.ALREADY_ON && it != AdbSwitchResult.READY }) {
            assertTrue(result.hotspotOpenFailure()!!.isNotEmpty(), "$result")
        }
    }

    @Test
    fun commandLineAndOutput() {
        val helper = HotspotSwitchCommand.helper
        assertEquals(HotspotSwitchMain::class.java.name, HotspotSwitchCommand.MAIN_CLASS)
        assertEquals(
            "CLASSPATH='/data/app/x-1/base.apk' app_process /system/bin ${HotspotSwitchCommand.MAIN_CLASS} start",
            helper.line("/data/app/x-1/base.apk", HotspotSwitchMain.START),
        )
        assertEquals(AdbSwitchResult.DONE, helper.parse("noise\nOPENHC_HOTSPOT_SWITCH|DONE|state=13\n"))
        assertEquals(AdbSwitchResult.ALREADY_ON, helper.parse("OPENHC_HOTSPOT_SWITCH|ALREADY_ON|state=13\r\n"))
        assertEquals(AdbSwitchResult.UNSUPPORTED, helper.parse("OPENHC_HOTSPOT_SWITCH|UNSUPPORTED|-"))
        assertEquals(AdbSwitchResult.FAILED, helper.parse("OPENHC_HOTSPOT_SWITCH|FAILED|code=3"))
        // 别的小程序的输出不认。
        assertEquals(AdbSwitchResult.FAILED, helper.parse("OPENHC_SCAN_SWITCH|DONE|-"))
        assertEquals(AdbSwitchResult.FAILED, helper.parse("Killed"))
    }
}
