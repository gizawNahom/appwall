package dev.appwall.ui

import android.content.Context
import android.text.format.DateFormat
import dev.appwall.R
import java.util.Date

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

    /** "2m ago", or "just now" (where [shortAgo]'s "now ago" would read oddly). */
    fun ago(context: Context, time: Long, now: Long = System.currentTimeMillis()): String =
        if (now - time < 60_000) context.getString(R.string.ago_just_now) else context.getString(R.string.ago_suffix, shortAgo(context, time, now))

    /** Clock time in the user's 12/24-hour format, e.g. "19:17" or "7:17 PM". */
    fun clock(context: Context, time: Long): String = DateFormat.getTimeFormat(context).format(Date(time))
}
