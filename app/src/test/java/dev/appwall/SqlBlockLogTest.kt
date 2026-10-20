package dev.appwall

import dev.appwall.core.log.AppSummary
import dev.appwall.core.log.Destination
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqlBlockLogTest {

    private val db = JdbcSqlDb().also(SqlBlockLog::create)
    private var now = START
    private var retentionDays = 7
    private val log = SqlBlockLog(db, { retentionDays }, { now })

    @After
    fun closeDb() = db.close()

    // Recording

    @Test
    fun repeatTriesWithinAMinuteShareARow() {
        repeat(3) { log.record(CHROME, tcp("1.2.3.4", 443)); now += 10_000 }
        assertEquals(1, rows())
        assertEquals(3, log.entriesFor(CHROME).single().attempts)
    }

    @Test
    fun aTryAfterTheRowsMinuteStartsANewRow() {
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 59_999
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 1
        log.record(CHROME, tcp("1.2.3.4", 443))
        assertEquals(listOf(1, 2), log.entriesFor(CHROME).map { it.attempts })
    }

    @Test
    fun afterClearingTriesAreRecordedAgainNotLost() {
        log.record(CHROME, tcp("1.2.3.4", 443))
        log.clear()
        now += 1_000
        log.record(CHROME, tcp("1.2.3.4", 443)) // must not "bump" the deleted row
        assertEquals(1, log.entriesFor(CHROME).single().attempts)
    }

    @Test
    fun everyHundredRowsOldOnesArePruned() {
        log.record(CHROME, tcp("9.9.9.9", 443))
        now += 8 * DAY
        for (i in 1..98) log.record(CHROME, tcp("1.2.3.$i", 443))
        assertEquals(99, rows()) // 99 inserts: no prune yet
        log.record(CHROME, tcp("1.2.4.1", 443)) // the 100th insert prunes
        assertEquals(99, rows())
        assertEquals(emptyList<String>(), log.destinations(CHROME).map { it.name }.filter { it == "9.9.9.9" })
    }

    // Queries

    @Test
    fun summariesPerAppNewestFirst() {
        log.record(CHROME, dns("a.example"))
        log.record(CHROME, tcp("1.2.3.4", 443))
        log.record(CHROME, tcp("1.2.3.4", 80)) // same address, other port: one place
        now += 1_000
        log.record(OTHER, tcp("5.6.7.8", 443))
        log.record(null, tcp("5.6.7.8", 443))
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
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 2 * DAY
        retentionDays = 1
        assertEquals(emptyList<AppSummary>(), log.summaries())
    }

    @Test
    fun destinationsSplitLookupsFromAddressesAndRankByTries() {
        log.record(CHROME, dns("a.example"))
        repeat(3) { log.record(CHROME, tcp("1.2.3.4", 443)) }
        log.record(CHROME, tcp("1.2.3.4", 80))
        log.record(CHROME, Packet(PacketParser.ICMP, SOURCE, 0, InetAddress.getByName("1.2.3.4"), 0, null))
        log.record(OTHER, tcp("9.9.9.9", 443))
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
        log.record(null, tcp("1.2.3.4", 443))
        log.record(CHROME, tcp("5.6.7.8", 443))
        assertEquals(listOf("1.2.3.4"), log.destinations(null).map { it.name })
        assertEquals(listOf("1.2.3.4"), log.entriesFor(null).map { it.address })
    }

    @Test
    fun entriesNewestFirstUpToTheLimit() {
        for (i in 1..5) {
            log.record(CHROME, tcp("1.2.3.$i", 443))
            now += 1_000
        }
        val entries = log.entriesFor(CHROME, limit = 3)
        assertEquals(listOf("1.2.3.5", "1.2.3.4", "1.2.3.3"), entries.map { it.address })
        assertEquals(CHROME, entries.first().packageName)
    }

    @Test
    fun attemptsSincePerApp() {
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 1_000
        log.record(CHROME, tcp("5.6.7.8", 443))
        log.record(OTHER, tcp("5.6.7.8", 443))
        assertEquals(mapOf(CHROME to 1, OTHER to 1), log.attemptsSince(START + 1))
        assertEquals(mapOf(CHROME to 2, OTHER to 1), log.attemptsSince(START))
    }

    @Test
    fun bucketsCountTriesPerWindowOldestFirst() {
        log.record(CHROME, tcp("1.2.3.4", 443)) // bucket 0
        now += HOUR + 1
        log.record(CHROME, tcp("5.6.7.8", 443)) // bucket 1
        log.record(OTHER, tcp("5.6.7.8", 443))
        now += 20_000
        log.record(CHROME, tcp("5.6.7.8", 443)) // bumps the bucket-1 row
        assertArrayEquals(intArrayOf(1, 3, 0), log.attemptsPerBucket(START, HOUR, 3))
        assertArrayEquals(intArrayOf(1, 2, 0), log.attemptsPerBucket(START, HOUR, 3, CHROME))
    }

    @Test
    fun aMergedRowCountsInTheWindowOfItsLatestTry() {
        now = START + HOUR - 10_000
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 20_000 // the same row, now in the next hour
        log.record(CHROME, tcp("1.2.3.4", 443))
        assertArrayEquals(intArrayOf(0, 2), log.attemptsPerBucket(START, HOUR, 2))
    }

    @Test
    fun lastAttemptTime() {
        assertNull(log.lastAttemptTime())
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 5_000
        log.record(OTHER, tcp("1.2.3.4", 443))
        assertEquals(now, log.lastAttemptTime())
        assertEquals(START, log.lastAttemptTime(CHROME))
        assertNull(log.lastAttemptTime("not.logged"))
    }

    @Test
    fun topDestinationsAndHowManyThereAre() {
        repeat(3) { log.record(CHROME, dns("busy.example")) }
        repeat(2) { log.record(CHROME, tcp("1.2.3.4", 443)) }
        log.record(CHROME, tcp("5.6.7.8", 443))
        assertEquals(listOf("busy.example", "1.2.3.4") to 3, log.topDestinations(CHROME, START, 2))
    }

    @Test
    fun pruneUsesTheCurrentRetention() {
        log.record(CHROME, tcp("1.2.3.4", 443))
        now += 2 * DAY
        log.record(CHROME, tcp("5.6.7.8", 443))
        log.prune()
        assertEquals(2, rows())
        retentionDays = 1
        log.prune()
        assertEquals(1, rows())
    }

    // How the queries scale (prints only: timings vary by machine, and a phone is slower)

    @Test
    fun timingAndQueryPlansWithAHundredThousandRows() {
        db.connection.autoCommit = false
        for (i in 0 until 100_000) {
            val app = APPS[i % APPS.size]
            db.execute(
                "INSERT INTO log (time, package, protocol, address, port, host, attempts) VALUES (?, ?, 6, ?, 443, ?, ?)",
                listOf(START + i * 6_000L, app, "10.0.${i % 50}.${i % 200}", if (i % 3 == 0) "h${i % 300}.example" else null, 1 + i % 4),
            )
        }
        db.connection.commit()
        db.connection.autoCommit = true
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
        time("attemptsPerBucket(24h, app)") { log.attemptsPerBucket(now - DAY, HOUR, 24, APPS[0]) }
        time("topDestinations(7d)") { log.topDestinations(APPS[0], now - 7 * DAY, 3) }

        for ((name, sql) in listOf(
            "destinations" to "SELECT COALESCE(host, address) FROM log WHERE package = 'x' AND time >= 0 GROUP BY 1",
            "entriesFor" to "SELECT time FROM log WHERE package = 'x' AND time >= 0 ORDER BY time DESC LIMIT 500",
            "lastAttemptTime(app)" to "SELECT MAX(time) FROM log WHERE package = 'x'",
        )) {
            val plan = db.query("EXPLAIN QUERY PLAN $sql") { it.string(3) }
            println("PLAN $name: ${plan.joinToString("; ")}")
        }
    }

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
