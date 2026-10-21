package dev.appwall.core.net

import java.net.InetAddress

/** The parts of a raw IP packet the blocked-traffic log cares about. */
data class Packet(
    val protocol: Int,
    val source: InetAddress,
    val sourcePort: Int,
    val destination: InetAddress,
    val destinationPort: Int,
    /** For a DNS query (UDP to port 53): the domain name being looked up. */
    val dnsQuery: String?,
)

object PacketParser {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ICMPV6 = 58

    /** The longest a DNS name can be on the wire, length bytes included (RFC 1035). */
    private const val MAX_DNS_NAME = 255

    /** Parses an IPv4 or IPv6 packet read from the tunnel; null if it's too short or not IP. */
    fun parse(b: ByteArray, length: Int): Packet? {
        if (length < 1) return null
        val headerLength: Int
        val protocol: Int
        val source: ByteArray
        val destination: ByteArray
        when ((b[0].toInt() shr 4) and 0xF) {
            4 -> {
                if (length < 20) return null
                headerLength = (b[0].toInt() and 0xF) * 4
                protocol = b[9].toInt() and 0xFF
                source = b.copyOfRange(12, 16)
                destination = b.copyOfRange(16, 20)
            }
            6 -> {
                if (length < 40) return null
                headerLength = 40 // extension headers aren't followed; such packets just get no ports
                protocol = b[6].toInt() and 0xFF
                source = b.copyOfRange(8, 24)
                destination = b.copyOfRange(24, 40)
            }
            else -> return null
        }

        var sourcePort = 0
        var destinationPort = 0
        if ((protocol == TCP || protocol == UDP) && length >= headerLength + 4) {
            sourcePort = u16(b, headerLength)
            destinationPort = u16(b, headerLength + 2)
        }
        val dnsQuery = if (protocol == UDP && destinationPort == 53) dnsQuestion(b, headerLength + 8, length) else null

        return Packet(
            protocol,
            InetAddress.getByAddress(source),
            sourcePort,
            InetAddress.getByAddress(destination),
            destinationPort,
            dnsQuery,
        )
    }

    fun protocolName(protocol: Int): String = when (protocol) {
        TCP -> "TCP"
        UDP -> "UDP"
        ICMP, ICMPV6 -> "ICMP"
        else -> "IP/$protocol"
    }

    /**
     * Reads the first question's name from a DNS message starting at [start]. A blocked app chooses
     * these bytes, so they're shown the way DNS tools show them: printable ASCII as is, anything
     * else (control characters, high bytes) and a dot or backslash inside a label escaped, as
     * `\DDD`, `\.` and `\\`. A name longer than DNS allows (255 bytes on the wire) isn't one.
     */
    private fun dnsQuestion(b: ByteArray, start: Int, length: Int): String? {
        if (length < start + 12 || u16(b, start + 4) == 0) return null // no room, or no questions
        val labels = mutableListOf<String>()
        var i = start + 12
        var wireLength = 1 // the closing zero byte
        while (true) {
            if (i >= length) return null // truncated before the name's end, so not the real name
            val labelLength = b[i].toInt() and 0xFF
            if (labelLength == 0) break
            if (labelLength and 0xC0 != 0 || i + 1 + labelLength > length) return null // compressed or truncated
            wireLength += 1 + labelLength
            if (wireLength > MAX_DNS_NAME) return null
            labels += presentation(b, i + 1, labelLength)
            i += 1 + labelLength
        }
        return labels.joinToString(".").ifEmpty { null }
    }

    /** One label as DNS tools print it (RFC 1035 section 5.1). */
    private fun presentation(b: ByteArray, from: Int, count: Int): String = buildString {
        for (k in from until from + count) {
            val c = b[k].toInt() and 0xFF
            when {
                c == '.'.code || c == '\\'.code -> append('\\').append(c.toChar())
                c in 0x21..0x7E -> append(c.toChar())
                else -> append('\\').append(c.toString().padStart(3, '0'))
            }
        }
    }

    private fun u16(b: ByteArray, offset: Int) = ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
}
