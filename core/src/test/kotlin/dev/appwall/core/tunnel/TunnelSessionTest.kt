package dev.appwall.core.tunnel

import dev.appwall.core.log.TryTracker.Companion.PENDING_MS
import dev.appwall.core.net.Packet
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TunnelSessionTest {

    /** A tunnel that plays back packets; [HANG_UP] means "hung up". Records reads and closes. */
    private class FakeTunnel(vararg packets: ByteArray?) : Tunnel {
        val script = ArrayDeque(packets.toList())
        var reads = 0
        var closes = 0
        /** Runs during the next read, as if it happened while the loop was waiting on this tunnel. */
        var duringRead: (() -> Unit)? = null

        override val packets = PacketSource { buffer, _ ->
            check(closes == 0) { "read after close" }
            reads++
            duringRead?.also { duringRead = null }?.invoke()
            when (val next = script.removeFirstOrNull()) {
                null -> 0
                HANG_UP -> -1
                else -> next.copyInto(buffer).let { next.size }
            }
        }

        override fun close() {
            closes++
        }
    }

    private var now = 0L
    private val owners = HashMap<Int, String?>() // by source port
    private val tries = ArrayList<String?>()
    private var failures = 0
    private val session = TunnelSession(
        owners = { packet -> owners[packet.sourcePort]?.let { listOf(it) } },
        loggingEnabled = { true },
        onTry = { owner, _ -> tries += owner },
        onFailure = { failures++ },
        clock = { now },
        pollMs = 1,
    ).apply { blocked = setOf(CHROME, OTHER) }
    private val buffer = ByteArray(32767)

    /** Most tests drive the running loop one turn at a time, with [TunnelSession.step]. */
    private fun runLoopByHand() = assertTrue(session.claimLoop())

    @Test
    fun withoutALoopASwitchClosesTheOldTunnelAtOnce() {
        val old = FakeTunnel()
        session.switchTo(old)
        session.switchTo(FakeTunnel())
        assertEquals(1, old.closes)
    }

    @Test
    fun handOverClosesTheOldTunnelAndNeverReadsItAgain() {
        val old = FakeTunnel(tcp(1000))
        val new = FakeTunnel(tcp(1001))
        owners[1000] = CHROME
        owners[1001] = CHROME
        runLoopByHand()
        session.switchTo(old)
        assertTrue(session.step(buffer))
        session.switchTo(new)
        assertEquals(0, old.closes) // the loop closes it at its next turn, not the caller
        session.step(buffer)
        session.step(buffer)
        assertEquals(1, old.closes)
        assertEquals(1, old.reads)
        assertEquals(2, new.reads)
        assertEquals(listOf(CHROME, CHROME), tries)
    }

    @Test
    fun aPendingConnectionKeepsItsStateAcrossTheHandOver() {
        val old = FakeTunnel(tcp(1000))
        val new = FakeTunnel(tcp(1000)) // the same connection retransmits into the new tunnel
        runLoopByHand()
        session.switchTo(old)
        session.step(buffer) // Android can't name the owner yet: pending
        session.switchTo(new)
        owners[1000] = CHROME
        session.step(buffer)
        now += PENDING_MS
        session.step(buffer)
        assertEquals(listOf(CHROME), tries) // one try, attributed, not reset by the switch
    }

    @Test
    fun theOldTunnelHangingUpDuringAHandOverIsNotAFailure() {
        // Android hangs up the old tunnel as soon as its replacement is established.
        val old = FakeTunnel(HANG_UP)
        val new = FakeTunnel(tcp(1001))
        runLoopByHand()
        session.switchTo(old)
        old.duringRead = { session.switchTo(new) } // arrives while the loop waits on the old tunnel
        assertTrue(session.step(buffer)) // reads the hang-up: not a failure
        assertEquals(0, old.closes)
        assertTrue(session.step(buffer)) // switches
        assertEquals(0, failures)
        assertEquals(1, old.closes)
        assertEquals(1, new.reads)
    }

    @Test
    fun aHangUpWithNoReplacementFailsWithoutClosingTheTunnel() {
        val tunnel = FakeTunnel(HANG_UP)
        runLoopByHand()
        session.switchTo(tunnel)
        try {
            session.step(buffer)
            fail("expected the session to report a failed tunnel")
        } catch (expected: TunnelSession.TunnelFailed) {
            session.loopEnded(expected)
        }
        assertEquals(1, failures)
        assertEquals(0, tunnel.closes) // the wall stays up; the service decides what's next
    }

    @Test
    fun noTunnelClosesTheCurrentOneAndIdles() {
        val tunnel = FakeTunnel()
        runLoopByHand()
        session.switchTo(tunnel)
        session.step(buffer)
        session.switchTo(null)
        assertTrue(session.step(buffer))
        assertEquals(1, tunnel.closes)
        assertTrue(session.step(buffer)) // idles, reading nothing
        assertEquals(1, tunnel.reads)
    }

    @Test
    fun shutdownClosesEveryTunnelExactlyOnce() {
        val current = FakeTunnel()
        val waiting = FakeTunnel()
        runLoopByHand()
        session.switchTo(current)
        session.step(buffer)
        session.switchTo(waiting) // handed over, not yet taken
        session.shutdown()
        session.shutdown()
        assertEquals(0, current.closes) // the loop closes them as it ends, not the caller
        assertFalse(session.step(buffer))
        session.loopEnded(null)
        assertEquals(0, failures)
        assertEquals(1, current.closes)
        assertEquals(1, waiting.closes)
        val late = FakeTunnel()
        session.switchTo(late) // after shutdown: closed straight away, never read
        assertEquals(1, late.closes)
        assertEquals(0, late.reads)
    }

    @Test
    fun aTunnelReplacedBeforeItWasTakenIsClosedOnceAndNeverRead() {
        val skipped = FakeTunnel()
        val final = FakeTunnel()
        runLoopByHand()
        session.switchTo(skipped)
        session.switchTo(final)
        assertEquals(1, skipped.closes)
        session.step(buffer)
        assertEquals(0, skipped.reads)
        assertEquals(1, final.reads)
    }

    @Test
    fun theRealThreadClosesEverythingOnShutdown() {
        val tunnel = FakeTunnel()
        session.switchTo(tunnel)
        session.start()
        session.shutdown()
        val deadline = System.currentTimeMillis() + 2_000
        while (tunnel.closes == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, tunnel.closes)
        assertEquals(0, failures)
    }

    @Test
    fun aFailingTryIsSkippedAndTheLoopGoesOn() {
        val failing = TunnelSession(
            owners = { listOf(CHROME) },
            loggingEnabled = { true },
            onTry = { _, _ -> throw IllegalStateException("log is broken") },
            onFailure = { failures++ },
            clock = { now },
            pollMs = 1,
        ).apply { blocked = setOf(CHROME) }
        val tunnel = FakeTunnel(tcp(1000), tcp(1001))
        failing.claimLoop()
        failing.switchTo(tunnel)
        assertTrue(failing.step(buffer))
        assertTrue(failing.step(buffer))
        assertEquals(2, tunnel.reads)
        assertEquals(0, tunnel.closes)
        assertEquals(0, failures)
    }

    @Test
    fun aDamagedPacketIsIgnored() {
        val tunnel = FakeTunnel(byteArrayOf(0x45, 0, 0), tcp(1000))
        owners[1000] = CHROME
        session.switchTo(tunnel)
        session.step(buffer)
        session.step(buffer)
        assertEquals(listOf(CHROME), tries)
    }

    @Test
    fun nothingIsCountedWhileLoggingIsOff() {
        val quiet = TunnelSession(
            owners = { listOf(CHROME) },
            loggingEnabled = { false },
            onTry = { owner, _ -> tries += owner },
            onFailure = { failures++ },
            clock = { now },
            pollMs = 1,
        ).apply { blocked = setOf(CHROME) }
        quiet.switchTo(FakeTunnel(tcp(1000)))
        quiet.step(buffer)
        assertEquals(emptyList<String?>(), tries)
    }

    /** A minimal IPv4 TCP packet from [sourcePort] to 1.2.3.4:443. */
    private fun tcp(sourcePort: Int): ByteArray {
        val packet = ByteArray(28)
        packet[0] = 0x45
        packet[9] = 6
        InetAddress.getByName("10.111.0.1").address.copyInto(packet, 12)
        InetAddress.getByName("1.2.3.4").address.copyInto(packet, 16)
        packet[20] = (sourcePort shr 8).toByte()
        packet[21] = sourcePort.toByte()
        packet[22] = (443 shr 8).toByte()
        packet[23] = 443.toByte()
        return packet
    }

    private companion object {
        const val CHROME = "com.android.chrome"
        const val OTHER = "org.example.other"
        val HANG_UP = ByteArray(0)
    }
}
