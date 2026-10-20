package dev.appwall.core.log

import dev.appwall.core.log.LogMerger.Companion.WINDOW_MS
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LogMergerTest {

    private val merger = LogMerger()

    @Test
    fun aNewPlaceGetsARow() {
        assertEquals(LogWrite.Insert, merger.plan("k", 0))
    }

    @Test
    fun repeatsWithinAMinuteOfTheFirstTryBumpThatRow() {
        merger.inserted("k", 7, 0)
        assertEquals(LogWrite.Bump(7), merger.plan("k", 1))
        assertEquals(LogWrite.Bump(7), merger.plan("k", WINDOW_MS - 1))
    }

    @Test
    fun theWindowIsFixedFromTheFirstTryNotSliding() {
        // A try every 30 s for five minutes: rows of one minute each, not one row stamped at the end.
        var rows = 0
        var nextId = 1L
        for (t in 0..300_000L step 30_000) {
            if (merger.plan("k", t) == LogWrite.Insert) {
                merger.inserted("k", nextId++, t)
                rows++
            }
        }
        assertEquals(6, rows) // 0, 60, 120, 180, 240, 300 s
    }

    @Test
    fun differentPlacesDontShareRows() {
        merger.inserted("a", 1, 0)
        assertEquals(LogWrite.Insert, merger.plan("b", 1))
    }

    @Test
    fun forgetAndClearCloseRows() {
        merger.inserted("old", 1, 0)
        merger.inserted("new", 2, WINDOW_MS)
        merger.forget(WINDOW_MS)
        assertEquals(LogWrite.Insert, merger.plan("old", WINDOW_MS)) // closed anyway by time
        assertEquals(LogWrite.Bump(2), merger.plan("new", WINDOW_MS))
        merger.clear()
        assertEquals(LogWrite.Insert, merger.plan("new", WINDOW_MS))
    }

    @Test
    fun keysTellAppsPortsAndNamesApart() {
        fun p(port: Int, name: String? = null) =
            Packet(PacketParser.UDP, InetAddress.getByName("10.111.0.1"), 5000, InetAddress.getByName("1.2.3.4"), port, name)
        assertEquals(LogMerger.key("a", p(443)), LogMerger.key("a", p(443)))
        assertNotEquals(LogMerger.key("a", p(443)), LogMerger.key("b", p(443)))
        assertNotEquals(LogMerger.key("a", p(443)), LogMerger.key("a", p(80)))
        assertNotEquals(LogMerger.key("a", p(53, "x.example")), LogMerger.key("a", p(53, "y.example")))
    }
}
