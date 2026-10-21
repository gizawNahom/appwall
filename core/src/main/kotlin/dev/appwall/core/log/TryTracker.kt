package dev.appwall.core.log

import dev.appwall.core.net.Packet

/**
 * Turns the packets blocked apps send into "tries": one per connection, not one per packet.
 *
 * Blocked apps never get an answer, so they resend: a TCP connection retransmits its opening
 * packet for a minute or more, and Android's resolver repeats each name lookup (and asks for IPv4
 * and IPv6 separately). Counting packets made a single page load look like hundreds of tries.
 *
 * - A connection (protocol, both addresses and ports) is one try for as long as it keeps sending.
 * - A name lookup is one try per app and name every [LOOKUP_WINDOW_MS].
 *
 * It also works harder to find out which app a try came from. Android sometimes can't say on the
 * first packet, so an unidentified connection waits up to [PENDING_MS] and asks again on each
 * packet that follows. If Android still can't say, the try goes to the one blocked app that
 * connected to that same address and port shortly before, if exactly one did; otherwise it stays
 * unidentified rather than guessed.
 *
 * Not thread-safe: use it from the tunnel's reading thread only.
 */
class TryTracker(
    /** Which app sent the packet, as far as Android can tell, or null. */
    private val ownerOf: (Packet) -> String?,
    /** Called once per try, with the app (null if unidentified) and the try's first packet. */
    private val onTry: (owner: String?, packet: Packet) -> Unit,
) {
    private class Connection(val first: Packet, val start: Long) {
        var lastSeen = start
        var owner: String? = null
        var counted = false
    }

    // Typed as Kotlin maps, not HashMap, so reading them involves no Java platform types.
    private val connections: MutableMap<String, Connection> = HashMap()
    /** "app|name" to when that lookup last counted as a try. */
    private val lookups: MutableMap<String, Long> = HashMap()
    /** "address|port" to the one app seen connecting there and when; [AMBIGUOUS] if several. */
    private val destinations: MutableMap<String, Pair<String, Long>> = HashMap()
    private var lastSweep = 0L

    fun onPacket(packet: Packet, now: Long) {
        val connection = connections.getOrPut(connectionKey(packet)) { Connection(packet, now) }
        connection.lastSeen = now
        if (!connection.counted) {
            connection.owner = ownerOf(packet)
            if (connection.owner != null) count(connection, now)
        }
        if (now - lastSweep >= SWEEP_MS) sweep(now)
    }

    /**
     * Settles connections that waited long enough for an owner, and forgets old state. Called
     * from [onPacket] at most every [SWEEP_MS]; call it regularly when no packets arrive, too.
     */
    fun sweep(now: Long) {
        lastSweep = now
        // Forgotten first, so what's left is what settling may go by.
        lookups.values.removeAll { !isRepeatLookup(it, now) }
        destinations.values.removeAll { (_, time) -> !isLearned(time, now) }
        for (connection in connections.values) {
            if (!connection.counted && now - connection.start >= PENDING_MS) {
                connection.owner = learnedOwner(connection.first)
                count(connection, now)
            }
        }
        connections.values.removeAll { now - it.lastSeen >= CONNECTION_IDLE_MS }
    }

    private fun count(connection: Connection, now: Long) {
        connection.counted = true
        val packet = connection.first
        val owner = connection.owner
        val name = packet.dnsQuery
        if (name != null) {
            val key = "$owner|$name"
            val last = lookups[key]
            if (last != null && isRepeatLookup(last, now)) return
            lookups[key] = now
        } else if (owner != null) {
            val key = destinationKey(packet)
            val known = destinations[key]?.first
            destinations[key] = (if (known == null || known == owner) owner else AMBIGUOUS) to now
        }
        onTry(owner, packet)
    }

    /** A lookup now repeats the one that counted at [last]: the same app and name, too soon after. */
    private fun isRepeatLookup(last: Long, now: Long) = now - last < LOOKUP_WINDOW_MS

    /** An app seen at a destination at [time] still says who connects there. */
    private fun isLearned(time: Long, now: Long) = now - time < LEARN_MS

    /** The one blocked app recently seen connecting to the packet's destination, if just one. */
    private fun learnedOwner(packet: Packet): String? {
        // A lookup's destination is AppWall's own DNS server, which says nothing about the app.
        if (packet.dnsQuery != null) return null
        return destinations[destinationKey(packet)]?.first.takeIf { it != AMBIGUOUS }
    }

    private fun connectionKey(p: Packet) =
        "${p.protocol}|${p.source.hostAddress}|${p.sourcePort}|${p.destination.hostAddress}|${p.destinationPort}|${p.dnsQuery}"

    private fun destinationKey(p: Packet) = "${p.destination.hostAddress}|${p.destinationPort}"

    companion object {
        /** How long an unidentified connection waits for Android to name its app. */
        const val PENDING_MS = 3_000L
        /** A connection quiet this long is over; TCP gives up retransmitting within about two minutes. */
        const val CONNECTION_IDLE_MS = 120_000L
        const val LOOKUP_WINDOW_MS = 30_000L
        /** How long a destination remembers which app connected to it. */
        const val LEARN_MS = 10 * 60_000L
        /** [TryTracker.onPacket] sweeps at most this often; settling an unidentified try can lag by this much. */
        const val SWEEP_MS = 1_000L
        private const val AMBIGUOUS = ""
    }
}
