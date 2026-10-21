package dev.appwall

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AppWall's one way to ask something: a bottom sheet with a small [label], a [title], a [body],
 * optional [names] in a scrolling box, and its [choices]. Two choices sit side by side; more stack,
 * first on top. Every choice closes the sheet; so do Back and a tap outside it.
 */
class AppSheet(
    private val title: CharSequence,
    private val body: CharSequence,
    private val choices: List<Choice>,
    private val label: CharSequence? = null,
    private val names: List<String> = emptyList(),
) {
    /** How a choice looks, by what it does. */
    enum class Role {
        /** The safe main choice: solid accent. */
        MAIN,
        /** A choice that is risky on purpose (block part of Android, clear the log): solid coral. */
        RISKY,
        /** The risky choice beside a safer main one: coral outline, so it doesn't compete. */
        RISKY_QUIET,
        /** The safe way out beside a risky choice: plain outline. */
        SECONDARY,
        /** Back or Cancel at the bottom of a stack: text only. */
        QUIET,
    }

    class Choice(val text: CharSequence, val role: Role, val onChoose: () -> Unit = {})

    fun show(activity: Activity) {
        val view = activity.layoutInflater.inflate(R.layout.sheet, null)
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

        view.findViewById<TextView>(R.id.sheet_label).apply {
            text = label
            visibility = if (label == null) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.sheet_title).text = title
        view.findViewById<TextView>(R.id.sheet_body).text = body
        if (names.isNotEmpty()) {
            view.findViewById<TextView>(R.id.sheet_names).text = names.joinToString("\n")
            view.findViewById<View>(R.id.sheet_names_box).apply {
                visibility = View.VISIBLE
                if (names.size > NAMES_BEFORE_SCROLL) layoutParams.height = activity.dp(NAMES_MAX_DP)
            }
        }

        val sideBySide = choices.size == 2
        val row = view.findViewById<LinearLayout>(R.id.sheet_choices)
        row.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        choices.forEachIndexed { index, choice ->
            val button = button(activity, choice)
            button.setOnClickListener {
                dialog.dismiss()
                choice.onChoose()
            }
            val params = if (sideBySide) {
                LinearLayout.LayoutParams(0, activity.dp(BUTTON_DP), 1f).apply { if (index > 0) marginStart = activity.dp(GAP_DP) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(BUTTON_DP)).apply {
                    if (index > 0) topMargin = activity.dp(if (choice.role == Role.QUIET) 4 else GAP_DP)
                }
            }
            row.addView(button, params)
        }
        dialog.show()
    }

    private fun button(activity: Activity, choice: Choice) = Button(activity).apply {
        text = choice.text
        setTextAppearance(R.style.TextAppearance_AppWall_Button)
        isAllCaps = false
        stateListAnimator = null
        val (background, color) = when (choice.role) {
            Role.MAIN -> R.drawable.bg_button_accent to R.color.ground
            Role.RISKY -> R.drawable.bg_button_blocked to R.color.on_blocked
            Role.RISKY_QUIET -> R.drawable.bg_outline to R.color.blocked
            Role.SECONDARY -> R.drawable.bg_outline to R.color.text
            Role.QUIET -> activity.themeResource(android.R.attr.selectableItemBackground) to R.color.text_secondary
        }
        setBackgroundResource(background)
        setTextColor(activity.getColor(color))
    }

    private companion object {
        const val BUTTON_DP = 48
        const val GAP_DP = 10
        /** A name list taller than this scrolls inside its box. */
        const val NAMES_MAX_DP = 132
        const val NAMES_BEFORE_SCROLL = 4

        fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

        fun Activity.themeResource(attr: Int): Int =
            TypedValue().also { theme.resolveAttribute(attr, it, true) }.resourceId
    }
}
