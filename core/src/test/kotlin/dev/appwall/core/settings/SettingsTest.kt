package dev.appwall.core.settings

import dev.appwall.core.firewall.NewAppMode
import dev.appwall.core.health.ProbeResult
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
    fun aBlockedSetKeepsOnlyItsPackageNames() {
        assertEquals(setOf("a"), decodeFirewallPolicy(mapOf("blocked" to setOf("a", 1))).blocked)
        assertEquals(emptySet<String>(), decodeFirewallPolicy(mapOf("blocked" to emptySet<String>())).blocked)
    }

    @Test
    fun eachKeyIsCheckedForItsOwnType() {
        // key, a value it may hold, values it may not
        val table = listOf(
            Triple("enabled", true, listOf("yes", 1)),
            Triple("blocked", setOf("a"), listOf("a", setOf("a", 1), listOf("a"))),
            Triple("new_app_mode", "ALLOW", listOf("SOMETIMES", "ask", 1)),
            Triple("start_on_boot", false, listOf("no", 0)),
            Triple("logging", true, listOf("yes", 1)),
            Triple("log_retention_days", 30, listOf("30", 30L)),
            Triple("show_system_apps", false, listOf("no", 0)),
        )
        for ((key, good, bads) in table) {
            assertEquals("$key = $good", emptyList<String>(), settingsProblems(mapOf(key to good)))
            for (bad in bads) assertEquals("$key = $bad", listOf(key), settingsProblems(mapOf(key to bad)).map { it.substringBefore(" ") })
        }
        assertEquals(listOf("log_retention_days is String"), settingsProblems(mapOf("log_retention_days" to "30"))) // names what's there
        assertEquals(emptyList<String>(), settingsProblems(mapOf("blocked" to emptySet<String>()))) // nothing blocked is fine
    }

    @Test
    fun theStrictCheckFindsWhatLenientDecodingHides() {
        assertEquals(emptyList<String>(), settingsProblems(emptyMap()))
        assertEquals(emptyList<String>(), settingsProblems(mapOf("blocked" to setOf("a"), "new_app_mode" to "BLOCK", "log_retention_days" to 7, "timers" to setOf<String>())))
        val problems = settingsProblems(mapOf("enabled" to "yes", "blocked" to "a", "new_app_mode" to "SOMETIMES", "log_retention_days" to "7"))
        assertEquals(listOf("enabled", "blocked", "new_app_mode", "log_retention_days"), problems.map { it.substringBefore(" ") })
        val settings = StoredSettings(MapStore(mutableMapOf("blocked" to "a"))) { ChangeSignal(FakeScheduler()) }
        assertTrue(settings.probe() is ProbeResult.Failed)
        assertEquals(ProbeResult.Ok, StoredSettings(MapStore(mutableMapOf())) { ChangeSignal(FakeScheduler()) }.probe())
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
            setStartOnBoot(false)
            setLoggingEnabled(false)
        }
        val reread = StoredSettings(store) { ChangeSignal(FakeScheduler()) }
        assertTrue(reread.policy.enabled)
        assertEquals(NewAppMode.ALLOW, reread.policy.newAppMode)
        assertFalse(reread.policy.startOnBoot)
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
