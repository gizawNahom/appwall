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
import dev.appwall.core.net.PacketParser
import dev.appwall.core.screens.AppLogState
import dev.appwall.core.screens.AppLogToggle
import dev.appwall.core.screens.DayLabel
import dev.appwall.core.screens.TimelineRow
import dev.appwall.core.screens.appLogState
import dev.appwall.core.screens.shortCount
import java.time.ZoneId

/**
 * One app's blocked traffic. Destinations answers "where", most tried first; Timeline answers
 * "when", newest first, grouped by day. Also the place to allow (or re-block) the app.
 * Opened from the log, or from "Full log" on the home screen. Refreshes live while open.
 */
class AppLogActivity : Activity() {

    private val settings get() = appWall.settings
    /** Null for traffic AppWall couldn't attribute to an app. */
    private var packageName: String? = null
    private var label = ""
    private var showTimeline = false
    private val destinations = DestinationsAdapter()
    private val timeline = TimelineAdapter()
    /** What's on screen, handed to the rebuilt screen on rotation so it doesn't start empty. */
    private var shown: AppLogState? = null
    /** The tabs and column titles, when they scroll with the list (see [scrollWithList]). */
    private var header: View? = null
    /** Keeps each destination's "2h ago" true while nothing else changes. */
    private val ticker = MinuteTicker(this) { destinations.notifyDataSetChanged() }

    /** Rebuilds the page's [AppLogState] whenever the log or the block rules change, while visible. */
    private val loader by lazy {
        logLoader({
            val pkg = packageName
            val withTimeline = showTimeline
            val policy = settings.policy
            val days = settings.logSettings.retentionDays
            val now = System.currentTimeMillis()
            fun(log: SqlBlockLog) = appLogState(
                pkg, policy, days,
                destinations = log.destinations(pkg),
                entries = if (withTimeline) log.entriesFor(pkg) else emptyList(),
                showTimeline = withTimeline, now = now, zone = ZoneId.systemDefault(),
            )
        }, ::render)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_log)
        fitWidth()
        header = scrollWithList(
            findViewById(R.id.app_log_list),
            above = listOf(findViewById(R.id.app_log_tabs), findViewById(R.id.dest_columns)),
        )
        packageName = intent.getStringExtra(EXTRA_PACKAGE)
        showTimeline = savedInstanceState?.getBoolean(STATE_TIMELINE) ?: false

        val pkg = packageName
        label = if (pkg == null) getString(R.string.unknown_app) else LogActivity.appLabel(this, pkg)
        view<TextView>(R.id.app_log_name).text = label
        view<ImageView>(R.id.app_log_icon).apply {
            if (pkg != null) setImageDrawable(AppIcons(this@AppLogActivity)[pkg]) else visibility = View.GONE
        }
        view<View>(R.id.app_log_back).setOnClickListener { finish() }
        view<View>(R.id.tab_destinations).setOnClickListener { selectTab(timeline = false) }
        view<View>(R.id.tab_timeline).setOnClickListener { selectTab(timeline = true) }
        view<Button>(R.id.app_log_toggle).visibility = View.INVISIBLE // until the first state arrives
        view<Button>(R.id.app_log_toggle).setOnClickListener {
            // The page redraws when the policy change comes back through the loader.
            if (pkg != null) appWall.firewall.setBlocked(listOf(pkg), !settings.policy.isBlocked(pkg))
        }
        selectTab(showTimeline)
        (lastNonConfigurationInstance as? AppLogState)?.takeIf { it.showTimeline == showTimeline }?.let(::render)
    }

    override fun onRetainNonConfigurationInstance(): Any? = shown

    override fun onStart() {
        super.onStart()
        loader.start(appWall.logChanged, settings.policyChanged)
        ticker.start()
    }

    override fun onStop() {
        ticker.stop()
        loader.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TIMELINE, showTimeline)
    }

    private fun selectTab(timeline: Boolean) {
        showTimeline = timeline
        view<View>(R.id.tab_destinations).isSelected = !timeline
        view<View>(R.id.tab_timeline).isSelected = timeline
        view<View>(R.id.dest_columns).visibility = if (timeline) View.GONE else View.VISIBLE
        view<ListView>(R.id.app_log_list).adapter = if (timeline) this.timeline else destinations
        loader.reload()
    }

    private fun render(state: AppLogState) {
        shown = state
        // The "● BLOCKED" / "ALLOWED" tag and the button that flips it.
        view<TextView>(R.id.app_log_state).apply {
            setText(if (state.blocked) R.string.state_blocked else R.string.state_allowed)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (state.blocked) R.drawable.dot_blocked else 0, 0, 0, 0)
        }
        view<Button>(R.id.app_log_toggle).apply {
            visibility = if (state.toggle == AppLogToggle.NONE) View.GONE else View.VISIBLE
            text = getString(if (state.toggle == AppLogToggle.ALLOW) R.string.app_log_allow else R.string.app_log_block, label)
        }

        destinations.state = state
        timeline.rows = state.timeline
        (if (state.showTimeline) timeline else destinations).notifyDataSetChanged()
        view<TextView>(R.id.tab_destinations).text =
            FontSpan.labelWithCount(this, getString(R.string.log_tab_destinations), state.destinations.size)
        view<TextView>(R.id.dest_column).text =
            resources.getQuantityString(R.plurals.dest_column, state.retentionDays, state.retentionDays)
        view<View>(R.id.app_log_empty).visibility = if (state.empty) View.VISIBLE else View.GONE
    }

    /** A view of this screen, wherever it is now: in the layout, or in the list's header. */
    private fun <T : View> view(id: Int): T = findViewById(id) ?: header?.findViewById(id) ?: error("No view $id")

    private inner class DestinationsAdapter : BaseAdapter() {
        var state: AppLogState? = null
        private val items get() = state?.destinations.orEmpty()

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
            view.findViewById<MeterView>(R.id.dest_meter).fraction = d.attempts.toFloat() / (state?.meterMax ?: 1)
            view.findViewById<TextView>(R.id.dest_count).text = shortCount(d.attempts.toLong())
            return view
        }
    }

    /** Day headings and entries in one list, newest first. */
    private inner class TimelineAdapter : BaseAdapter() {
        var rows: List<TimelineRow> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is TimelineRow.Entry) 1 else 0
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is TimelineRow.Entry) {
                val entry = row.entry
                val view = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
                view.findViewById<TextView>(R.id.log_time).text = TimeText.clock(this@AppLogActivity, entry.time)
                view.findViewById<TextView>(R.id.log_target).text = entry.host ?: entry.address
                view.findViewById<TextView>(R.id.log_port).text = when {
                    entry.host != null -> getString(R.string.log_port_dns)
                    entry.port != 0 -> entry.port.toString()
                    else -> PacketParser.protocolName(entry.protocol)
                }
                view.findViewById<TextView>(R.id.log_attempts).text = getString(R.string.log_attempts, entry.attempts)
                return view
            }
            val day = row as TimelineRow.Day
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log_day, parent, false)
            view.findViewById<TextView>(R.id.day_label).text = dayLabel(day.label)
            view.findViewById<TextView>(R.id.day_total).text =
                resources.getQuantityString(R.plurals.log_day_total, day.attempts, day.attempts)
            return view
        }

        private fun dayLabel(label: DayLabel): String = when (label) {
            DayLabel.Today -> getString(R.string.log_today)
            DayLabel.Yesterday -> getString(R.string.log_yesterday)
            is DayLabel.Date -> DateUtils.formatDateTime(
                this@AppLogActivity, label.startOfDay,
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
    }
}
