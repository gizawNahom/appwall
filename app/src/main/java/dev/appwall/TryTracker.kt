package dev.appwall

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

    private val connections = HashMap<String, Connection>()
    /** "app|name" to when that lookup last counted as a try. */
    private val lookups = HashMap<String, Long>()
    /** "address|port" to the one app seen connecting there and when; [AMBIGUOUS] if several. */
    private val destinations = HashMap<String, Pair<String, Long>>()
    private var lastSweep = 0L

    fun onPacket(packet: Packet, now: Long) {
        val key = connectionKey(packet)
        val connection = connections[key]
        if (connection == null) {
            val created = Connection(packet, now)
            connections[key] = created
            created.owner = ownerOf(packet)
            if (created.owner != null) count(created, now)
        } else {
            connection.lastSeen = now
            if (!connection.counted) {
                connection.owner = ownerOf(packet)
                if (connection.owner != null) count(connection, now)
            }
        }
        if (now - lastSweep >= SWEEP_MS) sweep(now)
    }

    /**
     * Settles connections that waited long enough for an owner, and forgets old state. Called
     * from [onPacket]; call it regularly when no packets arrive, too.
     */
    fun sweep(now: Long) {
        lastSweep = now
        val iterator = connections.values.iterator()
        while (iterator.hasNext()) {
            val connection = iterator.next()
            if (!connection.counted && now - connection.start >= PENDING_MS) {
                connection.owner = learnedOwner(connection.first, now)
                count(connection, now)
            }
            if (now - connection.lastSeen >= CONNECTION_IDLE_MS) iterator.remove()
        }
        lookups.values.removeAll { now - it >= LOOKUP_WINDOW_MS }
        destinations.values.removeAll { now - it.second >= LEARN_MS }
    }

    private fun count(connection: Connection, now: Long) {
        connection.counted = true
        val packet = connection.first
        val owner = connection.owner
        val name = packet.dnsQuery
        if (name != null) {
            val key = "$owner|$name"
            val last = lookups[key]
            if (last != null && now - last < LOOKUP_WINDOW_MS) return
            lookups[key] = now
        } else if (owner != null) {
            val key = destinationKey(packet)
            val known = destinations[key]?.first
            destinations[key] = (if (known == null || known == owner) owner else AMBIGUOUS) to now
        }
        onTry(owner, packet)
    }

    /** The one blocked app recently seen connecting to the packet's destination, if just one. */
    private fun learnedOwner(packet: Packet, now: Long): String? {
        // A lookup's destination is AppWall's own DNS server, which says nothing about the app.
        if (packet.dnsQuery != null) return null
        val (owner, time) = destinations[destinationKey(packet)] ?: return null
        return owner.takeIf { it != AMBIGUOUS && now - time < LEARN_MS }
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
        private const val SWEEP_MS = 1_000L
        private const val AMBIGUOUS = ""
    }
}
