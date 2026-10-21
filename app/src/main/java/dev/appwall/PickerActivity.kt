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
import dev.appwall.core.screens.PickerEmpty
import dev.appwall.core.screens.PickerFilter
import dev.appwall.core.screens.pickerState

/**
 * "Block an app": pick any number of allowed apps and block them together. Returns the chosen
 * packages as [EXTRA_PACKAGES]; the home screen applies them (so it can offer Undo). What it
 * shows comes from [pickerState]; this only keeps the inputs and draws.
 */
class PickerActivity : Activity() {

    private val settings get() = appWall.settings
    private lateinit var icons: AppIcons
    private val adapter = PickAdapter()
    private var apps: List<AppEntry>? = null
    private val picked = LinkedHashSet<String>()
    private var filter = PickerFilter.APPS
    private var query = ""
    private val chips = listOf(R.id.filter_apps to PickerFilter.APPS, R.id.filter_recent to PickerFilter.RECENT, R.id.filter_system to PickerFilter.SYSTEM)

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
                render()
            }
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })
        chips.forEach { (id, chipFilter) ->
            findViewById<View>(id).setOnClickListener {
                filter = chipFilter
                render()
            }
        }

        // Usually the home screen loaded the list moments ago; only load when it hasn't.
        apps = appWall.apps.latest
        if (apps == null) {
            appWall.apps.load { loaded ->
                apps = loaded
                render()
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_PICKED, picked.toTypedArray())
    }

    private fun render() {
        val state = pickerState(apps, settings.policy.blocked, filter, query, picked, System.currentTimeMillis())
        chips.forEach { (id, chipFilter) -> findViewById<View>(id).isSelected = chipFilter == filter }
        findViewById<View>(R.id.loading).visibility = if (state.shown == null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.allowed_total).text =
            if (state.shown == null) "" else getString(R.string.picker_allowed_total, state.allowedTotal)
        adapter.shown = state.shown.orEmpty()
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.empty).apply {
            visibility = if (state.empty == PickerEmpty.NONE) View.GONE else View.VISIBLE
            when (state.empty) {
                PickerEmpty.NONE -> Unit
                PickerEmpty.NO_MATCH -> setText(R.string.empty_search)
                PickerEmpty.NO_RECENT -> setText(R.string.picker_empty_recent)
                PickerEmpty.NOTHING -> setText(R.string.picker_empty)
            }
        }
        findViewById<Button>(R.id.confirm).apply {
            isEnabled = state.pickedCount > 0
            text = if (state.pickedCount == 0) {
                getString(R.string.picker_select)
            } else {
                resources.getQuantityString(R.plurals.picker_block_n, state.pickedCount, state.pickedCount)
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
    }
}
