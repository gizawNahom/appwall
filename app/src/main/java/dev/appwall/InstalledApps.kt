package dev.appwall

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    /**
     * A system app with no launcher icon, i.e. a background service the user never opens.
     * Preinstalled apps people actually use (Chrome, YouTube) have an icon, so they don't count.
     */
    val isSystemService: Boolean,
)

object InstalledApps {
    /** Apps that can use the network (request INTERNET), sorted by name. Slow: call off the UI thread. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return packages
            .filter { it.packageName != context.packageName }
            .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
            .mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    packageName = pkg.packageName,
                    isSystem = isSystem,
                    isSystemService = isSystem && pm.getLaunchIntentForPackage(pkg.packageName) == null,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
