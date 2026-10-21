package dev.appwall

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import dev.appwall.core.screens.ScreenFit
import dev.appwall.core.screens.screenFit

/** How this screen fits its window right now; see [screenFit]. */
val Activity.fit: ScreenFit
    get() = resources.configuration.let { screenFit(it.screenWidthDp, it.screenHeightDp, it.fontScale) }

/** Centers [root]'s content on a wide window by widening its side padding. */
fun Activity.fitWidth(root: View = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) {
    val gutter = (fit.sideGutterDp * resources.displayMetrics.density).toInt()
    root.setPaddingRelative(gutter, root.paddingTop, gutter, root.paddingBottom)
}

/**
 * On a short window, moves [above] (the views between the screen's top bar and [list]) into the
 * list, so they scroll away with it instead of leaving it no room. Returns the header that now
 * holds them, or null when the window is tall enough to leave them pinned.
 *
 * A list detaches its header while it is scrolled off, so the activity can't find views in it by
 * id then: look them up in the returned header instead.
 */
fun Activity.scrollWithList(list: ListView, above: List<View>): ViewGroup? {
    if (fit.pinnedHeader) return null
    return moveInto(above).also { list.addHeaderView(it, null, false) }
}

/** A column holding [views], taken from where they were; each keeps its margins. */
private fun Activity.moveInto(views: List<View>) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    views.forEach { view ->
        (view.parent as ViewGroup).removeView(view)
        addView(view)
    }
}
