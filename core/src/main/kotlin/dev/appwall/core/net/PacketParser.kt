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
    /** A DNS message's fixed header; the first question's name follows it. */
    private const val DNS_HEADER = 12
    /** Where in the header a DNS message says how many questions it asks. */
    private const val QUESTION_COUNT = 4

    /** Parses an IPv4 or IPv6 packet read from the tunnel; null if it's too short or not IP. */
    fun parse(b: ByteArray, length: Int): Packet? {
        if (length == 0) return null
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

        return Packet(protocol, address(source), sourcePort, address(destination), destinationPort, dnsQuery)
    }

    /**
     * An IPv4 or IPv6 address from its 4 or 16 bytes. Typed here, so the Java method's result is an
     * [InetAddress] (it never returns null) rather than a platform type null-checked at each use;
     * [Packet]'s constructor still rejects a null.
     */
    private val address: (ByteArray) -> InetAddress = InetAddress::getByAddress

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
        val nameStart = start + DNS_HEADER
        val nameEnd = nameEnd(b, nameStart, length) ?: return null
        if (nameEnd + 1 - nameStart > MAX_DNS_NAME) return null
        // Read only now: a whole name was there, so the header before it was too.
        if (u16(b, start + QUESTION_COUNT) == 0) return null // no questions, so the bytes aren't a name
        return labels(b, nameStart).joinToString(".").ifEmpty { null } // the root name
    }

    /**
     * Where the name starting at [from] ends: the index of its closing zero byte. Null if it's
     * compressed, or cut off by [length] before its end (so not the real name). Reads nothing at
     * or past [length].
     */
    private fun nameEnd(b: ByteArray, from: Int, length: Int): Int? {
        var i = from
        while (i < length) {
            val labelLength = b[i].toInt() and 0xFF
            if (labelLength == 0) return i
            if (labelLength and 0xC0 != 0) return null // a pointer to a name elsewhere in the message
            i += 1 + labelLength
        }
        return null
    }

    /** The labels of the name starting at [from], as shown. Only for a name [nameEnd] found whole. */
    private fun labels(b: ByteArray, from: Int): List<String> {
        val labels = mutableListOf<String>()
        var i = from
        while (b[i].toInt() != 0) {
            val labelLength = b[i].toInt() and 0xFF
            labels += presentation(b, i + 1, labelLength)
            i += 1 + labelLength
        }
        return labels
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
