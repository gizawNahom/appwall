package dev.appwall.core.state

/** Why the firewall isn't running. */
enum class OffReason {
    /** The service isn't running; e.g. right after the app starts. */
    NOT_RUNNING,
    /** The user turned it off. */
    TURNED_OFF,
    /** Android took the VPN away: the user turned it off in system settings, or another VPN took over. */
    REVOKED,
    /** No VPN permission, so no tunnel could be set up. */
    NO_PERMISSION,
    /** Reading the tunnel kept failing, so the service turned itself off. */
    TUNNEL_FAILED,
}

/** Whether the firewall is running, as only the firewall service itself can say. */
sealed interface FirewallState {
    data class Off(val reason: OffReason) : FirewallState
    data object Starting : FirewallState
    /** Running; [routedApps] 0 means "on, nothing to block". */
    data class On(val routedApps: Int) : FirewallState
}

/** Android's Always-on VPN setting for AppWall, which only the running service can read. */
enum class AlwaysOn { UNKNOWN, OFF, ON, LOCKDOWN }

data class FirewallStatus(
    val firewall: FirewallState = FirewallState.Off(OffReason.NOT_RUNNING),
    val alwaysOn: AlwaysOn = AlwaysOn.UNKNOWN,
) {
    val running: Boolean get() = firewall !is FirewallState.Off
}

/**
 * The firewall's live status, for screens to show and observe. Starts as off (not running); only
 * the firewall service publishes to it, so a screen never shows "on" from a saved setting alone.
 */
class FirewallStatusBoard(val changed: ChangeSignal) {
    @Volatile
    var status = FirewallStatus()
        private set

    fun publish(status: FirewallStatus) {
        if (status == this.status) return
        this.status = status
        changed.notifyChanged()
    }

    /** The service stopped: off for [reason], unless it already said why. Always-on is unknown again. */
    fun stopped(reason: OffReason) {
        val firewall = status.firewall
        val keep = firewall is FirewallState.Off && firewall.reason != OffReason.NOT_RUNNING
        publish(FirewallStatus(if (keep) firewall else FirewallState.Off(reason), AlwaysOn.UNKNOWN))
    }
}
