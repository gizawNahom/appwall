package dev.appwall.core.state

import dev.appwall.core.state.FirewallState.Off
import dev.appwall.core.state.FirewallState.On
import dev.appwall.core.state.FirewallState.Starting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallStatusBoardTest {

    private val scheduler = FakeScheduler()
    private val board = FirewallStatusBoard(ChangeSignal(scheduler, scheduler::now))
    private var heard = 0

    init {
        board.changed.subscribe { heard++ }
    }

    @Test
    fun startsOffBecauseNothingHasSaidItsRunning() {
        assertEquals(Off(OffReason.NOT_RUNNING), board.status.firewall)
        assertFalse(board.status.running)
        assertEquals(AlwaysOn.UNKNOWN, board.status.alwaysOn)
    }

    @Test
    fun startingAndOnCountAsRunning() {
        board.publish(FirewallStatus(Starting))
        assertTrue(board.status.running)
        board.publish(FirewallStatus(On(3), AlwaysOn.ON))
        assertTrue(board.status.running)
        board.publish(FirewallStatus(On(0)))
        assertTrue(board.status.running) // on, nothing to block
    }

    @Test
    fun publishingTheSameStatusAgainIsQuiet() {
        board.publish(FirewallStatus(On(2)))
        scheduler.advance()
        board.publish(FirewallStatus(On(2)))
        scheduler.advance()
        assertEquals(1, heard)
    }

    @Test
    fun stoppingKeepsAReasonGivenEarlier() {
        // e.g. the session gave up, then the service was destroyed
        board.publish(FirewallStatus(On(2), AlwaysOn.ON))
        board.publish(FirewallStatus(Off(OffReason.TUNNEL_FAILED)))
        board.stopped(OffReason.TURNED_OFF)
        assertEquals(Off(OffReason.TUNNEL_FAILED), board.status.firewall)
    }

    @Test
    fun stoppingWhileOnSaysTheGivenReasonAndForgetsAlwaysOn() {
        board.publish(FirewallStatus(On(2), AlwaysOn.LOCKDOWN))
        board.stopped(OffReason.TURNED_OFF)
        assertEquals(FirewallStatus(Off(OffReason.TURNED_OFF), AlwaysOn.UNKNOWN), board.status)
    }

    @Test
    fun aRevokedFirewallReportsItAfterTheServiceIsGone() {
        board.publish(FirewallStatus(On(1)))
        board.publish(FirewallStatus(Off(OffReason.REVOKED)))
        board.stopped(OffReason.TURNED_OFF)
        scheduler.advance()
        assertEquals(Off(OffReason.REVOKED), board.status.firewall)
        assertEquals(1, heard) // coalesced into one delivery
    }

    @Test
    fun droppedTriesSurviveTheServicesUpdates() {
        board.countDropped(5)
        board.setLogHealth(LogHealth.Ok)
        board.publish(FirewallStatus(On(2)))
        assertEquals(5L, board.status.droppedTries)
        assertEquals(LogHealth.Ok, board.status.logHealth)
        board.stopped(OffReason.TURNED_OFF)
        assertEquals(5L, board.status.droppedTries)
        scheduler.advance()
        assertEquals(1, heard)
        board.countDropped(5)
        scheduler.advance()
        assertEquals(1, heard) // an unchanged count is quiet
    }
}
