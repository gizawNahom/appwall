package dev.appwall

import android.content.Context

/** Short time phrases used across the home screen. */
object TimeText {
    /** "now", "2m", "3h", "4d"; "—" when [time] is null (nothing happened yet). */
    fun shortAgo(context: Context, time: Long?, now: Long = System.currentTimeMillis()): String {
        if (time == null) return "—"
        val minutes = (now - time) / 60_000
        return when {
            minutes < 1 -> context.getString(R.string.ago_now)
            minutes < 60 -> context.getString(R.string.ago_minutes, minutes)
            minutes < 24 * 60 -> context.getString(R.string.ago_hours, minutes / 60)
            else -> context.getString(R.string.ago_days, minutes / (24 * 60))
        }
    }
}
