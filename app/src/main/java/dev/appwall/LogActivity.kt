package dev.appwall

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import dev.appwall.core.log.AppSummary
import dev.appwall.core.screens.LogEmpty
import dev.appwall.core.screens.LogState
import dev.appwall.core.screens.logState
import dev.appwall.core.screens.startOfDay
import java.time.ZoneId

/**
 * Blocked traffic, all apps: a summary of the tries over the retention period, then one row per
 * app, most recently active first. Tapping an app opens its own log. Refreshes live while open;
 * what it shows comes from [logState].
 */
class LogActivity : Activity() {

    private val settings get() = appWall.settings
    private lateinit var icons: AppIcons
    private val adapter = AppsAdapter()
    /** What's on screen, handed to the rebuilt screen on rotation so it doesn't start empty. */
    private var shown: LogState? = null
    /** The summary and column titles, when they scroll with the list (see [scrollWithList]). */
    private var header: View? = null

    /** Reloads whenever the log changes, while the screen is visible. */
    private val loader by lazy {
        logLoader({
            val now = System.currentTimeMillis()
            val startOfToday = startOfDay(now, ZoneId.systemDefault())
            val logSettings = settings.logSettings
            fun(log: SqlBlockLog) = logState(
                apps = log.summaries(),
                todayAttempts = log.attemptsSince(startOfToday).values.sum(),
                hourly = log.attemptsPerBucket(now - DAY_MS, HOUR_MS, 24),
                settings = logSettings,
            )
        }, ::render)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        fitWidth()
        icons = AppIcons(this)

        findViewById<View>(R.id.log_back).setOnClickListener { finish() }
        findViewById<View>(R.id.log_clear).setOnClickListener { confirmClear() }
        val openSettings = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.log_footer).setOnClickListener(openSettings)
        findViewById<View>(R.id.log_open_settings).setOnClickListener(openSettings)
        header = scrollWithList(
            findViewById(R.id.log_list),
            above = listOf(findViewById(R.id.log_summary), findViewById(R.id.log_columns)),
        )
        findViewById<ListView>(R.id.log_list).apply {
            adapter = this@LogActivity.adapter
            setOnItemClickListener { parent, _, position, _ ->
                val app = parent.getItemAtPosition(position) as? AppSummary ?: return@setOnItemClickListener
                startActivity(AppLogActivity.intent(this@LogActivity, app.packageName))
            }
        }
        (lastNonConfigurationInstance as? LogState)?.let(::render)
    }

    override fun onRetainNonConfigurationInstance(): Any? = shown

    override fun onStart() {
        super.onStart()
        loader.start(appWall.logChanged, settings.logSettingsChanged)
    }

    override fun onStop() {
        loader.stop()
        super.onStop()
    }

    private fun confirmClear() {
        // Deleting the log can't be undone: coral, like every destructive action.
        AppSheet(
            title = getString(R.string.log_clear_title),
            body = getString(R.string.log_clear_text),
            choices = listOf(
                AppSheet.Choice(getString(android.R.string.cancel), AppSheet.Role.SECONDARY),
                AppSheet.Choice(getString(R.string.log_clear), AppSheet.Role.RISKY) { onDb({ it.clear() }) {} },
            ),
        ).show(this)
    }

    private fun render(state: LogState) {
        shown = state
        val days = state.retentionDays
        adapter.apps = state.apps
        adapter.notifyDataSetChanged()

        val hasEntries = state.empty == LogEmpty.NONE
        listOf(R.id.log_summary, R.id.log_columns, R.id.log_list, R.id.log_clear).forEach {
            view<View>(it).visibility = if (hasEntries) View.VISIBLE else View.GONE
        }
        view<View>(R.id.log_empty).visibility = if (hasEntries) View.GONE else View.VISIBLE
        view<TextView>(R.id.log_footer).text = if (state.loggingEnabled) {
            resources.getQuantityString(R.plurals.log_footer, days, days)
        } else {
            getString(R.string.log_footer_off)
        }
        if (!hasEntries) {
            val off = state.empty == LogEmpty.LOGGING_OFF
            view<TextView>(R.id.log_empty_title).setText(if (off) R.string.log_off_title else R.string.log_empty_title)
            view<TextView>(R.id.log_empty_body).setText(if (off) R.string.log_off_body else R.string.log_empty_body)
            view<View>(R.id.log_open_settings).visibility = if (off) View.VISIBLE else View.GONE
            return
        }

        view<TextView>(R.id.log_today).text = state.todayAttempts.toString()
        view<TextView>(R.id.log_period).text = state.periodAttempts.toString()
        view<TextView>(R.id.log_period_label).text = resources.getQuantityString(R.plurals.log_stat_period, days, days)
        view<TextView>(R.id.log_apps).text = state.apps.size.toString()
        view<TextView>(R.id.log_column_app).text = resources.getQuantityString(R.plurals.log_column_app, days, days)
        view<SparklineView>(R.id.log_chart).apply {
            values = state.hourly.toIntArray()
            contentDescription = getString(R.string.log_chart_description, state.hourly.sum())
        }
    }

    /** A view of this screen, wherever it is now: in the layout, or in the list's header. */
    private fun <T : View> view(id: Int): T = findViewById(id) ?: header?.findViewById(id) ?: error("No view $id")

    private inner class AppsAdapter : BaseAdapter() {
        var apps: List<AppSummary> = emptyList()
        private val labels = HashMap<String, String>()

        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log_app, parent, false)
            val app = apps[position]
            val label = labelFor(app.packageName)
            val ago = TimeText.shortAgo(this@LogActivity, app.lastTime)
            view.findViewById<ImageView>(R.id.log_app_icon).setImageDrawable(app.packageName?.let { icons[it] })
            view.findViewById<TextView>(R.id.log_app_name).text = label
            view.findViewById<TextView>(R.id.log_app_summary).text = getString(
                R.string.log_app_summary,
                resources.getQuantityString(R.plurals.log_tries, app.attempts, app.attempts),
                resources.getQuantityString(R.plurals.log_places, app.destinations, app.destinations),
            )
            view.findViewById<TextView>(R.id.log_app_time).text = ago
            view.contentDescription = resources.getQuantityString(R.plurals.log_app_description, app.attempts, label, app.attempts, ago)
            return view
        }

        private fun labelFor(packageName: String?): String {
            if (packageName == null) return getString(R.string.unknown_app)
            return labels.getOrPut(packageName) { appLabel(this@LogActivity, packageName) }
        }
    }

    companion object {
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        /** An app's name, or its package if it has been uninstalled since. */
        fun appLabel(activity: Activity, packageName: String): String = try {
            val pm = activity.packageManager
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
