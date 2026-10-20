package dev.appwall.core.log

import dev.appwall.core.net.Packet

/** How to log one try: add a row, or bump the count of a recent row for the same place. */
sealed interface LogWrite {
    data object Insert : LogWrite
    data class Bump(val rowId: Long) : LogWrite
}

/**
 * Folds repeat tries into one log row. A row takes repeat tries to the same place for [windowMs]
 * after its first try; the next one after that starts a new row, so a steady stream of tries
 * stays spread across the minutes it happened in.
 */
class LogMerger(private val windowMs: Long = WINDOW_MS) {

    /** Rows still open for repeats, by [key]: row id and the row's first try time. */
    private val open = HashMap<String, Pair<Long, Long>>()

    fun plan(key: String, now: Long): LogWrite {
        val (rowId, firstTry) = open[key] ?: return LogWrite.Insert
        return if (now - firstTry < windowMs) LogWrite.Bump(rowId) else LogWrite.Insert
    }

    /** A row was added for [key] at [now]. */
    fun inserted(key: String, rowId: Long, now: Long) {
        open[key] = rowId to now
    }

    /** Forgets rows whose window has closed. */
    fun forget(now: Long) {
        open.values.removeAll { now - it.second >= windowMs }
    }

    /** The log was cleared: no row is open any more. */
    fun clear() = open.clear()

    companion object {
        const val WINDOW_MS = 60_000L

        /** What makes two tries "the same place": app, protocol, address, port and looked-up name. */
        fun key(owner: String?, packet: Packet): String =
            listOf(owner, packet.protocol, packet.destination.hostAddress, packet.destinationPort, packet.dnsQuery).joinToString("|")
    }
}
