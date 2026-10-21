package dev.appwall.core.screens

/** Wider than this, rows are hard to scan: names sit far from their buttons. */
const val MAX_CONTENT_DP = 600

/** Below this height, a card pinned above a list leaves the list too little room. */
const val PINNED_HEADER_MIN_HEIGHT_DP = 560

/** The space beside content on a phone. */
const val GUTTER_DP = 16

/**
 * How a screen fits the window it has. [sideGutterDp] keeps content at most [MAX_CONTENT_DP] wide,
 * centered. [pinnedHeader] says whether the card above a list stays put; when it doesn't, it
 * scrolls away with the list. Android rebuilds a screen whenever its window changes (rotation,
 * split-screen, folding, text size), so this is worked out once per screen.
 */
data class ScreenFit(val sideGutterDp: Int, val pinnedHeader: Boolean)

/** The card grows with the text, so larger text needs a taller window to keep it pinned. */
fun screenFit(widthDp: Int, heightDp: Int, fontScale: Float): ScreenFit = ScreenFit(
    sideGutterDp = maxOf(GUTTER_DP, (widthDp - MAX_CONTENT_DP) / 2),
    pinnedHeader = heightDp >= PINNED_HEADER_MIN_HEIGHT_DP * fontScale.coerceAtLeast(1f),
)
