package dev.appwall.core.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFitTest {

    @Test
    fun aPhoneUprightKeepsItsCardPinned() {
        assertEquals(true, screenFit(widthDp = 384, heightDp = 780, fontScale = 1f).pinnedHeader)
        assertEquals(true, screenFit(widthDp = 384, heightDp = 780, fontScale = 1.3f).pinnedHeader)
    }

    @Test
    fun aShortWindowLetsTheCardScrollAway() {
        val table = listOf(
            Triple(800, 360, 1f), // a phone on its side
            Triple(384, 400, 1f), // half of a split screen
            Triple(384, 780, 2f), // upright, with the largest text
        )
        table.forEach { (width, height, scale) ->
            assertEquals("$width x $height at $scale", false, screenFit(width, height, scale).pinnedHeader)
        }
    }

    @Test
    fun theCardStaysPinnedFromTheMinimumHeightUp() {
        assertEquals(true, screenFit(widthDp = 384, heightDp = PINNED_HEADER_MIN_HEIGHT_DP, fontScale = 1f).pinnedHeader)
        assertEquals(false, screenFit(widthDp = 384, heightDp = PINNED_HEADER_MIN_HEIGHT_DP - 1, fontScale = 1f).pinnedHeader)
        // Twice the text needs twice the height.
        assertEquals(true, screenFit(widthDp = 384, heightDp = 2 * PINNED_HEADER_MIN_HEIGHT_DP, fontScale = 2f).pinnedHeader)
        assertEquals(false, screenFit(widthDp = 384, heightDp = 2 * PINNED_HEADER_MIN_HEIGHT_DP - 1, fontScale = 2f).pinnedHeader)
    }

    @Test
    fun smallTextNeverPinsAShortWindow() {
        // Below 1x the card shrinks, but the list still needs the room.
        assertEquals(false, screenFit(widthDp = 800, heightDp = 360, fontScale = 0.85f).pinnedHeader)
    }

    @Test
    fun narrowWindowsKeepTheUsualGutter() {
        assertEquals(16, screenFit(widthDp = 320, heightDp = 600, fontScale = 1f).sideGutterDp)
        assertEquals(16, screenFit(widthDp = 384, heightDp = 780, fontScale = 1f).sideGutterDp)
        assertEquals(16, screenFit(widthDp = MAX_CONTENT_DP + 32, heightDp = 780, fontScale = 1f).sideGutterDp)
    }

    @Test
    fun wideWindowsCenterTheContentAtItsMostReadableWidth() {
        listOf(800, 1280, 1920).forEach { width ->
            val gutter = screenFit(widthDp = width, heightDp = 800, fontScale = 1f).sideGutterDp
            assertEquals("width $width", MAX_CONTENT_DP, width - 2 * gutter)
        }
    }
}
