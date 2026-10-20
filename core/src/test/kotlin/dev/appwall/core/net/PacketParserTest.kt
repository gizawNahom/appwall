package dev.appwall.core.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketParserTest {

    @Test
    fun ipv4Tcp() {
        val p = parse(ipv4(PacketParser.TCP, "10.111.0.1", "93.184.216.34", transport(40000, 443)))!!
        assertEquals(PacketParser.TCP, p.protocol)
        assertEquals(InetAddress.getByName("10.111.0.1"), p.source)
        assertEquals(40000, p.sourcePort)
        assertEquals(InetAddress.getByName("93.184.216.34"), p.destination)
        assertEquals(443, p.destinationPort)
        assertNull(p.dnsQuery)
    }

    @Test
    fun ipv6Udp() {
        val p = parse(ipv6(PacketParser.UDP, "fd00:a11:a11::1", "2001:db8::1", transport(5000, 443)))!!
        assertEquals(PacketParser.UDP, p.protocol)
        assertEquals(InetAddress.getByName("fd00:a11:a11::1"), p.source)
        assertEquals(InetAddress.getByName("2001:db8::1"), p.destination)
        assertEquals(5000, p.sourcePort)
        assertEquals(443, p.destinationPort)
    }

    @Test
    fun icmpHasNoPorts() {
        val p = parse(ipv4(PacketParser.ICMP, "10.111.0.1", "8.8.8.8", byteArrayOf(8, 0, 0, 0)))!!
        assertEquals(PacketParser.ICMP, p.protocol)
        assertEquals(0, p.sourcePort)
        assertEquals(0, p.destinationPort)
    }

    @Test
    fun ipv4OptionsMovePortsBack() {
        // IHL 6: a 24-byte header, so the ports start 4 bytes later than usual.
        val p = parse(ipv4(PacketParser.TCP, "10.111.0.1", "1.2.3.4", transport(1234, 80), optionWords = 1))!!
        assertEquals(1234, p.sourcePort)
        assertEquals(80, p.destinationPort)
    }

    @Test
    fun dnsQueryName() {
        val p = parse(ipv4(PacketParser.UDP, "10.111.0.1", "10.111.0.2", transport(3333, 53) + dns("www.example.com")))!!
        assertEquals("www.example.com", p.dnsQuery)
    }

    @Test
    fun dnsOverIpv6() {
        val p = parse(ipv6(PacketParser.UDP, "fd00:a11:a11::1", "fd00::2", transport(3333, 53) + dns("example.org")))!!
        assertEquals("example.org", p.dnsQuery)
    }

    @Test
    fun dnsWithNoQuestionsHasNoName() {
        val message = dns("example.org").also { it[5] = 0 } // QDCOUNT = 0
        assertNull(parse(ipv4(PacketParser.UDP, "10.111.0.1", "10.111.0.2", transport(3333, 53) + message))!!.dnsQuery)
    }

    @Test
    fun compressedDnsNameIsNotGuessed() {
        val message = dnsHeader() + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1)
        assertNull(parse(ipv4(PacketParser.UDP, "10.111.0.1", "10.111.0.2", transport(3333, 53) + message))!!.dnsQuery)
    }

    @Test
    fun dnsNameCutMidLabelIsNotGuessed() {
        val packet = ipv4(PacketParser.UDP, "10.111.0.1", "10.111.0.2", transport(3333, 53) + dns("www.example.com"))
        // Cut inside "example".
        val cut = packet.size - "com".length - 2 - 4 - 4
        assertNull(PacketParser.parse(packet, cut)!!.dnsQuery)
    }

    @Test
    fun dnsNameCutBetweenLabelsIsNotGuessed() {
        val packet = ipv4(PacketParser.UDP, "10.111.0.1", "10.111.0.2", transport(3333, 53) + dns("www.example.com"))
        // Cut right after "example": whole labels so far, but the name never ends.
        val cut = packet.size - 4 - 1 - "com".length - 1
        assertNull(PacketParser.parse(packet, cut)!!.dnsQuery)
    }

    @Test
    fun onlyTheGivenLengthIsRead() {
        // The tunnel reuses one buffer, so bytes past the length are leftovers from older packets.
        val packet = ipv4(PacketParser.TCP, "10.111.0.1", "1.2.3.4", transport(1234, 80))
        val buffer = packet + ByteArray(100) { 0x7F }
        assertEquals(parse(packet), PacketParser.parse(buffer, packet.size))
    }

    @Test
    fun truncatedTransportHeaderHasNoPorts() {
        val packet = ipv4(PacketParser.TCP, "10.111.0.1", "1.2.3.4", transport(1234, 80))
        val p = PacketParser.parse(packet, 20 + 2)!!
        assertEquals(0, p.sourcePort)
        assertEquals(0, p.destinationPort)
    }

    @Test
    fun tooShortOrNotIpIsRejected() {
        assertNull(PacketParser.parse(ByteArray(0), 0))
        assertNull(PacketParser.parse(ipv4(PacketParser.TCP, "10.0.0.1", "10.0.0.2", ByteArray(0)), 19))
        assertNull(PacketParser.parse(ipv6(PacketParser.TCP, "::1", "::2", ByteArray(0)), 39))
        assertNull(PacketParser.parse(byteArrayOf(0x50, 0, 0, 0), 4)) // IP version 5
    }

    @Test
    fun protocolNames() {
        assertEquals("TCP", PacketParser.protocolName(PacketParser.TCP))
        assertEquals("UDP", PacketParser.protocolName(PacketParser.UDP))
        assertEquals("ICMP", PacketParser.protocolName(PacketParser.ICMP))
        assertEquals("ICMP", PacketParser.protocolName(PacketParser.ICMPV6))
        assertEquals("IP/47", PacketParser.protocolName(47))
    }

    // Packet builders. Only the fields the parser reads are filled in.

    private fun parse(packet: ByteArray) = PacketParser.parse(packet, packet.size)

    private fun ipv4(protocol: Int, source: String, destination: String, payload: ByteArray, optionWords: Int = 0): ByteArray {
        val header = ByteArray(20 + optionWords * 4)
        header[0] = (0x40 or (5 + optionWords)).toByte()
        header[9] = protocol.toByte()
        InetAddress.getByName(source).address.copyInto(header, 12)
        InetAddress.getByName(destination).address.copyInto(header, 16)
        return header + payload
    }

    private fun ipv6(protocol: Int, source: String, destination: String, payload: ByteArray): ByteArray {
        val header = ByteArray(40)
        header[0] = 0x60
        header[6] = protocol.toByte()
        InetAddress.getByName(source).address.copyInto(header, 8)
        InetAddress.getByName(destination).address.copyInto(header, 24)
        return header + payload
    }

    /** The first 8 bytes of a TCP or UDP header: the ports, then zeros. */
    private fun transport(sourcePort: Int, destinationPort: Int) =
        byteArrayOf((sourcePort shr 8).toByte(), sourcePort.toByte(), (destinationPort shr 8).toByte(), destinationPort.toByte(), 0, 0, 0, 0)

    private fun dnsHeader() = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)

    /** A DNS query for [name], type A, class IN. */
    private fun dns(name: String): ByteArray {
        var question = ByteArray(0)
        for (label in name.split('.')) question += byteArrayOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII)
        return dnsHeader() + question + byteArrayOf(0, 0, 1, 0, 1)
    }
}
