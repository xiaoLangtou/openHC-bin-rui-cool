package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.ascii
import io.github.gcbond.openhc.core.hisight.bool
import io.github.gcbond.openhc.core.hisight.hex
import io.github.gcbond.openhc.core.hisight.int
import io.github.gcbond.openhc.core.hisight.obj
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 解析器差分测试：同一批报文（自测里的合成样例、它们的逐字节截断和头部区域的单字节替换）交给参考实现和本实现。
 * 准则：参考实现拒绝的本实现必须拒绝；参考实现接受的，本实现要么给出完全相同的结果，要么因为头行里有
 * HTAB 以外的控制字符而拒绝（本实现更严格的唯一一处）。
 */
class RtspParserTest {
    private val parser = Vectors.section("parser").jsonObject

    private sealed class Outcome {
        class Accepted(val message: RtspMessage) : Outcome()
        class Rejected(val error: MalformedMessageException) : Outcome()
    }

    private fun run(payload: ByteArray): Outcome = try {
        Outcome.Accepted(RtspParser.parse(payload))
    } catch (e: MalformedMessageException) {
        Outcome.Rejected(e)
    }

    private var stricter = 0

    /**
     * 有意比参考实现宽的一种：不带结尾空行的空 GET_PARAMETER 保活，Date 带了时间（参考实现只收空 Date）。
     * 只有 Date、CSeq 两个头，没有正文，整条报文里没有空行。
     */
    private fun isDatedKeepAlive(payload: ByteArray, m: RtspMessage): Boolean =
        m.firstLine.startsWith("GET_PARAMETER ") && m.headers.keys == setOf("date", "cseq") &&
            (m.headers["date"] ?: "").isNotEmpty() && m.body.isEmpty() &&
            !String(payload, Charsets.ISO_8859_1).contains("\r\n\r\n")

    /**
     * 有意的差异：少了头部结束空行的请求，正文长度正好等于 Content-Length 时本实现接受（参考实现拒绝）。
     */
    private fun isRequestWithoutBlankLine(payload: ByteArray, m: RtspMessage): Boolean =
        !String(payload, Charsets.ISO_8859_1).contains("\r\n\r\n") && !m.firstLine.startsWith("RTSP/") &&
            m.headers["content-length"]?.toIntOrNull() == m.body.size && m.body.isNotEmpty()

    /** 参考实现接受、本实现拒绝时，必须是头部区域含控制字符这一种情况。 */
    private fun assertStricter(payload: ByteArray, bodyStart: Int, error: MalformedMessageException, label: String) {
        assertEquals("控制消息头含控制字符", error.message, "$label：拒绝原因不是控制字符")
        var hasControl = false
        var i = 0
        while (i < bodyStart) {
            val b = payload[i].toInt() and 0xFF
            if (b == 0x0D && i + 1 < payload.size && payload[i + 1].toInt() == 0x0A) {
                i += 2
                continue
            }
            if ((b < 0x20 && b != 0x09) || b == 0x7F) hasControl = true
            i++
        }
        assertTrue(hasControl, "$label：头部区域没有控制字符却被拒绝")
        stricter++
    }

    @Test
    fun corpusEntriesMatchPrototype() {
        for ((index, element) in parser.arr("entries").withIndex()) {
            val entry = element.jsonObject
            val payload = entry.getValue("hex").hex()
            val label = "样例 $index"
            when (val outcome = run(payload)) {
                is Outcome.Rejected -> if (entry.bool("ok")) {
                    assertStricter(payload, entry.int("bodyStart"), outcome.error, label)
                }
                is Outcome.Accepted -> {
                    if (!entry.bool("ok") && (isDatedKeepAlive(payload, outcome.message) || isRequestWithoutBlankLine(payload, outcome.message))) continue
                    assertTrue(entry.bool("ok"), "$label：参考实现拒绝的报文被接受了")
                    val m = outcome.message
                    assertEquals(entry.str("first"), m.firstLine, label)
                    val expected = entry.obj("headers").mapValues { it.value.jsonPrimitive.content }
                    assertEquals(expected, m.headers, label)
                    assertEquals(entry.str("body"), m.body.toHex(), label)
                    assertEquals(entry.int("cseq"), m.cseq, label)
                }
            }
        }
        // 显式构造的四条控制字符样例必须全部走「更严格」分支。
        assertEquals(4, stricter)
    }

    @Test
    fun truncationsAndMutationsMatchPrototype() {
        val entries = parser.arr("entries")
        val replacements = parser.arr("replacements").map { it.jsonPrimitive.int }
        var checked = 0
        for (element in parser.arr("derived")) {
            val derived = element.jsonObject
            val base = entries[derived.int("base")].jsonObject.getValue("hex").hex()
            val prefixes = derived.obj("prefixes")
            for (k in base.indices) {
                compare(base.copyOf(k), prefixes["$k"]?.jsonPrimitive?.content, "截断 ${derived.int("base")}/$k")
                checked++
            }
            val mutations = derived.obj("mutations")
            for (pos in 0 until derived.int("mutationEnd")) {
                for (rep in replacements) {
                    if (base[pos].toInt() == rep) continue
                    val mutated = base.copyOf()
                    mutated[pos] = rep.toByte()
                    compare(mutated, mutations["$pos:$rep"]?.jsonPrimitive?.content, "替换 ${derived.int("base")}/$pos/$rep")
                    checked++
                }
            }
        }
        assertTrue(checked > 3000, "差分语料太少：$checked")
    }

