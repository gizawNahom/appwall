package dev.appwall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Turns the firewall back on after the phone restarts (if the user wants that) and after
 * AppWall itself is updated, which otherwise stops it until the app is opened again.
 */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rules = Rules(context)
        val wanted = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> rules.startOnBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> false
        }
        // Only if it was on before, and we still hold the VPN permission (no dialog possible here).
        if (!wanted || !rules.enabled || VpnService.prepare(context) != null) return
        try {
            context.startForegroundService(
                Intent(context, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_APPLY)
            )
        } catch (e: IllegalStateException) {
            Log.w("AppWall", "Could not start the firewall from ${intent.action}", e)
        }
    }
}
