package dev.appwall.storage

import android.content.Context
import dev.appwall.core.settings.KeyValueStore

/** [KeyValueStore] over the app's "rules" SharedPreferences file, where settings have always been kept. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun all(): Map<String, Any?> = prefs.all

    override fun write(values: Map<String, Any>) {
        val editor = prefs.edit()
        for ((key, value) in values) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported setting type: ${value::class}")
            }
        }
        editor.apply()
    }

    private companion object {
        const val FILE = "rules"
    }
}
