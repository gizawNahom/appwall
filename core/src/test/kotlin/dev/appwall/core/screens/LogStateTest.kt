package dev.appwall.core.screens

import dev.appwall.core.log.AppSummary
import dev.appwall.core.settings.LogSettings
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LogStateTest {

    private val apps = listOf(AppSummary("com.android.chrome", 7, 3, 2_000), AppSummary(null, 2, 1, 1_000))
    private val hourly = IntArray(24).also { it[23] = 5 }

    @Test
    fun totalsAndRows() {
        val state = logState(apps, todayAttempts = 4, hourly = hourly, settings = LogSettings(retentionDays = 30))
        assertEquals(apps, state.apps)
        assertEquals(4, state.todayAttempts)
        assertEquals(9, state.periodAttempts)
        assertEquals(5, state.hourly.last())
        assertEquals(LogEmpty.NONE, state.empty)
        assertEquals(30, state.retentionDays)
    }

    @Test
    fun whyItsEmpty() {
        val table = listOf(
            Triple(emptyList<AppSummary>(), true, LogEmpty.NOTHING_YET),
            Triple(emptyList(), false, LogEmpty.LOGGING_OFF),
            Triple(apps, false, LogEmpty.NONE), // kept rows still show while logging is off
        )
        for ((rows, logging, expected) in table) {
            assertEquals("logging $logging, ${rows.size} rows", expected, logState(rows, 0, IntArray(24), LogSettings(loggingEnabled = logging)).empty)
        }
    }

    @Test
    fun todayStartsAtLocalMidnight() {
        val zone = ZoneId.of("Africa/Addis_Ababa")
        fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        assertEquals(at("2026-10-21T00:00"), startOfDay(at("2026-10-21T00:30"), zone))
        assertEquals(at("2026-10-20T00:00"), startOfDay(at("2026-10-20T23:59"), zone))
    }
}
