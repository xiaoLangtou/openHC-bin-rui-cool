package io.github.gcbond.openhc.core.dmsdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 控制连接上的载荷是不是文字消息。数据都是合成的。 */
class TextPayloadTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { acc, p ->
        acc + when (p) {
            is String -> p.toByteArray(Charsets.US_ASCII)
            is ByteArray -> p
            else -> error("不支持的类型")
        }
    }

    @Test
    fun requestsAndResponsesAreText() {
        assertTrue(DmsdpMessage.looksLikeText(bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n")))
        assertTrue(DmsdpMessage.looksLikeText(bytes("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 4\r\n\r\nbody")))
        assertTrue(DmsdpMessage.looksLikeText(bytes("DMSDP/1.0 200 OK\r\nCSeq: 3\r\n\r\n")))
    }

    /** 少了头部结束空行、只用 LF 换行、只有首行的文字消息：认得出，也拆得开。 */
    @Test
    fun textWithoutStandardBlankLineIsParsed() {
        val noBlank = bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n")
        assertTrue(DmsdpMessage.looksLikeText(noBlank))
        assertEquals("3", DmsdpMessage.parse(noBlank).header("CSeq"))
        val lf = DmsdpMessage.parse(bytes("SET_PARAMETER * DMSDP/1.0\nCSeq: 4\n\nbody"))
        assertEquals("4", lf.header("CSeq"))
        assertEquals("body", String(lf.body, Charsets.US_ASCII))
        val only = bytes("DMSDP/1.0 200 OK")
        assertTrue(DmsdpMessage.looksLikeText(only))
        assertEquals("DMSDP/1.0 200 OK", DmsdpMessage.parse(only).startLine)
    }

    /** 二进制块碰巧以大写字母开头、中间含空行：不当文字。 */
    @Test
    fun binaryBlocksStartingWithCapitalsAreNotText() {
        assertFalse(DmsdpMessage.looksLikeText(bytes("R", byteArrayOf(0, 1, 2), "\r\n\r\n", byteArrayOf(9))))
        assertFalse(DmsdpMessage.looksLikeText(bytes("VX", byteArrayOf(-1, 7), " a\r\n\r\n")))
        assertFalse(DmsdpMessage.looksLikeText(bytes("GABC\r\n\r\n")), "首行没有空格")
        assertFalse(DmsdpMessage.looksLikeText(bytes("Get x DMSDP/1.0\r\n\r\n")), "方法名有小写")
        assertFalse(DmsdpMessage.looksLikeText(bytes("SET_PARAMETER x\r\nCSeq: 3\r\n\r\n")), "首行没有版本")
        assertFalse(DmsdpMessage.looksLikeText(bytes("#", ByteArray(17), "\r\n\r\n")))
        assertFalse(DmsdpMessage.looksLikeText(ByteArray(0)))
    }
}
