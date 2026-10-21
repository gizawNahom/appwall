package dev.appwall.core.state

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeSignalTest {

    private val scheduler = FakeScheduler()
    private var heard = 0

    @Test
    fun listenersHearAChangeOnTheSchedulersThread() {
        val signal = ChangeSignal(scheduler)
        signal.subscribe { heard++ }
        signal.notifyChanged()
        assertEquals(0, heard) // not on the caller's thread
        scheduler.advance()
        assertEquals(1, heard)
    }

    @Test
    fun changesBeforeADeliveryAreCoalesced() {
        val signal = ChangeSignal(scheduler)
        signal.subscribe { heard++ }
        repeat(10) { signal.notifyChanged() } // e.g. ten apps blocked at once
        scheduler.advance()
        assertEquals(1, heard)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun noDeliveryAfterUnsubscribing() {
        val signal = ChangeSignal(scheduler)
        val subscription = signal.subscribe { heard++ }
        signal.notifyChanged()
        subscription.unsubscribe() // e.g. the screen stopped before the delivery ran
        scheduler.advance()
        assertEquals(0, heard)
    }

    @Test
    fun unsubscribingOneListenerLeavesTheOthers() {
        val signal = ChangeSignal(scheduler)
        var other = 0
        val subscription = signal.subscribe { heard++ }
        signal.subscribe { other++ }
        subscription.unsubscribe()
        signal.notifyChanged()
        scheduler.advance()
        assertEquals(0, heard)
        assertEquals(1, other)
    }

    @Test
    fun everyListenerHearsIt() {
        val signal = ChangeSignal(scheduler)
        var other = 0
        signal.subscribe { heard++ }
        signal.subscribe { other++ }
        signal.notifyChanged()
        scheduler.advance()
        assertEquals(1, heard)
        assertEquals(1, other)
    }

    @Test
    fun throttledDeliveriesAreSpacedOut() {
        val signal = ChangeSignal(scheduler, throttleMs = 1_000)
        signal.subscribe { heard++ }
        signal.notifyChanged()
        scheduler.advance()
        assertEquals(1, heard) // the first change goes straight out
        signal.notifyChanged()
        scheduler.advance(500)
        assertEquals(1, heard) // too soon
        scheduler.advance(500)
        assertEquals(2, heard)
    }

    @Test
    fun aThrottledChangeLongAfterTheLastDeliveryGoesStraightOut() {
        val signal = ChangeSignal(scheduler, throttleMs = 1_000)
        signal.subscribe { heard++ }
        signal.notifyChanged()
        scheduler.advance(5_000)
        signal.notifyChanged()
        scheduler.advance()
        assertEquals(2, heard)
    }

    @Test
    fun aThrottledChangeExactlyOneIntervalLaterGoesStraightOut() {
        val signal = ChangeSignal(scheduler, throttleMs = 1_000)
        signal.subscribe { heard++ }
        signal.notifyChanged()
        scheduler.advance(1_000)
        signal.notifyChanged()
        scheduler.advance()
        assertEquals(2, heard)
    }

    @Test
    fun theLastChangeOfABurstIsAlwaysDelivered() {
        val signal = ChangeSignal(scheduler, throttleMs = 1_000)
        signal.subscribe { heard++ }
        // A stream of tries: a change every 100 ms for 3 seconds, then quiet.
        repeat(30) {
            signal.notifyChanged()
            scheduler.advance(100)
        }
        scheduler.advance(2_000)
        assertEquals(4, heard) // at 0, 1000, 2000 and a trailing one at 3000, not 30
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun aChangeDuringADeliveryIsDeliveredToo() {
        val signal = ChangeSignal(scheduler)
        signal.subscribe {
            heard++
            if (heard == 1) signal.notifyChanged()
        }
        signal.notifyChanged()
        scheduler.advance()
        assertEquals(2, heard)
    }
}
