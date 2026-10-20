package dev.appwall

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.appwall.core.apps.AppEntry
import java.util.concurrent.Executor

/**
 * The apps installed on the phone that can use the network. Loading takes seconds, so it runs on
 * [executor]; the latest result is kept so other screens (e.g. the picker) can show it at once.
 */
class InstalledAppsRepository(context: Context, private val executor: Executor) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The most recent load's result, or null before the first one finishes. */
    @Volatile
    var latest: List<AppEntry>? = null
        private set

    /** Loads the list in the background and hands it to [onLoaded] on the UI thread. */
    fun load(onLoaded: (List<AppEntry>) -> Unit) {
        executor.execute {
            val loaded = loadNow()
            latest = loaded
            mainHandler.post { onLoaded(loaded) }
        }
    }

    /** Apps that can use the network (request INTERNET), sorted by name. */
    private fun loadNow(): List<AppEntry> {
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return packages
            .filter { it.packageName != appContext.packageName }
            .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
            .mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    packageName = pkg.packageName,
                    isSystem = isSystem,
                    isSystemService = isSystem && pm.getLaunchIntentForPackage(pkg.packageName) == null,
                    installedAt = pkg.firstInstallTime,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
