package dev.appwall.core.screens

import dev.appwall.core.log.Destination
import dev.appwall.core.log.LogEntry
import dev.appwall.core.settings.FirewallPolicy
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogStateTest {

    private val addis = ZoneId.of("Africa/Addis_Ababa") // UTC+3
    private val utc = ZoneId.of("UTC")
    private val chrome = "com.android.chrome"

    private fun at(text: String, zone: ZoneId = addis) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun entry(time: Long, attempts: Int = 1) = LogEntry(time, chrome, 6, "1.2.3.4", 443, null, attempts)

    private fun state(
        packageName: String? = chrome,
        blocked: Set<String> = setOf(chrome),
        destinations: List<Destination> = emptyList(),
        entries: List<LogEntry> = emptyList(),
        timeline: Boolean = true,
        now: Long = at("2026-10-21T00:30"),
        zone: ZoneId = addis,
    ) = appLogState(packageName, FirewallPolicy(blocked = blocked), 7, destinations, entries, timeline, now, zone)

    @Test
    fun daysSplitAtLocalMidnight() {
        val justAfter = entry(at("2026-10-21T00:10"), attempts = 2)
        val justBefore = entry(at("2026-10-20T23:50"))
        val earlier = entry(at("2026-10-18T12:00"))
        val rows = state(entries = listOf(justAfter, justBefore, earlier)).timeline
        assertEquals(
            listOf(
                TimelineRow.Day(DayLabel.Today, 2), TimelineRow.Entry(justAfter),
                TimelineRow.Day(DayLabel.Yesterday, 1), TimelineRow.Entry(justBefore),
                TimelineRow.Day(DayLabel.Date(at("2026-10-18T00:00")), 1), TimelineRow.Entry(earlier),
            ),
            rows,
        )
        val days = rows.filterIsInstance<TimelineRow.Day>()
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.Date(at("2026-10-18T00:00"))), days.map { it.label })
        assertEquals(listOf(2, 1, 1), days.map { it.attempts })
        assertEquals(at("2026-10-18T00:00"), (days.last().label as DayLabel.Date).startOfDay)
        assertEquals(listOf(justAfter, justBefore, earlier), rows.filterIsInstance<TimelineRow.Entry>().map { it.entry })
    }

    @Test
    fun theTimeZoneDecidesTheDay() {
        // 00:10 in Addis Ababa is still 21:10 the day before in UTC: both entries are "today" there.
        val entries = listOf(entry(at("2026-10-21T00:10")), entry(at("2026-10-20T23:50")))
        val rows = state(entries = entries, now = at("2026-10-21T00:30"), zone = utc).timeline
        assertEquals(TimelineRow.Day(DayLabel.Today, 2), rows.first())
        assertEquals(1, rows.count { it is TimelineRow.Day })
    }

    @Test
    fun theButtonFollowsTheRule() {
        val table = listOf(
            Triple(chrome, setOf(chrome), true to AppLogToggle.ALLOW),
            Triple(chrome, emptySet(), false to AppLogToggle.BLOCK),
            Triple(null, emptySet(), true to AppLogToggle.NONE), // no app to allow; it reached the tunnel, so it's blocked
        )
        for ((packageName, blocked, expected) in table) {
            val s = state(packageName = packageName, blocked = blocked)
            assertEquals("$packageName, blocked $blocked", expected, s.blocked to s.toggle)
        }
        assertEquals(AppLogToggle.entries.toSet(), table.map { it.third.second }.toSet()) // every button is covered
    }

    @Test
    fun emptyIsAboutTheShownTab() {
        val places = listOf(Destination("a.example", true, emptyList(), 4, 0), Destination("1.2.3.4", false, listOf(443), 9, 0))
        val destinationsTab = state(destinations = places, timeline = false)
        assertEquals(false, destinationsTab.empty)
        assertEquals(9, destinationsTab.meterMax)
        assertEquals(emptyList<TimelineRow>(), destinationsTab.timeline)
        assertEquals(true, state(destinations = places, timeline = true).empty) // no entries
    }

    @Test
    fun eachTabShowsItsOwnRows() {
        val places = listOf(Destination("1.2.3.4", false, listOf(443), 9, 0), Destination("a.example", true, emptyList(), 4, 0))
        val entries = listOf(entry(at("2026-10-21T00:10")))
        val destinationsTab = state(destinations = places, entries = entries, timeline = false)
        assertEquals(false, destinationsTab.showTimeline)
        assertEquals(places, destinationsTab.destinations)
        assertEquals(7, destinationsTab.retentionDays)
        val timelineTab = state(destinations = places, entries = entries, timeline = true)
        assertEquals(true, timelineTab.showTimeline)
        assertEquals(false, timelineTab.empty)
    }

    @Test
    fun theFullMeterIsTheMostTriesToOnePlaceAndNeverZero() {
        fun place(attempts: Int) = Destination("a.example", true, emptyList(), attempts, 0)
        assertEquals(9, state(destinations = listOf(place(9), place(4)), timeline = false).meterMax) // the most, wherever it is
        assertEquals(1, state(destinations = listOf(place(1)), timeline = false).meterMax)
        assertEquals(1, state(destinations = listOf(place(0)), timeline = false).meterMax)
        assertEquals(1, state(timeline = false).meterMax)
    }
}
