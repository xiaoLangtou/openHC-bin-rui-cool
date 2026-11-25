package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 文件分享：管理消息（module 1 / type 17）与两条不加密的文件 TCP。 */
class FileShareTest {

    private val phone = Phone()

    private fun config(fileShare: DmsdpConfig.FileShare? = DmsdpConfig.FileShare()) = DmsdpConfig(
        host = Synthetic.host(),
        deviceId = Synthetic.SELF_ID,
        bluetoothMac = Synthetic.MAC,
        localIp = Synthetic.LOCAL_IP,
        phoneIp = Synthetic.PHONE_IP,
        fileShare = fileShare,
    )

    private fun session(fileShare: DmsdpConfig.FileShare? = DmsdpConfig.FileShare()): DmsdpSession =
        encryptedSession(config = config(fileShare)).first

    private fun DmsdpSession.management(text: String, nowMillis: Long = 0) =
        management(text.toByteArray(), nowMillis)

    private fun DmsdpSession.management(raw: ByteArray, nowMillis: Long = 0) =
        feed(DmsdpChannel.CONTROL, phone.encryptedRecord(1, 17, raw), nowMillis)

    /** 车机经控制连接回的管理消息（解密、拆记录后的 JSON 文本）。 */
    private fun managementReplies(outputs: List<DmsdpOutput>): List<String> =
        outputs.sends(DmsdpChannel.CONTROL).map { frame ->
            val record = DeviceRecords.parseAll(phone.openControlFrame(frame)).single()
            assertEquals(1L, record.module)
            assertEquals(17, record.type)
            String(record.data)
        }