    private fun compare(payload: ByteArray, expected: String?, label: String) {
        when (val outcome = run(payload)) {
            is Outcome.Rejected -> if (expected != null) {
                assertStricter(payload, expected.substringAfter('/').toInt(), outcome.error, label)
            }
            is Outcome.Accepted -> {
                if (expected == null && (isDatedKeepAlive(payload, outcome.message) || isRequestWithoutBlankLine(payload, outcome.message))) return
                if (expected == null) fail("$label：参考实现拒绝的报文被接受了")
                assertEquals(expected.substringBefore('/'), digest(outcome.message), label)
            }
        }
    }

    /** 与生成脚本相同的规范化摘要：首行、按名排序的头、正文十六进制、CSeq。 */
    private fun digest(m: RtspMessage): String {
        val sb = StringBuilder()
        sb.append(m.firstLine.length).append(':').append(m.firstLine).append('|')
        for (name in m.headers.keys.sorted()) {
            val value = m.headers.getValue(name)
            sb.append(name.length).append(':').append(name).append('=').append(value.length).append(':').append(value).append('|')
        }
        sb.append(m.body.toHex()).append('|').append(m.cseq)
        return MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8)).toHex().substring(0, 12)
    }

    @Test
    fun requestLinesMatchPrototype() {
        for (element in Vectors.root.arr("requestLines")) {
            val entry = element.jsonObject
            val line = entry.str("line")
            val expected = entry["method"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val actual = try {
                RtspParser.requestMethod(line)
            } catch (e: MalformedMessageException) {
                null
            }
            assertEquals(expected, actual, line)
        }
        // 方括号主机直接拒绝（比参考实现严格）。
        assertFailsWith<MalformedMessageException> { RtspParser.requestMethod("GET_PARAMETER rtsp://[::1]/x RTSP/1.0") }
    }

    @Test
    fun errorMessagesDoNotEchoPeerContent() {
        val secret = "PRIVATE_SENTINEL"
        val samples = listOf(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: $secret\r\n\r\n$secret",
            "$secret * RTSP/1.0\r\nCSeq: 1\r\nX-$secret\r\n\r\n",
            "RTSP/1.0 200 OK\r\nCSeq: $secret\r\n\r\n",
        )
        for (sample in samples) {
            val e = assertFailsWith<MalformedMessageException> {
                val m = RtspParser.parse(ascii(sample))
                RtspParser.requestMethod(m.firstLine)
            }
            assertTrue(secret !in e.message.orEmpty())
        }
    }

    @Test
    fun bodyLooksLikeHeadersButStaysBody() {
        val body = "CSeq: 999\r\nPublic: X\r\n"
        val m = RtspParser.parse(
            ascii("GET_PARAMETER * RTSP/1.0\r\nCSeq: 10\r\nContent-Length: ${body.length}\r\n\r\n$body"),
        )
        assertEquals(10, m.cseq)
        assertContentEquals(ascii(body), m.body)
        assertEquals(setOf("cseq", "content-length"), m.headers.keys)
    }

    /** 不带结尾空行的空 GET_PARAMETER 保活：Date 空着或带时间都接受；多了别的头、或后面还有字节仍拒绝。 */
    @Test
    fun keepAliveWithoutBlankLineMayCarryADate() {
        for (date in listOf("", "2026-01-02 03:04:05 AM Fri")) {
            val m = RtspParser.parse("GET_PARAMETER rtsp://example.invalid/s RTSP/1.0\r\nDate: $date\r\nCSeq: 5\r\n".toByteArray())
            assertEquals(5, m.cseq, date)
            assertEquals(0, m.body.size, date)
        }
        assertFailsWith<MalformedMessageException> {
            RtspParser.parse("GET_PARAMETER rtsp://example.invalid/s RTSP/1.0\r\nDate: x\r\nCSeq: 5\r\nServer: y\r\n".toByteArray())
        }
        assertFailsWith<MalformedMessageException> {
            RtspParser.parse("GET_PARAMETER rtsp://example.invalid/s RTSP/1.0\r\nDate: x\r\nCSeq: 5\r\nzz".toByteArray())
        }
    }

    /** 请求少了头部结束空行：Content-Length 正好等于之后的字节数就分开；对不上照旧报错。数据是合成的。 */
    @Test
    fun requestWithoutBlankLineSplitsAtContentLength() {
        val body = "his_trigger_method: PLAY\r\n"
        val head = "SET_PARAMETER rtsp://example.invalid/s RTSP/1.0\r\nCSeq: 5\r\nContent-Type: text/parameters\r\n"
        val ok = RtspParser.parse((head + "Content-Length: ${body.length}\r\n" + body).toByteArray(Charsets.US_ASCII))
        kotlin.test.assertEquals(5, ok.cseq)
        kotlin.test.assertEquals(body, String(ok.body, Charsets.US_ASCII))
        kotlin.test.assertFailsWith<io.github.gcbond.openhc.core.common.MalformedMessageException> {
            RtspParser.parse((head + "Content-Length: ${body.length + 1}\r\n" + body).toByteArray(Charsets.US_ASCII))
        }
    }
}
