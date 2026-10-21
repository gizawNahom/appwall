package dev.appwall.core.screens

import dev.appwall.core.log.Destination
import dev.appwall.core.log.LogEntry
import dev.appwall.core.settings.FirewallPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A timeline day's heading; dates are left to the screen to format. */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    /** An earlier day, as the epoch ms of its start in the screen's time zone. */
    data class Date(val startOfDay: Long) : DayLabel
}

/** The timeline: a heading for each day, followed by that day's entries. */
sealed interface TimelineRow {
    data class Day(val label: DayLabel, val attempts: Int) : TimelineRow
    data class Entry(val entry: LogEntry) : TimelineRow
}

/** The button under the app's name: allow a blocked app, block an allowed one, or none at all. */
enum class AppLogToggle { NONE, ALLOW, BLOCK }

data class AppLogState(
    /** Shown as "● BLOCKED"; traffic without an app counts as blocked (it reached the tunnel). */
    val blocked: Boolean,
    val toggle: AppLogToggle,
    val showTimeline: Boolean,
    /** Most tried first. */
    val destinations: List<Destination>,
    /** The most tries to one destination: a full meter. At least 1. */
    val meterMax: Int,
    /** Empty unless [showTimeline]. */
    val timeline: List<TimelineRow>,
    /** The shown tab has nothing to show. */
    val empty: Boolean,
    val retentionDays: Int,
)

/**
 * One app's log page ([packageName] null: traffic AppWall couldn't attribute to an app). [entries]
 * are newest first, as the log returns them; days are calendar days in [zone].
 */
fun appLogState(
    packageName: String?,
    policy: FirewallPolicy,
    retentionDays: Int,
    destinations: List<Destination>,
    entries: List<LogEntry>,
    showTimeline: Boolean,
    now: Long,
    zone: ZoneId,
): AppLogState {
    val blocked = packageName == null || policy.isBlocked(packageName)
    val toggle = when {
        packageName == null -> AppLogToggle.NONE // no app to allow
        blocked -> AppLogToggle.ALLOW
        else -> AppLogToggle.BLOCK
    }
    val timeline = if (showTimeline) timelineRows(entries, now, zone) else emptyList()
    return AppLogState(
        blocked = blocked,
        toggle = toggle,
        showTimeline = showTimeline,
        destinations = destinations,
        meterMax = destinations.maxOfOrNull { it.attempts }?.coerceAtLeast(1) ?: 1,
        timeline = timeline,
        empty = if (showTimeline) timeline.isEmpty() else destinations.isEmpty(),
        retentionDays = retentionDays,
    )
}

private fun timelineRows(entries: List<LogEntry>, now: Long, zone: ZoneId): List<TimelineRow> {
    fun dayOf(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val today = dayOf(now)
    return entries.groupBy { dayOf(it.time) }.flatMap { (day, dayEntries) ->
        val label = when (day) {
            today -> DayLabel.Today
            today.minusDays(1) -> DayLabel.Yesterday
            else -> DayLabel.Date(day.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        listOf(TimelineRow.Day(label, dayEntries.sumOf { it.attempts })) + dayEntries.map(TimelineRow::Entry)
    }
}
