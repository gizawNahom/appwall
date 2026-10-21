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
import android.os.SystemClock
import android.util.Log
import dev.appwall.core.firewall.DNS_SERVER
import dev.appwall.core.firewall.TunnelPlan
import dev.appwall.core.firewall.tunnelPlan
import dev.appwall.core.state.AlwaysOn
import dev.appwall.core.state.FirewallState
import dev.appwall.core.state.FirewallStatus
import dev.appwall.core.state.OffReason
import dev.appwall.core.tunnel.TunnelSession

/**
 * Blocks apps by routing only the blocked ones into a local tunnel that is read only for logging
 * and never forwarded. Their packets go nowhere, so they get no network. All other apps stay on the
 * normal network, untouched.
 */
class FirewallVpnService : VpnService() {

    /**
     * Reads the tunnel for as long as this service runs, through every rule change, and is the
     * only thing that closes a tunnel once handed one.
     */
    private lateinit var session: TunnelSession

    /** Where this service says whether the firewall runs. Nothing else publishes to it. */
    private val status get() = appWall.firewallStatus

    /** When the session was restarted after stopping unexpectedly (elapsed ms), for the per-minute cap. */
    private val sessionRestarts = ArrayDeque<Long>()
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
        val tries = appWall.tries
        val settings = appWall.settings
        session = TunnelSession(
            owners = ConnectivityOwners(this),
            loggingEnabled = { settings.logSettings.loggingEnabled },
            onTry = tries::offer,
            onFailure = { error ->
                Log.e(TAG, "Tunnel session stopped", error)
                mainHandler.post(::onSessionFailed)
            },
            // Logged now and then, not per packet: a broken tunnel could fail on every one.
            onSkipped = { count, error -> if (count % 100 == 1) Log.w(TAG, "Skipped a packet ($count so far)", error) },
        )
        session.start()
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
                status.publish(FirewallStatus(FirewallState.Off(OffReason.TURNED_OFF)))
                closeTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_STATUS -> {
                // Always-on is set in Android's settings, which don't tell us; a screen asks.
                if (status.status.running) status.publish(status.status.copy(alwaysOn = alwaysOnNow())) else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_BLOCK_NEW_APP, ACTION_ALLOW_NEW_APP -> {
                handleNewAppChoice(intent)
                return START_STICKY
            }
        }
        // ACTION_APPLY from the UI, or a start by the system (e.g. Always-on VPN).
        appWall.settings.setEnabled(true)
        if (!status.status.running) status.publish(FirewallStatus(FirewallState.Starting))
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

        val blocked = appWall.settings.policy.blocked
        session.blocked = blocked
        val plan = tunnelPlan(blocked, ::isInstalled)
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
            publishOn(0)
            return
        }

        val newTunnel = builder.establish()
        if (newTunnel == null) {
            // VPN permission was revoked or never granted. If we were started at boot with
            // startForegroundService(), Android insists we go foreground first, even to quit.
            appWall.settings.setEnabled(false)
            status.publish(FirewallStatus(FirewallState.Off(OffReason.NO_PERMISSION)))
            closeTunnel()
            showStatus(0)
            stopSelf()
            return
        }
        // The new tunnel is already up, so blocked apps are never left without one.
        session.switchTo(VpnTunnel(newTunnel))
        showStatus(routed)
        publishOn(routed)
    }

    private fun publishOn(routedApps: Int) {
        status.publish(FirewallStatus(FirewallState.On(routedApps), alwaysOnNow()))
    }

    /** Android's Always-on setting for AppWall; only the running VPN service may ask. */
    private fun alwaysOnNow(): AlwaysOn = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> AlwaysOn.UNKNOWN
        isLockdownEnabled -> AlwaysOn.LOCKDOWN
        isAlwaysOn -> AlwaysOn.ON
        else -> AlwaysOn.OFF
    }

    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "Blocked app no longer installed: $packageName")
        false
    }

    /**
     * The session's loop stopped on its own. The tunnel is still up, so blocked apps are still
     * blocked; the loop restarts on it. If it keeps stopping, turn the wall off deliberately and
     * say so, rather than look on while nothing reads the tunnel.
     */
    private fun onSessionFailed() {
        val now = SystemClock.elapsedRealtime()
        while (sessionRestarts.isNotEmpty() && now - sessionRestarts.first() >= RESTART_WINDOW_MS) sessionRestarts.removeFirst()
        if (sessionRestarts.size < MAX_RESTARTS) {
            sessionRestarts.addLast(now)
            Log.w(TAG, "Restarting the tunnel session (${sessionRestarts.size}/$MAX_RESTARTS this minute)")
            session.start()
            return
        }
        Log.e(TAG, "Tunnel session keeps stopping; turning the firewall off")
        appWall.settings.setEnabled(false)
        status.publish(FirewallStatus(FirewallState.Off(OffReason.TUNNEL_FAILED)))
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
        val settings = appWall.settings
        // Allow also unblocks: it's the Unblock button when new apps are blocked automatically.
        settings.setBlocked(listOf(packageName), intent.action == ACTION_BLOCK_NEW_APP)
        // The button may be tapped after the firewall was turned off; then just save the rule.
        if (settings.policy.enabled) applyRules() else stopSelf()
    }

    override fun onRevoke() {
        // The user turned us off from system settings, or another VPN app took over.
        appWall.settings.setEnabled(false)
        status.publish(FirewallStatus(FirewallState.Off(OffReason.REVOKED)))
        closeTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        status.stopped(OffReason.TURNED_OFF)
        unregisterReceiver(installReceiver)
        session.shutdown() // closes the tunnel and ends the session's thread
        hideStatus()
        super.onDestroy()
    }

    /** No tunnel: the session closes the current one at its next turn and waits for another. */
    private fun closeTunnel() {
        session.switchTo(null)
    }

    companion object {
        const val ACTION_APPLY = "dev.appwall.APPLY"
        const val ACTION_STOP = "dev.appwall.STOP"
        /** Re-check Always-on and publish it, if running; sent by screens showing it. */
        const val ACTION_REFRESH_STATUS = "dev.appwall.REFRESH_STATUS"
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
