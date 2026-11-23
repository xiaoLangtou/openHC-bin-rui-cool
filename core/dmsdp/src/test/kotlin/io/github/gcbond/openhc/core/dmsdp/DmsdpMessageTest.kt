package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 控制消息解析：常规消息、缺头部结束空行的兼容（鸿蒙 NEXT HiCar 实测会发）、截断消息照旧报错。 */
class DmsdpMessageTest {
    @Test
    fun regularMessageWithBlankLine() {
        val m = DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\n\r\nbody".toByteArray())
        assertEquals("SET_PARAMETER * DMSDP/1.0", m.startLine)
        assertEquals("1", m.header("CSeq"))
        assertEquals("body", String(m.body))
    }

    @Test
    fun headersOnlyWithoutBlankLineIsAccepted() {
        // 鸿蒙 NEXT HiCar 15.2.x 会发没有正文、也没有头部结束空行的控制消息。
        val m = DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 2\r\nX-Foo: bar\r\n".toByteArray())
        assertEquals("SET_PARAMETER * DMSDP/1.0", m.startLine)
        assertEquals("2", m.header("CSeq"))
        assertEquals("bar", m.header("X-Foo"))
        assertEquals(0, m.bodySize)
    }

    @Test
    fun headersOnlyWithDeclaredBodyIsRejected() {
        // 声明了非 0 Content-Length 却没有空行：正文位置不明，不能按纯头部吃掉，照旧报错。
        try {
            DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nContent-Length: 4\r\n".toByteArray())
            fail("应该报错")
        } catch (e: MalformedMessageException) {
            assertTrue(e.message!!.contains("缺少头部结束的空行"))
        }
    }

    @Test
    fun truncatedMessageIsRejectedWithPreview() {
        try {
            DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3".toByteArray())
            fail("应该报错")
        } catch (e: MalformedMessageException) {
            assertTrue(e.message!!.contains("缺少头部结束的空行"))
            assertTrue(e.message!!.contains("53 45 54")) // "SET" 的十六进制预览
        }
    }
}
