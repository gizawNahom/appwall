package dev.appwall.core.firewall

import dev.appwall.core.settings.FirewallPolicy
import dev.appwall.core.settings.FirewallPolicyReader
import dev.appwall.core.settings.FirewallPolicyWriter
import dev.appwall.core.state.ChangeSignal
import dev.appwall.core.state.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class FirewallCommandsTest {

    private val settings = FakePolicy()
    private var running = false
    private var starts = 0
    private var stops = 0
    private val commands = FirewallCommands(settings, settings, { running }, { starts++ }, { stops++ })

    @Test
    fun blockingWhileOnButNotRunningStartsTheService() {
        settings.policy = FirewallPolicy(enabled = true)
        commands.block(listOf("a", "b"))
        assertEquals(setOf("a", "b"), settings.policy.blocked)
        assertEquals(1, settings.writes) // one write for several apps
        assertEquals(1, starts)
    }

    @Test
    fun aRunningServiceIsNotStartedAgain() {
        settings.policy = FirewallPolicy(enabled = true, blocked = setOf("a"))
        running = true
        commands.allow(listOf("a"))
        assertEquals(emptySet<String>(), settings.policy.blocked)
        assertEquals(0, starts)
    }

    @Test
    fun whileOffRulesAreOnlySaved() {
        commands.block(listOf("a"))
        assertEquals(setOf("a"), settings.policy.blocked)
        assertEquals(0, starts)
    }

    @Test
    fun turningOnAndOff() {
        commands.turnOn()
        assertEquals(true, settings.policy.enabled)
        assertEquals(1, starts)
        running = true
        commands.turnOff()
        assertEquals(false, settings.policy.enabled)
        assertEquals(1, stops)
        running = false
        commands.turnOff() // already stopped: nothing to stop
        assertEquals(1, stops)
    }

    private class FakePolicy : FirewallPolicyReader, FirewallPolicyWriter {
        override var policy = FirewallPolicy()
        override val policyChanged = ChangeSignal(FakeScheduler())
        var writes = 0

        override fun setEnabled(enabled: Boolean) = write(policy.copy(enabled = enabled))
        override fun setBlocked(packages: Collection<String>, blocked: Boolean) =
            write(policy.copy(blocked = if (blocked) policy.blocked + packages else policy.blocked - packages.toSet()))
        override fun setNewAppMode(mode: NewAppMode) = write(policy.copy(newAppMode = mode))
        override fun setStartOnBoot(start: Boolean) = write(policy.copy(startOnBoot = start))

        private fun write(updated: FirewallPolicy) {
            writes++
            policy = updated
        }
    }
}
