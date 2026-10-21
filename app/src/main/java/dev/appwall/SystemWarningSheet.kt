package dev.appwall

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import dev.appwall.core.apps.AppEntry
import dev.appwall.core.screens.SystemAppWarning
import dev.appwall.core.screens.joinNames

/**
 * The bottom sheet asked before blocking part of Android. What it offers comes from
 * [SystemAppWarning]: one app on its own can be kept allowed or blocked anyway; several offer
 * blocking only the others (the safe choice, when there are others), all of them anyway, or Back.
 * [block] is handed whichever apps the person chose to block.
 */
object SystemWarningSheet {

    /** A name list taller than this scrolls inside its box. */
    private const val NAMES_MAX_DP = 132
    private const val NAMES_BEFORE_SCROLL = 4

    fun show(activity: Activity, warning: SystemAppWarning, block: (List<AppEntry>) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.sheet_system_warning, null)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setDimAmount(0.55f)
                setWindowAnimations(android.R.style.Animation_InputMethod) // rises from the bottom edge
            }
        }
        fun choose(apps: List<AppEntry>) {
            dialog.dismiss()
            block(apps)
        }

        val all = warning.system + warning.others
        val names = warning.system.map { it.label }
        val label = view.findViewById<TextView>(R.id.sheet_label)
        val title = view.findViewById<TextView>(R.id.sheet_title)
        val body = view.findViewById<TextView>(R.id.sheet_body)

        if (all.size == 1) {
            label.setText(R.string.warn_label_one)
            title.text = activity.getString(R.string.warn_title_one, names[0])
            body.text = activity.getString(R.string.warn_one, names[0])
            view.findViewById<View>(R.id.sheet_pair).visibility = View.VISIBLE
            view.findViewById<Button>(R.id.sheet_keep).setOnClickListener { dialog.dismiss() }
            view.findViewById<Button>(R.id.sheet_anyway).setOnClickListener { choose(all) }
        } else {
            label.setText(R.string.warn_label_many)
            title.text = activity.getString(R.string.warn_title_many, all.size)
            body.text = when {
                !warning.namesInline -> activity.getString(R.string.warn_counted, names.size)
                names.size == 1 -> activity.getString(R.string.warn_inline_one, names[0])
                else -> activity.getString(R.string.warn_inline_many, joinNames(names, activity.getString(R.string.warn_and)))
            }
            if (!warning.namesInline) {
                view.findViewById<TextView>(R.id.sheet_names).text = names.joinToString("\n")
                view.findViewById<View>(R.id.sheet_names_box).apply {
                    visibility = View.VISIBLE
                    if (names.size > NAMES_BEFORE_SCROLL) {
                        layoutParams.height = (NAMES_MAX_DP * activity.resources.displayMetrics.density).toInt()
                    }
                }
            }
            view.findViewById<View>(R.id.sheet_stack).visibility = View.VISIBLE
            view.findViewById<Button>(R.id.sheet_others).apply {
                visibility = if (warning.canBlockOthers) View.VISIBLE else View.GONE
                val others = warning.others.size
                text = activity.resources.getQuantityString(R.plurals.warn_block_others, others, others)
                setOnClickListener { choose(warning.others) }
            }
            view.findViewById<Button>(R.id.sheet_all).apply {
                text = activity.getString(R.string.warn_block_all, all.size)
                setOnClickListener { choose(all) }
            }
            view.findViewById<Button>(R.id.sheet_back).setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }
}
