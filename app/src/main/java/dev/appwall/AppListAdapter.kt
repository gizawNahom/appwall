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
import dev.appwall.core.screens.DetailsSummary
import dev.appwall.core.screens.HomeRow
import dev.appwall.core.screens.HomeState
import dev.appwall.core.screens.HomeTab
import dev.appwall.core.screens.RowDetails

/**
 * The home list. Draws what [HomeState] decided, nothing more: which rows, in what order, their
 * counts, and what the open row shows. Taps go back to the screen through [onAction].
 */
class AppListAdapter(
    private val context: Context,
    private val icons: AppIcons,
    private val onAction: (AppEntry, Action) -> Unit,
) : BaseAdapter() {

    /** What a button in a row asks for. TOGGLE is the quick Block/Allow button on the row itself. */
    enum class Action { TOGGLE, ALLOW, BLOCK, FULL_LOG }

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
        val isExpanded = row.details != null
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
        row.details?.let { bindDetails(view, app, blocked, it) }

        val base = when {
            blocked -> context.resources.getQuantityString(R.plurals.row_blocked_description, attempts, app.label, attempts)
            else -> context.getString(R.string.row_allowed_description, app.label)
        }
        view.contentDescription = context.getString(
            if (isExpanded) R.string.row_expanded else R.string.row_collapsed, base,
        )
        return view
    }

    /** Fills the open row's section: recent activity for blocked apps, and the actions that fit. */
    private fun bindDetails(view: View, app: AppEntry, blocked: Boolean, details: RowDetails) {
        view.findViewById<TextView>(R.id.details_summary).text = when (val summary = details.summary) {
            DetailsSummary.Loading -> ""
            DetailsSummary.NoAttempts -> context.getString(R.string.details_no_attempts)
            DetailsSummary.Allowed -> context.getString(R.string.details_allowed)
            is DetailsSummary.Activity -> context.getString(
                R.string.details_activity,
                TimeText.shortAgo(context, summary.lastAttempt, System.currentTimeMillis()),
                context.resources.getQuantityString(R.plurals.details_week, summary.week, summary.week),
            )
        }
        val chart = view.findViewById<SparklineView>(R.id.details_chart)
        val hourly = details.hourly
        chart.visibility = if (hourly != null) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.details_chart_caption).visibility = chart.visibility
        if (hourly != null) {
            chart.values = hourly.toIntArray()
            chart.contentDescription = context.getString(R.string.details_chart_description, hourly.sum())
        }
        view.findViewById<TextView>(R.id.details_destinations).apply {
            visibility = if (details.topDestinations.isEmpty()) View.GONE else View.VISIBLE
            text = buildString {
                append(details.topDestinations.joinToString(" · "))
                if (details.moreDestinations > 0) append(" · ").append(context.getString(R.string.details_more, details.moreDestinations))
            }
        }
        val primary = view.findViewById<Button>(R.id.details_primary)
        if (blocked) bindButton(primary, app, R.string.allow, Action.ALLOW) else bindButton(primary, app, R.string.block, Action.BLOCK)
        bindButton(view.findViewById(R.id.details_log), app, R.string.details_full_log, Action.FULL_LOG, visible = details.showFullLog)
    }

    private fun bindButton(button: Button, app: AppEntry, label: Int, action: Action, visible: Boolean = true) {
        button.visibility = if (visible) View.VISIBLE else View.GONE
        button.setText(label)
        button.setOnClickListener { onAction(app, action) }
    }
}
