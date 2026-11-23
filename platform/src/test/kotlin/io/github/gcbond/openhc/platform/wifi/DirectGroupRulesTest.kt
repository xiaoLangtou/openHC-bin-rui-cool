package io.github.gcbond.openhc.platform.wifi

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectGroupRulesTest {

    @Test
    fun withoutStationFiveGhzComesFirst() {
        // 免点连接时手机热点关着、车机 WiFi 多半没连：先 5 GHz 的 149、36，再 2.4 GHz，最后交给系统。
        val expected = listOf(5745, 5180, 2437, 2412, 2462, null)
        assertEquals(expected, DirectGroupRules.plan(null))
        assertEquals(expected, DirectGroupRules.plan(0))
    }

    @Test
    fun stationOnFiveGhzKeepsTheSameChannelOrFallsBackToTwoPointFour() {
        // 车机连着手机热点（5745）：同频建，不在别的 5 GHz 信道建。
        assertEquals(listOf(5745, 2437, 2412, 2462, null), DirectGroupRules.plan(5745))
        assertEquals(listOf(5200, 2437, 2412, 2462, null), DirectGroupRules.plan(5200))
        // 需要雷达避让的信道（5500）不跟，也不去别的 5 GHz 信道，直接 2.4 GHz。
        assertEquals(listOf(2437, 2412, 2462, null), DirectGroupRules.plan(5500))
    }

    @Test
    fun stationOnTwoPointFourStillTriesFiveGhzFirst() {
        assertEquals(listOf(5745, 5180, 2412, 2437, 2462, null), DirectGroupRules.plan(2412))
        assertEquals(listOf(5745, 5180, 2437, 2412, 2462, null), DirectGroupRules.plan(2437))
        // 13 信道（2472）不在明确信道里，不对齐。
        assertEquals(listOf(5745, 5180, 2437, 2412, 2462, null), DirectGroupRules.plan(2472))
    }

    @Test
    fun nameAndPassphraseFollowTheRules() {
        val random = Random(42)
        repeat(50) {
            val name = DirectGroupRules.networkName(random)
            assertTrue(Regex("DIRECT-[A-Za-z0-9]{2}-OpenHC").matches(name), name)
            assertTrue(name.toByteArray().size <= 32)
            val pass = DirectGroupRules.passphrase(random)
            assertEquals(16, pass.length)
            assertTrue(pass.all { it.isLetterOrDigit() && it.code < 128 }, pass)
        }
    }

    @Test
    fun ownerMacPrefersTheInterface() {
        val mac = byteArrayOf(0x0a, 0x11, 0x22, 0x33, 0x44, 0x55)
        assertEquals("0a:11:22:33:44:55" to DirectMacSource.INTERFACE, DirectGroupRules.pickOwnerMac(mac, "1a:2b:3c:4d:5e:6f"))
        assertEquals("1a:2b:3c:4d:5e:6f" to DirectMacSource.GROUP_OWNER, DirectGroupRules.pickOwnerMac(null, "1A:2B:3C:4D:5E:6F"))
        assertEquals("1a:2b:3c:4d:5e:6f" to DirectMacSource.GROUP_OWNER,
            DirectGroupRules.pickOwnerMac(byteArrayOf(0x02, 0, 0, 0, 0, 0), "1a:2b:3c:4d:5e:6f"))
        assertEquals(MacText.PLACEHOLDER to DirectMacSource.PLACEHOLDER, DirectGroupRules.pickOwnerMac(null, "02:00:00:00:00:00"))
    }
}
