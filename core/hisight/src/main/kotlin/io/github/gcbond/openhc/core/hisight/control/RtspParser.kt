package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException

/** 一条已解析的控制消息。头名统一小写；[body] 只含 Content-Length 声明的正文。 */
internal class RtspMessage(
    val firstLine: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val cseq: Int,
) {
    val isResponse: Boolean get() = firstLine.startsWith("RTSP/1.0 ")

    fun header(name: String): String? = headers[name]

    /** Content-Type 是否为 text/parameters（大小写不敏感）。 */
    val isTextParameters: Boolean get() = (headers["content-type"] ?: "").lowercase() == TEXT_PARAMETERS

    companion object {
        const val TEXT_PARAMETERS = "text/parameters"
    }
}

/**
 * 控制消息解析。手机发来的消息并不总是标准 RTSP，这里只兼容下列几种写法，其余一律拒绝：
 *
 * 1. 头可以被空行分成几段：第一个空行之后若还有字节、且此前没有 Content-Length，就继续把后面的行
 *    当头读，但只认固定的几个控制头（[CONTINUATION_HEADERS]），以免把正文误当成头。
 * 2. 有 Content-Length 时，它之后的第一个空行就是正文起点，剩余字节必须正好等于声明长度。
 * 3. 应答首行可能紧接着重复一次完全相同的 `RTSP/1.0 200 OK`，只跳过这一次。
 * 4. SET_PARAMETER（text/parameters、正长度、正文以 CRLF 结尾）的声明区间外可以再多一个 CRLF。
 * 5. 没有 Content-Length/Content-Type 的 200 应答，空行后可以跟以裸 LF 结尾的 Session、Range 两行，
 *    Range 只能是 `npt=now-`。
 * 6. 空的 GET_PARAMETER 保活：只有空 Date 和 CSeq 两个头、目标是 rtsp:// URI，没有结尾空行。
 * 7. SETUP 的 200 应答：没有结尾空行，Content-Length 恰好覆盖紧随其后的最后一行 Transport。
 *
 * 头名大小写不敏感，任何位置的重复头都拒绝。头行里出现 HTAB 以外的控制字符也拒绝。
 * 异常信息只含计数，不含对端原文。
 */
internal object RtspParser {
    private val CONTINUATION_HEADERS = setOf(
        "date", "server", "cseq", "require", "public", "content-type",
        "content-length", "transport", "session", "authneg", "trigger",
    )
    private const val OK_STATUS = "RTSP/1.0 200 OK"
    private val EMPTY = ByteArray(0)

    /** 一个以 CRLF 结尾的行：内容是 `[start, end)`，CRLF 之后从 [next] 开始。 */
    private class Line(val start: Int, val end: Int) {
        val isBlank: Boolean get() = start == end
        val next: Int get() = end + 2
    }

    /** 整个载荷按 CRLF 切开；最后一个 CRLF 之后剩下的字节从 [tailStart] 开始，不算一行。 */
    private class Lines(val items: List<Line>, val tailStart: Int)

    /** 读到的头，同时记下每个头在第几行，用来判断行与行是否相邻。 */
    private class HeaderTable {
        val values = LinkedHashMap<String, String>()
        val lineOf = HashMap<String, Int>()
    }

    /** 读到一个空行后的判断结果：消息到此为止，或空行后面还是头。 */
    private sealed class AtBlank {
        class Complete(val message: RtspMessage) : AtBlank()
        object MoreHeaders : AtBlank()
    }

    fun parse(payload: ByteArray): RtspMessage =
        try {
            parseStrict(payload)
        } catch (e: MalformedMessageException) {
            // 照常拆不开时，再看是不是少了头部结束空行的请求；也不是就照原来的错报。
            val fallback = try {
                requestWithoutBlankLine(payload)
            } catch (ignored: MalformedMessageException) {
                null
            }
            fallback ?: throw e
        }

