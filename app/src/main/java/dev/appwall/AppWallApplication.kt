package dev.appwall

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.appwall.apps.InstalledAppsRepository
import dev.appwall.core.log.TryQueue
import dev.appwall.core.firewall.FirewallCommands
import dev.appwall.core.health.StartupChecks
import dev.appwall.core.health.StartupDecision
import dev.appwall.core.state.LogHealth
import dev.appwall.core.settings.FirewallPolicyWriter
import dev.appwall.core.settings.ScreenSettings
import dev.appwall.core.settings.StoredSettings
import dev.appwall.core.state.ChangeSignal
import dev.appwall.core.state.FirewallStatusBoard
import dev.appwall.core.state.Scheduler
import dev.appwall.core.state.ScreenLoader
import dev.appwall.firewall.FirewallVpnService
import dev.appwall.storage.AndroidSqlDb
import dev.appwall.storage.PrefsStore
import dev.appwall.storage.SqlBlockLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Builds AppWall's shared objects once per process and hands them out: screens, the service and
 * receivers ask for them here instead of reaching for globals. Kept light, since it runs on every
 * process start, boot included: nothing here touches the disk until first used.
 *
 * The rest of :app sits by role (firewall, storage, apps) and by screen (home, log, settings),
 * with view pieces several screens share in ui.
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

    /** Fires after each change to the log is committed; screens showing the log reload on it. */
    lateinit var logChanged: ChangeSignal
        private set

    /** Where the tunnel leaves blocked tries for the log; the database thread writes them within a second. */
    lateinit var tries: TryQueue
        private set

    private lateinit var storedSettings: StoredSettings

    /**
     * The firewall policy, log settings and screen preferences, as snapshots kept current by their
     * writers. Changing the policy goes through [firewall] instead.
     */
    val settings: ScreenSettings get() = storedSettings

    /** Writes the firewall policy directly. Only the service and [firewall] use it. */
    val policyWriter: FirewallPolicyWriter get() = storedSettings

    /** Blocking, allowing, on and off: saved, then applied by the running service. */
    lateinit var firewall: FirewallCommands
        private set

    /** The start-up checks' verdict: whether the firewall may start, and whether the log is usable. */
    lateinit var startup: StartupChecks
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
        storedSettings = StoredSettings(PrefsStore(this)) { ChangeSignal(MainThreadScheduler) }
        logChanged = ChangeSignal(MainThreadScheduler)
        log = SqlBlockLog(
            AndroidSqlDb(this, SqlBlockLog.FILE, SqlBlockLog.VERSION, SqlBlockLog::create, SqlBlockLog::upgrade, SqlBlockLog::reset),
            retentionDays = { settings.logSettings.retentionDays },
            onDbThread = { Thread.currentThread() === dbThread },
            changed = logChanged::notifyChanged,
        )
        tries = TryQueue { db.schedule(::writeTries, 1, TimeUnit.SECONDS) }
        apps = InstalledAppsRepository(this, namedExecutor("appwall-apps"))
        firewallStatus = FirewallStatusBoard(ChangeSignal(MainThreadScheduler))
        firewall = FirewallCommands(
            storedSettings, storedSettings,
            isRunning = { firewallStatus.status.running },
            startService = { sendToFirewall(FirewallVpnService.ACTION_APPLY) },
            stopService = { sendToFirewall(FirewallVpnService.ACTION_STOP) },
        )

        // Wired; now probe before use. Settings are in memory already; the log is checked as the
        // database thread's first job, so it runs before any write or query.
        startup = StartupChecks(storedSettings, log)
        reportRefusals(startup.decision)
        db.execute {
            val decision = startup.checkLog()
            firewallStatus.setLogHealth(decision.logHealth)
            reportRefusals(decision.copy(refused = decision.refused - "settings_unreadable")) // said already
        }
    }

    private fun reportRefusals(decision: StartupDecision) {
        for (code in decision.refused) Log.w("AppWall", "health.startup.refused reason=$code")
    }

    private fun sendToFirewall(action: String) {
        startService(Intent(this, FirewallVpnService::class.java).setAction(action))
    }

    /** On the database thread: writes the queued tries as one batch. */
    private fun writeTries() {
        val batch = tries.drain()
        // An unusable log (see the start-up checks) drops tries; the apps stay blocked regardless.
        if (startup.decision.logHealth is LogHealth.Unavailable) return
        try {
            log.record(batch)
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

/**
 * A [ScreenLoader] that runs its queries with the block log on the database thread. [query] runs
 * on the UI thread when a reload is asked for, and returns the work to do with the log.
 */
fun <T> Activity.logLoader(query: () -> (SqlBlockLog) -> T, show: (T) -> Unit): ScreenLoader<T> {
    val app = appWall
    return ScreenLoader(
        worker = app.db,
        ui = MainThreadScheduler,
        onError = { Log.w("AppWall", "Block log query failed", it) },
        prepare = { query().let { work -> { work(app.log) } } },
        show = { if (!isDestroyed) show(it) },
    )
}

/** Delivers on Android's main (UI) thread. */
private object MainThreadScheduler : Scheduler {
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
