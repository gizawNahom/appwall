package dev.appwall.core.log

import dev.appwall.core.net.Packet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One blocked try as the tunnel saw it: which app (null = unknown), where to, and when. */
data class BlockedTry(val owner: String?, val packet: Packet, val time: Long)

/**
 * Carries blocked tries from the tunnel thread to the database thread, so the tunnel never waits
 * on the disk. [offer] never blocks: when the queue is full, the try is dropped and counted.
 *
 * The first try offered after a [drain] calls [flushSoon], which should drain the queue (on the
 * database thread) within a second; tries arriving meanwhile join that batch.
 */
class TryQueue(
    capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val flushSoon: () -> Unit,
) {
    private val queue = ArrayBlockingQueue<BlockedTry>(capacity)
    private val flushPending = AtomicBoolean(false)
    private val dropped = AtomicLong()

    /** Tries dropped because the queue was full, since this queue was made. */
    val droppedTries: Long get() = dropped.get()

    fun offer(owner: String?, packet: Packet) {
        if (!queue.offer(BlockedTry(owner, packet, clock()))) dropped.incrementAndGet()
        if (flushPending.compareAndSet(false, true)) flushSoon()
    }

    /** Takes every try queued so far, oldest first. */
    fun drain(): List<BlockedTry> {
        // Cleared first: a try offered while draining either joins this batch or asks for the next.
        flushPending.set(false)
        return ArrayList<BlockedTry>().also { queue.drainTo(it) }
    }

    companion object {
        /** About 20 s of a pathological 100 new tries a second, should the database thread stall. */
        const val CAPACITY = 2_048
    }
}
