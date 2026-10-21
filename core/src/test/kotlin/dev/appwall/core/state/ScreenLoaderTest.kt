package dev.appwall.core.state

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLoaderTest {

    private val ui = FakeScheduler()
    private val worker = FakeWorker()
    private val signal = ChangeSignal(ui)
    private var input = "a"
    private var prepared = 0
    private var loads = 0
    private val shown = ArrayList<String>()
    private val errors = ArrayList<Exception>()
    private val loader = ScreenLoader(worker, ui, { errors += it }, {
        prepared++
        val captured = input
        { loads++; if (captured == "boom") error("failed"); captured }
    }, { shown += it })

    @Test
    fun loadsOnStartAndOnEachSignal() {
        loader.start(signal)
        worker.runAll(); ui.advance()
        signal.notifyChanged()
        ui.advance(); worker.runAll(); ui.advance()
        assertEquals(listOf("a", "a"), shown)
    }

    @Test
    fun nothingIsLoadedBeforeStart() {
        loader.reload()
        assertEquals(0, prepared)
        assertEquals(0, worker.pending)
    }

    @Test
    fun startingAgainWhileStartedChangesNothing() {
        loader.start(signal)
        loader.start(signal)
        assertEquals(1, prepared)
        worker.runAll(); ui.advance()
        signal.notifyChanged()
        ui.advance()
        assertEquals(2, prepared) // subscribed once, so one reload per signal
    }

    @Test
    fun reloadsWhileOneIsQueuedCollapseIntoTheNewest() {
        loader.start(signal)
        input = "b"
        loader.reload()
        input = "c"
        loader.reload()
        assertEquals(1, worker.pending)
        worker.runAll(); ui.advance()
        assertEquals(1, loads)
        assertEquals(listOf("c"), shown)
    }

    @Test
    fun nothingIsShownOrLoadedAfterStop() {
        loader.start(signal)
        worker.runAll()
        loader.stop()
        ui.advance()
        signal.notifyChanged()
        ui.advance(); worker.runAll(); ui.advance()
        assertEquals(emptyList<String>(), shown)
        assertEquals(1, loads)
    }

    @Test
    fun aLoadStillQueuedAtStopIsDropped() {
        loader.start(signal)
        loader.stop()
        worker.runAll(); ui.advance()
        assertEquals(0, loads)
    }

    @Test
    fun aScreenRestartedBeforeItsLoadRanLoadsOnceWithWhatItShowsNow() {
        loader.start(signal)
        loader.stop()
        input = "b"
        loader.start(signal)
        worker.runAll(); ui.advance()
        assertEquals(1, loads)
        assertEquals(listOf("b"), shown)
    }

    @Test
    fun aRestartedScreenReloadsOncePerSignal() {
        loader.start(signal)
        loader.stop()
        loader.start(signal)
        worker.runAll(); ui.advance()
        signal.notifyChanged()
        ui.advance()
        assertEquals(3, prepared) // two starts and one signal: the first subscription is gone
    }

    @Test
    fun aFailedLoadIsReportedNotShown() {
        input = "boom"
        loader.start(signal)
        worker.runAll(); ui.advance()
        assertEquals(1, errors.size)
        assertEquals(emptyList<String>(), shown)
        input = "a"
        loader.reload()
        worker.runAll(); ui.advance()
        assertEquals(listOf("a"), shown) // and it keeps working
    }

    private class FakeWorker : Executor {
        private val tasks = ArrayList<Runnable>()
        val pending get() = tasks.size
        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }
}