    private fun frame(code: Int, content: String = ""): ByteArray {
        val json = "{\"cmdType\":$code,\"cmdContent\":${quote(content)}}".toByteArray()
        return le32(json.size) + json
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun offer(vararg files: Pair<String, Long>): ByteArray =
        frame(101, files.joinToString(",", "[", "]") { "{\"fileName\":${quote(it.first)},\"fileSize\":${it.second}}" })

    private fun named(name: String, data: ByteArray = ByteArray(0)): ByteArray {
        val bytes = name.toByteArray()
        return le32(bytes.size) + bytes + data
    }

    /** 建好两条文件连接，返回会话。 */
    private fun connected(fileShare: DmsdpConfig.FileShare = DmsdpConfig.FileShare()): DmsdpSession {
        val session = session(fileShare)
        assertEquals(40100, assertIs<DmsdpAction.Connect>(session.management("{\"cmdId\":\"7\",\"port\":40100}").withoutRaw().single()).port)
        assertTrue(session.onConnected(DmsdpChannel.FILE_CONTROL, 0).isEmpty())
        val data = assertIs<DmsdpAction.Connect>(session.management("{\"cmdId\":8,\"port\":\"40101\"}").withoutRaw().single())
        assertEquals(DmsdpChannel.FILE_DATA, data.channel)
        assertEquals(40101, data.port)
        assertTrue(session.onConnected(DmsdpChannel.FILE_DATA, 0).isEmpty())
        return session
    }

    private fun DmsdpSession.offered(vararg files: Pair<String, Long>, nowMillis: Long = 0): Long {
        val event = feed(DmsdpChannel.FILE_CONTROL, offer(*files), nowMillis).withoutRaw().single()
        return assertIs<DmsdpEvent.FileOffered>(event).offerId
    }

    @Test
    fun managementHandshakeIsIdempotentAcrossFormats() {
        val session = session()
        val replies = ArrayList<String>()
        val events = ArrayList<DmsdpEvent>()
        for (raw in listOf("1", "2", "9", "{\"cmdId\":\"1\"}", "{\"cmdId\":2}", "{\"cmdId\":\"9\"}", "{\"cmdId\":1}")) {
            val out = session.management(raw)
            replies += managementReplies(out)
            events += out.events()
        }
        assertEquals(
            listOf("{\"cmdId\":\"9\"}", "{\"cmdId\":\"2\",\"version\":\"4.0\",\"info\":{\"carIp\":\"192.0.2.10\",\"phoneIp\":\"192.0.2.20\"}}"),
            replies,
            "1 只回一次能力通告，2 只回一次网络信息（与另一份实现输出的字节相同）",
        )
        assertEquals(listOf<DmsdpEvent>(DmsdpEvent.FileShareSupported), events)
    }

    @Test
    fun unknownManagementIsHandedOutAndBadJsonOnlyStopsFileShare() {
        val session = session()
        // 单字节只认旧格式 1/2/9，其余（包括 "{"）都交出，不当 JSON 解析。
        for (raw in listOf("0", "3", "{", "{\"cmdId\":\"99\"}", "{\"cmdId\":7.0}", "{\"cmdId\":true}", "{\"other\":1}")) {
            val event = session.management(raw).withoutRaw().single()
            assertEquals(17, assertIs<DmsdpEvent.UnhandledDeviceData>(event).type, raw)
        }
        for (raw in listOf("{\"cmdId\"", "[1]", "\"x\"")) {
            assertNull(assertIs<DmsdpEvent.FileShareFailed>(session.management(raw).withoutRaw().single()).offerId, raw)
        }
        assertTrue(session.management(byteArrayOf(0x7B, 0xFF.toByte())).withoutRaw().single() is DmsdpEvent.FileShareFailed, "坏 UTF-8")
        // DMSDP 会话不受影响。
        assertEquals(1, managementReplies(session.management("1")).size)
        assertTrue(session.isEncrypted)
    }

    @Test
    fun portsAreStrictAndDuplicatesIgnored() {
        for ((value, expected) in listOf("1" to 1, "65535" to 65535, "\"1\"" to 1, "\"65535\"" to 65535, "\"00001\"" to 1)) {
            val out = session().management("{\"cmdId\":\"8\",\"port\":$value,\"info\":\"ignored\"}")
            assertEquals(expected, assertIs<DmsdpAction.Connect>(out.withoutRaw().single()).port, value)
        }
        val invalid = listOf(
            "true", "1.0", "0", "-1", "65536", "null", "[]", "{}", "\"\"", "\"0\"", "\"65536\"", "\"000001\"",
            "\"+1\"", "\"-1\"", "\" 1\"", "\"1 \"", "\"１\"", "\"1e2\"",
        )
        for (value in invalid) {
            val event = session().management("{\"cmdId\":\"7\",\"port\":$value}").withoutRaw().single()
            assertEquals("文件分享手机端口无效", assertIs<DmsdpEvent.FileShareFailed>(event).reason, value)
        }
        assertIs<DmsdpEvent.FileShareFailed>(session().management("{\"cmdId\":\"7\"}").withoutRaw().single())

        val session = connected()
        assertTrue(session.management("{\"cmdId\":\"7\",\"port\":40200}").withoutRaw().isEmpty(), "已连上时重复的端口通知忽略")
        assertTrue(session.management("{\"cmdId\":\"8\",\"port\":40201}").withoutRaw().isEmpty())
    }

    @Test
    fun controlFramesMatchCrossVectors() {
        for ((code, vector) in listOf(
            102 to CrossVectors.FILE_CONTROL_102, 103 to CrossVectors.FILE_CONTROL_103, 104 to CrossVectors.FILE_CONTROL_104,
            108 to CrossVectors.FILE_CONTROL_108, 111 to CrossVectors.FILE_CONTROL_111,
        )) {
            assertEquals(vector, IncomingFileTransfer.controlFrame(code).toHex())
        }
        assertEquals("05000000", le32(5).toHex(), "长度是小端")
    }

    @Test
    fun wholeTransferWithSplitFrames() {
        val session = connected()
        val offerFrame = hex(CrossVectors.FILE_OFFER_101)
        val events = ArrayList<DmsdpOutput>()
        var pos = 0
        for (size in listOf(1, 2, 1, 5, offerFrame.size - 9)) {
            events += session.feed(DmsdpChannel.FILE_CONTROL, offerFrame, pos, size, 0)
            pos += size
        }
        val offered = assertIs<DmsdpEvent.FileOffered>(events.withoutRaw().single())
        assertEquals(listOf("照片.jpg" to 5L, "b.txt" to 0L), offered.files.map { it.name to it.size })
        assertEquals(5L, offered.totalBytes)
        assertEquals(1000, session.nextTickAtMillis?.let { it - 29_000 }, "等用户确认 30 秒")

        val accepted = session.acceptFileOffer(offered.offerId, 1_000)
        assertEquals(CrossVectors.FILE_CONTROL_102, accepted.only<DmsdpAction.Send>().bytes.toHex())
        assertTrue(session.acceptFileOffer(offered.offerId, 1_000).isEmpty(), "重复接受忽略")

        val stream = hex(CrossVectors.FILE_DATA_STREAM)
        val received = ArrayList<DmsdpOutput>()
        for (i in stream.indices) received += session.feed(DmsdpChannel.FILE_DATA, stream, i, 1, 2_000)
        val names = received.map {
            when (it) {
                is DmsdpEvent.FileStarted -> "开始 ${it.name} ${it.size}"
                is DmsdpEvent.FileChunk -> "数据 ${it.name} ${String(it.data)}"
                is DmsdpEvent.FileFinished -> "结束 ${it.name}"
                is DmsdpEvent.FileOfferReceived -> "收齐 ${it.offerId}"
                else -> it.toString()
            }
        }
        assertEquals(
            listOf("开始 照片.jpg 5") + "HELLO".map { "数据 照片.jpg $it" } +
                listOf("结束 照片.jpg", "开始 b.txt 0", "结束 b.txt", "收齐 ${offered.offerId}"),
            names,
        )
        // 106 晚于全部数据到达也可以。
        assertTrue(session.feed(DmsdpChannel.FILE_CONTROL, frame(106), 3_000).isEmpty())
        assertTrue(session.feed(DmsdpChannel.FILE_CONTROL, frame(111), 3_000).isEmpty())
        val done = session.confirmFileOfferSaved(offered.offerId)
        assertEquals(CrossVectors.FILE_CONTROL_111, assertIs<DmsdpAction.Send>(done[0]).bytes.toHex())
        assertEquals(DmsdpChannel.FILE_DATA, assertIs<DmsdpAction.Close>(done[1]).channel)
        assertNull(session.nextTickAtMillis?.takeIf { it > 1_000_000 }, "没有文件时限")
        // 数据连接已关闭：迟到的字节丢弃；手机再给数据端口时重新连接。
        assertTrue(session.feed(DmsdpChannel.FILE_DATA, hex("00")).isEmpty())
        assertIs<DmsdpAction.Connect>(session.management("{\"cmdId\":\"8\",\"port\":40102}").withoutRaw().single())
    }

    @Test
    fun stickyControlFramesAndChunkedData() {
        val session = connected()
        val out = session.feed(DmsdpChannel.FILE_CONTROL, offer("a.bin" to 70_000L) + frame(111), 0)
        val id = assertIs<DmsdpEvent.FileOffered>(out.withoutRaw().single()).offerId
        session.acceptFileOffer(id, 0)
        session.feed(DmsdpChannel.FILE_CONTROL, frame(106), 0)
        val content = ByteArray(70_000) { (it * 7).toByte() }
        val all = named("a.bin", content)
        val chunks = session.feed(DmsdpChannel.FILE_DATA, all, 0, 40_000, 0) + session.feed(DmsdpChannel.FILE_DATA, all, 40_000, all.size - 40_000, 0)
        val data = chunks.filterIsInstance<DmsdpEvent.FileChunk>().map { it.data }.reduce { a, b -> a + b }
        assertContentEquals(content, data)
        assertIs<DmsdpEvent.FileOfferReceived>(chunks.last())
    }

    @Test
    fun rejectCancelAndInsufficientSpace() {
        val session = connected()
        val first = session.offered("a.txt" to 3)
        val rejected = session.rejectFileOffer(first)
        assertEquals(CrossVectors.FILE_CONTROL_104, assertIs<DmsdpAction.Send>(rejected[0]).bytes.toHex())
        assertEquals(DmsdpChannel.FILE_DATA, assertIs<DmsdpAction.Close>(rejected[1]).channel)
        assertEquals(DmsdpEvent.FileOfferEnded.Reason.REJECTED, assertIs<DmsdpEvent.FileOfferEnded>(rejected[2]).reason)
        assertTrue(session.rejectFileOffer(first).isEmpty(), "过期的请求忽略")

        session.management("{\"cmdId\":\"8\",\"port\":40101}")
        session.onConnected(DmsdpChannel.FILE_DATA, 0)
        val second = session.offered("b.txt" to 3)
        assertEquals(CrossVectors.FILE_CONTROL_103, session.rejectFileOffer(second, insufficientSpace = true).sends(DmsdpChannel.FILE_CONTROL).single().toHex())

        session.management("{\"cmdId\":\"8\",\"port\":40101}")
        session.onConnected(DmsdpChannel.FILE_DATA, 0)
        val third = session.offered("c.txt" to 3)
        session.acceptFileOffer(third, 0)
        assertEquals(2, session.feed(DmsdpChannel.FILE_DATA, named("c.txt", "ab".toByteArray())).size, "开始 + 一段数据")
        val cancelled = session.cancelFileOffer(third)
        assertEquals(CrossVectors.FILE_CONTROL_108, cancelled.sends(DmsdpChannel.FILE_CONTROL).single().toHex())
        assertEquals(DmsdpChannel.FILE_DATA, cancelled.filterIsInstance<DmsdpAction.Close>().single().channel)
        assertEquals(DmsdpEvent.FileOfferEnded.Reason.CANCELLED, (cancelled.events().single() as DmsdpEvent.FileOfferEnded).reason)

        val fourth = session.offered("d.txt" to 3)
        assertEquals(CrossVectors.FILE_CONTROL_104, session.cancelFileOffer(fourth).sends(DmsdpChannel.FILE_CONTROL).single().toHex(), "未接受时取消回 104")
    }

    @Test
    fun peerEndsRequest() {
        val reasons = mapOf(
            105 to DmsdpEvent.FileOfferEnded.Reason.PEER_TIMEOUT,
            107 to DmsdpEvent.FileOfferEnded.Reason.PEER_CANCELLED,
            112 to DmsdpEvent.FileOfferEnded.Reason.PEER_FAILED,
        )
        for ((code, reason) in reasons) {
            val session = connected()
            val id = session.offered("a.txt" to 3)
            session.acceptFileOffer(id, 0)
            val out = session.feed(DmsdpChannel.FILE_CONTROL, frame(code))
            assertEquals(DmsdpChannel.FILE_DATA, assertIs<DmsdpAction.Close>(out[0]).channel)
            assertEquals(reason, assertIs<DmsdpEvent.FileOfferEnded>(out[1]).reason)
            assertTrue(session.feed(DmsdpChannel.FILE_CONTROL, frame(code)).isEmpty(), "没有请求时再收到也忽略")
        }
    }

    @Test
    fun localTimeouts() {
        val options = DmsdpConfig.FileShare(offerTimeoutMillis = 30_000, idleTimeoutMillis = 60_000)
        val session = connected(options)
        session.offered("a.txt" to 3, nowMillis = 10_000)
        assertTrue(session.onTick(39_999).none { it is DmsdpEvent.FileOfferEnded })
        val expired = session.onTick(40_000)
        assertEquals(CrossVectors.FILE_CONTROL_104, expired.sends(DmsdpChannel.FILE_CONTROL).single().toHex())
        assertEquals(DmsdpEvent.FileOfferEnded.Reason.LOCAL_TIMEOUT, (expired.events().single() as DmsdpEvent.FileOfferEnded).reason)

        session.management("{\"cmdId\":\"8\",\"port\":40101}")
        session.onConnected(DmsdpChannel.FILE_DATA, 0)
        val id = session.offered("b.txt" to 3, nowMillis = 50_000)
        session.acceptFileOffer(id, 50_000)
        session.feed(DmsdpChannel.FILE_DATA, named("b.txt", "x".toByteArray()), 70_000)
        assertTrue(session.onTick(129_999).none { it is DmsdpEvent.FileOfferEnded }, "有数据就顺延")
        val stalled = session.onTick(130_000)
        assertEquals(CrossVectors.FILE_CONTROL_108, stalled.sends(DmsdpChannel.FILE_CONTROL).single().toHex())
        assertEquals(DmsdpEvent.FileOfferEnded.Reason.LOCAL_TIMEOUT, (stalled.events().single() as DmsdpEvent.FileOfferEnded).reason)
    }

    @Test
    fun badOffersStopFileShareOnly() {
        val badNames = listOf("", ".", "..", "a/b", "a\\b", "a:b", "a\u0000b", "a‎b", "x".repeat(256), "好".repeat(86))
        val cases = badNames.map { offer(it to 1) } + listOf(
            frame(101, "[]"),
            frame(101, (1..21).joinToString(",", "[", "]") { "{\"fileName\":\"f$it\",\"fileSize\":1}" }),
            frame(101, "[{\"fileName\":\"a\",\"fileSize\":true}]"),
            frame(101, "[{\"fileName\":\"a\",\"fileSize\":1.0}]"),
            frame(101, "[{\"fileName\":\"a\",\"fileSize\":-1}]"),
            frame(101, "[{\"fileName\":\"a\",\"fileSize\":10737418240}]"),
            frame(101, "[{\"fileName\":\"a\",\"fileSize\":0}]"),
            frame(101, "[{\"fileName\":\"Ab\",\"fileSize\":1},{\"fileName\":\"aB\",\"fileSize\":1}]"),
            frame(101, "[{\"fileName\":\"é\",\"fileSize\":1},{\"fileName\":\"é\",\"fileSize\":1}]"),
            frame(101, "{}"),
            frame(101, "not json"),
            frame(999),
            frame(106),
            le32(0),
            le32(5_242_880),
            "{\"cmdType\":\"101\",\"cmdContent\":\"[]\"}".toByteArray().let { le32(it.size) + it },
            "{\"cmdType\":101}".toByteArray().let { le32(it.size) + it },
            le32(2) + hex("ffff"),
        )
        for (case in cases) {
            val session = connected()
            val out = session.feed(DmsdpChannel.FILE_CONTROL, case)
            assertTrue(out.any { it is DmsdpEvent.FileShareFailed }, case.toHex())
            assertEquals(
                setOf(DmsdpChannel.FILE_CONTROL, DmsdpChannel.FILE_DATA),
                out.filterIsInstance<DmsdpAction.Close>().map { it.channel }.toSet(),
            )
            assertTrue(session.isEncrypted, "DMSDP 会话继续")
            assertTrue(session.feed(DmsdpChannel.FILE_CONTROL, frame(101, "[]")).isEmpty(), "文件连接已放弃，迟到数据丢弃")
        }
        val okName = "好".repeat(85)
        assertIs<DmsdpEvent.FileOffered>(connected().feed(DmsdpChannel.FILE_CONTROL, offer(okName to 1)).withoutRaw().single(), "255 字节的名字可以")
    }

    @Test
    fun failureReasonsDoNotCarryContent() {
        val session = connected()
        val out = session.feed(DmsdpChannel.FILE_CONTROL, frame(101, "[{\"fileName\":\"PRIVATE-NAME.jpg\",\"fileSize\":}]"))
        val failed = out.filterIsInstance<DmsdpEvent.FileShareFailed>().single()
        assertTrue("PRIVATE-NAME" !in failed.reason, failed.reason)
        assertTrue("PRIVATE-NAME" !in failed.toString())
    }

    @Test
    fun unassignedCodePointsAreAllowedInNames() {
        // U+0378 在各版 Unicode 里都未分配；老系统上新 emoji 也是这种情况，不能因此拒收。
        val offered = connected().feed(DmsdpChannel.FILE_CONTROL, offer("a\u0378b.jpg" to 1, "图\uD83E\uDD23.png" to 1)).withoutRaw().single()
        assertEquals(2, assertIs<DmsdpEvent.FileOffered>(offered).files.size)
    }

    @Test
    fun badDataStreamsStopFileShareOnly() {
        val cases = listOf(
            named("other.txt", "abc".toByteArray()),
            named("a.txt", "abc".toByteArray()) + named("a.txt", "abc".toByteArray()),
            named("a.txt", "abc".toByteArray()) + named("b.txt", "d".toByteArray()) + hex("00"),
            le32(0),
            le32(256) + ByteArray(256),
            le32(2) + hex("ffff"),
        )
        for (case in cases) {
            val session = connected()
            val id = session.offered("a.txt" to 3, "b.txt" to 1)
            session.acceptFileOffer(id, 0)
            val out = session.feed(DmsdpChannel.FILE_DATA, case)
            assertEquals(id, (out.last() as DmsdpEvent.FileShareFailed).offerId, case.toHex())
        }
        // 用户接受之前就来数据。
        val early = connected()
        early.offered("a.txt" to 3)
        assertIs<DmsdpEvent.FileShareFailed>(early.feed(DmsdpChannel.FILE_DATA, named("a.txt")).last())
    }

    @Test
    fun closedConnectionsDuringTransfer() {
        val truncated = connected()
        val id = truncated.offered("a.txt" to 3)
        truncated.acceptFileOffer(id, 0)
        truncated.feed(DmsdpChannel.FILE_DATA, named("a.txt", "ab".toByteArray()))
        val out = truncated.onClosed(DmsdpChannel.FILE_DATA, 0)
        assertEquals(DmsdpChannel.FILE_CONTROL, assertIs<DmsdpAction.Close>(out[0]).channel)
        assertEquals(id, assertIs<DmsdpEvent.FileShareFailed>(out[1]).offerId)

        val idle = connected()
        assertTrue(idle.onClosed(DmsdpChannel.FILE_DATA, 0).isEmpty(), "没有请求时数据连接断开没关系")

        val control = connected()
        control.offered("a.txt" to 3)
        val closed = control.onClosed(DmsdpChannel.FILE_CONTROL, 0)
        assertEquals(DmsdpChannel.FILE_DATA, assertIs<DmsdpAction.Close>(closed[0]).channel)
        assertIs<DmsdpEvent.FileShareFailed>(closed[1])

        // 连接还没建好就已放弃：迟到的连接要关掉。
        val late = session()
        late.management("{\"cmdId\":\"7\",\"port\":40100}")
        late.management("{\"cmdId\":\"8\",\"port\":0}")
        assertEquals(DmsdpChannel.FILE_CONTROL, assertIs<DmsdpAction.Close>(late.onConnected(DmsdpChannel.FILE_CONTROL, 0).withoutRaw().single()).channel)
    }

    @Test
    fun closingSessionCancelsActiveRequest() {
        val session = connected()
        val id = session.offered("a.txt" to 3)
        session.acceptFileOffer(id, 0)
        val out = session.close()
        assertEquals(CrossVectors.FILE_CONTROL_108, out.sends(DmsdpChannel.FILE_CONTROL).single().toHex())
        assertEquals(DmsdpEvent.FileOfferEnded.Reason.CANCELLED, (out.events().single() as DmsdpEvent.FileOfferEnded).reason)
        assertTrue(session.close().isEmpty())
        assertFailsWith<IllegalStateException> { session.acceptFileOffer(id, 0) }
    }

    @Test
    fun withoutFileShareManagementIsUnhandled() {
        val session = session(fileShare = null)
        val event = session.management("{\"cmdId\":\"7\",\"port\":40100}").withoutRaw().single()
        assertEquals(17, assertIs<DmsdpEvent.UnhandledDeviceData>(event).type)
        assertFailsWith<IllegalStateException> { session.feed(DmsdpChannel.FILE_CONTROL, hex("00")) }
        assertFailsWith<IllegalStateException> { session.acceptFileOffer(1, 0) }
    }
}
