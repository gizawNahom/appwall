package dev.appwall.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Loads app icons once and reuses them; shared by the app list and the wall. */
class AppIcons(private val context: Context) {
    private val cache = HashMap<String, Drawable?>()

    operator fun get(packageName: String): Drawable? = cache.getOrPut(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
