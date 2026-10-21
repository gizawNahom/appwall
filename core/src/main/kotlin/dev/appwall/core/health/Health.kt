package dev.appwall.core.health

import dev.appwall.core.state.LogHealth

/** What a start-up check found. */
sealed interface ProbeResult {
    data object Ok : ProbeResult
    data class Failed(val reason: String) : ProbeResult
}

/**
 * Something AppWall can't work without, checked for real before first use. The settings readers
 * and the block log extend this, so a new adapter of them can't compile without a check.
 */
interface Probed {
    fun probe(): ProbeResult
}

data class StartupDecision(
    /** False when the settings can't be trusted: the blocked apps might be wrong. */
    val firewallMayStart: Boolean,
    /** Without a usable log, tries are dropped; blocking carries on regardless. */
    val logHealth: LogHealth,
    /** One code per refusal, for the health.startup.refused log line. */
    val refused: List<String>,
)

/**
 * What may run, given the start-up checks. [log] is null while still being checked (it runs on the
 * database thread). A broken log never stops the firewall; unreadable settings do.
 */
fun startupDecision(settings: ProbeResult, log: ProbeResult?): StartupDecision {
    val logHealth = when (log) {
        null -> LogHealth.Checking
        ProbeResult.Ok -> LogHealth.Ok
        is ProbeResult.Failed -> LogHealth.Unavailable(log.reason)
    }
    return StartupDecision(
        firewallMayStart = settings == ProbeResult.Ok,
        logHealth = logHealth,
        refused = listOfNotNull(
            "settings_unreadable".takeIf { settings is ProbeResult.Failed },
            "log_unavailable".takeIf { log is ProbeResult.Failed },
        ),
    )
}

/**
 * The start-up sequence: wire, then probe, then use. Settings are checked as soon as this is made
 * (they're already in memory); the log when [checkLog] runs, as the database thread's first job.
 */
class StartupChecks(settings: Probed, private val log: Probed) {
    private val settingsResult = settings.probe()

    @Volatile var decision = startupDecision(settingsResult, null)
        private set

    fun checkLog(): StartupDecision = startupDecision(settingsResult, log.probe()).also { decision = it }
}
