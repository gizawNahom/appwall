package dev.appwall.core.screens

import dev.appwall.core.firewall.NewAppMode
import dev.appwall.core.settings.FirewallPolicy
import dev.appwall.core.settings.LogSettings
import dev.appwall.core.settings.ScreenPrefs
import dev.appwall.core.state.AlwaysOn
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStatesTest {

    // Settings

    @Test
    fun alwaysOnRow() {
        val table = listOf(
            Triple(AlwaysOn.ON, false, AlwaysOnText.ON to StatusLevel.ON),
            Triple(AlwaysOn.OFF, false, AlwaysOnText.OFF to StatusLevel.NEUTRAL),
            Triple(AlwaysOn.LOCKDOWN, false, AlwaysOnText.LOCKDOWN to StatusLevel.PROBLEM),
            Triple(AlwaysOn.UNKNOWN, false, AlwaysOnText.UNKNOWN to StatusLevel.NEUTRAL),
            Triple(AlwaysOn.UNKNOWN, true, AlwaysOnText.OTHER_VPN to StatusLevel.PROBLEM),
        )
        for ((alwaysOn, otherVpn, expected) in table) {
            val state = settingsState(FirewallPolicy(), LogSettings(), alwaysOn, otherVpn, ScreenPrefs())
            assertEquals("$alwaysOn, other VPN $otherVpn", expected, state.alwaysOnText to state.alwaysOnLevel)
        }
        // Every way the row can read is covered.
        assertEquals(AlwaysOnText.entries.toSet(), table.map { it.third.first }.toSet())
        assertEquals(StatusLevel.entries.toSet(), table.map { it.third.second }.toSet())
    }

    @Test
    fun settingsRowsShowTheSavedValues() {
        val state = settingsState(
            FirewallPolicy(startOnBoot = false, newAppMode = NewAppMode.BLOCK), LogSettings(loggingEnabled = false, retentionDays = 30),
            AlwaysOn.ON, false, ScreenPrefs(),
        )
        assertEquals(listOf(false, NewAppMode.BLOCK, false, 30), listOf(state.startOnBoot, state.newAppMode, state.loggingEnabled, state.retentionDays))
        val defaults = settingsState(FirewallPolicy(), LogSettings(), AlwaysOn.ON, false, ScreenPrefs())
        assertEquals(listOf(true, NewAppMode.ASK, true, 7), listOf(defaults.startOnBoot, defaults.newAppMode, defaults.loggingEnabled, defaults.retentionDays))
    }

    @Test
    fun showSystemAppsLivesInSettings() {
        assertEquals(false, settingsState(FirewallPolicy(), LogSettings(), AlwaysOn.ON, false, ScreenPrefs()).showSystemApps)
        assertEquals(true, settingsState(FirewallPolicy(), LogSettings(), AlwaysOn.ON, false, ScreenPrefs(showSystemApps = true)).showSystemApps)
    }
}
