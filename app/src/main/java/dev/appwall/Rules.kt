package dev.appwall

import android.content.Context

/** Settings persisted on the device: on/off state, the set of blocked packages, and list display options. */
class Rules(context: Context) {
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYSTEM, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SYSTEM, value).apply()

    fun blocked(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet())!!.toSet()

    fun isBlocked(packageName: String): Boolean = packageName in blocked()

    fun setBlocked(packageName: String, block: Boolean) {
        val updated = blocked().toMutableSet()
        if (block) updated += packageName else updated -= packageName
        prefs.edit().putStringSet(KEY_BLOCKED, updated).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_BLOCKED = "blocked"
        const val KEY_SHOW_SYSTEM = "show_system_apps"
    }
}
