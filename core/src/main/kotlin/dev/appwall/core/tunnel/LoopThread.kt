package dev.appwall.core.tunnel

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Where a [TunnelSession]'s loop runs, and how it waits there while it has no tunnel to read:
 * a [LoopThread] in the service. Tests run the loop's turns by hand instead.
 */
interface LoopHost {
    /** Starts running [loop], which returns once the session stops or fails. */
    fun launch(loop: Runnable)

    /** Waits up to [timeoutMs], or less if [wake] is called, even just before. */
    fun idle(timeoutMs: Int)

    /** Ends the current [idle] early, or else the next one. */
    fun wake()
}

/** Runs the loop on a thread of its own, named [name]. */
class LoopThread(private val name: String) : LoopHost {
    /** At most one wake-up: one that comes before an idle isn't lost, and several don't pile up. */
    private val wakeUps = ArrayBlockingQueue<Unit>(1)

    override fun launch(loop: Runnable) = Thread(loop, name).start()

    override fun idle(timeoutMs: Int) {
        wakeUps.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun wake() {
        wakeUps.offer(Unit)
    }
}
