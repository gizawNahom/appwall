package dev.appwall.core.log

import dev.appwall.core.log.TryTracker.Companion.CONNECTION_IDLE_MS
import dev.appwall.core.log.TryTracker.Companion.LEARN_MS
import dev.appwall.core.log.TryTracker.Companion.LOOKUP_WINDOW_MS
import dev.appwall.core.log.TryTracker.Companion.PENDING_MS
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class TryTrackerTest {

    /** What Android says owns each packet, by source port; missing = it can't tell. */
    private val owners = HashMap<Int, String?>()
    private val tries = ArrayList<Pair<String?, Packet>>()
    private val tracker = TryTracker({ owners[it.sourcePort] }) { owner, packet -> tries += owner to packet }

    @Test
    fun retransmitsOfOneConnectionAreOneTry() {
        owners[1000] = CHROME
        repeat(5) { tracker.onPacket(tcp(1000, "1.2.3.4", 443), it * 2_000L) }
        assertEquals(listOf(CHROME), owners())
    }

    @Test
    fun eachNewConnectionIsATry() {
        owners[1000] = CHROME
        owners[1001] = CHROME
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        tracker.onPacket(tcp(1001, "1.2.3.4", 443), 10)
        assertEquals(listOf(CHROME, CHROME), owners())
    }

    @Test
    fun repeatLookupsOfANameCountOncePerWindow() {
        for (port in 2000..2003) owners[port] = CHROME
        tracker.onPacket(dns(2000, "example.com"), 0)
        tracker.onPacket(dns(2001, "example.com"), 1_000) // the resolver's retry
        tracker.onPacket(dns(2002, "example.com"), LOOKUP_WINDOW_MS - 1)
        assertEquals(1, tries.size)
        tracker.onPacket(dns(2003, "example.com"), LOOKUP_WINDOW_MS)
        assertEquals(2, tries.size)
    }

    @Test
    fun differentNamesAndAppsAreSeparateLookups() {
        owners[2000] = CHROME
        owners[2001] = CHROME
        owners[2002] = OTHER
        tracker.onPacket(dns(2000, "a.example"), 0)
        tracker.onPacket(dns(2001, "b.example"), 0)
        tracker.onPacket(dns(2002, "a.example"), 0)
        assertEquals(3, tries.size)
    }

    @Test
    fun unidentifiedConnectionWaitsForAndroidToNameItsApp() {
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        assertEquals(0, tries.size) // pending, not yet counted
        owners[1000] = CHROME // Android can say on the retransmit
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 1_000)
        assertEquals(listOf(CHROME), owners())
        tracker.sweep(PENDING_MS + 1_000)
        assertEquals(1, tries.size)
    }

    @Test
    fun stillUnidentifiedAfterWaitingGoesToTheOneAppSeenAtThatDestination() {
        owners[1000] = CHROME
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        tracker.onPacket(tcp(1001, "1.2.3.4", 443), 100) // Android can't name this one
        tracker.sweep(100 + PENDING_MS - 1)
        assertEquals(1, tries.size)
        tracker.sweep(100 + PENDING_MS)
        assertEquals(listOf(CHROME, CHROME), owners())
    }

    @Test
    fun destinationsMatchOnPortToo() {
        owners[1000] = CHROME
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        tracker.onPacket(tcp(1001, "1.2.3.4", 80), 100)
        tracker.sweep(100 + PENDING_MS)
        assertEquals(listOf(CHROME, null), owners())
    }

    @Test
    fun aDestinationSeveralAppsUseStaysUnidentified() {
        owners[1000] = CHROME
        owners[1001] = OTHER
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        tracker.onPacket(tcp(1001, "1.2.3.4", 443), 0)
        tracker.onPacket(tcp(1002, "1.2.3.4", 443), 100)
        tracker.sweep(100 + PENDING_MS)
        assertEquals(listOf(CHROME, OTHER, null), owners())
    }

    @Test
    fun learnedDestinationsAreForgotten() {
        owners[1000] = CHROME
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        val later = LEARN_MS + 1
        tracker.onPacket(tcp(1001, "1.2.3.4", 443), later)
        tracker.sweep(later + PENDING_MS)
        assertEquals(listOf(CHROME, null), owners())
    }

    @Test
    fun lookupsNeverBorrowAnOwnerFromTheirDestination() {
        // Every lookup goes to AppWall's own DNS server, so the address says nothing about the app.
        owners[2000] = CHROME
        tracker.onPacket(dns(2000, "a.example"), 0)
        tracker.onPacket(dns(2001, "b.example"), 100)
        tracker.sweep(100 + PENDING_MS)
        assertEquals(listOf(CHROME, null), owners())
    }

    @Test
    fun quietConnectionsAreForgottenSoTheirPortCanBeReused() {
        owners[1000] = CHROME
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 0)
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), CONNECTION_IDLE_MS - 1) // still the same connection
        tracker.sweep(2 * CONNECTION_IDLE_MS)
        tracker.onPacket(tcp(1000, "1.2.3.4", 443), 2 * CONNECTION_IDLE_MS + 1)
        assertEquals(listOf(CHROME, CHROME), owners())
    }

    private fun owners() = tries.map { it.first }

    private fun tcp(sourcePort: Int, destination: String, port: Int) =
        Packet(PacketParser.TCP, InetAddress.getByName("10.111.0.1"), sourcePort, InetAddress.getByName(destination), port, null)

    private fun dns(sourcePort: Int, name: String) =
        Packet(PacketParser.UDP, InetAddress.getByName("10.111.0.1"), sourcePort, InetAddress.getByName("10.111.0.2"), 53, name)

    private companion object {
        const val CHROME = "com.android.chrome"
        const val OTHER = "org.example.other"
    }
}
