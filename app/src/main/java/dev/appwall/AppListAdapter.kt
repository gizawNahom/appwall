package dev.appwall

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView

class AppListAdapter(
    private val context: Context,
    private val rules: Rules,
    private val onRulesChanged: () -> Unit,
) : BaseAdapter() {

    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""
    private val icons = HashMap<String, Drawable?>()

    fun setApps(apps: List<AppEntry>) {
        allApps = apps
        refilter()
    }

    fun setQuery(text: String) {
        query = text.trim()
        refilter()
    }

    /** Flips the block rule for the app at [position] in the shown list. */
    fun toggle(position: Int) {
        val app = shown[position]
        rules.setBlocked(app.packageName, !rules.isBlocked(app.packageName))
        notifyDataSetChanged()
        onRulesChanged()
    }

    private fun refilter() {
        shown = if (query.isEmpty()) allApps else allApps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val app = shown[position]
        view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(iconFor(app.packageName))
        view.findViewById<TextView>(R.id.app_label).text = app.label
        view.findViewById<TextView>(R.id.app_package).text =
            if (app.isSystem) context.getString(R.string.system_app, app.packageName) else app.packageName
        view.findViewById<Switch>(R.id.app_blocked).isChecked = rules.isBlocked(app.packageName)
        return view
    }

    private fun iconFor(packageName: String): Drawable? = icons.getOrPut(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
