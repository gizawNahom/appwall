package dev.appwall.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogValuesTest {

    @Test
    fun aConnectedDestinationShowsItsPortsInOrder() {
        val destination = Destination.fromPortList("1.2.3.4", isLookup = false, portList = "443,80,8080", attempts = 5, lastTime = 9_000)
        assertEquals("1.2.3.4", destination.name)
        assertFalse(destination.isLookup)
        assertEquals(listOf(80, 443, 8080), destination.ports)
        assertEquals(5, destination.attempts)
        assertEquals(9_000L, destination.lastTime)
    }

    @Test
    fun aLookupShowsNoPort() {
        // Its port is always DNS's, which says nothing about the app.
        val destination = Destination.fromPortList("a.example", isLookup = true, portList = "53", attempts = 1, lastTime = 9_000)
        assertTrue(destination.isLookup)
        assertEquals(emptyList<Int>(), destination.ports)
    }

    @Test
    fun portZeroAndNonNumbersAreNotShown() {
        // Port 0 stands for a protocol without ports, such as ICMP.
        assertEquals(listOf(443), Destination.fromPortList("1.2.3.4", false, "0,443,x", 3, 9_000).ports)
    }

    @Test
    fun aLogEntryKeepsEachFieldTheScreensShow() {
        val entry = LogEntry(time = 9_000, packageName = CHROME, protocol = 17, address = "10.111.0.2", port = 53, host = "a.example", attempts = 2)
        assertEquals(
            listOf<Any?>(9_000L, CHROME, 17, "10.111.0.2", 53, "a.example", 2),
            listOf(entry.time, entry.packageName, entry.protocol, entry.address, entry.port, entry.host, entry.attempts),
        )
    }

    @Test
    fun anAppSummaryKeepsEachFieldTheScreensShow() {
        val summary = AppSummary(packageName = CHROME, attempts = 7, destinations = 3, lastTime = 9_000)
        assertEquals(
            listOf<Any?>(CHROME, 7, 3, 9_000L),
            listOf(summary.packageName, summary.attempts, summary.destinations, summary.lastTime),
        )
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
