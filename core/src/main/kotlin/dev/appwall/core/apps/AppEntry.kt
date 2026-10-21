package dev.appwall.core.apps

/** An installed app that can use the network. */
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
