package io.github.gcbond.openhc.settings

import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.wireless.WirelessPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectReminderTest {
    private val base = mapOf(
        PendingSetting.CLARITY to "standard",
        PendingSetting.UI_SCALE to "100",
        PendingSetting.WIRELESS_MODE to "direct",
        PendingSetting.SCAN_PAUSE to "false",
    )

    private fun after(vararg changes: Pair<PendingSetting, String>) = base + changes

    @Test
    fun nothingToSayWhenNoConnectionIsRunning() {
        val changed = ReconnectReminder.changed(base, after(PendingSetting.CLARITY to "high"), wiredActive = false, wirelessActive = false)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun listsChangedItemsInPageOrder() {
        val changed = ReconnectReminder.changed(
            base,
            after(PendingSetting.UI_SCALE to "85", PendingSetting.CLARITY to "high"),
            wiredActive = true,
            wirelessActive = false,
        )
        assertEquals(listOf(PendingSetting.UI_SCALE, PendingSetting.CLARITY), changed)
    }

    @Test
    fun changedBackToTheSameValueIsNotAChange() {
        assertTrue(ReconnectReminder.changed(base, after(), wiredActive = true, wirelessActive = true).isEmpty())
    }

    @Test
    fun wirelessOnlyItemsMatterOnlyForAWirelessConnection() {
        val modeChanged = after(PendingSetting.WIRELESS_MODE to "hotspot")
        assertTrue(ReconnectReminder.changed(base, modeChanged, wiredActive = true, wirelessActive = false).isEmpty())
        assertEquals(
            listOf(PendingSetting.WIRELESS_MODE),
            ReconnectReminder.changed(base, modeChanged, wiredActive = false, wirelessActive = true),
        )
    }

    @Test
    fun turningScanPauseOffIsImmediateButTurningItOnWaits() {
        val on = after(PendingSetting.SCAN_PAUSE to "true")
        assertEquals(listOf(PendingSetting.SCAN_PAUSE), ReconnectReminder.changed(base, on, wiredActive = false, wirelessActive = true))
        assertTrue(ReconnectReminder.changed(on, base, wiredActive = false, wirelessActive = true).isEmpty())
    }

    @Test
    fun whichPhasesHaveAlreadyReadTheSettings() {
        for (phase in listOf(Phase.WAITING_FOR_PHONE, Phase.CONNECTING, Phase.PAIRING, Phase.PROJECTING)) {
            assertTrue(ReconnectReminder.wiredUsesOldSettings(phase), "$phase")
        }
        for (phase in listOf(Phase.IDLE, Phase.STOPPING, Phase.REPLUG_REQUIRED)) {
            assertFalse(ReconnectReminder.wiredUsesOldSettings(phase), "$phase")
        }
        assertTrue(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.WAITING_FOR_SCAN))
        assertTrue(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.PROJECTING))
        assertFalse(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.IDLE))
        assertFalse(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.STOPPING))
    }
}
