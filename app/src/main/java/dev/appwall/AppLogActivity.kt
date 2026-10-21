package dev.appwall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import dev.appwall.core.log.Destination
import dev.appwall.core.log.LogEntry
import dev.appwall.core.net.PacketParser
import java.util.Calendar

/**
 * One app's blocked traffic. Destinations answers "where", most tried first; Timeline answers
 * "when", newest first, grouped by day. Also the place to allow (or re-block) the app.
 * Opened from the log, or from "Full log" on the home screen. Refreshes live while open.
 */
class AppLogActivity : Activity() {

    private val settings get() = appWall.settings
    /** Null for traffic AppWall couldn't attribute to an app. */
    private var packageName: String? = null
    private var showTimeline = false
    private val destinations = DestinationsAdapter()
    private val timeline = TimelineAdapter()

    /** Reloads whenever the log changes, while the screen is visible. */
    private val loader by lazy {
        logLoader({
            val pkg = packageName
            val withTimeline = showTimeline
            fun(log: SqlBlockLog) = log.destinations(pkg) to (if (withTimeline) log.entriesFor(pkg) else null)
        }) { (places, entries) -> show(places, entries) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_log)
        packageName = intent.getStringExtra(EXTRA_PACKAGE)
        showTimeline = savedInstanceState?.getBoolean(STATE_TIMELINE) ?: false

        val pkg = packageName
        val label = if (pkg == null) getString(R.string.unknown_app) else LogActivity.appLabel(this, pkg)
        findViewById<TextView>(R.id.app_log_name).text = label
        findViewById<ImageView>(R.id.app_log_icon).apply {
            if (pkg != null) setImageDrawable(AppIcons(this@AppLogActivity)[pkg]) else visibility = View.GONE
        }
        findViewById<View>(R.id.app_log_back).setOnClickListener { finish() }
        findViewById<View>(R.id.tab_destinations).setOnClickListener { selectTab(timeline = false) }
        findViewById<View>(R.id.tab_timeline).setOnClickListener { selectTab(timeline = true) }
        findViewById<Button>(R.id.app_log_toggle).apply {
            // Unattributed traffic has no app to allow.
            visibility = if (pkg == null) View.GONE else View.VISIBLE
            setOnClickListener {
                if (pkg == null) return@setOnClickListener
                appWall.firewall.setBlocked(listOf(pkg), !settings.policy.isBlocked(pkg))
                updateState(label)
            }
        }
        updateState(label)
        selectTab(showTimeline)
    }

    override fun onStart() {
        super.onStart()
        loader.start(appWall.logChanged)
    }

    override fun onStop() {
        loader.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TIMELINE, showTimeline)
    }

    /** The "● BLOCKED" / "ALLOWED" tag and the button that flips it. */
    private fun updateState(label: String) {
        val pkg = packageName
        val blocked = pkg == null || settings.policy.isBlocked(pkg)
        findViewById<TextView>(R.id.app_log_state).apply {
            setText(if (blocked) R.string.state_blocked else R.string.state_allowed)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (blocked) R.drawable.dot_blocked else 0, 0, 0, 0)
        }
        findViewById<Button>(R.id.app_log_toggle).text =
            getString(if (blocked) R.string.app_log_allow else R.string.app_log_block, label)
    }

    private fun selectTab(timeline: Boolean) {
        showTimeline = timeline
        findViewById<View>(R.id.tab_destinations).isSelected = !timeline
        findViewById<View>(R.id.tab_timeline).isSelected = timeline
        findViewById<View>(R.id.dest_columns).visibility = if (timeline) View.GONE else View.VISIBLE
        findViewById<ListView>(R.id.app_log_list).adapter = if (timeline) this.timeline else destinations
        loader.reload()
    }

    private fun show(places: List<Destination>, entries: List<LogEntry>?) {
        val days = settings.logSettings.retentionDays
        destinations.items = places
        destinations.max = destinations.items.maxOfOrNull { it.attempts } ?: 1
        if (entries != null) timeline.setEntries(entries)
        (if (showTimeline) timeline else destinations).notifyDataSetChanged()

        findViewById<TextView>(R.id.tab_destinations).text =
            FontSpan.labelWithCount(this, getString(R.string.log_tab_destinations), destinations.items.size)
        findViewById<TextView>(R.id.dest_column).text = resources.getQuantityString(R.plurals.dest_column, days, days)
        val empty = if (showTimeline) timeline.isEmpty else destinations.isEmpty
        findViewById<View>(R.id.app_log_empty).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private inner class DestinationsAdapter : BaseAdapter() {
        var items: List<Destination> = emptyList()
        var max = 1

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_destination, parent, false)
            val d = items[position]
            val ago = TimeText.ago(this@AppLogActivity, d.lastTime)
            view.findViewById<TextView>(R.id.dest_name).text = d.name
            view.findViewById<TextView>(R.id.dest_meta).text = when {
                d.isLookup -> getString(R.string.dest_lookup, ago)
                d.ports.isNotEmpty() -> getString(R.string.dest_ports, d.ports.joinToString(", "), ago)
                else -> getString(R.string.dest_no_port, ago)
            }
            view.findViewById<MeterView>(R.id.dest_meter).fraction = d.attempts.toFloat() / max
            view.findViewById<TextView>(R.id.dest_count).text = d.attempts.toString()
            return view
        }
    }

    private class DayHeading(val label: String, val total: Int)

    /** Day headings and entries in one list, newest first. */
    private inner class TimelineAdapter : BaseAdapter() {
        private var rows: List<Any> = emptyList() // DayHeading or LogEntry

        fun setEntries(entries: List<LogEntry>) {
            val today = startOfDay(System.currentTimeMillis())
            rows = entries.groupBy { startOfDay(it.time) }.flatMap { (day, dayEntries) ->
                listOf(DayHeading(dayLabel(day, today), dayEntries.sumOf { it.attempts })) + dayEntries
            }
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is LogEntry) 1 else 0
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is LogEntry) {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
                view.findViewById<TextView>(R.id.log_time).text = TimeText.clock(this@AppLogActivity, row.time)
                view.findViewById<TextView>(R.id.log_target).text = row.host ?: row.address
                view.findViewById<TextView>(R.id.log_port).text = when {
                    row.host != null -> getString(R.string.log_port_dns)
                    row.port != 0 -> row.port.toString()
                    else -> PacketParser.protocolName(row.protocol)
                }
                view.findViewById<TextView>(R.id.log_attempts).text = getString(R.string.log_attempts, row.attempts)
                return view
            }
            val heading = row as DayHeading
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log_day, parent, false)
            view.findViewById<TextView>(R.id.day_label).text = heading.label
            view.findViewById<TextView>(R.id.day_total).text =
                resources.getQuantityString(R.plurals.log_day_total, heading.total, heading.total)
            return view
        }

        private fun dayLabel(day: Long, today: Long): String = when (day) {
            today -> getString(R.string.log_today)
            startOfDay(today - 1) -> getString(R.string.log_yesterday)
            else -> DateUtils.formatDateTime(
                this@AppLogActivity, day,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
            ).uppercase()
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "dev.appwall.LOG_PACKAGE"
        private const val STATE_TIMELINE = "timeline"

        /** Opens the log of [packageName], or of unattributed traffic when null. */
        fun intent(context: Context, packageName: String?): Intent =
            Intent(context, AppLogActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)

        private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
