package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * 控制连接上的一条文本消息（RTSP 风格）：首行、按原顺序的头部、正文。
 *
 * 请求首行形如 `SET_PARAMETER * DMSDP/1.0`，响应首行形如 `DMSDP/1.0 200 OK`。行尾都是 CRLF，
 * 头部之后一个空行，再接正文。正文长度以控制帧长度为准；`Content-Length` 原样留在 [headers] 里，
 * 由具体消息的处理方校验。
 */
class DmsdpMessage(
    val startLine: String,
    /** 头部，保持收到的顺序；名称保留原大小写，名称和值都已去掉首尾空白。可能有重名。 */
    val headers: List<Pair<String, String>>,
    body: ByteArray,
) {
    private val bodyBytes = body.copyOf()

    val body: ByteArray get() = bodyBytes.copyOf()

    val bodySize: Int get() = bodyBytes.size

    /** 首行第一个词是 `DMSDP/1.0` 即为响应。 */
    val isResponse: Boolean get() = firstToken == VERSION

    /** 请求的方法名；响应为 null。 */
    val method: String? get() = if (isResponse) null else firstToken

    /** 响应的状态码；请求或状态码不是数字时为 null。 */
    val statusCode: Int?
        get() {
            if (!isResponse) return null
            val code = startLine.split(' ').getOrNull(1) ?: return null
            return if (code.length == 3 && isAsciiDecimal(code)) code.toInt() else null
        }

    /** 按名称取第一个头部的值，名称不区分大小写。 */
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private val firstToken: String get() = startLine.substringBefore(' ')

    override fun toString() = "DmsdpMessage($startLine, ${headers.size} 个头部, 正文 ${bodyBytes.size} 字节)"

    internal companion object {
        const val VERSION = "DMSDP/1.0"
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        /**
         * 拆一条文本消息：头部区必须是 ASCII、以第一个 CRLFCRLF 结束，每个头部行都要有冒号。
         * 这里不判断方法、版本、重名和 Content-Length。
         */
        fun parse(payload: ByteArray): DmsdpMessage {
            val headerEnd = indexOfBlankLine(payload)
            if (headerEnd < 0) {
                // 兼容：部分手机（鸿蒙 NEXT 的 HiCar 15.2.x 实测）偶尔发没有正文、也没有头部结束空行的控制消息。
                // 消息以 CRLF 结尾、声明正文为 0 时，按「纯头部」解析，不再为此拆掉整个会话。
                // 声明了正文长度、没以 CRLF 结尾（截断）或头部行缺冒号时，照旧报错（附原始字节预览便于定位）。
                val fallback = headersOnlyEnd(payload)
                if (fallback >= 0 && !declaresNonEmptyBody(payload, fallback)) {
                    val lines = String(payload, 0, fallback, Charsets.US_ASCII).split("\r\n")
                    val headers = ArrayList<Pair<String, String>>(lines.size - 1)
                    for (k in 1 until lines.size) {
                        val line = lines[k]
                        val colon = line.indexOf(':')
                        if (colon < 0) throw MalformedMessageException("控制消息头部第 $k 行缺少冒号（载荷 ${preview(payload)}）")
                        headers += line.substring(0, colon).trimControl() to line.substring(colon + 1).trimControl()
                    }
                    return DmsdpMessage(lines[0], headers, ByteArray(0))
                }
                throw MalformedMessageException("控制消息缺少头部结束的空行（载荷 ${preview(payload)}）")
            }
            for (i in 0 until headerEnd) {
                if (payload[i] < 0) throw MalformedMessageException("控制消息头部含非 ASCII 字节")
            }
            val lines = String(payload, 0, headerEnd, Charsets.US_ASCII).split("\r\n")
            val headers = ArrayList<Pair<String, String>>(lines.size - 1)
            for (k in 1 until lines.size) {
                val line = lines[k]
                val colon = line.indexOf(':')
                if (colon < 0) throw MalformedMessageException("控制消息头部第 $k 行缺少冒号（载荷 ${preview(payload)}）")
                headers += line.substring(0, colon).trimControl() to line.substring(colon + 1).trimControl()
            }
            return DmsdpMessage(lines[0], headers, payload.copyOfRange(headerEnd + 4, payload.size))
        }

        /**
         * 「纯头部」消息的判定：以 CRLF 结尾（没有正文，也就没有头部结束空行）。
         * 返回头部区的长度（不含结尾 CRLF），不满足返回 -1。
         */
        private fun headersOnlyEnd(data: ByteArray): Int {
            if (data.size < 2 || data[data.size - 2] != CR || data[data.size - 1] != LF) return -1
            for (i in 0 until data.size - 2) {
                if (data[i] < 0) return -1
            }
            return data.size - 2
        }

        /** 头部里声明了非 0 的 Content-Length 时不能按纯头部处理（正文可能被当成头部吃掉）。 */
        private fun declaresNonEmptyBody(data: ByteArray, headerLength: Int): Boolean {
            val lines = String(data, 0, headerLength, Charsets.US_ASCII).split("\r\n")
            val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim() ?: return false
            return isAsciiDecimal(length) && length.toInt() > 0
        }

        /** 原始载荷的十六进制预览（最多 64 字节），用于把无法解析的消息记进日志。 */
        private fun preview(data: ByteArray): String {
            val sb = StringBuilder(data.size.coerceAtMost(64) * 3 + 1)
            for (i in 0 until data.size.coerceAtMost(64)) {
                if (i > 0) sb.append(' ')
                sb.append(String.format("%02X", data[i]))
            }
            if (data.size > 64) sb.append(" …")
            return sb.toString()
        }

        /** 编码一条消息：首行、头部各占一行（CRLF），再一个空行，再正文。 */
        fun encode(startLine: String, headers: List<Pair<String, String>>, body: ByteArray = ByteArray(0)): ByteArray {
            val text = StringBuilder(startLine).append("\r\n")
            for ((name, value) in headers) text.append(name).append(": ").append(value).append("\r\n")
            text.append("\r\n")
            val head = text.toString().toByteArray(Charsets.UTF_8)
            return if (body.isEmpty()) head else head + body
        }

        private fun indexOfBlankLine(data: ByteArray): Int {
            for (i in 0..data.size - 4) {
                if (data[i] == CR && data[i + 1] == LF && data[i + 2] == CR && data[i + 3] == LF) return i
            }
            return -1
        }

        /** 去掉首尾空白和控制字符（与 Java String.trim 相同的范围）。 */
        private fun String.trimControl(): String = trim { it <= ' ' }
    }
}

/** 名称不区分大小写时是否有重名头部。 */
internal fun DmsdpMessage.hasDuplicateHeaders(): Boolean {
    val seen = HashSet<String>()
    for ((name, _) in headers) {
        if (!seen.add(name.lowercase())) return true
    }
    return false
}

/** Content-Length（缺省按 0）与实际正文长度一致。 */
internal fun DmsdpMessage.contentLengthMatches(): Boolean {
    val declared = header("Content-Length") ?: return bodySize == 0
    if (!isAsciiDecimal(declared) || declared.length > 9) return false
    return declared.toInt() == bodySize
}
