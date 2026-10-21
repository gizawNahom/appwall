package dev.appwall.storage

import dev.appwall.core.health.ProbeResult
import dev.appwall.core.health.Probed
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
 * 3.18 (Android 8) supports. Every SQL text is a literal or a constant; values only ever travel as
 * bound `?` arguments, so nothing an app controls (its name, the hosts it looks up) can become SQL.
 *
 * Used only on the database thread, which [onDbThread] recognizes; any other caller fails loudly
 * rather than racing it. That one thread is also why nothing here needs a lock. Every committed
 * change (a batch, a prune, a clear) calls [changed] once.
 */
class SqlBlockLog(
    private val db: SqlDb,
    private val retentionDays: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDbThread: () -> Boolean,
    private val changed: () -> Unit = {},
    /** The most entries kept, newest first: a blocked app looking up endless new names can't fill the disk. */
    private val maxRows: Int = MAX_ROWS,
) : Probed {
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
        changed()
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
                    db.execute(KEEP_NEWEST, listOf(maxRows))
                    merger.forget(now)
                }
            }
        }
    }

    /** One app's entries (null = the unattributed ones), newest first. */
    fun entriesFor(packageName: String?, limit: Int = 500): List<LogEntry> {
        checkThread()
        return db.query(
            if (packageName == null) ENTRIES_UNATTRIBUTED else ENTRIES_FOR_APP,
            listOfNotNull(packageName) + since() + limit,
        ) { LogEntry(it.long(0), packageName, it.int(1), it.string(2)!!, it.int(3), it.string(4), it.int(5)) }
    }

    /** Where one app (null = unattributed) tried to go over the retention period, most tried first. */
    fun destinations(packageName: String?): List<Destination> {
        checkThread()
        return db.query(
            if (packageName == null) DESTINATIONS_UNATTRIBUTED else DESTINATIONS_FOR_APP,
            listOfNotNull(packageName) + since(),
        ) { Destination.fromPortList(it.string(0)!!, isLookup = it.int(1) != 0, portList = it.string(2)!!, it.int(3), it.long(4)) }
    }

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
     * (for the log's chart). A merged row counts in the window of its latest attempt.
     */
    fun attemptsPerBucket(since: Long, bucketMs: Long, buckets: Int): IntArray {
        checkThread()
        val result = IntArray(buckets)
        db.query(
            "SELECT (time - ?) / ?, SUM(attempts) FROM log WHERE time >= ? GROUP BY 1",
            listOf(since, bucketMs, since),
        ) { it.int(0) to it.int(1) }.forEach { (bucket, attempts) ->
            if (bucket in 0 until buckets) result[bucket] = attempts
        }
        return result
    }

    /** When the most recent blocked attempt happened, or null if none is logged. */
    fun lastAttemptTime(): Long? {
        checkThread()
        return db.query("SELECT MAX(time) FROM log") { if (it.isNull(0)) null else it.long(0) }.single()
    }

    /** Deletes entries older than the retention period, e.g. right after it was shortened. */
    fun prune() {
        checkThread()
        db.execute("DELETE FROM log WHERE time < ?", listOf(clock() - retentionMs()))
        changed()
    }

    fun clear() {
        checkThread()
        db.execute("DELETE FROM log")
        merger.clear()
        changed()
    }

    /**
     * The start-up check: the database opens, is at [VERSION], and takes a write that reads back,
     * inside a transaction that's always rolled back. Catches a corrupt file, full or read-only
     * storage, a failed upgrade, or SQL this phone's SQLite doesn't run.
     */
    override fun probe(): ProbeResult {
        checkThread()
        return try {
            val version = db.query("PRAGMA user_version") { it.int(0) }.single()
            if (version != VERSION) return ProbeResult.Failed("version $version, expected $VERSION")
            db.transaction {
                val rowId = db.insert(
                    "INSERT INTO log (time, package, protocol, address, port, host, attempts) VALUES (0, ?, 0, '0.0.0.0', 0, NULL, 0)",
                    listOf(PROBE_PACKAGE),
                )
                throw RollBack(db.query("SELECT COUNT(*) FROM log WHERE _id = ?", listOf(rowId)) { it.int(0) }.single() == 1)
            }
        } catch (e: RollBack) {
            if (e.readBack) ProbeResult.Ok else ProbeResult.Failed("a written row didn't read back")
        } catch (e: Exception) {
            ProbeResult.Failed(e.toString())
        }
    }

    /** Ends the probe's transaction without keeping its row. */
    private class RollBack(val readBack: Boolean) : Exception()

    private fun checkThread() = check(onDbThread()) { "The block log is used only on the database thread" }

    private fun retentionMs() = retentionDays() * DAY_MS
    private fun since() = clock() - retentionMs()

    companion object {
        const val FILE = "blocklog.db"
        /** 2: adds the (package, time) index for the per-app queries. */
        const val VERSION = 2
        private const val PROBE_PACKAGE = "dev.appwall.probe"
        const val MAX_ROWS = 50_000
        /** Deletes all but the newest ? entries (ids only grow, so newest = highest). */
        private const val KEEP_NEWEST = "DELETE FROM log WHERE _id <= (SELECT _id FROM log ORDER BY _id DESC LIMIT 1 OFFSET ?)"

        // Queries with an unattributed variant: `package = ?` can't match the null ones. Chosen
        // whole, so no SQL text is ever assembled at run time.
        private const val ENTRIES = "SELECT time, protocol, address, port, host, attempts FROM log WHERE "
        private const val ENTRIES_TAIL = " AND time >= ? ORDER BY time DESC LIMIT ?"
        private const val ENTRIES_FOR_APP = ENTRIES + "package = ?" + ENTRIES_TAIL
        private const val ENTRIES_UNATTRIBUTED = ENTRIES + "package IS NULL" + ENTRIES_TAIL
        private const val DESTINATIONS =
            "SELECT COALESCE(host, address), host IS NOT NULL, GROUP_CONCAT(DISTINCT port), SUM(attempts), MAX(time) FROM log WHERE "
        private const val DESTINATIONS_TAIL = " AND time >= ? GROUP BY 1, 2 ORDER BY 4 DESC"
        private const val DESTINATIONS_FOR_APP = DESTINATIONS + "package = ?" + DESTINATIONS_TAIL
        private const val DESTINATIONS_UNATTRIBUTED = DESTINATIONS + "package IS NULL" + DESTINATIONS_TAIL
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Creates the schema in a new, empty database. */
        fun create(db: SqlDb) {
            db.execute(
                "CREATE TABLE log (_id INTEGER PRIMARY KEY, time INTEGER NOT NULL, package TEXT, " +
                    "protocol INTEGER NOT NULL, address TEXT NOT NULL, port INTEGER NOT NULL, host TEXT, " +
                    "attempts INTEGER NOT NULL)"
            )
            db.execute("CREATE INDEX log_time ON log (time)")
            createPackageTimeIndex(db)
        }

        /** Brings a database made by an older version up to [VERSION], keeping its entries. */
        fun upgrade(db: SqlDb, from: Int, to: Int) {
            if (from < 2 && to >= 2) createPackageTimeIndex(db)
        }

        /**
         * A newer version made this database (an older build was installed over it). Its schema
         * can't be trusted here, and it's only a log: start it over rather than fail to open.
         */
        fun reset(db: SqlDb) {
            db.execute("DROP TABLE IF EXISTS log")
            create(db)
        }

        // One app's entries, newest first: AppLog, an open row on Home, and their counts.
        private fun createPackageTimeIndex(db: SqlDb) = db.execute("CREATE INDEX log_package_time ON log (package, time)")
    }
}
