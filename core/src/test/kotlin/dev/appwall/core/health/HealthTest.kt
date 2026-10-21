package dev.appwall.core.health

import dev.appwall.core.state.LogHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTest {

    private val broken = ProbeResult.Failed("broken")

    @Test
    fun decisionTable() {
        data class Case(val settings: ProbeResult, val log: ProbeResult?, val mayStart: Boolean, val logHealth: LogHealth, val refused: List<String>)
        val cases = listOf(
            Case(ProbeResult.Ok, null, true, LogHealth.Checking, emptyList()),
            Case(ProbeResult.Ok, ProbeResult.Ok, true, LogHealth.Ok, emptyList()),
            // A broken log never stops blocking.
            Case(ProbeResult.Ok, broken, true, LogHealth.Unavailable("broken"), listOf("log_unavailable")),
            Case(broken, ProbeResult.Ok, false, LogHealth.Ok, listOf("settings_unreadable")),
            Case(broken, null, false, LogHealth.Checking, listOf("settings_unreadable")),
            Case(broken, broken, false, LogHealth.Unavailable("broken"), listOf("settings_unreadable", "log_unavailable")),
        )
        for (c in cases) {
            val decision = startupDecision(c.settings, c.log)
            assertEquals("$c", Triple(c.mayStart, c.logHealth, c.refused), Triple(decision.firewallMayStart, decision.logHealth, decision.refused))
        }
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
