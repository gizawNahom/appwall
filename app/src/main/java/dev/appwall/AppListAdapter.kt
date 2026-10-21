package dev.appwall

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import dev.appwall.core.apps.AppEntry
import dev.appwall.core.screens.HomeRow
import dev.appwall.core.screens.HomeState
import dev.appwall.core.screens.HomeTab

/**
 * The home list, drawn from [HomeState]: which rows, in what order, and their counts are decided
 * there. Tapping a row expands it in place (one at a time) to show what's known about the app.
 * Rules only change through buttons, reported to the screen through [onAction].
 */
class AppListAdapter(
    private val context: Context,
    private val icons: AppIcons,
    private val onAction: (AppEntry, Action) -> Unit,
) : BaseAdapter() {

    /** What a button in a row asks for. TOGGLE is the quick Block/Allow button on the row itself. */
    enum class Action { TOGGLE, ALLOW, BLOCK, FULL_LOG }

    /** The expanded row's app, if any; it stays open across refreshes and tab switches. */
    private var expanded: String? = null

    fun toggleExpanded(app: AppEntry) {
        expanded = if (expanded == app.packageName) null else app.packageName
        notifyDataSetChanged()
    }

    /** The open row's app, if any: its [details] should be loaded. */
    val expandedPackage: String? get() = expanded

    /** What the open row shows, loaded off the UI thread with [loadDetails]. */
    var details: Details? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** The open row's recent activity. */
    class Details(
        val packageName: String,
        val lastAttempt: Long?,
        val week: Int,
        val hourly: IntArray,
        val topDestinations: List<String>,
        val destinationCount: Int,
    )

    private var shown: List<HomeRow> = emptyList()
    private var tab = HomeTab.BLOCKED
    private var maxAttempts = 1

    fun show(state: HomeState) {
        shown = state.rows
        tab = state.tab
        maxAttempts = state.meterMax
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position].app
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val row = shown[position]
        val app = row.app
        val attempts = row.attemptsToday
        val blocked = row.blocked
        val isExpanded = expanded == app.packageName
        val showActivity = tab == HomeTab.BLOCKED

        view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(icons[app.packageName])
        view.findViewById<TextView>(R.id.app_label).text = app.label
        view.findViewById<TextView>(R.id.app_package).text = app.packageName
        view.findViewById<TextView>(R.id.app_state).visibility = if (tab == HomeTab.SEARCH && blocked) View.VISIBLE else View.GONE
        view.findViewById<MeterView>(R.id.app_meter).apply {
            visibility = if (showActivity) View.VISIBLE else View.GONE
            fraction = attempts.toFloat() / maxAttempts
        }
        view.findViewById<TextView>(R.id.app_count).apply {
            visibility = if (showActivity) View.VISIBLE else View.GONE
            text = attempts.toString()
        }
        view.findViewById<Button>(R.id.app_action).apply {
            // The expanded details carry the same actions, so the quick button steps aside.
            visibility = if (showActivity || isExpanded) View.GONE else View.VISIBLE
            setText(if (blocked) R.string.allow else R.string.block)
            contentDescription = context.getString(if (blocked) R.string.allow_app else R.string.block_app_named, app.label)
            setOnClickListener { onAction(app, Action.TOGGLE) }
        }

        val details = view.findViewById<View>(R.id.app_details)
        details.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) bindDetails(view, app, blocked)

        val base = when {
            blocked -> context.resources.getQuantityString(R.plurals.row_blocked_description, attempts, app.label, attempts)
            else -> context.getString(R.string.row_allowed_description, app.label)
        }
        view.contentDescription = context.getString(
            if (isExpanded) R.string.row_expanded else R.string.row_collapsed, base,
        )
        return view
    }

    /** Fills the expanded section: recent activity for blocked apps, and the actions that fit. */
    private fun bindDetails(view: View, app: AppEntry, blocked: Boolean) {
        val now = System.currentTimeMillis()
        // Until this row's details arrive, show its actions only.
        val details = details?.takeIf { it.packageName == app.packageName }
        val lastAttempt = details?.lastAttempt
        val summary = view.findViewById<TextView>(R.id.details_summary)
        val chart = view.findViewById<SparklineView>(R.id.details_chart)
        val caption = view.findViewById<View>(R.id.details_chart_caption)
        val destinations = view.findViewById<TextView>(R.id.details_destinations)
        val primary = view.findViewById<Button>(R.id.details_primary)
        val fullLog = view.findViewById<Button>(R.id.details_log)

        if (blocked && details == null) {
            summary.text = ""
            chart.visibility = View.GONE
            caption.visibility = View.GONE
            destinations.visibility = View.GONE
            bindButton(primary, app, R.string.allow, Action.ALLOW)
        } else if (blocked && details != null) {
            val week = details.week
            summary.text = if (lastAttempt == null) {
                context.getString(R.string.details_no_attempts)
            } else {
                context.getString(
                    R.string.details_activity,
                    TimeText.shortAgo(context, lastAttempt, now),
                    context.resources.getQuantityString(R.plurals.details_week, week, week),
                )
            }
            val hourly = details.hourly
            chart.values = hourly
            chart.contentDescription = context.getString(R.string.details_chart_description, hourly.sum())
            chart.visibility = if (lastAttempt != null) View.VISIBLE else View.GONE
            caption.visibility = chart.visibility
            val top = details.topDestinations
            val total = details.destinationCount
            destinations.visibility = if (top.isEmpty()) View.GONE else View.VISIBLE
            destinations.text = buildString {
                append(top.joinToString(" · "))
                if (total > top.size) append(" · ").append(context.getString(R.string.details_more, total - top.size))
            }
            bindButton(primary, app, R.string.allow, Action.ALLOW)
        } else {
            summary.setText(R.string.details_allowed)
            chart.visibility = View.GONE
            caption.visibility = View.GONE
            destinations.visibility = View.GONE
            bindButton(primary, app, R.string.block, Action.BLOCK)
        }
        // The log is only worth offering when there's something in it.
        bindButton(fullLog, app, R.string.details_full_log, Action.FULL_LOG, visible = lastAttempt != null)
    }

    private fun bindButton(button: Button, app: AppEntry, label: Int, action: Action, visible: Boolean = true) {
        button.visibility = if (visible) View.VISIBLE else View.GONE
        button.setText(label)
        button.setOnClickListener { onAction(app, action) }
    }

    companion object {
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val WEEK_MS = 7 * DAY_MS

        /** Runs the open row's queries; call it on the database thread. */
        fun loadDetails(log: SqlBlockLog, packageName: String, now: Long): Details {
            val (top, total) = log.topDestinations(packageName, now - WEEK_MS, 3)
            return Details(
                packageName = packageName,
                lastAttempt = log.lastAttemptTime(packageName),
                week = log.attemptsSince(now - WEEK_MS)[packageName] ?: 0,
                hourly = log.attemptsPerBucket(now - DAY_MS, HOUR_MS, 24, packageName),
                topDestinations = top,
                destinationCount = total,
            )
        }
    }
}
