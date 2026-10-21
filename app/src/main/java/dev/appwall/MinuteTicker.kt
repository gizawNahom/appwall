package dev.appwall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Calls [onTick] on the UI thread at every minute change while started, so "2 min ago" stays
 * true on a screen nobody touches. Android sends the tick itself: no timer, and nothing is
 * read from the log. Start in onStart and stop in onStop.
 */
class MinuteTicker(private val context: Context, private val onTick: () -> Unit) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onTick()
    }
    private var started = false

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(receiver)
    }
}
