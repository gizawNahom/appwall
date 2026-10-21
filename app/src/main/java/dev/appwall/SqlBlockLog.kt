package dev.appwall

import dev.appwall.core.log.AppSummary
import dev.appwall.core.log.BlockedTry
import dev.appwall.core.log.Destination
import dev.appwall.core.log.LogEntry
import dev.appwall.core.log.LogMerger
import dev.appwall.core.log.LogWrite

/**
 * On-device log of blocked tries, in one SQL table. Repeat tries to the same place are folded
 * into rows by [LogMerger]. Entries expire after the user's chosen retention period.
 *
 * Talks only to [SqlDb], so all of its SQL runs in JVM tests too. Keep the SQL to what SQLite
 * 3.18 (Android 8) supports.
 *
 * Used only on the database thread, which [onDbThread] recognizes; any other caller fails loudly
 * rather than racing it. That one thread is also why nothing here needs a lock.
 */
class SqlBlockLog(
    private val db: SqlDb,
    private val retentionDays: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDbThread: () -> Boolean,
) {
    private val merger = LogMerger()
    private var insertsSincePrune = 0

    /** Writes a batch of tries from the tunnel, in one transaction. */
    fun record(tries: List<BlockedTry>) {
        checkThread()
        if (tries.isEmpty()) return
        try {
            db.transaction { tries.forEach(::write) }
        } catch (e: Exception) {
            merger.clear() // it may name rows the rollback took away
            throw e
        }
    }

    private fun write(attempt: BlockedTry) {
        val (packageName, packet, now) = attempt
        val address = packet.destination.hostAddress ?: return
        val key = LogMerger.key(packageName, packet)
        when (val write = merger.plan(key, now)) {
            is LogWrite.Bump -> db.execute("UPDATE log SET attempts = attempts + 1, time = ? WHERE _id = ?", listOf(now, write.rowId))
            LogWrite.Insert -> {
                val rowId = db.insert(
                    "INSERT INTO log (time, package, protocol, address, port, host, attempts) VALUES (?, ?, ?, ?, ?, ?, 1)",
                    listOf(now, packageName, packet.protocol, address, packet.destinationPort, packet.dnsQuery),
                )
                merger.inserted(key, rowId, now)
                if (++insertsSincePrune >= 100) {
                    insertsSincePrune = 0
                    db.execute("DELETE FROM log WHERE time < ?", listOf(now - retentionMs()))
                    merger.forget(now)
                }
            }
        }
    }

    /** One app's entries (null = the unattributed ones), newest first. */
    fun entriesFor(packageName: String?, limit: Int = 500): List<LogEntry> {
        checkThread()
        val (forApp, appArgs) = appFilter(packageName)
        return db.query(
            "SELECT time, protocol, address, port, host, attempts FROM log WHERE $forApp AND time >= ? ORDER BY time DESC LIMIT ?",
            appArgs + since() + limit,
        ) { LogEntry(it.long(0), packageName, it.int(1), it.string(2)!!, it.int(3), it.string(4), it.int(5)) }
    }

    /** Where one app (null = unattributed) tried to go over the retention period, most tried first. */
    fun destinations(packageName: String?): List<Destination> {
        checkThread()
        val (forApp, appArgs) = appFilter(packageName)
        return db.query(
            "SELECT COALESCE(host, address), host IS NOT NULL, GROUP_CONCAT(DISTINCT port), SUM(attempts), MAX(time) " +
                "FROM log WHERE $forApp AND time >= ? GROUP BY 1, 2 ORDER BY 4 DESC",
            appArgs + since(),
        ) {
            val isLookup = it.int(1) != 0
            // A lookup's port is always DNS's, which says nothing about the app.
            val ports = if (isLookup) emptyList() else it.string(2)!!.split(',').mapNotNull(String::toIntOrNull).filter { p -> p != 0 }.sorted()
            Destination(it.string(0)!!, isLookup, ports, it.int(3), it.long(4))
        }
    }

    /** SQL matching one app's rows; `package = ?` can't match the unattributed (null) ones. */
    private fun appFilter(packageName: String?): Pair<String, List<Any?>> =
        if (packageName == null) "package IS NULL" to emptyList() else "package = ?" to listOf(packageName)

    /** One row per app, most recently active first. */
    fun summaries(): List<AppSummary> {
        checkThread()
        return db.query(
            "SELECT package, SUM(attempts), COUNT(DISTINCT COALESCE(host, address)), MAX(time) " +
                "FROM log WHERE time >= ? GROUP BY package ORDER BY MAX(time) DESC",
            listOf(since()),
        ) { AppSummary(it.string(0), it.int(1), it.int(2), it.long(3)) }
    }

    /** Total attempts per app since [time]; apps with none are absent. */
    fun attemptsSince(time: Long): Map<String?, Int> {
        checkThread()
        return db.query("SELECT package, SUM(attempts) FROM log WHERE time >= ? GROUP BY package", listOf(time)) {
            it.string(0) to it.int(1)
        }.toMap()
    }

    /**
     * Attempts in [buckets] consecutive windows of [bucketMs] starting at [since], oldest first
     * (for sparklines), for one app or (null) all of them. A merged row counts in the window of
     * its latest attempt.
     */
    fun attemptsPerBucket(since: Long, bucketMs: Long, buckets: Int, packageName: String? = null): IntArray {
        checkThread()
        val result = IntArray(buckets)
        val forApp = if (packageName != null) " AND package = ?" else ""
        db.query(
            "SELECT (time - ?) / ?, SUM(attempts) FROM log WHERE time >= ?$forApp GROUP BY 1",
            listOfNotNull(since, bucketMs, since, packageName),
        ) { it.int(0) to it.int(1) }.forEach { (bucket, attempts) ->
            if (bucket in 0 until buckets) result[bucket] = attempts
        }
        return result
    }

    /** When the most recent blocked attempt happened (by one app, or any), or null if none is logged. */
    fun lastAttemptTime(packageName: String? = null): Long? {
        checkThread()
        val (where, args) = if (packageName != null) " WHERE package = ?" to listOf(packageName) else "" to emptyList()
        return db.query("SELECT MAX(time) FROM log$where", args) { if (it.isNull(0)) null else it.long(0) }.single()
    }

    /**
     * Where one app tried to go since [since], most attempted first: domain names where known,
     * otherwise addresses. Returns the top [limit] and the total number of distinct destinations.
     */
    fun topDestinations(packageName: String, since: Long, limit: Int): Pair<List<String>, Int> {
        checkThread()
        val all = db.query(
            "SELECT COALESCE(host, address), SUM(attempts) FROM log WHERE package = ? AND time >= ? GROUP BY 1 ORDER BY 2 DESC",
            listOf(packageName, since),
        ) { it.string(0)!! }
        return all.take(limit) to all.size
    }

    /** Deletes entries older than the retention period, e.g. right after it was shortened. */
    fun prune() {
        checkThread()
        db.execute("DELETE FROM log WHERE time < ?", listOf(clock() - retentionMs()))
    }

    fun clear() {
        checkThread()
        db.execute("DELETE FROM log")
        merger.clear()
    }

    private fun checkThread() = check(onDbThread()) { "The block log is used only on the database thread" }

    private fun retentionMs() = retentionDays() * DAY_MS
    private fun since() = clock() - retentionMs()

    companion object {
        const val FILE = "blocklog.db"
        const val VERSION = 1
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Creates the schema in a new, empty database. */
        fun create(db: SqlDb) {
            db.execute(
                "CREATE TABLE log (_id INTEGER PRIMARY KEY, time INTEGER NOT NULL, package TEXT, " +
                    "protocol INTEGER NOT NULL, address TEXT NOT NULL, port INTEGER NOT NULL, host TEXT, " +
                    "attempts INTEGER NOT NULL)"
            )
            db.execute("CREATE INDEX log_time ON log (time)")
        }
    }
}
