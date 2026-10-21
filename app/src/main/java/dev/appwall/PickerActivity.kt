package dev.appwall

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import dev.appwall.core.apps.AppEntry

/**
 * "Block an app": pick any number of allowed apps and block them together. Returns the chosen
 * packages as [EXTRA_PACKAGES]; the home screen applies them (so it can offer Undo).
 */
class PickerActivity : Activity() {

    /**
     * APPS: everything with an icon, preinstalled apps included. RECENT: apps you installed in the
     * last week, newest first. SYSTEM: preinstalled apps and background services, which can break
     * other apps when blocked, so they're kept out of the default view.
     */
    private enum class Filter { APPS, RECENT, SYSTEM }

    private val settings get() = appWall.settings
    private lateinit var icons: AppIcons
    private val adapter = PickAdapter()
    private var allowed: List<AppEntry> = emptyList()
    private val picked = LinkedHashSet<String>()
    private var filter = Filter.APPS
    private var query = ""
    private val chips = listOf(R.id.filter_apps to Filter.APPS, R.id.filter_recent to Filter.RECENT, R.id.filter_system to Filter.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        icons = AppIcons(this)
        savedInstanceState?.getStringArray(STATE_PICKED)?.let { picked += it }

        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.confirm).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGES, picked.toTypedArray()))
            finish()
        }
        findViewById<ListView>(R.id.list).apply {
            adapter = this@PickerActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val app = this@PickerActivity.adapter.getItem(position)
                if (!picked.remove(app.packageName)) picked += app.packageName
                this@PickerActivity.adapter.notifyDataSetChanged()
                updateConfirm()
            }
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refilter()
            }
        })
        chips.forEach { (id, f) -> findViewById<View>(id).setOnClickListener { selectFilter(f) } }
        selectFilter(Filter.APPS)
        updateConfirm()

        // Usually the home screen loaded the list moments ago; only load when it hasn't.
        val latest = appWall.apps.latest
        if (latest != null) {
            setApps(latest)
        } else {
            findViewById<View>(R.id.loading).visibility = View.VISIBLE
            appWall.apps.load { loaded ->
                findViewById<View>(R.id.loading).visibility = View.GONE
                setApps(loaded)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_PICKED, picked.toTypedArray())
    }

    private fun setApps(apps: List<AppEntry>) {
        allowed = apps.filter { !settings.policy.isBlocked(it.packageName) }
        findViewById<TextView>(R.id.allowed_total).text = getString(R.string.picker_allowed_total, allowed.size)
        refilter()
    }

    private fun selectFilter(f: Filter) {
        filter = f
        chips.forEach { (id, chipFilter) -> findViewById<View>(id).isSelected = chipFilter == f }
        refilter()
    }

    private fun refilter() {
        val matching = allowed.filter {
            query.isEmpty() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        val weekAgo = System.currentTimeMillis() - RECENT_MS
        adapter.shown = when (filter) {
            Filter.APPS -> matching.filter { !it.isSystemService }
            Filter.RECENT -> matching.filter { !it.isSystem && it.installedAt >= weekAgo }.sortedByDescending { it.installedAt }
            Filter.SYSTEM -> matching.filter { it.isSystem }
        }
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.empty).apply {
            visibility = if (adapter.shown.isEmpty() && allowed.isNotEmpty()) View.VISIBLE else View.GONE
            setText(
                when {
                    query.isNotEmpty() -> R.string.empty_search
                    filter == Filter.RECENT -> R.string.picker_empty_recent
                    else -> R.string.picker_empty
                }
            )
        }
    }

    private fun updateConfirm() {
        findViewById<Button>(R.id.confirm).apply {
            isEnabled = picked.isNotEmpty()
            text = if (picked.isEmpty()) {
                getString(R.string.picker_select)
            } else {
                resources.getQuantityString(R.plurals.picker_block_n, picked.size, picked.size)
            }
        }
    }

    private inner class PickAdapter : BaseAdapter() {
        var shown: List<AppEntry> = emptyList()

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_pick, parent, false)
            val app = shown[position]
            val checked = app.packageName in picked
            view.findViewById<ImageView>(R.id.pick_icon).setImageDrawable(icons[app.packageName])
            view.findViewById<TextView>(R.id.pick_label).text = app.label
            view.findViewById<TextView>(R.id.pick_package).text = app.packageName
            view.findViewById<CheckBox>(R.id.pick_check).isChecked = checked
            view.contentDescription = getString(if (checked) R.string.picker_row_selected else R.string.picker_row, app.label)
            return view
        }
    }

    companion object {
        /** Result: the packages the user chose to block. */
        const val EXTRA_PACKAGES = "dev.appwall.PICKED_PACKAGES"
        private const val STATE_PICKED = "picked"
        private const val RECENT_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
