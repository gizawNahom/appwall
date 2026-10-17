package dev.appwall

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/** Shows blocked connection attempts, newest first, refreshing live while open. */
class LogActivity : Activity() {

    private lateinit var log: BlockLog
    private lateinit var icons: AppIcons
    private lateinit var empty: View
    private val adapter = EntryAdapter()
    private val labels = HashMap<String, String>()
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
        findViewById<ListView>(R.id.log_list).adapter = adapter
        findViewById<View>(R.id.log_clear).setOnClickListener {
            log.clear()
            reload()
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
        adapter.entries = log.entries()
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.entries.isEmpty()) View.VISIBLE else View.GONE
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

    private inner class EntryAdapter : BaseAdapter() {
        var entries: List<LogEntry> = emptyList()

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
            val entry = entries[position]
            view.findViewById<ImageView>(R.id.log_icon).setImageDrawable(entry.packageName?.let { icons[it] })
            view.findViewById<TextView>(R.id.log_app).text = labelFor(entry.packageName)
            view.findViewById<TextView>(R.id.log_target).text = describeTarget(entry)
            view.findViewById<TextView>(R.id.log_time).text =
                DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
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

    private companion object {
        const val REFRESH_MS = 2000L
    }
}
