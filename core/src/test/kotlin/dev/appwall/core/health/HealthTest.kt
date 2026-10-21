package dev.appwall.core.health

import dev.appwall.core.state.LogHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTest {

    private val broken = ProbeResult.Failed("broken")

    @Test
    fun decisionTable() {
        val table = listOf(
            Triple(ProbeResult.Ok, null, StartupDecision(true, LogHealth.Checking, emptyList())),
            Triple(ProbeResult.Ok, ProbeResult.Ok, StartupDecision(true, LogHealth.Ok, emptyList())),
            // A broken log never stops blocking.
            Triple(ProbeResult.Ok, broken, StartupDecision(true, LogHealth.Unavailable("broken"), listOf("log_unavailable"))),
            Triple(broken, ProbeResult.Ok, StartupDecision(false, LogHealth.Ok, listOf("settings_unreadable"))),
            Triple(broken, null, StartupDecision(false, LogHealth.Checking, listOf("settings_unreadable"))),
            Triple(broken, broken, StartupDecision(false, LogHealth.Unavailable("broken"), listOf("settings_unreadable", "log_unavailable"))),
        )
        for ((settings, log, expected) in table) assertEquals("$settings, $log", expected, startupDecision(settings, log))
    }

    @Test
    fun everyCheckRunsBeforeItsResultIsUsed() {
        val settings = FakeProbed(ProbeResult.Ok)
        val log = FakeProbed(broken)
        val checks = StartupChecks(settings, log)
        // Settings are checked on creation, before anything can ask whether the firewall may start.
        assertEquals(1, settings.probes)
        assertEquals(0, log.probes)
        assertEquals(LogHealth.Checking, checks.decision.logHealth)
        // The log only when its thread gets to it; then the decision says so.
        checks.checkLog()
        assertEquals(1, log.probes)
        assertEquals(1, settings.probes) // not again
        assertEquals(LogHealth.Unavailable("broken"), checks.decision.logHealth)
        assertEquals(true, checks.decision.firewallMayStart)
    }

    private class FakeProbed(private val result: ProbeResult) : Probed {
        var probes = 0
        override fun probe(): ProbeResult {
            probes++
            return result
        }
    }
}
