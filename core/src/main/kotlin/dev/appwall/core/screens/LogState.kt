package dev.appwall.core.screens

import dev.appwall.core.log.AppSummary
import dev.appwall.core.settings.LogSettings
import java.time.Instant
import java.time.ZoneId

/** Why the log has no rows to show, if it hasn't. */
enum class LogEmpty { NONE, NOTHING_YET, LOGGING_OFF }

data class LogState(
    /** One row per app, most recently active first. */
    val apps: List<AppSummary>,
    val todayAttempts: Int,
    /** Attempts over the whole retention period. */
    val periodAttempts: Int,
    /** Attempts per hour over the last 24 hours, oldest first. */
    val hourly: List<Int>,
    val empty: LogEmpty,
    val loggingEnabled: Boolean,
    val retentionDays: Int,
)

/** The all-apps log screen. With rows, it shows them even while logging is off (they're kept). */
fun logState(apps: List<AppSummary>, todayAttempts: Int, hourly: IntArray, settings: LogSettings): LogState =
    LogState(
        apps = apps,
        todayAttempts = todayAttempts,
        periodAttempts = apps.sumOf { it.attempts },
        hourly = hourly.toList(),
        empty = when {
            apps.isNotEmpty() -> LogEmpty.NONE
            !settings.loggingEnabled -> LogEmpty.LOGGING_OFF
            else -> LogEmpty.NOTHING_YET
        },
        loggingEnabled = settings.loggingEnabled,
        retentionDays = settings.retentionDays,
    )

/** When the calendar day containing [now] started in [zone] (epoch ms), for "today" totals. */
fun startOfDay(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
