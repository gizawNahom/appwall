package dev.appwall

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LogEntry(
    val time: Long,
    val packageName: String?,
    val protocol: Int,
    val address: String,
    val port: Int,
    val host: String?,
    val attempts: Int,
)

/** Per-app totals for the grouped log view. */
data class AppSummary(
    val packageName: String?,
    val attempts: Int,
    val destinations: Int,
    val lastTime: Long,
)

/** One place an app tried to reach: a domain name it looked up, or an address it connected to. */
data class Destination(
    val name: String,
    /** Only a name lookup was seen (the app never got an address to connect to). */
    val isLookup: Boolean,
    val ports: List<Int>,
    val attempts: Int,
    val lastTime: Long,
)

/**
 * On-device log of blocked connection attempts. Apps retry a lot, so repeats of the same attempt
 * within a minute are folded into one row with an attempt count. Entries expire after the
 * user's chosen retention period (7 days by default).
 */
class BlockLog private constructor(context: Context) : SQLiteOpenHelper(context, "blocklog.db", null, 1) {

    private val rules = Rules(context)

    /** Recently written rows by attempt key, so retries bump a row instead of adding new ones. */
    private val recent = HashMap<String, Pair<Long, Long>>() // key -> (row id, last attempt time)
    private var insertsSincePrune = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log (_id INTEGER PRIMARY KEY, time INTEGER NOT NULL, package TEXT, " +
                "protocol INTEGER NOT NULL, address TEXT NOT NULL, port INTEGER NOT NULL, host TEXT, " +
                "attempts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX log_time ON log (time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun record(packageName: String?, packet: Packet) {
        val now = System.currentTimeMillis()
        val address = packet.destination.hostAddress ?: return
        val key = listOf(packageName, packet.protocol, address, packet.destinationPort, packet.dnsQuery).joinToString("|")
        val db = writableDatabase

        val previous = recent[key]
        if (previous != null && now - previous.second < MERGE_WINDOW_MS) {
            db.execSQL("UPDATE log SET attempts = attempts + 1, time = ? WHERE _id = ?", arrayOf(now, previous.first))
            recent[key] = previous.first to now
            return
        }

        val row = ContentValues().apply {
            put("time", now)
            put("package", packageName)
            put("protocol", packet.protocol)
            put("address", address)
            put("port", packet.destinationPort)
            put("host", packet.dnsQuery)
            put("attempts", 1)
        }
        recent[key] = db.insert("log", null, row) to now

        if (++insertsSincePrune >= 100) {
            insertsSincePrune = 0
            db.delete("log", "time < ?", arrayOf((now - retentionMs()).toString()))
            recent.entries.removeAll { now - it.value.second >= MERGE_WINDOW_MS }
        }
    }

    @Synchronized
    fun entries(limit: Int = 500): List<LogEntry> {
        val since = System.currentTimeMillis() - retentionMs()
        readableDatabase.query(
            "log", arrayOf("time", "package", "protocol", "address", "port", "host", "attempts"),
            "time >= ?", arrayOf(since.toString()), null, null, "time DESC", limit.toString(),
        ).use { c ->
            val result = ArrayList<LogEntry>(c.count)
            while (c.moveToNext()) {
                result += LogEntry(
                    time = c.getLong(0),
                    packageName = c.getString(1),
                    protocol = c.getInt(2),
                    address = c.getString(3),
                    port = c.getInt(4),
                    host = c.getString(5),
                    attempts = c.getInt(6),
                )
            }
            return result
        }
    }

    /** One app's entries (null = the unattributed ones), newest first. */
    @Synchronized
    fun entriesFor(packageName: String?, limit: Int = 500): List<LogEntry> {
        val (forApp, appArgs) = appFilter(packageName)
        val since = System.currentTimeMillis() - retentionMs()
        readableDatabase.query(
            "log", arrayOf("time", "protocol", "address", "port", "host", "attempts"),
            "$forApp AND time >= ?", appArgs + since.toString(), null, null, "time DESC", limit.toString(),
        ).use { c ->
            val result = ArrayList<LogEntry>(c.count)
            while (c.moveToNext()) {
                result += LogEntry(c.getLong(0), packageName, c.getInt(1), c.getString(2), c.getInt(3), c.getString(4), c.getInt(5))
            }
            return result
        }
    }

