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
import android.net.VpnService
import android.os.Build
import android.util.Log

/**
 * Blocks apps by routing only the blocked ones into a local tunnel that is read only for logging
 * and never forwarded. Their packets go nowhere, so they get no network. All other apps stay on the
 * normal network, untouched.
 */
class FirewallVpnService : VpnService() {

    /** Reads (and owns) the current tunnel; null while no tunnel is up. */
    private var reader: TunnelReader? = null

    /**
     * Watches for newly installed apps. Android no longer delivers install events to apps that
     * aren't running, so this only works while the firewall service is alive.
     */
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // an update, not a new app
            val packageName = intent.data?.schemeSpecificPart ?: return
            NewAppNotifier.onInstalled(context, packageName)
        }
    }

    override fun onCreate() {
        super.onCreate()
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

        var routed = 0
        for (packageName in Rules(this).blocked()) {
            try {
                builder.addAllowedApplication(packageName)
                routed++
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Blocked app no longer installed: $packageName")
            }
        }

        // An allow-list with no apps would route EVERY app into the tunnel, so with nothing
        // to block we must not establish a tunnel at all.
        if (routed == 0) {
            closeTunnel()
            showStatus(0)
            return
        }

        val newTunnel = builder.establish()
        if (newTunnel == null) {
            // VPN permission was revoked or never granted.
            Rules(this).enabled = false
            closeTunnel()
            stopSelf()
            return
        }
        reader?.shutdown()
        reader = TunnelReader(this, newTunnel).also { it.start() }
        showStatus(routed)
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
            .setSmallIcon(R.drawable.ic_shield)
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

    /** The user tapped Block or Allow on a new-app notification. */
    private fun handleNewAppChoice(intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        NewAppNotifier.cancel(this, packageName)
        val rules = Rules(this)
        if (intent.action == ACTION_BLOCK_NEW_APP) rules.setBlocked(packageName, true)
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
        unregisterReceiver(installReceiver)
        closeTunnel()
        hideStatus()
        super.onDestroy()
    }

    private fun closeTunnel() {
        reader?.shutdown()
        reader = null
    }

    companion object {
        /** Fake DNS server inside the tunnel; see [applyRules]. */
        const val DNS_SERVER = "10.111.0.2"
        const val ACTION_APPLY = "dev.appwall.APPLY"
        const val ACTION_STOP = "dev.appwall.STOP"
        const val ACTION_BLOCK_NEW_APP = "dev.appwall.BLOCK_NEW_APP"
        const val ACTION_ALLOW_NEW_APP = "dev.appwall.ALLOW_NEW_APP"
        private const val TAG = "AppWall"
        private const val CHANNEL_STATUS = "status"
        private const val NOTIFICATION_ID = 1
    }
}
