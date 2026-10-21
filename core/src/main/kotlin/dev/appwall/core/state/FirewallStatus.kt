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
    /** Blocked tries the log had no room for (they were still blocked), since the app started. */
    val droppedTries: Long = 0,
) {
    val running: Boolean get() = firewall !is FirewallState.Off
}

/**
 * The firewall's live status, for screens to show and observe. Starts as off (not running); only
 * the firewall service publishes to it, so a screen never shows "on" from a saved setting alone.
 * The database thread adds only the dropped-tries count.
 */
class FirewallStatusBoard(val changed: ChangeSignal) {
    @Volatile
    var status = FirewallStatus()
        private set

    /** The service's view of the firewall; [FirewallStatus.droppedTries] is kept, not replaced. */
    fun publish(status: FirewallStatus) = update { status.copy(droppedTries = it.droppedTries) }

    /** The service stopped: off for [reason], unless it already said why. Always-on is unknown again. */
    fun stopped(reason: OffReason) = update { current ->
        val firewall = current.firewall
        val keep = firewall is FirewallState.Off && firewall.reason != OffReason.NOT_RUNNING
        FirewallStatus(if (keep) firewall else FirewallState.Off(reason), AlwaysOn.UNKNOWN, current.droppedTries)
    }

    /** From the database thread: [total] tries dropped so far for want of room in the log's queue. */
    fun countDropped(total: Long) = update { it.copy(droppedTries = total) }

    private fun update(change: (FirewallStatus) -> FirewallStatus) {
        synchronized(this) {
            val next = change(status)
            if (next == status) return
            status = next
        }
        changed.notifyChanged()
    }
}
