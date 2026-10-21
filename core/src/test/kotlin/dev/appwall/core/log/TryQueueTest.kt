package dev.appwall.core.log

import dev.appwall.core.net.Packet
import dev.appwall.core.net.PacketParser
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class TryQueueTest {

    private var now = 1_000L
    private var flushes = 0
    private val queue = TryQueue(capacity = 3, clock = { now }) { flushes++ }

    @Test
    fun triesComeOutInOrderStampedWhenOffered() {
        queue.offer("a", packet(443))
        now = 2_000
        queue.offer(null, packet(80))
        assertEquals(listOf(BlockedTry("a", packet(443), 1_000), BlockedTry(null, packet(80), 2_000)), queue.drain())
        assertEquals(emptyList<BlockedTry>(), queue.drain())
    }

    @Test
    fun oneFlushIsAskedForPerBatch() {
        repeat(3) { queue.offer("a", packet(443)) }
        assertEquals(1, flushes)
        queue.drain()
        queue.offer("a", packet(443))
        assertEquals(2, flushes)
    }

    @Test
    fun aFullQueueDropsAndCountsWithoutBlocking() {
        repeat(5) { queue.offer("a", packet(it)) }
        assertEquals(2, queue.droppedTries)
        assertEquals(listOf(0, 1, 2), queue.drain().map { it.packet.destinationPort }) // the oldest are kept
        queue.offer("a", packet(443))
        assertEquals(1, queue.drain().size)
        assertEquals(2, queue.droppedTries)
    }

    private fun packet(port: Int) =
        Packet(PacketParser.TCP, InetAddress.getByName("10.111.0.1"), 40000, InetAddress.getByName("1.2.3.4"), port, null)
}
