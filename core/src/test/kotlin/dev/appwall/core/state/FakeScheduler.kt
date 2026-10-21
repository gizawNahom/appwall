package dev.appwall.core.state

/** Runs posted tasks only when the test advances time, like a main thread under the test's control. */
class FakeScheduler : Scheduler {
    override var now = 0L
        private set
    private val tasks = ArrayList<Pair<Long, Runnable>>()

    override fun post(task: Runnable) = postDelayed(0, task)

    override fun postDelayed(delayMs: Long, task: Runnable) {
        tasks += (now + delayMs) to task
    }

    val pending: Int get() = tasks.size

    /** Moves the clock forward by [ms], running every task that comes due, in order. */
    fun advance(ms: Long = 0) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            now = maxOf(now, next.first)
            next.second.run()
        }
        now = until
    }
}