    /**
     * 请求少了头部结束的空行（手机切换应用时会发这种）：头部之后直接接正文。从前往后找一处行首，
     * 之前的都是合规、不重复的头，其中 Content-Length 正好等于之后的字节数，就从这里分开。整条里有空行的不走这里。
     */
    private fun requestWithoutBlankLine(p: ByteArray): RtspMessage? {
        for (i in 0..p.size - 4) if (isCrlf(p, i) && isCrlf(p, i + 2)) return null
        val lines = splitLines(p)
        val firstLine = lines.items.firstOrNull() ?: return null
        if (!isAscii(p, firstLine.start, firstLine.end)) return null
        val first = ascii(p, firstLine.start, firstLine.end)
        requestMethod(first)
        val values = LinkedHashMap<String, String>()
        for (k in 1..lines.items.size) {
            val bodyStart = if (k < lines.items.size) lines.items[k].start else lines.tailStart
            val declared = values["content-length"]?.let { decimal(it, 5).toInt() }
            if (declared != null && declared > 0 && p.size - bodyStart == declared && "cseq" in values) {
                return RtspMessage(first, values, p.copyOfRange(bodyStart, p.size), cseqOf(values))
            }
            if (k == lines.items.size) break
            val line = lines.items[k]
            if (!isAscii(p, line.start, line.end) || hasControlCharacter(p, line.start, line.end)) return null
            val text = ascii(p, line.start, line.end)
            val colon = text.indexOf(':')
            val name = if (colon > 0) text.substring(0, colon).lowercase() else return null
            if (!isHeaderName(name) || name in values) return null
            values[name] = trimSpaceTab(text.substring(colon + 1))
        }
        return null
    }

    private fun parseStrict(payload: ByteArray): RtspMessage {
        val lines = splitLines(payload)
        val firstLine = lines.items.firstOrNull() ?: throw MalformedMessageException("控制消息缺少首行结束符")
        if (!isAscii(payload, firstLine.start, firstLine.end)) throw MalformedMessageException("控制消息首行含非 ASCII 字节")
        val first = ascii(payload, firstLine.start, firstLine.end)

        val headers = HeaderTable()
        var index = 1
        if (first == OK_STATUS && index < lines.items.size && sameContent(payload, lines.items[index], firstLine)) index++
        var blankLines = 0
        while (index < lines.items.size) {
            val line = lines.items[index]
            if (line.isBlank) {
                when (val result = atBlankLine(payload, first, headers, line.next, firstBlank = blankLines == 0)) {
                    is AtBlank.Complete -> return result.message
                    AtBlank.MoreHeaders -> blankLines++
                }
            } else {
                readHeader(payload, line, index, headers, onlyKnownNames = blankLines > 0)
            }
            index++
        }
        if (blankLines > 0) throw lengthMismatch(payload.size - lastBlankEnd(lines))
        return keepAliveWithoutBlankLine(payload, lines, first, headers)
            ?: setupReplyWithoutBlankLine(payload, lines, first, headers)
            ?: throw MalformedMessageException("控制消息缺少头部结束空行：${shown(payload)}")
    }

    /** 报错里带上消息开头（不可打印字节换成点、长串隐去），用来对照手机发了什么。 */
    private fun shown(p: ByteArray): String {
        val text = buildString {
            for (i in 0 until minOf(p.size, SHOWN_BYTES)) {
                val c = p[i].toInt() and 0xFF
                append(
                    when (c) {
                        0x0D -> "\\r"
                        0x0A -> "\\n"
                        in 0x20..0x7E -> c.toChar().toString()
                        else -> "."
                    },
                )
            }
        }.replace(LONG_TOKEN) { "<隐去 ${it.value.length} 字符>" }
        return if (p.size > SHOWN_BYTES) "$text…（共 ${p.size} 字节）" else text
    }

    private fun splitLines(p: ByteArray): Lines {
        val items = ArrayList<Line>()
        var lineStart = 0
        var i = 0
        while (i + 1 < p.size) {
            if (p[i] == CR && p[i + 1] == LF) {
                items += Line(lineStart, i)
                i += 2
                lineStart = i
            } else {
                i++
            }
        }
        return Lines(items, lineStart)
    }

    private fun lastBlankEnd(lines: Lines): Int = lines.items.last { it.isBlank }.next

    private fun readHeader(p: ByteArray, line: Line, index: Int, headers: HeaderTable, onlyKnownNames: Boolean) {
        if (!isAscii(p, line.start, line.end)) throw MalformedMessageException("控制消息头含非 ASCII 字节")
        if (hasControlCharacter(p, line.start, line.end)) throw MalformedMessageException("控制消息头含控制字符")
        val text = ascii(p, line.start, line.end)
        val colon = text.indexOf(':')
        val name = (if (colon >= 0) text.substring(0, colon) else text).lowercase()
        if (colon < 0 || !isHeaderName(name)) throw MalformedMessageException("控制消息头格式错误")
        if (name in headers.values) throw MalformedMessageException("控制消息头重复")
        // 空行之后再出现的头只认固定几个，否则可能是被误当成头的正文。
        if (onlyKnownNames && name !in CONTINUATION_HEADERS) throw MalformedMessageException("空行后出现未知的头")
        headers.values[name] = trimSpaceTab(text.substring(colon + 1))
        headers.lineOf[name] = index
    }

