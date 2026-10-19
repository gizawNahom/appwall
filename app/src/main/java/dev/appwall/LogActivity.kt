package dev.appwall

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.ImageView
import android.widget.TextView

/**
 * Blocked connection attempts grouped by app, most recently active app first. Tap an app to
 * expand its individual attempts. Refreshes live while open.
 */
class LogActivity : Activity() {

    private lateinit var log: BlockLog
    private lateinit var icons: AppIcons
    private lateinit var list: ExpandableListView
    private lateinit var empty: View
    private val adapter = GroupedAdapter()
    private val labels = HashMap<String, String>()

    /** Expanded apps, by package (null = unattributed), so expansion survives live refreshes. */
    private val expanded = HashSet<String?>()
    private var scrollToPackage: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        log = BlockLog.get(this)
        icons = AppIcons(this)
        empty = findViewById(R.id.log_empty)
        list = findViewById(R.id.log_list)
        list.setAdapter(adapter)
        list.setOnGroupExpandListener { expanded += adapter.groups[it].packageName }
        list.setOnGroupCollapseListener { expanded -= adapter.groups[it].packageName }
        findViewById<View>(R.id.log_clear).setOnClickListener {
            log.clear()
            reload()
        }

        // Opened from a long-press on the wall: start with that app expanded.
        intent.getStringExtra(EXTRA_PACKAGE)?.let {
            expanded += it
            scrollToPackage = it
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun reload() {
        val byApp = log.entries(limit = 2000).groupBy { it.packageName }
        adapter.groups = log.summaries()
        adapter.children = adapter.groups.map { byApp[it.packageName].orEmpty().take(MAX_ENTRIES_PER_APP) }
        adapter.notifyDataSetChanged()

        // Group positions shift as apps reorder, so re-apply expansion by package.
        adapter.groups.forEachIndexed { position, group ->
            if (group.packageName in expanded) list.expandGroup(position) else list.collapseGroup(position)
        }
        scrollToPackage?.let { packageName ->
            val position = adapter.groups.indexOfFirst { it.packageName == packageName }
            if (position >= 0) list.setSelectedGroup(position)
            scrollToPackage = null
        }
        empty.visibility = if (adapter.groups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun labelFor(packageName: String?): String {
        if (packageName == null) return getString(R.string.unknown_app)
        return labels.getOrPut(packageName) {
            try {
                packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName // uninstalled since
            }
        }
    }

    private fun timeAgo(time: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)

    private inner class GroupedAdapter : BaseExpandableListAdapter() {
        var groups: List<AppSummary> = emptyList()
        var children: List<List<LogEntry>> = emptyList()

        override fun getGroupCount() = groups.size
        override fun getChildrenCount(groupPosition: Int) = children[groupPosition].size
        override fun getGroup(groupPosition: Int) = groups[groupPosition]
        override fun getChild(groupPosition: Int, childPosition: Int) = children[groupPosition][childPosition]
        override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
        override fun getChildId(groupPosition: Int, childPosition: Int) = childPosition.toLong()
        override fun hasStableIds() = false
        override fun isChildSelectable(groupPosition: Int, childPosition: Int) = false

        override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log_app, parent, false)
            val group = groups[groupPosition]
            view.findViewById<ImageView>(R.id.log_app_icon).setImageDrawable(group.packageName?.let { icons[it] })
            view.findViewById<TextView>(R.id.log_app_name).text = labelFor(group.packageName)
            view.findViewById<TextView>(R.id.log_app_summary).text = getString(
                R.string.log_app_summary,
                resources.getQuantityString(R.plurals.log_attempt_count, group.attempts, group.attempts),
                resources.getQuantityString(R.plurals.log_destination_count, group.destinations, group.destinations),
            )
            view.findViewById<TextView>(R.id.log_app_time).text = timeAgo(group.lastTime)
            view.findViewById<TextView>(R.id.log_app_chevron).text = if (isExpanded) "▲" else "▼"
            return view
        }

        override fun getChildView(
            groupPosition: Int, childPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup,
        ): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
            val entry = children[groupPosition][childPosition]
            view.findViewById<TextView>(R.id.log_target).text = describeTarget(entry)
            view.findViewById<TextView>(R.id.log_time).text = timeAgo(entry.time)
            view.findViewById<TextView>(R.id.log_attempts).apply {
                text = getString(R.string.log_attempts, entry.attempts)
                visibility = if (entry.attempts > 1) View.VISIBLE else View.GONE
            }
            return view
        }

        private fun describeTarget(entry: LogEntry): String {
            val protocol = PacketParser.protocolName(entry.protocol)
            return when {
                entry.host != null -> getString(R.string.log_dns_lookup, entry.host)
                entry.port != 0 -> getString(R.string.log_address_port, entry.address, entry.port, protocol)
                else -> getString(R.string.log_address, entry.address, protocol)
            }
        }
    }

    companion object {
        /** Package to show expanded when the log opens (from a long-press on the wall). */
        const val EXTRA_PACKAGE = "dev.appwall.LOG_PACKAGE"
        private const val REFRESH_MS = 2000L
        private const val MAX_ENTRIES_PER_APP = 200
    }
}
