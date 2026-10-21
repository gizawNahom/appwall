package dev.appwall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.appwall.core.firewall.NewAppDecision
import dev.appwall.core.firewall.newAppDecision

/**
 * Handles freshly installed apps according to [NewAppMode]: asks "Block it? [Block] [Allow]",
 * allows silently, or blocks automatically and says so with an [Unblock] button.
 */
object NewAppNotifier {
    // High importance so Android shows a heads-up pop-up with the Block/Allow buttons visible.
    // (A channel's importance can't be raised after creation, hence the new id.)
    private const val CHANNEL_NEW_APPS = "new_apps_alert"
    private const val OLD_CHANNEL_NEW_APPS = "new_apps"

    // Each new app's notification is told apart by its package name (the notification tag).
    private const val NOTIFICATION_ID = 2

    /** Returns true if the block rules changed (the app was blocked automatically). */
    fun onInstalled(context: Context, packageName: String): Boolean {
        val settings = context.appWall.settings
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val decision = newAppDecision(
            mode = settings.policy.newAppMode,
            isSelf = packageName == context.packageName,
            alreadyBlocked = settings.policy.isBlocked(packageName),
            usesInternet = info?.let { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true },
        )
        if (decision == NewAppDecision.IGNORE) return false
        val label = info?.applicationInfo?.loadLabel(pm)?.toString() ?: packageName

        if (decision == NewAppDecision.AUTO_BLOCK) {
            settings.setBlocked(listOf(packageName), true)
            post(
                context, packageName,
                title = context.getString(R.string.new_app_blocked_title, label),
                text = context.getString(R.string.new_app_blocked_text),
                openIntent = Intent(context, MainActivity::class.java),
                actions = listOf(action(context, packageName, FirewallVpnService.ACTION_ALLOW_NEW_APP, R.string.unblock)),
            )
            return true
        }

        // Tapping the notification itself opens AppWall straight into a Block/Allow dialog for this app.
        val askInApp = Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts("package", packageName, null))
            .putExtra(MainActivity.EXTRA_NEW_APP, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        post(
            context, packageName,
            title = context.getString(R.string.new_app_title, label),
            text = context.getString(R.string.new_app_text),
            openIntent = askInApp,
            actions = listOf(
                action(context, packageName, FirewallVpnService.ACTION_BLOCK_NEW_APP, R.string.block),
                action(context, packageName, FirewallVpnService.ACTION_ALLOW_NEW_APP, R.string.allow),
            ),
        )
        return false
    }

    private fun post(
        context: Context, packageName: String, title: String, text: String,
        openIntent: Intent, actions: List<Notification.Action>,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_NEW_APPS)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_APPS,
                context.getString(R.string.channel_new_apps),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val openApp = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, CHANNEL_NEW_APPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
        actions.forEach { builder.addAction(it) }
        manager.notify(packageName, NOTIFICATION_ID, builder.build())
    }

    fun cancel(context: Context, packageName: String) {
        context.getSystemService(NotificationManager::class.java).cancel(packageName, NOTIFICATION_ID)
    }

    private fun action(context: Context, packageName: String, action: String, title: Int): Notification.Action {
        // The package: URI makes each app's pending intents distinct, so buttons don't overwrite each other.
        val intent = Intent(context, FirewallVpnService::class.java)
            .setAction(action)
            .setData(Uri.fromParts("package", packageName, null))
        val pending = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, context.getString(title), pending).build()
    }
}
