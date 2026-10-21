package dev.appwall

import dev.appwall.core.health.ProbeResult
import dev.appwall.core.log.AppSummary
import dev.appwall.core.log.BlockedTry
import dev.appwall.core.log.Destination
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlBlockLogTest {

    private val db = CountingDb(JdbcSqlDb().also(SqlBlockLog::create))
    private var now = START
    private var retentionDays = 7
    private val testThread = Thread.currentThread()
    private var changes = 0
    private val log = SqlBlockLog(db, { retentionDays }, { now }, onDbThread = { Thread.currentThread() === testThread }, changed = { changes++ })

    @After
    fun closeDb() = db.jdbc.close()

    // Recording

    @Test
    fun aBatchIsWrittenInOneTransactionAndMerged() {
        val tries = listOf(
            BlockedTry(CHROME, tcp("1.2.3.4", 443), now),
            BlockedTry(CHROME, tcp("1.2.3.4", 443), now + 10_000), // same place within the minute: merged
            BlockedTry(OTHER, tcp("1.2.3.4", 443), now + 20_000),
            BlockedTry(CHROME, dns("a.example"), now + 30_000),
        )
        log.record(tries)
        assertEquals(1, db.transactions)
        assertEquals(0, db.writesOutsideTransactions)
        assertEquals(1, changes) // one signal per batch, not per try
        assertEquals(3, rows())
        now += 30_000
        assertEquals(listOf(1, 3), log.summaries().map { it.attempts }.sorted())
    }

    @Test
    fun aFailedBatchLeavesNothingHalfWritten() {
        record(CHROME, tcp("1.2.3.4", 443))
        db.failAfterWrites = 1
        val batch = listOf(BlockedTry(CHROME, tcp("1.2.3.4", 443), now + 1), BlockedTry(OTHER, tcp("5.6.7.8", 443), now + 2))
        assertThrows(IllegalStateException::class.java) { log.record(batch) }
        assertEquals(1, changes) // only the first, committed write said so
        db.failAfterWrites = null
        assertEquals(listOf(1), log.summaries().map { it.attempts })
        record(OTHER, tcp("5.6.7.8", 443)) // must be inserted, not "bump" a rolled-back row
        assertEquals(2, rows())
    }

    @Test
    fun theLogRefusesOtherThreads() {
        var error: Throwable? = null
        Thread { error = runCatching { log.summaries() }.exceptionOrNull() }.apply { start(); join() }
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun repeatTriesWithinAMinuteShareARow() {
        repeat(3) { record(CHROME, tcp("1.2.3.4", 443)); now += 10_000 }
        assertEquals(1, rows())
        assertEquals(3, log.entriesFor(CHROME).single().attempts)
    }

    @Test
    fun aTryAfterTheRowsMinuteStartsANewRow() {
        record(CHROME, tcp("1.2.3.4", 443))
        now += 59_999
        record(CHROME, tcp("1.2.3.4", 443))
        now += 1
        record(CHROME, tcp("1.2.3.4", 443))
        assertEquals(listOf(1, 2), log.entriesFor(CHROME).map { it.attempts })
    }

    @Test
    fun afterClearingTriesAreRecordedAgainNotLost() {
        record(CHROME, tcp("1.2.3.4", 443))
        log.clear()
        now += 1_000
        record(CHROME, tcp("1.2.3.4", 443)) // must not "bump" the deleted row
        assertEquals(1, log.entriesFor(CHROME).single().attempts)
    }

    @Test
    fun everyHundredRowsOldOnesArePruned() {
        record(CHROME, tcp("9.9.9.9", 443))
        now += 8 * DAY
        for (i in 1..98) record(CHROME, tcp("1.2.3.$i", 443))
        assertEquals(99, rows()) // 99 inserts: no prune yet
        record(CHROME, tcp("1.2.4.1", 443)) // the 100th insert prunes
        assertEquals(99, rows())
        assertEquals(emptyList<String>(), log.destinations(CHROME).map { it.name }.filter { it == "9.9.9.9" })
    }

    // Queries

    @Test
    fun summariesPerAppNewestFirst() {
        record(CHROME, dns("a.example"))
        record(CHROME, tcp("1.2.3.4", 443))
        record(CHROME, tcp("1.2.3.4", 80)) // same address, other port: one place
        now += 1_000
        record(OTHER, tcp("5.6.7.8", 443))
        record(null, tcp("5.6.7.8", 443))
        assertEquals(
            listOf(
                AppSummary(OTHER, 1, 1, now),
                AppSummary(null, 1, 1, now),
                AppSummary(CHROME, 3, 2, START),
            ).sortedBy { it.packageName.toString() },
            log.summaries().sortedBy { it.packageName.toString() },
        )
        assertEquals(CHROME, log.summaries().last().packageName)
    }

    @Test
    fun summariesLeaveOutWhatsPastRetention() {
        record(CHROME, tcp("1.2.3.4", 443))
        now += 2 * DAY
        retentionDays = 1
        assertEquals(emptyList<AppSummary>(), log.summaries())
    }

    @Test
    fun destinationsSplitLookupsFromAddressesAndRankByTries() {
        record(CHROME, dns("a.example"))
        repeat(3) { record(CHROME, tcp("1.2.3.4", 443)) }
        record(CHROME, tcp("1.2.3.4", 80))
        record(CHROME, Packet(PacketParser.ICMP, SOURCE, 0, InetAddress.getByName("1.2.3.4"), 0, null))
        record(OTHER, tcp("9.9.9.9", 443))
        assertEquals(
            listOf(
                Destination("1.2.3.4", isLookup = false, ports = listOf(80, 443), attempts = 5, lastTime = START),
                Destination("a.example", isLookup = true, ports = emptyList(), attempts = 1, lastTime = START),
            ),
            log.destinations(CHROME),
        )
    }

    @Test
    fun unattributedTriesHaveTheirOwnDestinations() {
        record(null, tcp("1.2.3.4", 443))
        record(CHROME, tcp("5.6.7.8", 443))
        assertEquals(listOf("1.2.3.4"), log.destinations(null).map { it.name })
        assertEquals(listOf("1.2.3.4"), log.entriesFor(null).map { it.address })
    }

    @Test
    fun entriesNewestFirstUpToTheLimit() {
        for (i in 1..5) {
            record(CHROME, tcp("1.2.3.$i", 443))
            now += 1_000
        }
        val entries = log.entriesFor(CHROME, limit = 3)
        assertEquals(listOf("1.2.3.5", "1.2.3.4", "1.2.3.3"), entries.map { it.address })
        assertEquals(CHROME, entries.first().packageName)
    }

    @Test
    fun attemptsSincePerApp() {
        record(CHROME, tcp("1.2.3.4", 443))
        now += 1_000
        record(CHROME, tcp("5.6.7.8", 443))
        record(OTHER, tcp("5.6.7.8", 443))
        assertEquals(mapOf(CHROME to 1, OTHER to 1), log.attemptsSince(START + 1))
        assertEquals(mapOf(CHROME to 2, OTHER to 1), log.attemptsSince(START))
    }

    @Test
    fun bucketsCountTriesPerWindowOldestFirst() {
        record(CHROME, tcp("1.2.3.4", 443)) // bucket 0
        now += HOUR + 1
        record(CHROME, tcp("5.6.7.8", 443)) // bucket 1
        record(OTHER, tcp("5.6.7.8", 443))
        now += 20_000
        record(CHROME, tcp("5.6.7.8", 443)) // bumps the bucket-1 row
        assertArrayEquals(intArrayOf(1, 3, 0), log.attemptsPerBucket(START, HOUR, 3))
    }

    @Test
    fun aMergedRowCountsInTheWindowOfItsLatestTry() {
        now = START + HOUR - 10_000
        record(CHROME, tcp("1.2.3.4", 443))
        now += 20_000 // the same row, now in the next hour
        record(CHROME, tcp("1.2.3.4", 443))
        assertArrayEquals(intArrayOf(0, 2), log.attemptsPerBucket(START, HOUR, 2))
    }

    @Test
    fun lastAttemptTime() {
        assertNull(log.lastAttemptTime())
        record(CHROME, tcp("1.2.3.4", 443))
        now += 5_000
        record(OTHER, tcp("1.2.3.4", 443))
        assertEquals(now, log.lastAttemptTime())
    }

    @Test
    fun pruneUsesTheCurrentRetention() {
        record(CHROME, tcp("1.2.3.4", 443))
        now += 2 * DAY
        record(CHROME, tcp("5.6.7.8", 443))
        log.prune()
        assertEquals(2, rows())
        retentionDays = 1
        log.prune()
        assertEquals(1, rows())
    }

    // Values an app controls never become SQL

    @Test
    fun hostileNamesAreStoredAndFoundVerbatimAndBreakNothing() {
        val evil = "x'); DROP TABLE log;--"
        val evilHost = "a.example' OR '1'='1"
        record(evil, tcp("1.2.3.4", 443))
        record(evil, dns(evilHost))
        record(CHROME, tcp("5.6.7.8", 443))
        now += 1_000

        assertEquals(3, rows()) // the table is still there, with all three rows
        assertEquals(setOf(evil, CHROME), log.summaries().map { it.packageName }.toSet())
        assertEquals(setOf("1.2.3.4", evilHost), log.destinations(evil).map { it.name }.toSet())
        assertEquals(setOf(evilHost, null), log.entriesFor(evil).map { it.host }.toSet())
        // The quote didn't widen the match: Chrome's row isn't in the hostile app's results.
        assertEquals(listOf("5.6.7.8"), log.destinations(CHROME).map { it.name })
        assertEquals(2, log.attemptsSince(START)[evil])
    }

    // Start-up check

    @Test
    fun theProbePassesAndLeavesNoRowBehind() {
        record(CHROME, tcp("1.2.3.4", 443))
        db.execute("PRAGMA user_version = ${SqlBlockLog.VERSION}")
        assertEquals(ProbeResult.Ok, log.probe())
        assertEquals(1, rows()) // the probe's row was rolled back
        assertEquals(listOf(CHROME), db.query("SELECT package FROM log") { it.string(0) })
    }

    @Test
    fun theProbeFailsOnAnUnexpectedVersion() {
        db.execute("PRAGMA user_version = 1")
        assertTrue(log.probe() is ProbeResult.Failed)
    }

    // Schema versions

    @Test
    fun upgradingFromVersionOneKeepsEveryRowAndAddsTheIndex() {
        JdbcSqlDb().use { old ->
            createVersionOne(old)
            old.execute("INSERT INTO log (time, package, protocol, address, port, host, attempts) VALUES (?, ?, 6, '1.2.3.4', 443, NULL, 3)", listOf(START, CHROME))
            old.execute("INSERT INTO log (time, package, protocol, address, port, host, attempts) VALUES (?, NULL, 17, '10.111.0.2', 53, 'a.example', 1)", listOf(START + 1))
            old.transaction { SqlBlockLog.upgrade(old, 1, SqlBlockLog.VERSION) }
            assertEquals(2, old.query("SELECT COUNT(*) FROM log") { it.int(0) }.single())
            assertEquals(listOf(4), old.query("SELECT SUM(attempts) FROM log") { it.int(0) })
            assertEquals(setOf("log_time", "log_package_time"), indexes(old))
        }
    }

    @Test
    fun aFreshDatabaseMatchesAnUpgradedOne() {
        JdbcSqlDb().use { upgraded ->
            JdbcSqlDb().use { fresh ->
                createVersionOne(upgraded)
                SqlBlockLog.upgrade(upgraded, 1, SqlBlockLog.VERSION)
                SqlBlockLog.create(fresh)
                assertEquals(schema(fresh), schema(upgraded))
            }
        }
    }

    @Test
    fun aDowngradeStartsTheLogOver() {
        record(CHROME, tcp("1.2.3.4", 443))
        SqlBlockLog.reset(db)
        assertEquals(0, rows())
        assertEquals(schema(JdbcSqlDb().also(SqlBlockLog::create)), schema(db))
    }

    @Test
    fun perAppQueriesUseThePackageTimeIndex() {
        for (sql in listOf(
            "SELECT time FROM log WHERE package = ? AND time >= ? ORDER BY time DESC LIMIT 500", // entriesFor
            "SELECT COALESCE(host, address), SUM(attempts) FROM log WHERE package = ? AND time >= ? GROUP BY 1", // destinations
        )) {
            val plan = db.query("EXPLAIN QUERY PLAN $sql", listOf("x", "0").take(sql.count { it == '?' })) { it.string(3) }.joinToString()
            assertTrue("$sql: $plan", "log_package_time" in plan)
        }
    }

    /** The schema as version 1 (the first release) created it. */
    private fun createVersionOne(target: SqlDb) {
        target.execute(
            "CREATE TABLE log (_id INTEGER PRIMARY KEY, time INTEGER NOT NULL, package TEXT, " +
                "protocol INTEGER NOT NULL, address TEXT NOT NULL, port INTEGER NOT NULL, host TEXT, " +
                "attempts INTEGER NOT NULL)"
        )
        target.execute("CREATE INDEX log_time ON log (time)")
    }

    private fun indexes(target: SqlDb) =
        target.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'log'") { it.string(0)!! }.toSet()

    private fun schema(target: SqlDb) =
        target.query("SELECT type, name, sql FROM sqlite_master WHERE tbl_name = 'log' ORDER BY name") { "${it.string(0)} ${it.string(1)}: ${it.string(2)}" }

    // How the queries scale (prints only: timings vary by machine, and a phone is slower)

    @Test
    fun timingAndQueryPlansWithAHundredThousandRows() {
        db.jdbc.connection.autoCommit = false
        for (i in 0 until 100_000) {
            val app = APPS[i % APPS.size]
            db.execute(
                "INSERT INTO log (time, package, protocol, address, port, host, attempts) VALUES (?, ?, 6, ?, 443, ?, ?)",
                listOf(START + i * 6_000L, app, "10.0.${i % 50}.${i % 200}", if (i % 3 == 0) "h${i % 300}.example" else null, 1 + i % 4),
            )
        }
        db.jdbc.connection.commit()
        db.jdbc.connection.autoCommit = true
        now = START + 100_000 * 6_000L
        retentionDays = 30

        fun time(name: String, work: () -> Unit) {
            work() // warm up
            val start = System.nanoTime()
            repeat(5) { work() }
            println("TIMING $name: ${(System.nanoTime() - start) / 5 / 1_000_000} ms")
        }
        time("summaries") { log.summaries() }
        time("destinations(busiest)") { log.destinations(APPS[0]) }
        time("entriesFor(busiest)") { log.entriesFor(APPS[0]) }
        time("attemptsPerBucket(24h)") { log.attemptsPerBucket(now - DAY, HOUR, 24) }

        for ((name, sql) in listOf(
            "destinations" to "SELECT COALESCE(host, address) FROM log WHERE package = 'x' AND time >= 0 GROUP BY 1",
            "entriesFor" to "SELECT time FROM log WHERE package = 'x' AND time >= 0 ORDER BY time DESC LIMIT 500",
        )) {
            val plan = db.query("EXPLAIN QUERY PLAN $sql") { it.string(3) }
            println("PLAN $name: ${plan.joinToString("; ")}")
        }
    }

    private fun record(owner: String?, packet: Packet) = log.record(listOf(BlockedTry(owner, packet, now)))

    private fun rows() = db.query("SELECT COUNT(*) FROM log") { it.int(0) }.single()

    private fun tcp(address: String, port: Int) =
        Packet(PacketParser.TCP, SOURCE, 40000, InetAddress.getByName(address), port, null)

    private fun dns(name: String) =
        Packet(PacketParser.UDP, SOURCE, 40000, InetAddress.getByName("10.111.0.2"), 53, name)

    private companion object {
        const val START = 1_760_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val CHROME = "com.android.chrome"
        const val OTHER = "org.example.other"
        val APPS = listOf(CHROME, OTHER, "a.b", "c.d", "e.f")
        val SOURCE: InetAddress = InetAddress.getByName("10.111.0.1")
    }
}

/** Counts transactions and writes outside them, and can fail a write on purpose. */
private class CountingDb(val jdbc: JdbcSqlDb) : SqlDb by jdbc {
    var transactions = 0
    var writesOutsideTransactions = 0
    /** Fail the write after this many more succeed; null = never. */
    var failAfterWrites: Int? = null
    private var inTransaction = false

    override fun execute(sql: String, args: List<Any?>) {
        wrote()
        jdbc.execute(sql, args)
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        wrote()
        return jdbc.insert(sql, args)
    }

    override fun <T> transaction(work: () -> T): T {
        transactions++
        inTransaction = true
        try {
            return jdbc.transaction(work)
        } finally {
            inTransaction = false
        }
    }

    private fun wrote() {
        if (!inTransaction) writesOutsideTransactions++
        failAfterWrites?.let { check(it > 0) { "Failing on purpose" }; failAfterWrites = it - 1 }
    }
}
