package dev.appwall.core.firewall

import dev.appwall.core.settings.FirewallPolicyReader
import dev.appwall.core.settings.FirewallPolicyWriter

/**
 * The one way screens and notifications change what the firewall enforces. Each command saves the
 * policy; a running firewall service sees the change and applies it itself. If the policy says on
 * but the service isn't running (e.g. Android killed it), the command starts it.
 */
class FirewallCommands(
    private val policy: FirewallPolicyReader,
    private val writer: FirewallPolicyWriter,
    private val isRunning: () -> Boolean,
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
) {
    fun turnOn() {
        writer.setEnabled(true)
        ensureRunning()
    }

    fun turnOff() {
        writer.setEnabled(false)
        if (isRunning()) stopService()
    }

    fun block(packages: Collection<String>) = setBlocked(packages, true)

    fun allow(packages: Collection<String>) = setBlocked(packages, false)

    fun setBlocked(packages: Collection<String>, blocked: Boolean) {
        writer.setBlocked(packages, blocked)
        ensureRunning()
    }

    fun setNewAppMode(mode: NewAppMode) = writer.setNewAppMode(mode)

    fun setStartOnBoot(start: Boolean) = writer.setStartOnBoot(start)

    /** Starts the service if the firewall should be on but isn't running. */
    fun ensureRunning() {
        if (policy.policy.enabled && !isRunning()) startService()
    }
}
