package dev.appwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import dev.appwall.core.firewall.DNS_SERVER
import dev.appwall.core.firewall.TunnelPlan
import dev.appwall.core.firewall.tunnelPlan

/**
 * Blocks apps by routing only the blocked ones into a local tunnel that is read only for logging
 * and never forwarded. Their packets go nowhere, so they get no network. All other apps stay on the
 * normal network, untouched.
 */
class FirewallVpnService : VpnService() {

    /** Reads (and owns) the current tunnel; null while no tunnel is up. */
    private var reader: TunnelReader? = null

    /** When readers were restarted after dying unexpectedly (elapsed ms), for the per-minute cap. */
    private val readerRestarts = ArrayDeque<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Watches for newly installed apps. Android no longer delivers install events to apps that
     * aren't running, so this only works while the firewall service is alive.
     */
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // an update, not a new app
            val packageName = intent.data?.schemeSpecificPart ?: return
            if (NewAppNotifier.onInstalled(context, packageName)) applyRules()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                closeTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_BLOCK_NEW_APP, ACTION_ALLOW_NEW_APP -> {
                handleNewAppChoice(intent)
                return START_STICKY
            }
        }
        // ACTION_APPLY from the UI, or a start by the system (e.g. Always-on VPN).
        Rules(this).enabled = true
        applyRules()
        return START_STICKY
    }

    /** (Re)builds the tunnel from the current rules. Safe to call while already running. */
    private fun applyRules() {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("10.111.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:a11:a11::1", 128)
            .addRoute("::", 0)
            // A DNS server inside the tunnel: blocked apps' lookups land in it, which lets the
            // log show the domain names they tried to reach. Nothing ever answers.
            .addDnsServer(DNS_SERVER)

        val plan = tunnelPlan(Rules(this).blocked(), ::isInstalled)
        var routed = 0
        if (plan is TunnelPlan.Route) {
            for (packageName in plan.packages) {
                try {
                    builder.addAllowedApplication(packageName)
                    routed++
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Blocked app uninstalled just now: $packageName")
                }
            }
        }

        // See TunnelPlan.NoTunnel: an empty allow-list would route EVERY app into the tunnel.
        // (Also checked after building, in case a planned app was uninstalled meanwhile.)
        if (routed == 0) {
            closeTunnel()
            showStatus(0)
            return
        }

        val newTunnel = builder.establish()
        if (newTunnel == null) {
            // VPN permission was revoked or never granted. If we were started at boot with
            // startForegroundService(), Android insists we go foreground first, even to quit.
            Rules(this).enabled = false
            closeTunnel()
            showStatus(0)
            stopSelf()
            return
        }
        reader?.shutdown()
        startReader(newTunnel)
        showStatus(routed)
    }

    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "Blocked app no longer installed: $packageName")
        false
    }

    private fun startReader(tunnel: ParcelFileDescriptor) {
        reader = TunnelReader(this, tunnel) { dead -> mainHandler.post { onReaderDied(dead) } }.also { it.start() }
    }

    /**
     * The tunnel's reader stopped on its own. The tunnel itself is still up, so blocked apps are
     * still blocked; a fresh reader takes it over. If readers keep dying, turn the wall off
     * deliberately and say so, rather than look on while nothing reads the tunnel.
     */
    private fun onReaderDied(dead: TunnelReader) {
        if (reader !== dead) return // already replaced or stopped
        val now = SystemClock.elapsedRealtime()
        while (readerRestarts.isNotEmpty() && now - readerRestarts.first() >= RESTART_WINDOW_MS) readerRestarts.removeFirst()
        if (readerRestarts.size < MAX_RESTARTS) {
            readerRestarts.addLast(now)
            Log.w(TAG, "Tunnel reader stopped; restarting it (${readerRestarts.size}/$MAX_RESTARTS this minute)")
            startReader(dead.tunnel)
            return
        }
        Log.e(TAG, "Tunnel reader keeps stopping; turning the firewall off")
        Rules(this).enabled = false
        closeTunnel()
        showStopped()
        stopSelf()
    }

    /** Tells the user the firewall turned itself off after an error, so they know apps are unblocked. */
    private fun showStopped() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_STOPPED_ID,
            Notification.Builder(this, CHANNEL_PROBLEMS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.stopped_title))
                .setContentText(getString(R.string.stopped_text))
                .setStyle(Notification.BigTextStyle().bigText(getString(R.string.stopped_text)))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Shows an ongoing "AppWall is on" notification. Samsung's status bar doesn't always draw the
     * VPN key, so this is how the user knows the firewall is active. Running in the foreground
     * also makes Android far less likely to kill the service.
     */
    private fun showStatus(blockedCount: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (blockedCount == 0) {
            getString(R.string.status_on_none)
        } else {
            resources.getQuantityString(R.plurals.status_on, blockedCount, blockedCount)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            // Android can refuse a foreground start when we were launched from the background
            // (e.g. Always-on VPN at boot). Still show the status, just as a plain notification.
            Log.w(TAG, "Could not enter foreground", e)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun hideStatus() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** The user tapped Block, Allow or Unblock on a new-app notification. */
    private fun handleNewAppChoice(intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        NewAppNotifier.cancel(this, packageName)
        val rules = Rules(this)
        // Allow also unblocks: it's the Unblock button when new apps are blocked automatically.
        rules.setBlocked(packageName, intent.action == ACTION_BLOCK_NEW_APP)
        // The button may be tapped after the firewall was turned off; then just save the rule.
        if (rules.enabled) applyRules() else stopSelf()
    }

    override fun onRevoke() {
        // The user turned us off from system settings, or another VPN app took over.
        Rules(this).enabled = false
        closeTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        running = null
        unregisterReceiver(installReceiver)
        closeTunnel()
        hideStatus()
        super.onDestroy()
    }

    private fun closeTunnel() {
        reader?.shutdown()
        reader = null
    }

    /** Live Always-on state, as only the running VPN service can ask Android for it. */
    data class AlwaysOnStatus(val alwaysOn: Boolean, val blocksWithoutVpn: Boolean)

    companion object {
        @Volatile private var running: FirewallVpnService? = null

        /**
         * True when some other app's VPN is up. Android allows one VPN at a time, so AppWall
         * can't run then. Any VPN seen while our own service isn't running can't be ours.
         */
        fun anotherVpnActive(context: Context): Boolean {
            if (running != null) return false
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION") // allNetworks: still the simplest way to list current networks
            return connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }

        /** Null when it can't be known: the firewall isn't running, or Android is older than 10. */
        fun alwaysOnStatus(): AlwaysOnStatus? {
            val service = running ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return AlwaysOnStatus(service.isAlwaysOn, service.isLockdownEnabled)
        }

        const val ACTION_APPLY = "dev.appwall.APPLY"
        const val ACTION_STOP = "dev.appwall.STOP"
        const val ACTION_BLOCK_NEW_APP = "dev.appwall.BLOCK_NEW_APP"
        const val ACTION_ALLOW_NEW_APP = "dev.appwall.ALLOW_NEW_APP"
        private const val TAG = "AppWall"
        private const val CHANNEL_STATUS = "status"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_PROBLEMS = "problems"
        private const val NOTIFICATION_STOPPED_ID = 3 // 2 is NewAppNotifier's (tagged per app)
        /** At most this many reader restarts per [RESTART_WINDOW_MS] before giving up. */
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 60_000L
    }
}