    private fun atBlankLine(p: ByteArray, first: String, headers: HeaderTable, bodyStart: Int, firstBlank: Boolean): AtBlank {
        val values = headers.values
        val bodyLength = p.size - bodyStart
        if (firstBlank && first == OK_STATUS &&
            "content-length" !in values && "content-type" !in values && "session" !in values && "range" !in values
        ) {
            sessionRangeTail(p, bodyStart)?.let { session ->
                val cseq = cseqOf(values)
                values["session"] = session
                values["range"] = NPT_NOW
                return AtBlank.Complete(RtspMessage(first, values, EMPTY, cseq))
            }
        }
        val lengthText = values["content-length"]
            ?: return if (bodyLength == 0) AtBlank.Complete(RtspMessage(first, values, EMPTY, cseqOf(values))) else AtBlank.MoreHeaders
        val declared = decimal(lengthText, 5).toInt()
        if (declared >= 0 && (declared == bodyLength || isSetParameterFooter(p, first, values, bodyStart, declared))) {
            return AtBlank.Complete(RtspMessage(first, values, p.copyOfRange(bodyStart, bodyStart + declared), cseqOf(values)))
        }
        throw lengthMismatch(bodyLength)
    }

    /** SET_PARAMETER 的帧尾：声明的正文本身以 CRLF 结束，声明区间外正好再多一个 CRLF。 */
    private fun isSetParameterFooter(p: ByteArray, first: String, values: Map<String, String>, bodyStart: Int, declared: Int): Boolean =
        declared >= 2 && p.size - bodyStart == declared + 2 &&
            isCrlf(p, bodyStart + declared - 2) && isCrlf(p, bodyStart + declared) &&
            (values["content-type"] ?: "").lowercase() == RtspMessage.TEXT_PARAMETERS &&
            first.startsWith("SET_PARAMETER ") && requestMethod(first) == "SET_PARAMETER"

    /**
     * 空 GET_PARAMETER 保活：只有 Date 和 CSeq，最后一行之后没有空行也没有多余字节。Date 可以是空的，也可以带时间
     * （鸿蒙 3.0 的手机投屏约 30 秒后发这种，2026-11 实机）。
     */
    private fun keepAliveWithoutBlankLine(p: ByteArray, lines: Lines, first: String, headers: HeaderTable): RtspMessage? {
        val values = headers.values
        val matches = lines.tailStart == p.size && first.startsWith("GET_PARAMETER rtsp://") &&
            values.size == 2 && "date" in values && "cseq" in values &&
            printableOutsideCrlf(p) && requestMethod(first) == "GET_PARAMETER"
        return if (matches) RtspMessage(first, values, EMPTY, cseqOf(values)) else null
    }

    /** SETUP 的 200：Content-Length 恰好等于紧跟其后、也是最后一行的 Transport（含 CRLF）的长度。 */
    private fun setupReplyWithoutBlankLine(p: ByteArray, lines: Lines, first: String, headers: HeaderTable): RtspMessage? {
        val values = headers.values
        if (first != OK_STATUS || (values["content-type"] ?: "").lowercase() != RtspMessage.TEXT_PARAMETERS) return null
        val declared = decimal(values["content-length"] ?: "", 5).toInt()
        val lengthLine = headers.lineOf["content-length"] ?: return null
        val transportLine = headers.lineOf["transport"] ?: return null
        val transport = lines.items[transportLine]
        val matches = declared > 0 && transportLine == lengthLine + 1 && transportLine == lines.items.size - 1 &&
            lines.tailStart == p.size && declared == transport.next - transport.start
        return if (matches) RtspMessage(first, values, EMPTY, cseqOf(values)) else null
    }

    private fun sameContent(p: ByteArray, a: Line, b: Line): Boolean {
        if (a.end - a.start != b.end - b.start) return false
        for (k in 0 until a.end - a.start) if (p[a.start + k] != p[b.start + k]) return false
        return true
    }

    /**
     * 校验请求首行并返回方法名：`方法 SP 目标 SP RTSP/1.0`，单个空格分隔；目标是 `*`，
     * 或带非空主机部分、全为可见 ASCII 的 rtsp:// URI。URI 只校验格式，不用于连接。
     */
    fun requestMethod(firstLine: String): String {
        val firstSpace = firstLine.indexOf(' ')
        val lastSpace = firstLine.lastIndexOf(' ')
        if (firstSpace <= 0 || lastSpace <= firstSpace) throw badRequestLine()
        val method = firstLine.substring(0, firstSpace)
        val target = firstLine.substring(firstSpace + 1, lastSpace)
        if (firstLine.substring(lastSpace + 1) != "RTSP/1.0") throw badRequestLine()
        if (!method.all { it in 'A'..'Z' || it == '_' }) throw badRequestLine()
        if (target != "*" && !isRtspUri(target)) throw badRequestLine()
        return method
    }

