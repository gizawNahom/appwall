package dev.appwall

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import dev.appwall.core.state.ChangeSignal
import dev.appwall.core.state.FirewallStatusBoard
import dev.appwall.core.state.Scheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Builds AppWall's shared objects once per process and hands them out: screens, the service and
 * receivers ask for them here instead of reaching for globals. Kept light, since it runs on every
 * process start, boot included: nothing here touches the disk until first used.
 */
class AppWallApplication : Application() {

    /** The blocked traffic log. Opening the database waits for the first query. */
    lateinit var log: SqlBlockLog
        private set

    /** Installed apps, loaded off the UI thread and remembered between screens. */
    lateinit var apps: InstalledAppsRepository
        private set

    /** Whether the firewall is running, as published by the service. Screens observe its signal. */
    lateinit var firewallStatus: FirewallStatusBoard
        private set

    override fun onCreate() {
        super.onCreate()
        val rules = Rules(this)
        log = SqlBlockLog(AndroidSqlDb(this, SqlBlockLog.FILE, SqlBlockLog.VERSION, SqlBlockLog::create), { rules.logRetentionDays })
        apps = InstalledAppsRepository(this, namedExecutor("appwall-apps"))
        firewallStatus = FirewallStatusBoard(ChangeSignal(MainThreadScheduler))
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
