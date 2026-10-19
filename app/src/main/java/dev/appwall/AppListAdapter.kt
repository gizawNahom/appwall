package dev.appwall

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * The home list. On the Blocked tab: blocked apps, busiest first, with today's attempts. On the
 * Allowed tab: everything else (background system services only when asked for), by name. In
 * search: every app matching the query, whatever its state.
 *
 * Tapping a row is the screen's business (it shows details); only the row's Block/Allow button
 * changes a rule, reported through [onAction].
 */
class AppListAdapter(
    private val context: Context,
    private val rules: Rules,
    private val icons: AppIcons,
    private val onAction: (AppEntry) -> Unit,
) : BaseAdapter() {

    enum class Tab { BLOCKED, ALLOWED, SEARCH }

    var tab = Tab.BLOCKED
        set(value) {
            field = value
            refilter()
        }

    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var attemptsToday: Map<String?, Int> = emptyMap()
    private var maxAttempts = 1
    private var query = ""
    private var showSystem = false

    fun setApps(apps: List<AppEntry>) {
        allApps = apps
        refilter()
    }

    fun setAttemptsToday(attempts: Map<String?, Int>) {
        attemptsToday = attempts
        refilter()
    }

    fun setQuery(text: String) {
        query = text.trim()
        refilter()
    }

    fun setShowSystem(show: Boolean) {
        showSystem = show
        refilter()
    }

    fun labelOf(packageName: String): String? = allApps.firstOrNull { it.packageName == packageName }?.label

    /** Re-reads the block rules, e.g. after an app was blocked or allowed. */
    fun refresh() = refilter()

    fun blockedCount() = allApps.count { rules.isBlocked(it.packageName) }

    fun allowedCount() = allApps.count { !rules.isBlocked(it.packageName) && (showSystem || !it.isSystemService) }

    private fun matches(app: AppEntry) =
        query.isEmpty() || app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)

    private fun refilter() {
        val blocked = rules.blocked()
        shown = when (tab) {
            // Every blocked app, including system services hidden from the Allowed tab.
            Tab.BLOCKED -> allApps
                .filter { it.packageName in blocked && matches(it) }
                .sortedWith(compareByDescending<AppEntry> { attemptsToday[it.packageName] ?: 0 }.thenBy { it.label.lowercase() })
            Tab.ALLOWED -> allApps
                .filter { it.packageName !in blocked && (showSystem || !it.isSystemService) && matches(it) }
            // Search looks everywhere, system services included; nothing until something is typed.
            Tab.SEARCH -> if (query.isEmpty()) emptyList() else allApps.filter { matches(it) }
        }
        maxAttempts = shown.maxOfOrNull { attemptsToday[it.packageName] ?: 0 }?.coerceAtLeast(1) ?: 1
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val app = shown[position]
        val attempts = attemptsToday[app.packageName] ?: 0
        val blocked = rules.isBlocked(app.packageName)
        val showActivity = tab == Tab.BLOCKED

        view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(icons[app.packageName])
        view.findViewById<TextView>(R.id.app_label).text = app.label
        view.findViewById<TextView>(R.id.app_package).text = app.packageName
        view.findViewById<View>(R.id.app_state).visibility = if (tab == Tab.SEARCH && blocked) View.VISIBLE else View.GONE
        view.findViewById<MeterView>(R.id.app_meter).apply {
            visibility = if (showActivity) View.VISIBLE else View.GONE
            fraction = attempts.toFloat() / maxAttempts
        }
        view.findViewById<TextView>(R.id.app_count).apply {
            visibility = if (showActivity) View.VISIBLE else View.GONE
            text = attempts.toString()
        }
        view.findViewById<Button>(R.id.app_action).apply {
            visibility = if (showActivity) View.GONE else View.VISIBLE
            setText(if (blocked) R.string.allow else R.string.block)
            contentDescription = context.getString(if (blocked) R.string.allow_app else R.string.block_app_named, app.label)
            setOnClickListener { onAction(app) }
        }
        view.contentDescription = when {
            blocked -> context.resources.getQuantityString(R.plurals.row_blocked_description, attempts, app.label, attempts)
            else -> context.getString(R.string.row_allowed_description, app.label)
        }
        return view
    }
}