    private fun isRtspUri(target: String): Boolean {
        if (!target.startsWith("rtsp://") || target.length == 7) return false
        if (!target.all { it in '!'..'~' }) return false
        var authorityEnd = target.length
        for (i in 7 until target.length) {
            val c = target[i]
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i
                break
            }
        }
        val authority = target.substring(7, authorityEnd)
        // 不接受方括号主机（IPv6 字面量）。
        return authority.isNotEmpty() && '[' !in authority && ']' !in authority
    }

    /** 1..10 位十进制、取值 1..2^31-1 的 CSeq。 */
    private fun cseqOf(headers: Map<String, String>): Int {
        val value = decimal(headers["cseq"] ?: "", 10)
        if (value < 1 || value > Int.MAX_VALUE) throw MalformedMessageException("控制消息缺少有效的 CSeq")
        return value.toInt()
    }

    /** 1..[maxDigits] 位 ASCII 十进制数的值，格式不对返回 -1。 */
    fun decimal(text: String, maxDigits: Int): Long {
        if (text.isEmpty() || text.length > maxDigits) return -1
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') return -1
            value = value * 10 + (c - '0')
        }
        return value
    }

    /** `session: <可见ASCII> LF range: npt=now- LF`，两个名字大小写不敏感，必须正好到结尾。 */
    private fun sessionRangeTail(p: ByteArray, start: Int): String? {
        var i = start
        if (!matchIgnoreCase(p, i, "session: ")) return null
        i += 9
        val valueStart = i
        while (i < p.size && p[i] in 0x21..0x7E) i++
        if (i == valueStart || i >= p.size || p[i] != LF) return null
        val session = ascii(p, valueStart, i)
        i++
        if (!matchIgnoreCase(p, i, "range")) return null
        i += 5
        val rest = ": $NPT_NOW\n"
        if (!matchExact(p, i, rest)) return null
        return if (i + rest.length == p.size) session else null
    }

    private fun printableOutsideCrlf(p: ByteArray): Boolean {
        var i = 0
        while (i < p.size) {
            if (p[i] == CR && i + 1 < p.size && p[i + 1] == LF) {
                i += 2
                continue
            }
            val b = p[i].toInt() and 0xFF
            if (b != 0x09 && (b < 0x20 || b > 0x7E)) return false
            i++
        }
        return true
    }

    private fun isHeaderName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun hasControlCharacter(p: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) {
            val b = p[i].toInt() and 0xFF
            if ((b < 0x20 && b != 0x09) || b == 0x7F) return true
        }
        return false
    }

    private fun lengthMismatch(bodyLength: Int) =
        MalformedMessageException("控制消息正文长度与 Content-Length 不一致（空行后剩余 $bodyLength 字节）")

    private fun badRequestLine() = MalformedMessageException("控制请求首行的方法、目标或版本格式无效")

    private const val NPT_NOW = "npt=now-"
    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A

    private const val SHOWN_BYTES = 160
    private val LONG_TOKEN = Regex("[0-9A-Za-z+/]{20,}")

    private fun isCrlf(p: ByteArray, at: Int) = at >= 0 && at + 1 < p.size && p[at] == CR && p[at + 1] == LF

    private fun isAscii(p: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (p[i] < 0) return false
        return true
    }

    private fun ascii(p: ByteArray, from: Int, to: Int) = String(p, from, to - from, Charsets.US_ASCII)

    private fun matchExact(p: ByteArray, at: Int, text: String): Boolean {
        if (at + text.length > p.size) return false
        for (k in text.indices) if (p[at + k].toInt() != text[k].code) return false
        return true
    }

    private fun matchIgnoreCase(p: ByteArray, at: Int, text: String): Boolean {
        if (at + text.length > p.size) return false
        for (k in text.indices) {
            var b = p[at + k].toInt() and 0xFF
            if (b in 'A'.code..'Z'.code) b += 32
            if (b != text[k].code) return false
        }
        return true
    }

    private fun trimSpaceTab(s: String): String {
        var from = 0
        var to = s.length
        while (from < to && (s[from] == ' ' || s[from] == '\t')) from++
        while (to > from && (s[to - 1] == ' ' || s[to - 1] == '\t')) to--
        return s.substring(from, to)
    }
}
