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

    /** One row per app, most recently active first. */
    @Synchronized
    fun summaries(): List<AppSummary> {
        val since = System.currentTimeMillis() - retentionMs()
        readableDatabase.rawQuery(
            "SELECT package, SUM(attempts), COUNT(DISTINCT COALESCE(host, address || ':' || port)), MAX(time) " +
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
