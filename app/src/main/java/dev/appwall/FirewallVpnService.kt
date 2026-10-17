package dev.appwall

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
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
    }

    override fun onRevoke() {
        // The user turned us off from system settings, or another VPN app took over.
        Rules(this).enabled = false
        closeTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        closeTunnel()
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
    }
}
