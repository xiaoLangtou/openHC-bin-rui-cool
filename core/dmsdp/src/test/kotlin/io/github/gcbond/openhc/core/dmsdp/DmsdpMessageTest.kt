package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * 控制消息解析：常规消息、缺头部结束空行的兼容（鸿蒙 NEXT HiCar 15.2.x 实测会发，v0.2.7 起
 * 兼容范围扩大：声明了 Content-Length 的纯头部也按纯头部收，正文位置只有截断时才报错）。
 */
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
    fun headersOnlyWithDeclaredBodyIsTreatedAsPureHeader() {
        // v0.2.7 兼容超集：声明了 Content-Length 但没有空行、整条以换行结尾——按纯头部收（正文 0 字节），
        // 不再为此拆掉整个会话（鸿蒙 NEXT 偶发）。
        val m = DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nContent-Length: 4\r\n".toByteArray())
        assertEquals("SET_PARAMETER * DMSDP/1.0", m.startLine)
        assertEquals("4", m.header("Content-Length"))
        assertEquals(0, m.bodySize)
    }

    @Test
    fun truncatedMessageIsRejected() {
        // 有换行但最后不是换行、也没有空行 = 正文截断：报错（会话保不住，正文位置不明）。
        assertThrows(MalformedMessageException::class.java) {
            DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\nX: y\r\nbody-without-end".toByteArray())
        }
        // 完全没有换行的整条也算纯头部（首行就是方法行）——不报错。
        val whole = DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0".toByteArray())
        assertEquals("SET_PARAMETER * DMSDP/1.0", whole.startLine)
        assertEquals(0, whole.bodySize)
        // 防止上面 fail() 分支被误删。
        try {
            DmsdpMessage.parse("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\nX: y\r\nbody-without-end".toByteArray())
            fail("截断消息应该报错")
        } catch (_: MalformedMessageException) {
        }
    }
}
