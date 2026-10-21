package dev.appwall.core.firewall

import dev.appwall.core.firewall.NewAppDecision.ASK
import dev.appwall.core.firewall.NewAppDecision.AUTO_BLOCK
import dev.appwall.core.firewall.NewAppDecision.IGNORE
import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionsTest {

    // tunnelPlan

    @Test
    fun nothingBlockedMeansNoTunnel() {
        assertEquals(TunnelPlan.NoTunnel, tunnelPlan(emptySet()) { true })
    }

    @Test
    fun onlyUninstalledAppsBlockedMeansNoTunnel() {
        // An empty allow-list would route every app into the tunnel, cutting all of them off.
        assertEquals(TunnelPlan.NoTunnel, tunnelPlan(setOf("gone.app")) { false })
    }

    @Test
    fun installedBlockedAppsAreRoutedInAStableOrder() {
        val plan = tunnelPlan(setOf("b.app", "gone.app", "a.app")) { it != "gone.app" }
        assertEquals(TunnelPlan.Route(listOf("a.app", "b.app")), plan)
        assertEquals(listOf("a.app", "b.app"), (plan as TunnelPlan.Route).packages)
    }

    // shouldCount

    @Test
    fun shouldCountTable() {
        data class Case(val name: String, val packet: Packet, val blocked: Int, val expected: Boolean)
        val cases = listOf(
            Case("TCP", packet(PacketParser.TCP, "1.2.3.4", 443), 3, true),
            Case("UDP", packet(PacketParser.UDP, "1.2.3.4", 443), 3, true),
            Case("DNS lookup", packet(PacketParser.UDP, DNS_SERVER, 53), 3, true),
            Case("IPv6 housekeeping", packet(PacketParser.ICMPV6, "ff02::2", 0), 1, false),
            Case("Private DNS probe", packet(PacketParser.TCP, DNS_SERVER, 853), 1, false),
            Case("DNS-over-TLS elsewhere is an app's", packet(PacketParser.TCP, "1.1.1.1", 853), 3, true),
            Case("ping, one app blocked", packet(PacketParser.ICMP, "8.8.8.8", 0), 1, true),
            Case("ping, several apps blocked", packet(PacketParser.ICMP, "8.8.8.8", 0), 2, false),
            Case("IPv6 multicast (ff02::16), even as one app's", packet(0, "ff02::16", 0), 1, false),
            Case("mDNS multicast", packet(PacketParser.UDP, "224.0.0.251", 5353), 3, false),
            Case("broadcast", packet(PacketParser.UDP, "255.255.255.255", 67), 3, false),
            Case("link-local", packet(PacketParser.UDP, "fe80::1", 547), 3, false),
            Case("a private LAN address is still an app's", packet(PacketParser.TCP, "192.168.1.10", 8080), 3, true),
        )
        for (c in cases) assertEquals(c.name, c.expected, shouldCount(c.packet, c.blocked))
    }

    // pickOwner

    @Test
    fun pickOwnerTable() {
        val chrome = "com.android.chrome"
        val other = "org.example.other"
        data class Case(val name: String, val owners: List<String>?, val blocked: Set<String>, val expected: String?)
        val cases = listOf(
            Case("Android names the app", listOf(chrome), setOf(chrome, other), chrome),
            Case("shared user ID: the blocked one wins", listOf(other, chrome), setOf(chrome), chrome),
            Case("owner isn't blocked: still the owner", listOf(other), setOf(chrome), other),
            Case("no answer, one app blocked", null, setOf(chrome), chrome),
            Case("no answer, several blocked", null, setOf(chrome, other), null),
            Case("empty answer counts as none", emptyList(), setOf(chrome), chrome),
        )
        for (c in cases) assertEquals(c.name, c.expected, pickOwner(c.owners, c.blocked))
    }

    // newAppDecision

    @Test
    fun newAppDecisionTable() {
        data class Case(val mode: NewAppMode, val self: Boolean, val blocked: Boolean, val internet: Boolean?, val expected: NewAppDecision)
        val cases = listOf(
            Case(NewAppMode.ASK, false, false, true, ASK),
            Case(NewAppMode.BLOCK, false, false, true, AUTO_BLOCK),
            Case(NewAppMode.ALLOW, false, false, true, IGNORE),
            Case(NewAppMode.ASK, false, false, false, IGNORE), // can't use the internet anyway
            Case(NewAppMode.BLOCK, false, false, false, IGNORE),
            Case(NewAppMode.ASK, false, false, null, IGNORE), // gone again before we looked
            Case(NewAppMode.BLOCK, true, false, true, IGNORE), // never AppWall itself
            Case(NewAppMode.ASK, false, true, true, IGNORE), // already blocked (e.g. reinstalled)
            Case(NewAppMode.BLOCK, false, true, true, IGNORE),
        )
        for (c in cases) assertEquals(c.toString(), c.expected, newAppDecision(c.mode, c.self, c.blocked, c.internet))
        assertEquals(NewAppDecision.entries.toSet(), cases.map { it.expected }.toSet()) // every decision is covered
    }

    private fun packet(protocol: Int, destination: String, port: Int) =
        Packet(protocol, InetAddress.getByName("10.111.0.1"), 40000, InetAddress.getByName(destination), port, null)

    @Test
    fun shouldReapplyTable() {
        data class Case(val running: Boolean, val enabled: Boolean, val blocked: Set<String>, val applied: Set<String>?, val expected: Boolean)
        val ab = setOf("a", "b")
        val cases = listOf(
            Case(true, true, ab, setOf("a"), true), // blocked another app
            Case(true, true, setOf("a"), ab, true), // allowed one
            Case(true, true, emptySet(), ab, true), // allowed the last: must drop the tunnel
            Case(true, true, ab, null, true), // never built yet
            Case(true, true, ab, setOf("b", "a"), false), // same apps: a repeat, or the service's own write
            Case(true, false, ab, setOf("a"), false), // turned off: stopping is the off command's job
            Case(false, true, ab, setOf("a"), false), // not running: the next start builds from the saved rules
        )
        for (c in cases) assertEquals(c.toString(), c.expected, shouldReapply(c.running, c.enabled, c.blocked, c.applied))
    }
}
