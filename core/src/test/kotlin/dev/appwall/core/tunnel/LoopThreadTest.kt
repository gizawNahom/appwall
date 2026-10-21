package dev.appwall.core.tunnel

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real thread under [TunnelSession]; each test ends at once unless the thread misbehaves. */
class LoopThreadTest {

    private val thread = LoopThread("appwall-test")

    @Test(timeout = 10_000)
    fun runsTheLoopOnANamedThreadOfItsOwn() {
        val ran = CountDownLatch(1)
        var name: String? = null
        thread.launch {
            name = Thread.currentThread().name
            ran.countDown()
        }
        ran.await()
        assertEquals("appwall-test", name)
    }

    @Test(timeout = 10_000)
    fun aWakeFromAnotherThreadEndsTheIdle() {
        val idled = CountDownLatch(1)
        thread.launch {
            thread.idle(60_000)
            idled.countDown()
        }
        thread.wake()
        idled.await()
    }

    @Test(timeout = 10_000)
    fun aWakeBeforeTheIdleIsNotLost() {
        thread.wake()
        thread.idle(60_000)
    }

    @Test(timeout = 10_000)
    fun wakesDontPileUp() {
        thread.wake()
        thread.wake()
        thread.idle(60_000)
        val start = System.nanoTime()
        thread.idle(50)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 50) // waited: the second wake was one too many
    }

    @Test(timeout = 10_000)
    fun anIdleWithNoWakeEndsAfterItsTimeout() {
        thread.idle(1)
    }
}
