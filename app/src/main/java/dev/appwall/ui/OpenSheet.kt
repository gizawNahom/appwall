package dev.appwall.ui

import android.app.Activity
import android.app.Dialog
import android.os.Bundle

/**
 * Remembers which sheet a screen has open, so that when Android rebuilds the screen (rotation,
 * split-screen, or after it was killed in the background) the question is asked again instead of
 * silently dropped. Each sheet is named by a key and a few string arguments; the screen turns
 * those back into its sheet in [reopen].
 *
 * Call [save] from onSaveInstanceState, [restore] at the end of onCreate, and [close] from
 * onDestroy.
 */
class OpenSheet(private val activity: Activity, private val reopen: (key: String, args: List<String>) -> Unit) {
    private var dialog: Dialog? = null
    private var key: String? = null
    private var args: List<String> = emptyList()

    /** Shows [sheet] as the screen's open sheet [key]. */
    fun show(sheet: AppSheet, key: String, args: List<String> = emptyList()) {
        close()
        this.key = key
        this.args = args
        val shown = sheet.show(activity)
        dialog = shown
        // A choice, Back or a tap outside: answered, so nothing to ask again.
        shown.setOnDismissListener { if (dialog === shown) forget() }
    }

    fun save(outState: Bundle) {
        val open = key ?: return
        outState.putString(STATE_KEY, open)
        outState.putStringArray(STATE_ARGS, args.toTypedArray())
    }

    fun restore(savedInstanceState: Bundle?) {
        val open = savedInstanceState?.getString(STATE_KEY) ?: return
        reopen(open, savedInstanceState.getStringArray(STATE_ARGS)?.toList().orEmpty())
    }

    /** Closes the sheet without counting it as answered; the screen is going away. */
    fun close() {
        val open = dialog ?: return
        dialog = null
        open.dismiss()
    }

    private fun forget() {
        dialog = null
        key = null
        args = emptyList()
    }

    private companion object {
        const val STATE_KEY = "open_sheet"
        const val STATE_ARGS = "open_sheet_args"
    }
}
