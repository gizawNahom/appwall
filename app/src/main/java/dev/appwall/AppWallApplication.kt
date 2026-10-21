package dev.appwall

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.appwall.core.log.TryQueue
import dev.appwall.core.state.ChangeSignal
import dev.appwall.core.state.FirewallStatusBoard
import dev.appwall.core.state.Scheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Builds AppWall's shared objects once per process and hands them out: screens, the service and
 * receivers ask for them here instead of reaching for globals. Kept light, since it runs on every
 * process start, boot included: nothing here touches the disk until first used.
 */
class AppWallApplication : Application() {

    /**
     * The one thread for all blocklog.db work: the tunnel's tries in batches, and screens' queries,
     * prune and clear. Neither the UI thread nor the tunnel ever waits on the disk.
     */
    lateinit var db: ScheduledExecutorService
        private set

    @Volatile
    private var dbThread: Thread? = null

    /** The blocked traffic log, for the database thread only. Opening the database waits for the first use. */
    lateinit var log: SqlBlockLog
        private set

    /** Where the tunnel leaves blocked tries for the log; the database thread writes them within a second. */
    lateinit var tries: TryQueue
        private set

    /** Installed apps, loaded off the UI thread and remembered between screens. */
    lateinit var apps: InstalledAppsRepository
        private set

    /** Whether the firewall is running, as published by the service. Screens observe its signal. */
    lateinit var firewallStatus: FirewallStatusBoard
        private set

    override fun onCreate() {
        super.onCreate()
        db = Executors.newSingleThreadScheduledExecutor { Thread(it, "appwall-db").also { thread -> dbThread = thread } }
        val rules = Rules(this)
        log = SqlBlockLog(
            AndroidSqlDb(this, SqlBlockLog.FILE, SqlBlockLog.VERSION, SqlBlockLog::create),
            retentionDays = { rules.logRetentionDays },
            onDbThread = { Thread.currentThread() === dbThread },
        )
        tries = TryQueue { db.schedule(::writeTries, 1, TimeUnit.SECONDS) }
        apps = InstalledAppsRepository(this, namedExecutor("appwall-apps"))
        firewallStatus = FirewallStatusBoard(ChangeSignal(MainThreadScheduler))
    }

    /** On the database thread: writes the queued tries as one batch. */
    private fun writeTries() {
        try {
            log.record(tries.drain())
        } catch (e: Exception) {
            // Only the log loses them; the apps stayed blocked.
            Log.w("AppWall", "Writing blocked tries failed", e)
        }
        firewallStatus.countDropped(tries.droppedTries)
    }

    /**
     * True when some other app's VPN is up. Android allows one VPN at a time, so AppWall can't
     * run then. A VPN seen while AppWall's own firewall isn't running can't be AppWall's.
     */
    fun anotherVpnActive(): Boolean {
        if (firewallStatus.status.running) return false
        val connectivity = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks: still the simplest way to list current networks
        return connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun namedExecutor(name: String): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name) }
}

/**
 * Runs [work] with the block log on the database thread, then hands its result to [show] on the
 * UI thread, unless this screen has gone by then. A failing query is logged and skipped.
 */
fun <T> Activity.onDb(work: (SqlBlockLog) -> T, show: (T) -> Unit) {
    val app = appWall
    app.db.execute {
        val result = try {
            work(app.log)
        } catch (e: Exception) {
            Log.w("AppWall", "Block log query failed", e)
            return@execute
        }
        runOnUiThread { if (!isDestroyed) show(result) }
    }
}

/** Delivers on Android's main (UI) thread. */
object MainThreadScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(task: () -> Unit) {
        handler.post(task)
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMs)
    }
}

/** The process's [AppWallApplication], from any context. */
val Context.appWall: AppWallApplication
    get() = applicationContext as AppWallApplication
