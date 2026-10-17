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

/** "New app: X can access the internet [Block] [Allow]" notifications for freshly installed apps. */
object NewAppNotifier {
    // High importance so Android shows a heads-up pop-up with the Block/Allow buttons visible.
    // (A channel's importance can't be raised after creation, hence the new id.)
    private const val CHANNEL_NEW_APPS = "new_apps_alert"
    private const val OLD_CHANNEL_NEW_APPS = "new_apps"

    // Each new app's notification is told apart by its package name (the notification tag).
    private const val NOTIFICATION_ID = 2

    fun onInstalled(context: Context, packageName: String) {
        if (packageName == context.packageName || Rules(context).isBlocked(packageName)) return
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }
        if (info.requestedPermissions?.contains(Manifest.permission.INTERNET) != true) return
        val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: packageName

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_NEW_APPS)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_APPS,
                context.getString(R.string.channel_new_apps),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        // Tapping the notification itself opens AppWall straight into a Block/Allow dialog for this app.
        val askInApp = Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts("package", packageName, null))
            .putExtra(MainActivity.EXTRA_NEW_APP, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openApp = PendingIntent.getActivity(context, 0, askInApp, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_NEW_APPS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.new_app_title, label))
            .setContentText(context.getString(R.string.new_app_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .addAction(action(context, packageName, FirewallVpnService.ACTION_BLOCK_NEW_APP, R.string.block))
            .addAction(action(context, packageName, FirewallVpnService.ACTION_ALLOW_NEW_APP, R.string.allow))
            .build()
        manager.notify(packageName, NOTIFICATION_ID, notification)
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