    /** Where one app (null = unattributed) tried to go over the retention period, most tried first. */
    @Synchronized
    fun destinations(packageName: String?): List<Destination> {
        val (forApp, appArgs) = appFilter(packageName)
        val since = System.currentTimeMillis() - retentionMs()
        readableDatabase.rawQuery(
            "SELECT COALESCE(host, address), host IS NOT NULL, GROUP_CONCAT(DISTINCT port), SUM(attempts), MAX(time) " +
                "FROM log WHERE $forApp AND time >= ? GROUP BY 1, 2 ORDER BY 4 DESC",
            appArgs + since.toString(),
        ).use { c ->
            val result = ArrayList<Destination>(c.count)
            while (c.moveToNext()) {
                val isLookup = c.getInt(1) != 0
                // A lookup's port is always DNS's, which says nothing about the app.
                val ports = if (isLookup) emptyList() else c.getString(2).split(',').mapNotNull { it.toIntOrNull() }.filter { it != 0 }.sorted()
                result += Destination(c.getString(0), isLookup, ports, c.getInt(3), c.getLong(4))
            }
            return result
        }
    }

    /** SQL matching one app's rows; `package = ?` can't match the unattributed (null) ones. */
    private fun appFilter(packageName: String?): Pair<String, Array<String>> =
        if (packageName == null) "package IS NULL" to emptyArray() else "package = ?" to arrayOf(packageName)

    /** One row per app, most recently active first. */
    @Synchronized
    fun summaries(): List<AppSummary> {
        val since = System.currentTimeMillis() - retentionMs()
        readableDatabase.rawQuery(
            "SELECT package, SUM(attempts), COUNT(DISTINCT COALESCE(host, address)), MAX(time) " +
                "FROM log WHERE time >= ? GROUP BY package ORDER BY MAX(time) DESC",
            arrayOf(since.toString()),
        ).use { c ->
            val result = ArrayList<AppSummary>(c.count)
            while (c.moveToNext()) {
                result += AppSummary(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3))
            }
            return result
        }
    }

    /** Total attempts per app since [time]; apps with none are absent. */
    @Synchronized
    fun attemptsSince(time: Long): Map<String?, Int> {
        readableDatabase.rawQuery(
            "SELECT package, SUM(attempts) FROM log WHERE time >= ? GROUP BY package",
            arrayOf(time.toString()),
        ).use { c ->
            val result = HashMap<String?, Int>()
            while (c.moveToNext()) result[c.getString(0)] = c.getInt(1)
            return result
        }
    }

    /**
     * Attempts in [buckets] consecutive windows of [bucketMs] starting at [since], oldest first
     * (for sparklines), for one app or (null) all of them. A merged row counts in the window of
     * its latest attempt.
     */
    @Synchronized
    fun attemptsPerBucket(since: Long, bucketMs: Long, buckets: Int, packageName: String? = null): IntArray {
        val result = IntArray(buckets)
        val forApp = if (packageName != null) " AND package = ?" else ""
        val args = listOfNotNull(since.toString(), bucketMs.toString(), since.toString(), packageName).toTypedArray()
        readableDatabase.rawQuery(
            "SELECT (time - ?) / ?, SUM(attempts) FROM log WHERE time >= ?$forApp GROUP BY 1",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val bucket = c.getInt(0)
                if (bucket in 0 until buckets) result[bucket] = c.getInt(1)
            }
        }
        return result
    }

    /** When the most recent blocked attempt happened (by one app, or any), or null if none is logged. */
    @Synchronized
    fun lastAttemptTime(packageName: String? = null): Long? {
        val (where, args) = if (packageName != null) " WHERE package = ?" to arrayOf(packageName) else "" to null
        return readableDatabase.rawQuery("SELECT MAX(time) FROM log$where", args).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    /**
     * Where one app tried to go since [since], most attempted first: domain names where known,
     * otherwise addresses. Returns the top [limit] and the total number of distinct destinations.
     */
    @Synchronized
    fun topDestinations(packageName: String, since: Long, limit: Int): Pair<List<String>, Int> {
        readableDatabase.rawQuery(
            "SELECT COALESCE(host, address), SUM(attempts) FROM log WHERE package = ? AND time >= ? " +
                "GROUP BY 1 ORDER BY 2 DESC",
            arrayOf(packageName, since.toString()),
        ).use { c ->
            val top = ArrayList<String>(limit)
            while (c.moveToNext() && top.size < limit) top += c.getString(0)
            return top to c.count
        }
    }

    /** Deletes entries older than the retention period, e.g. right after it was shortened. */
    @Synchronized
    fun prune() {
        writableDatabase.delete("log", "time < ?", arrayOf((System.currentTimeMillis() - retentionMs()).toString()))
    }

    private fun retentionMs() = rules.logRetentionDays * DAY_MS

    @Synchronized
    fun clear() {
        writableDatabase.delete("log", null, null)
        recent.clear()
    }

    companion object {
        private const val MERGE_WINDOW_MS = 60_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        @Volatile private var instance: BlockLog? = null

        fun get(context: Context): BlockLog =
            instance ?: synchronized(this) {
                instance ?: BlockLog(context.applicationContext).also { instance = it }
            }
    }
}
