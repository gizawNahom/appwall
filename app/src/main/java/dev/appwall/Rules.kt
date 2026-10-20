package dev.appwall

import android.content.Context
import dev.appwall.core.firewall.NewAppMode

/** Settings persisted on the device: on/off state, the set of blocked packages, and user preferences. */
class Rules(context: Context) {
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYSTEM, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SYSTEM, value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var newAppMode: NewAppMode
        get() = NewAppMode.entries.firstOrNull { it.name == prefs.getString(KEY_NEW_APP_MODE, null) } ?: NewAppMode.ASK
        set(value) = prefs.edit().putString(KEY_NEW_APP_MODE, value.name).apply()

    var loggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOGGING, true)
        set(value) = prefs.edit().putBoolean(KEY_LOGGING, value).apply()

    var logRetentionDays: Int
        get() = prefs.getInt(KEY_LOG_RETENTION_DAYS, 7)
        set(value) = prefs.edit().putInt(KEY_LOG_RETENTION_DAYS, value).apply()

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
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_NEW_APP_MODE = "new_app_mode"
        const val KEY_LOGGING = "logging"
        const val KEY_LOG_RETENTION_DAYS = "log_retention_days"
    }
}
