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
 * (within [pollMs]) and closes the old one. Two readers never exist. The loop runs, and idles
 * while it has no tunnel, where [host] says: on a [LoopThread] in the service.
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
    /** The loop stopped on its own; called from the loop. */
    private val onFailure: (Throwable?) -> Unit,
    /** A packet was skipped because handling it failed; [count] so far, for occasional logging. */
    private val onSkipped: (count: Int, error: Exception) -> Unit,
    private val clock: () -> Long,
    private val host: LoopHost,
    /** How long a read or an idle waits; also how long a hand-over can wait for the read in progress. */
    private val pollMs: Int,
) {
    @Volatile private var blocked: Set<String> = emptySet()

    private val tracker = TryTracker(::ownerOf, ::countTry)
    private var skipped = 0

    private val lock = Any()
    private var current: Tunnel? = null
    private var next: Tunnel? = null
    private var switchPending = false
    private var stopped = false
    private var running = false

    /** The blocked apps right now; replaced as a whole when the rules change. */
    fun updateBlocked(apps: Set<String>) {
        blocked = apps
    }

    /** Starts (or, after a failure, restarts) the loop. Does nothing if running or shut down. */
    fun start() {
        if (claimLoop()) host.launch(::loop)
    }

    /** Marks the loop as running, unless it already is or the session is shut down. */
    private fun claimLoop(): Boolean = synchronized(lock) {
        val free = !stopped && !running
        if (free) running = true
        free
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
            if (running) host.wake() else adopt()
        }
    }

    /** Stops for good and closes the tunnel(s), within [pollMs] if the loop is running. */
    fun shutdown() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            if (running) host.wake() else closeAll()
        }
    }

    private fun loop() {
        val buffer = ByteArray(32767)
        val failure = try {
            while (step(buffer)) Unit
            null
        } catch (e: Throwable) {
            // Errors too: an uncaught one would crash the app and drop the tunnel.
            e
        }
        loopEnded(failure)
    }

    /** The loop is over: close up if shut down, otherwise report the failure. */
    private fun loopEnded(failure: Throwable?) {
        val unexpected = synchronized(lock) {
            running = false
            if (stopped) closeAll()
            !stopped
        }
        if (unexpected) onFailure(failure)
    }

    /** One loop turn: take over a new tunnel if one is waiting, then read or idle. False once stopped. */
    internal fun step(buffer: ByteArray): Boolean {
        // Whether to go on, and with which tunnel, is decided in one go under the lock.
        var taken: Tunnel? = null
        val ending = synchronized(lock) {
            if (!stopped) {
                adopt()
                taken = current
            }
            stopped
        }
        if (ending) return false
        val tunnel = taken
        if (tunnel == null) {
            host.idle(pollMs) // nothing to read until switchTo() or shutdown() wakes it
            contained(::sweep)
            return true
        }
        val length = tunnel.packets.read(buffer, pollMs)
        when {
            length > 0 -> contained { handle(buffer, length) }
            length == 0 -> contained(::sweep)
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

    /** Reports the tries that have waited long enough for their app to be named. */
    private fun sweep() = tracker.sweep(clock())

    private fun ownerOf(packet: Packet): String? = pickOwner(owners.packagesOf(packet), blocked)

    private fun countTry(owner: String?, packet: Packet) = contained { onTry(owner, packet) }

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

    /** Runs [work]; if it fails, that packet (or sweep) is skipped and [onSkipped] is told. */
    private fun contained(work: Runnable) {
        try {
            work.run()
        } catch (e: Exception) {
            onSkipped(++skipped, e)
        }
    }

    /** The tunnel reported an error or hung up while no replacement was on its way. */
    class TunnelFailed : Exception("Tunnel hung up or failed")
}
