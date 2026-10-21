package dev.appwall.core.settings

import dev.appwall.core.firewall.NewAppMode
import dev.appwall.core.state.ChangeSignal
import dev.appwall.core.state.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun missingKeysGetTheDefaults() {
        assertEquals(FirewallPolicy(enabled = false, blocked = emptySet(), newAppMode = NewAppMode.ASK, startOnBoot = true), decodeFirewallPolicy(emptyMap()))
        assertEquals(LogSettings(loggingEnabled = true, retentionDays = 7), decodeLogSettings(emptyMap()))
        assertEquals(ScreenPrefs(showSystemApps = false), decodeScreenPrefs(emptyMap()))
    }

    @Test
    fun storedValuesAreRead() {
        val values = mapOf(
            "enabled" to true, "blocked" to setOf("a", "b"), "new_app_mode" to "BLOCK", "start_on_boot" to false,
            "logging" to false, "log_retention_days" to 30, "show_system_apps" to true,
        )
        assertEquals(FirewallPolicy(true, setOf("a", "b"), NewAppMode.BLOCK, false), decodeFirewallPolicy(values))
        assertEquals(LogSettings(false, 30), decodeLogSettings(values))
        assertEquals(ScreenPrefs(true), decodeScreenPrefs(values))
    }

    @Test
    fun unknownOrMistypedValuesGetTheDefaults() {
        val values = mapOf("enabled" to "yes", "blocked" to "a", "new_app_mode" to "SOMETIMES", "log_retention_days" to "7")
        assertEquals(FirewallPolicy(), decodeFirewallPolicy(values))
        assertEquals(LogSettings(), decodeLogSettings(values))
    }

    @Test
    fun eachWriteSavesOnceAndSignalsItsKindOnce() {
        val store = MapStore(mutableMapOf("blocked" to setOf("a")))
        val scheduler = FakeScheduler()
        val settings = StoredSettings(store) { ChangeSignal(scheduler) }
        var policy = 0
        var log = 0
        var screen = 0
        settings.policyChanged.subscribe { policy++ }
        settings.logSettingsChanged.subscribe { log++ }
        settings.screenPrefsChanged.subscribe { screen++ }

        settings.setBlocked(listOf("b", "c"), true)
        scheduler.advance()
        assertEquals(1, store.writes)
        assertEquals(Triple(1, 0, 0), Triple(policy, log, screen))
        assertEquals(setOf("a", "b", "c"), settings.policy.blocked)
        assertEquals(setOf("a", "b", "c"), store.values["blocked"])

        settings.setBlocked(listOf("a", "c"), false)
        settings.setLogRetentionDays(30)
        settings.setShowSystemApps(true)
        scheduler.advance()
        assertEquals(4, store.writes)
        assertEquals(Triple(2, 1, 1), Triple(policy, log, screen))
        assertTrue(settings.policy.isBlocked("b"))
        assertFalse(settings.policy.isBlocked("a"))
        assertEquals(30, settings.logSettings.retentionDays)
        assertTrue(settings.screenPrefs.showSystemApps)
    }

    @Test
    fun aNewInstanceReadsWhatWasWritten() {
        val store = MapStore(mutableMapOf())
        StoredSettings(store) { ChangeSignal(FakeScheduler()) }.apply {
            setEnabled(true)
            setNewAppMode(NewAppMode.ALLOW)
            setLoggingEnabled(false)
        }
        val reread = StoredSettings(store) { ChangeSignal(FakeScheduler()) }
        assertTrue(reread.policy.enabled)
        assertEquals(NewAppMode.ALLOW, reread.policy.newAppMode)
        assertFalse(reread.logSettings.loggingEnabled)
    }

    private class MapStore(val values: MutableMap<String, Any?>) : KeyValueStore {
        var writes = 0
        override fun all(): Map<String, Any?> = HashMap(values)
        override fun write(values: Map<String, Any>) {
            writes++
            this.values += values
        }
    }
}
