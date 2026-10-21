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

    private class Row(val id: Long, val firstTry: Long)

    /** Rows that may still be open for repeats, by [key]. A Kotlin map: no Java platform types. */
    private val rows: MutableMap<String, Row> = HashMap()

    fun plan(key: String, now: Long): LogWrite {
        val row = rows[key]
        return if (row != null && takesRepeats(row, now)) LogWrite.Bump(row.id) else LogWrite.Insert
    }

    /** A row was added for [key] at [now]. */
    fun inserted(key: String, rowId: Long, now: Long) {
        rows[key] = Row(rowId, now)
    }

    /** Forgets rows whose window has closed. */
    fun forget(now: Long) {
        rows.values.removeAll { !takesRepeats(it, now) }
    }

    /** The log was cleared: no row is open any more. */
    fun clear() = rows.clear()

    /** A row's window: it takes repeat tries until [windowMs] after its first. */
    private fun takesRepeats(row: Row, now: Long) = now - row.firstTry < windowMs

    companion object {
        const val WINDOW_MS = 60_000L

        /** What makes two tries "the same place": app, protocol, address, port and looked-up name. */
        fun key(owner: String?, packet: Packet): String =
            listOf(owner, packet.protocol, packet.destination.hostAddress, packet.destinationPort, packet.dnsQuery).joinToString("|")
    }
}
