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
import dev.appwall.core.screens.HomeItem
import dev.appwall.core.screens.HomeSection
import dev.appwall.core.screens.HomeState

/**
 * Home's one list. Draws what [HomeState] decided, nothing more: section headings, then each app
 * with the one action that fits it. Taps go back to the screen through [onAction].
 */
class AppListAdapter(
    private val context: Context,
    private val icons: AppIcons,
    private val onAction: (AppEntry, Action) -> Unit,
) : BaseAdapter() {

    /** BLOCK or UNBLOCK is the row's button; OPEN is a tap on the row itself (its log). */
    enum class Action { BLOCK, UNBLOCK, OPEN }

    private var items: List<HomeItem> = emptyList()

    fun show(state: HomeState) {
        items = state.items
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (items[position] is HomeItem.Row) 1 else 0
    override fun isEnabled(position: Int) = items[position] is HomeItem.Row

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val item = items[position]) {
            is HomeItem.Heading -> heading(item, convertView, parent)
            is HomeItem.Row -> row(item, convertView, parent)
        }

    private fun heading(item: HomeItem.Heading, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_section, parent, false)
        val label = context.getString(if (item.section == HomeSection.BLOCKED) R.string.section_blocked else R.string.section_allowed)
        (view as TextView).text = context.getString(R.string.section_heading, label, item.count)
        return view
    }

    private fun row(item: HomeItem.Row, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val app = item.app
        view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(icons[app.packageName])
        view.findViewById<TextView>(R.id.app_label).text = app.label
        view.findViewById<TextView>(R.id.app_sub).apply {
            if (item.blocked) {
                text = context.getString(R.string.row_no_internet)
                setTextColor(context.getColor(R.color.blocked))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_blocked, 0, 0, 0)
            } else {
                text = if (app.isSystem) context.getString(R.string.row_part_of_android, app.packageName) else app.packageName
                setTextColor(context.getColor(R.color.text_secondary))
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
        }
        view.findViewById<Button>(R.id.app_action).apply {
            setText(if (item.blocked) R.string.unblock else R.string.block)
            setTextColor(context.getColor(if (item.blocked) R.color.accent else R.color.text))
            contentDescription = context.getString(if (item.blocked) R.string.unblock_app else R.string.block_app_named, app.label)
            setOnClickListener { onAction(app, if (item.blocked) Action.UNBLOCK else Action.BLOCK) }
        }
        view.contentDescription = context.getString(
            if (item.blocked) R.string.row_blocked_description else R.string.row_allowed_description, app.label,
        )
        return view
    }
}
