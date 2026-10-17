package dev.appwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Blocks apps by routing only the blocked ones into a local tunnel that nothing ever reads
 * or forwards. Their packets go nowhere, so they get no network. All other apps stay on the
 * normal network, untouched.
 */
class FirewallVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeTunnel()
            stopSelf()
            return START_NOT_STICKY
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
        tunnel?.close()
        tunnel = newTunnel
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

    override fun onRevoke() {
        // The user turned us off from system settings, or another VPN app took over.
        Rules(this).enabled = false
        closeTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        closeTunnel()
        hideStatus()
        super.onDestroy()
    }

    private fun closeTunnel() {
        tunnel?.close()
        tunnel = null
    }

    companion object {
        const val ACTION_APPLY = "dev.appwall.APPLY"
        const val ACTION_STOP = "dev.appwall.STOP"
        private const val TAG = "AppWall"
        private const val CHANNEL_STATUS = "status"
        private const val NOTIFICATION_ID = 1
    }
}
