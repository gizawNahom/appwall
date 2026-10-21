package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry
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
            val state = settingsState(FirewallPolicy(), LogSettings(), alwaysOn, otherVpn)
            assertEquals("$alwaysOn, other VPN $otherVpn", expected, state.alwaysOnText to state.alwaysOnLevel)
        }
    }

    @Test
    fun settingsRowsShowTheSavedValues() {
        val state = settingsState(
            FirewallPolicy(startOnBoot = false, newAppMode = NewAppMode.BLOCK), LogSettings(loggingEnabled = false, retentionDays = 30),
            AlwaysOn.ON, false,
        )
        assertEquals(listOf(false, NewAppMode.BLOCK, false, 30), listOf(state.startOnBoot, state.newAppMode, state.loggingEnabled, state.retentionDays))
    }

    @Test
    fun showSystemAppsLivesInSettings() {
        assertEquals(false, settingsState(FirewallPolicy(), LogSettings(), AlwaysOn.ON, false).showSystemApps)
        assertEquals(true, settingsState(FirewallPolicy(), LogSettings(), AlwaysOn.ON, false, ScreenPrefs(showSystemApps = true)).showSystemApps)
    }

    // Picker

    private val now = 100 * DAY
    private val maps = app("Maps", "com.maps", installedAt = now - 2 * DAY)
    private val game = app("Game", "com.game", installedAt = now - DAY)
    private val old = app("Old", "com.old", installedAt = now - 30 * DAY)
    private val chrome = app("Chrome", "com.android.chrome", system = true)
    private val service = app("Telephony", "com.android.phone.svc", system = true, service = true)
    private val all = listOf(maps, game, old, chrome, service)

    private fun picker(filter: PickerFilter = PickerFilter.APPS, query: String = "", blocked: Set<String> = emptySet(), picked: Set<String> = emptySet()) =
        pickerState(all, blocked, filter, query, picked, now)

    @Test
    fun filters() {
        assertEquals(listOf(maps, game, old, chrome), picker().shown) // services are hidden by default
        assertEquals(listOf(game, maps), picker(PickerFilter.RECENT).shown) // newest first, not system, last week only
        assertEquals(listOf(chrome, service), picker(PickerFilter.SYSTEM).shown)
    }

    @Test
    fun onlyAllowedAppsAreOffered() {
        val state = picker(blocked = setOf("com.maps"))
        assertEquals(listOf(game, old, chrome), state.shown)
        assertEquals(4, state.allowedTotal)
    }

    @Test
    fun searchMatchesNameOrPackageIgnoringCaseAndSpaces() {
        assertEquals(listOf(chrome), picker(query = " CHROME ").shown)
        assertEquals(listOf(maps), picker(query = "com.ma").shown)
    }

    @Test
    fun whyTheListIsEmpty() {
        assertEquals(PickerEmpty.NONE, picker().empty)
        assertEquals(PickerEmpty.NO_MATCH, picker(query = "zzz").empty)
        assertEquals(PickerEmpty.NO_RECENT, picker(PickerFilter.RECENT, blocked = setOf("com.maps", "com.game")).empty)
        assertEquals(PickerEmpty.NOTHING, pickerState(listOf(service), emptySet(), PickerFilter.APPS, "", emptySet(), now).empty)
        // Everything blocked: no allowed apps, so no "empty" message either.
        assertEquals(PickerEmpty.NONE, picker(blocked = all.map { it.packageName }.toSet()).empty)
    }

    @Test
    fun loadingAndPickedCount() {
        val loading = pickerState(null, emptySet(), PickerFilter.APPS, "", setOf("a", "b"), now)
        assertEquals(null, loading.shown)
        assertEquals(2, loading.pickedCount)
        assertEquals(1, picker(picked = setOf("com.maps")).pickedCount)
    }

    private fun app(label: String, pkg: String, system: Boolean = false, service: Boolean = false, installedAt: Long = 0) =
        AppEntry(label, pkg, system, service, installedAt)

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
