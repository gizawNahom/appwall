package dev.appwall

import android.app.Activity
import android.app.AlertDialog
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
import java.util.Calendar

/**
 * Blocked traffic, all apps: a summary of the tries over the retention period, then one row per
 * app, most recently active first. Tapping an app opens its own log. Refreshes live while open.
 */
class LogActivity : Activity() {

    private val settings get() = appWall.settings
    private lateinit var icons: AppIcons
    private val adapter = AppsAdapter()

    /** Reloads whenever the log changes, while the screen is visible. */
    private val loader by lazy {
        logLoader({
            val now = System.currentTimeMillis()
            val startOfToday = startOfToday()
            fun(log: SqlBlockLog) =
                LogStats(log.summaries(), log.attemptsSince(startOfToday).values.sum(), log.attemptsPerBucket(now - DAY_MS, HOUR_MS, 24))
        }, ::show)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        icons = AppIcons(this)

        findViewById<View>(R.id.log_back).setOnClickListener { finish() }
        findViewById<View>(R.id.log_clear).setOnClickListener { confirmClear() }
        val openSettings = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.log_footer).setOnClickListener(openSettings)
        findViewById<View>(R.id.log_open_settings).setOnClickListener(openSettings)
        findViewById<ListView>(R.id.log_list).apply {
            adapter = this@LogActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(AppLogActivity.intent(this@LogActivity, this@LogActivity.adapter.getItem(position).packageName))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        loader.start(appWall.logChanged)
    }

    override fun onStop() {
        loader.stop()
        super.onStop()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear_title)
            .setMessage(R.string.log_clear_text)
            .setPositiveButton(R.string.log_clear) { _, _ -> onDb({ it.clear() }) {} }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The numbers the log screen shows, read together on the database thread. */
    private class LogStats(val apps: List<AppSummary>, val today: Int, val hourly: IntArray)

    private fun show(stats: LogStats) {
        val days = settings.logSettings.retentionDays
        adapter.apps = stats.apps
        adapter.notifyDataSetChanged()

        val hasEntries = adapter.apps.isNotEmpty()
        listOf(R.id.log_summary, R.id.log_columns, R.id.log_list, R.id.log_clear).forEach {
            findViewById<View>(it).visibility = if (hasEntries) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.log_empty).visibility = if (hasEntries) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.log_footer).text = if (settings.logSettings.loggingEnabled) {
            resources.getQuantityString(R.plurals.log_footer, days, days)
        } else {
            getString(R.string.log_footer_off)
        }
        if (!hasEntries) {
            val off = !settings.logSettings.loggingEnabled
            findViewById<TextView>(R.id.log_empty_title).setText(if (off) R.string.log_off_title else R.string.log_empty_title)
            findViewById<TextView>(R.id.log_empty_body).setText(if (off) R.string.log_off_body else R.string.log_empty_body)
            findViewById<View>(R.id.log_open_settings).visibility = if (off) View.VISIBLE else View.GONE
            return
        }

        findViewById<TextView>(R.id.log_today).text = stats.today.toString()
        findViewById<TextView>(R.id.log_period).text = adapter.apps.sumOf { it.attempts }.toString()
        findViewById<TextView>(R.id.log_period_label).text = resources.getQuantityString(R.plurals.log_stat_period, days, days)
        findViewById<TextView>(R.id.log_apps).text = adapter.apps.size.toString()
        findViewById<TextView>(R.id.log_column_app).text = resources.getQuantityString(R.plurals.log_column_app, days, days)
        findViewById<SparklineView>(R.id.log_chart).apply {
            values = stats.hourly
            contentDescription = getString(R.string.log_chart_description, values.sum())
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
