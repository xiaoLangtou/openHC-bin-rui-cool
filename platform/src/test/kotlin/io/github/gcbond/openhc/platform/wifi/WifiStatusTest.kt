package io.github.gcbond.openhc.platform.wifi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiStatusTest {
    @Test
    fun onlyAnEnabledClientThatIsNotJoiningCountsAsSearching() {
        for (state in listOf("DISCONNECTED", "INACTIVE", "SCANNING", "DORMANT")) {
            assertTrue(WifiStatus.searching(true, state), state)
            assertFalse(WifiStatus.searching(false, state), "WiFi 关着：$state")
        }
        for (state in listOf("COMPLETED", "ASSOCIATING", "ASSOCIATED", "AUTHENTICATING", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE")) {
            assertFalse(WifiStatus.searching(true, state), state)
        }
        assertFalse(WifiStatus.searching(true, null))
        assertFalse(WifiStatus.searching(true, "INTERFACE_DISABLED"))
    }
}
