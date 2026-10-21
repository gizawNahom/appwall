package dev.appwall.core.state

/** Runs tasks on the thread listeners expect, e.g. Android's main thread, by its clock. */
interface Scheduler {
    /** The time on the clock [postDelayed] counts by, in milliseconds. */
    val now: Long
    fun post(task: Runnable)
    /** Runs [task] after [delayMs]; at once, like [post], if that's 0 or less. */
    fun postDelayed(delayMs: Long, task: Runnable)
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
    private val throttleMs: Long = 0,
) {
    private val lock = Any()
    /** Replaced as a whole on every change, so a delivery can go through it without the lock. */
    @Volatile private var listeners = emptyList<() -> Unit>()
    private var scheduled = false
    private var lastDelivery: Long? = null

    fun subscribe(listener: () -> Unit): Subscription {
        synchronized(lock) { listeners = listeners + listener }
        return Subscription { synchronized(lock) { listeners = listeners - listener } }
    }

    fun notifyChanged() {
        val wait = synchronized(lock) {
            if (scheduled) return
            scheduled = true
            lastDelivery?.let { it + throttleMs - scheduler.now } ?: 0
        }
        scheduler.postDelayed(wait, ::deliver)
    }

    private fun deliver() {
        synchronized(lock) {
            scheduled = false
            lastDelivery = scheduler.now
        }
        // A change during this delivery schedules another, so none is lost.
        for (listener in listeners) listener()
    }
}
