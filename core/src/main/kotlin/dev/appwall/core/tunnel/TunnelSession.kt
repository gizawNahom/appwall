package dev.appwall.core.tunnel

import dev.appwall.core.firewall.pickOwner
import dev.appwall.core.firewall.shouldCount
import dev.appwall.core.log.TryTracker
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser

/** Where a tunnel's packets come from. Never closes anything itself, whatever goes wrong. */
fun interface PacketSource {
    /**
     * Waits up to [timeoutMs] for a packet and reads it into [buffer]. Returns its length, 0 if
     * none came in time, or a negative number if the source hung up or failed.
     */
    fun read(buffer: ByteArray, timeoutMs: Int): Int
}

/** One established tunnel: its packets, and the single way to close it. */
interface Tunnel {
    val packets: PacketSource
    fun close()
}

/** Which packages own a packet's connection, as far as the system can tell (null if it can't). */
fun interface ConnectionOwners {
    fun packagesOf(packet: Packet): List<String>?
}

/**
 * Reads blocked apps' packets from the tunnel for the whole time the firewall runs, and turns
 * them into tries. Nothing is ever forwarded, so blocked apps stay blocked.
 *
 * There is one session per service run, with one [TryTracker], so rule changes don't reset what
 * it knows. A rule change hands it a new tunnel with [switchTo]; it switches at its next loop turn
 * (within [pollMs]) and closes the old one. Two readers never exist.
 *
 * The tunnel must stay up whatever goes wrong: closing it gives blocked apps their network back.
 * So a packet that fails is skipped, and a tunnel is closed in only two cases: replaced by
 * [switchTo], or [shutdown]. Every close happens under [lock] and at most once, by the loop while
 * it runs, otherwise by the caller. If reading itself fails, the tunnel is left open and
 * [onFailure] is told; [start] runs the loop again on the same tunnel.
 */
class TunnelSession(
    private val owners: ConnectionOwners,
    private val loggingEnabled: () -> Boolean,
    /** Called once per try; must not block for long. Failures are contained. */
    private val onTry: (owner: String?, packet: Packet) -> Unit,
    /** The loop stopped on its own; called from the session's thread. */
    private val onFailure: (Throwable?) -> Unit,
    /** A packet was skipped because handling it failed; [count] so far, for occasional logging. */
    private val onSkipped: (count: Int, error: Exception) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Int = 1000,
) {
    /** The blocked apps right now; replaced as a whole when the rules change. */
    @Volatile var blocked: Set<String> = emptySet()

    private val tracker = TryTracker({ pickOwner(owners.packagesOf(it), blocked) }) { owner, packet ->
        contained { onTry(owner, packet) }
    }
    private var skipped = 0

    private val lock = Object()
    private var current: Tunnel? = null
    private var next: Tunnel? = null
    private var switchPending = false
    private var stopped = false
    private var running = false

    /** Starts (or, after a failure, restarts) the loop. Does nothing if running or shut down. */
    fun start() {
        if (claimLoop()) Thread(::loop, "appwall-tunnel").start()
    }

    /** Marks the loop as running, unless it already is or the session is shut down. */
    internal fun claimLoop(): Boolean = synchronized(lock) {
        if (stopped || running) return false
        running = true
        true
    }

    /** Hands over a new tunnel (or none: nothing to block). The previous one gets closed. */
    fun switchTo(tunnel: Tunnel?) {
        synchronized(lock) {
            if (stopped) {
                tunnel?.close()
                return
            }
            // Replaced again before the loop took it: that one was never read, close it now.
            if (switchPending) next?.takeIf { it !== tunnel }?.close()
            next = tunnel
            switchPending = true
            if (running) lock.notifyAll() else adopt()
        }
    }

    /** Stops for good and closes the tunnel(s), within [pollMs] if the loop is running. */
    fun shutdown() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            if (running) lock.notifyAll() else closeAll()
        }
    }

    private fun loop() {
        val buffer = ByteArray(32767)
        var failure: Throwable? = null
        try {
            while (step(buffer)) Unit
        } catch (e: Throwable) {
            // Errors too: an uncaught one would crash the app and drop the tunnel.
            failure = e
        } finally {
            loopEnded(failure)
        }
    }

    /** The loop is over: close up if shut down, otherwise report the failure. */
    internal fun loopEnded(failure: Throwable?) {
        val unexpected = synchronized(lock) {
            running = false
            if (stopped) closeAll()
            !stopped
        }
        if (unexpected) onFailure(failure)
    }

    /** One loop turn: take over a new tunnel if one is waiting, then read or idle. False once stopped. */
    internal fun step(buffer: ByteArray): Boolean {
        val tunnel = synchronized(lock) {
            if (stopped) return false
            adopt()
            current ?: run {
                lock.wait(pollMs.toLong()) // nothing to read until switchTo() or shutdown()
                null
            }
        }
        if (tunnel == null) {
            contained { tracker.sweep(clock()) }
            return true
        }
        val length = tunnel.packets.read(buffer, pollMs)
        when {
            length > 0 -> contained { handle(buffer, length) }
            length == 0 -> contained { tracker.sweep(clock()) }
            // An old tunnel hangs up once its replacement is established: just switch.
            else -> synchronized(lock) { if (!switchPending && !stopped) throw TunnelFailed() }
        }
        return true
    }

    private fun handle(buffer: ByteArray, length: Int) {
        if (!loggingEnabled()) return
        val packet = PacketParser.parse(buffer, length) ?: return
        if (!shouldCount(packet, blocked.size)) return
        tracker.onPacket(packet, clock())
    }

    /** Holds [lock]. Takes the waiting tunnel, if any, and closes the one it replaces. */
    private fun adopt() {
        if (!switchPending) return
        val old = current
        current = next
        next = null
        switchPending = false
        if (old != null && old !== current) old.close()
    }

    /** Holds [lock]. */
    private fun closeAll() {
        adopt()
        current?.close()
        current = null
    }

    private inline fun contained(work: () -> Unit) {
        try {
            work()
        } catch (e: Exception) {
            onSkipped(++skipped, e)
        }
    }

    /** The tunnel reported an error or hung up while no replacement was on its way. */
    class TunnelFailed : Exception("Tunnel hung up or failed")
}
