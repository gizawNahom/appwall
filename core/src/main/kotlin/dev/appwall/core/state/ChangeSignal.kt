package dev.appwall.core.state

import java.util.concurrent.CopyOnWriteArrayList

/** Runs tasks on the thread listeners expect, e.g. Android's main thread. */
interface Scheduler {
    fun post(task: () -> Unit)
    fun postDelayed(delayMs: Long, task: () -> Unit)
}

/** Stops a listener from hearing about further changes. */
fun interface Subscription {
    fun unsubscribe()
}

/**
 * Says "something changed", nothing more: listeners re-read whatever they show. Anyone may call
 * [notifyChanged] from any thread; listeners hear it on the [scheduler]'s thread.
 *
 * Changes that arrive before a delivery runs are coalesced into it. With [throttleMs], deliveries
 * are at least that far apart, and the last change is always delivered (a trailing delivery).
 */
class ChangeSignal(
    private val scheduler: Scheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttleMs: Long = 0,
) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    private var scheduled = false
    private var lastDelivery: Long? = null

    fun subscribe(listener: () -> Unit): Subscription {
        listeners += listener
        return Subscription { listeners -= listener }
    }

    fun notifyChanged() {
        val wait = synchronized(lock) {
            if (scheduled) return
            scheduled = true
            lastDelivery?.let { it + throttleMs - clock() } ?: 0
        }
        if (wait > 0) scheduler.postDelayed(wait, ::deliver) else scheduler.post(::deliver)
    }

    private fun deliver() {
        synchronized(lock) {
            scheduled = false
            lastDelivery = clock()
        }
        // A change during this delivery schedules another, so none is lost.
        for (listener in listeners) listener()
    }
}
