package io.github.gcbond.openhc.platform.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** MAC 地址的整理、过滤和打码。测试数据都是合成值。 */
class MacTextTest {

    @Test
    fun macTextIsNormalizedAndBogusValuesAreRejected() {
        assertEquals("0a:1b:2c:3d:4e:5f", MacText.normalize(" 0A:1B:2C:3D:4E:5F "))
        assertEquals("0a:1b:2c:3d:4e:5f", MacText.normalize("0a-1b-2c-3d-4e-5f"))
        assertNull(MacText.normalize("00:00:00:00:00:00"))
        assertNull(MacText.normalize("ff:ff:ff:ff:ff:ff"))
        assertNull(MacText.normalize(MacText.PLACEHOLDER), "系统给的占位值不算真实地址")
        assertNull(MacText.normalize("01:00:5e:00:00:01"), "组播地址")
        assertNull(MacText.normalize("0a:1b:2c:3d:4e"))
        assertNull(MacText.normalize(""))
        assertNull(MacText.normalize(null))
    }

    @Test
    fun hardwareAddressBytesAreFilteredTheSameWay() {
        assertEquals("0a:1b:2c:3d:4e:5f", MacText.of(byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F)))
        assertNull(MacText.of(byteArrayOf(0x02, 0, 0, 0, 0, 0)), "占位值")
        assertNull(MacText.of(byteArrayOf(1, 2, 3)))
        assertNull(MacText.of(null))
    }

    /** EUI-64：fe80:: + MAC 前 3 字节（第一字节第 2 位取反）+ ff:fe + MAC 后 3 字节。地址都是编的。 */
    @Test
    fun macFromAnEui64LinkLocalAddress() {
        fun v6(vararg b: Int) = ByteArray(16) { b[it].toByte() }
        val eui = v6(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0x08, 0x1b, 0x2c, 0xff, 0xfe, 0x3d, 0x4e, 0x5f)
        assertEquals("0a:1b:2c:3d:4e:5f", MacText.fromLinkLocal(eui))
        // 本地管理位本来是 1 的（随机 MAC），地址里那一位是 0。
        assertEquals("da:1b:2c:3d:4e:5f", MacText.fromLinkLocal(eui.copyOf().also { it[8] = 0xd8.toByte() }))
        assertNull(MacText.fromLinkLocal(eui.copyOf().also { it[12] = 0x12 }), "中间不是 ff:fe：随机生成的，推不出")
        assertNull(MacText.fromLinkLocal(eui.copyOf().also { it[0] = 0x20 }), "不是链路本地地址")
        assertNull(MacText.fromLinkLocal(eui.copyOf().also { it[5] = 1 }), "前缀不是 fe80::/64")
        assertNull(MacText.fromLinkLocal(eui.copyOf(4)))
        assertNull(MacText.fromLinkLocal(null))
        assertNull(MacText.fromLinkLocal(v6(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0x03, 0, 0, 0xff, 0xfe, 0, 0, 0)), "推出组播地址不算")
    }

    @Test
    fun logsNeverShowWholeMacs() {
        assertEquals("0a:**:**:**:4e:5f", MacText.mask("0a:1b:2c:3d:4e:5f"))
        assertEquals("0A:**:**:**:4E:5F", MacText.mask("0A-1B-2C-3D-4E-5F"))
        assertEquals(MacText.PLACEHOLDER, MacText.mask(MacText.PLACEHOLDER))
        assertEquals("**", MacText.mask("garbage"))
        assertEquals("(无)", MacText.mask(null))
    }
}
