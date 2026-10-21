package dev.appwall.core.state

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps one screen's data current while it's visible, without polling: it loads on [start] and
 * whenever a watched [ChangeSignal] fires, until [stop]. Call [start], [stop] and [reload] on the
 * [ui] thread.
 *
 * [prepare] runs on the UI thread and returns the load to run on the [worker], so it can capture
 * what the screen currently shows (a tab, an expanded row). Reloads asked for while one is still
 * queued collapse into it, running the newest load. Results reach [show] on the UI thread, and
 * never after [stop]; a load that throws goes to [onError] instead.
 */
class ScreenLoader<T>(
    private val worker: Executor,
    private val ui: Scheduler,
    private val onError: (Exception) -> Unit,
    private val prepare: () -> () -> T,
    private val show: (T) -> Unit,
) {
    private val queued = AtomicReference<(() -> T)?>(null)
    private var subscriptions = emptyList<Subscription>()
    private var started = false

    fun start(vararg signals: ChangeSignal) {
        if (started) return
        started = true
        subscriptions = signals.map { it.subscribe(::reload) }
        reload()
    }

    fun stop() {
        started = false
        subscriptions.forEach(Subscription::unsubscribe)
        subscriptions = emptyList()
    }

    fun reload() {
        if (!started) return
        if (queued.getAndSet(prepare()) == null) worker.execute(::run)
    }

    private fun run() {
        val load = queued.getAndSet(null) ?: return
        val result = try {
            load()
        } catch (e: Exception) {
            onError(e)
            return
        }
        ui.post { if (started) show(result) }
    }
}
