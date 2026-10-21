package dev.appwall.core.state

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLoaderTest {

    private val ui = FakeScheduler()
    private val worker = FakeWorker()
    private val signal = ChangeSignal(ui)
    private var input = "a"
    private var loads = 0
    private val shown = ArrayList<String>()
    private val errors = ArrayList<Exception>()
    private val loader = ScreenLoader(worker, ui, { errors += it }, {
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
