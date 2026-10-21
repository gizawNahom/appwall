package dev.appwall.core.tunnel

import dev.appwall.core.log.TryTracker.Companion.PENDING_MS
import dev.appwall.core.net.Packet
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            check(reads < RUNAWAY) { "read on and on: the loop should have ended" }
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

    /** Keeps the loop it's given instead of starting a thread, and records idles and wakes. */
    private class FakeLoopHost : LoopHost {
        var launches = 0
        private var loop: Runnable? = null
        val idles = ArrayList<Int>()
        var wakes = 0

        override fun launch(loop: Runnable) {
            launches++
            this.loop = loop
        }

        override fun idle(timeoutMs: Int) {
            check(idles.size < RUNAWAY) { "idled on and on: the loop should have ended" }
            idles += timeoutMs
        }

        override fun wake() {
            wakes++
        }

        /** Runs the launched loop here, until it ends. */
        fun runLoop() = checkNotNull(loop).run()
    }

    private var now = 0L
    private val owners = HashMap<Int, String?>() // by source port
    private val tries = ArrayList<String?>()
    private val failures = ArrayList<Throwable?>()
    private val skips = ArrayList<Pair<Int, Exception>>()
    private val host = FakeLoopHost()
    private val session = newSession()
    private val buffer = ByteArray(32767)

    private fun newSession(
        onTry: (String?, Packet) -> Unit = { owner, _ -> tries += owner },
        loggingEnabled: () -> Boolean = { true },
        host: LoopHost = this.host,
    ) = TunnelSession(
        owners = { packet -> owners[packet.sourcePort]?.let { listOf(it) } },
        loggingEnabled = loggingEnabled,
        onTry = onTry,
        onFailure = { failures += it },
        onSkipped = { count, error -> skips += count to error },
        clock = { now },
        host = host,
        pollMs = POLL_MS,
    ).apply { updateBlocked(setOf(CHROME, OTHER)) }

    /**
     * Most tests start the loop, then run its turns by hand with [TunnelSession.step], as the
     * loop's thread would; [FakeLoopHost.runLoop] runs it to its end.
     */
    private fun startLoop() {
        session.start()
        assertEquals(1, host.launches)
    }

    @Test
    fun startRunsOneLoopAtATime() {
        session.start()
        session.start()
        assertEquals(1, host.launches)
    }

    @Test
    fun aShutDownSessionNeverStartsItsLoop() {
        session.shutdown()
        session.start()
        assertEquals(0, host.launches)
    }

    @Test
    fun withoutALoopASwitchClosesTheOldTunnelAtOnce() {
        val old = FakeTunnel()
        session.switchTo(old)
        session.switchTo(FakeTunnel())
        assertEquals(1, old.closes)
        assertEquals(0, host.wakes) // there's no loop to wake
    }

    @Test
    fun withoutALoopShutdownClosesTheTunnelAtOnce() {
        val tunnel = FakeTunnel()
        session.switchTo(tunnel)
        session.shutdown()
        assertEquals(1, tunnel.closes)
        assertEquals(0, host.wakes)
    }

    @Test
    fun handOverClosesTheOldTunnelAndNeverReadsItAgain() {
        val old = FakeTunnel(tcp(1000))
        val new = FakeTunnel(tcp(1001))
        owners[1000] = CHROME
        owners[1001] = CHROME
        startLoop()
        session.switchTo(old)
        assertTrue(session.step(buffer))
        session.switchTo(new)
        assertEquals(0, old.closes) // the loop closes it at its next turn, not the caller
        assertTrue(session.step(buffer))
        assertTrue(session.step(buffer))
        assertEquals(1, old.closes)
        assertEquals(1, old.reads)
        assertEquals(2, new.reads)
        assertEquals(listOf(CHROME, CHROME), tries)
    }

    @Test
    fun handingOverTheTunnelBeingReadKeepsItOpen() {
        val tunnel = FakeTunnel()
        startLoop()
        session.switchTo(tunnel)
        session.step(buffer)
        session.switchTo(tunnel)
        session.step(buffer)
        assertEquals(0, tunnel.closes)
        assertEquals(2, tunnel.reads)
    }

    @Test
    fun theSameTunnelHandedOverTwiceBeforeItWasTakenIsKeptOpen() {
        val tunnel = FakeTunnel()
        startLoop()
        session.switchTo(tunnel)
        session.switchTo(tunnel)
        session.step(buffer)
        assertEquals(0, tunnel.closes)
        assertEquals(1, tunnel.reads)
    }

    @Test
    fun theLastHandOverBeforeTheLoopsTurnWins() {
        val first = FakeTunnel()
        val last = FakeTunnel()
        startLoop()
        session.switchTo(first)
        session.step(buffer)
        session.switchTo(null) // e.g. every app unblocked, then one blocked again
        session.switchTo(last)
        session.step(buffer)
        assertEquals(1, first.closes)
        assertEquals(0, last.closes)
        assertEquals(1, last.reads)
    }

    @Test
    fun aPendingConnectionKeepsItsStateAcrossTheHandOver() {
        val old = FakeTunnel(tcp(1000))
        val new = FakeTunnel(tcp(1000)) // the same connection retransmits into the new tunnel
        startLoop()
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
    fun anUnnamedTryIsReportedOnceItHasWaitedEvenIfTheTunnelGoesQuiet() {
        val tunnel = FakeTunnel(tcp(1000))
        startLoop()
        session.switchTo(tunnel)
        session.step(buffer) // Android can't name the owner: pending
        now += PENDING_MS - 1
        session.step(buffer) // nothing to read
        assertEquals(emptyList<String?>(), tries) // still waiting for a name
        now += 1
        session.step(buffer)
        assertEquals(listOf<String?>(null), tries) // reported, unnamed rather than guessed
    }

    @Test
    fun anUnnamedTryIsReportedOnceItHasWaitedEvenWithNoTunnel() {
        val tunnel = FakeTunnel(tcp(1000))
        startLoop()
        session.switchTo(tunnel)
        session.step(buffer)
        session.switchTo(null)
        now += PENDING_MS
        session.step(buffer)
        assertEquals(listOf<String?>(null), tries)
    }

    @Test
    fun withNoTunnelTheLoopIdlesUntilWoken() {
        startLoop()
        assertTrue(session.step(buffer))
        assertEquals(listOf(POLL_MS), host.idles)
        session.switchTo(FakeTunnel())
        assertEquals(1, host.wakes) // a hand-over wakes it rather than waiting out the poll
        session.shutdown()
        assertEquals(2, host.wakes) // and so does a shutdown
    }

    @Test
    fun aTunnelBeingReadIsNotAnIdle() {
        startLoop()
        session.switchTo(FakeTunnel())
        session.step(buffer)
        assertEquals(emptyList<Int>(), host.idles) // the read itself waits up to the poll
    }

    @Test
    fun theOldTunnelHangingUpDuringAHandOverIsNotAFailure() {
        // Android hangs up the old tunnel as soon as its replacement is established.
        val old = FakeTunnel(HANG_UP)
        val new = FakeTunnel(tcp(1001))
        startLoop()
        session.switchTo(old)
        old.duringRead = { session.switchTo(new) } // arrives while the loop waits on the old tunnel
        assertTrue(session.step(buffer)) // reads the hang-up: not a failure
        assertEquals(0, old.closes)
        assertTrue(session.step(buffer)) // switches
        assertEquals(emptyList<Throwable?>(), failures)
        assertEquals(1, old.closes)
        assertEquals(1, new.reads)
    }

    @Test
    fun aHangUpWithNoReplacementFailsWithoutClosingTheTunnel() {
        val tunnel = FakeTunnel(HANG_UP)
        startLoop()
        session.switchTo(tunnel)
        host.runLoop()
        assertTrue(failures.single() is TunnelSession.TunnelFailed)
        assertEquals(0, tunnel.closes) // the wall stays up; the service decides what's next
        session.start() // and may run the loop again, on the same tunnel
        assertEquals(2, host.launches)
        assertTrue(session.step(buffer))
        assertEquals(2, tunnel.reads)
    }

    @Test
    fun afterTheLoopFailedASwitchClosesTheOldTunnelAtOnce() {
        val failed = FakeTunnel(HANG_UP)
        startLoop()
        session.switchTo(failed)
        host.runLoop()
        session.switchTo(FakeTunnel()) // e.g. a rule change before the service restarts the loop
        assertEquals(1, failed.closes)
    }

    @Test
    fun anErrorEndsTheLoopWithoutClosingTheTunnel() {
        val error = StackOverflowError()
        val failing = newSession(onTry = { _, _ -> throw error })
        val tunnel = FakeTunnel(tcp(1000))
        owners[1000] = CHROME
        failing.start()
        failing.switchTo(tunnel)
        host.runLoop()
        assertSame(error, failures.single()) // not skipped like an exception: it's reported
        assertEquals(0, tunnel.closes)
    }

    @Test
    fun aHangUpAfterShutdownEndsTheLoopQuietly() {
        val tunnel = FakeTunnel(HANG_UP)
        startLoop()
        session.switchTo(tunnel)
        tunnel.duringRead = { session.shutdown() }
        host.runLoop()
        assertEquals(emptyList<Throwable?>(), failures)
        assertEquals(1, tunnel.closes)
    }

    @Test
    fun shutdownClosesEveryTunnelExactlyOnce() {
        val current = FakeTunnel()
        val waiting = FakeTunnel()
        startLoop()
        session.switchTo(current)
        session.step(buffer)
        session.switchTo(waiting) // handed over, not yet taken
        session.shutdown()
        session.shutdown()
        assertEquals(0, current.closes) // the loop closes them as it ends, not the caller
        assertFalse(session.step(buffer))
        host.runLoop()
        assertEquals(emptyList<Throwable?>(), failures)
        assertEquals(1, current.closes)
        assertEquals(1, waiting.closes)
        val late = FakeTunnel()
        session.switchTo(late) // after shutdown: closed straight away, never read
        session.switchTo(null)
        assertEquals(1, late.closes)
        assertEquals(0, late.reads)
        assertEquals(3, host.wakes) // the two hand-overs and the first shutdown; none after it
    }

    @Test
    fun aSessionThatNeverHadATunnelShutsDownQuietly() {
        startLoop()
        session.shutdown()
        host.runLoop()
        assertEquals(emptyList<Throwable?>(), failures)
    }

    @Test
    fun aTunnelReplacedBeforeItWasTakenIsClosedOnceAndNeverRead() {
        val skipped = FakeTunnel()
        val final = FakeTunnel()
        startLoop()
        session.switchTo(skipped)
        session.switchTo(final)
        assertEquals(1, skipped.closes)
        session.step(buffer)
        assertEquals(0, skipped.reads)
        assertEquals(1, final.reads)
    }

    @Test
    fun aFailingTryIsSkippedAndTheLoopGoesOn() {
        val broken = IllegalStateException("log is broken")
        val failing = newSession(onTry = { _, _ -> throw broken })
        val tunnel = FakeTunnel(tcp(1000), tcp(1001))
        owners[1000] = CHROME
        owners[1001] = CHROME
        failing.start()
        failing.switchTo(tunnel)
        assertTrue(failing.step(buffer))
        assertTrue(failing.step(buffer))
        assertEquals(listOf(1 to broken, 2 to broken), skips) // counted, for occasional logging
        assertEquals(2, tunnel.reads)
        assertEquals(0, tunnel.closes)
        assertEquals(emptyList<Throwable?>(), failures)
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
    fun androidsOwnChatterIsNotCounted() {
        val tunnel = FakeTunnel(tcp(1000, to = "224.0.0.251")) // multicast, e.g. mDNS
        owners[1000] = CHROME
        session.switchTo(tunnel)
        session.step(buffer)
        now += PENDING_MS
        session.step(buffer)
        assertEquals(emptyList<String?>(), tries)
    }

    @Test
    fun nothingIsCountedWhileLoggingIsOff() {
        val quiet = newSession(loggingEnabled = { false })
        owners[1000] = CHROME
        quiet.switchTo(FakeTunnel(tcp(1000)))
        quiet.step(buffer)
        assertEquals(emptyList<String?>(), tries)
    }

    @Test(timeout = 10_000)
    fun onItsOwnThreadTheLoopTakesATunnelAndClosesItOnShutdown() {
        val read = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val tunnel = object : Tunnel {
            override val packets = PacketSource { _, _ -> read.countDown(); 0 }
            override fun close() = closed.countDown()
        }
        // A poll far longer than the test may take: only wake-ups can get it done in time.
        val threaded = TunnelSession(
            owners = { null },
            loggingEnabled = { true },
            onTry = { _, _ -> },
            onFailure = { },
            onSkipped = { _, _ -> },
            clock = { 0 },
            host = LoopThread("appwall-tunnel-test"),
            pollMs = 60_000,
        )
        threaded.start() // idles: no tunnel yet
        threaded.switchTo(tunnel)
        read.await()
        threaded.shutdown()
        closed.await()
    }

    /** A minimal IPv4 TCP packet from [sourcePort] to port 443 of [to]. */
    private fun tcp(sourcePort: Int, to: String = "1.2.3.4"): ByteArray {
        val packet = ByteArray(28)
        packet[0] = 0x45
        packet[9] = 6
        InetAddress.getByName("10.111.0.1").address.copyInto(packet, 12)
        InetAddress.getByName(to).address.copyInto(packet, 16)
        packet[20] = (sourcePort shr 8).toByte()
        packet[21] = sourcePort.toByte()
        packet[22] = (443 shr 8).toByte()
        packet[23] = 443.toByte()
        return packet
    }

    private companion object {
        const val CHROME = "com.android.chrome"
        const val OTHER = "org.example.other"
        const val POLL_MS = 1
        /** More turns than any test takes: a loop still going is a loop that should have ended. */
        const val RUNAWAY = 100
        val HANG_UP = ByteArray(0)
    }
}
