package dev.appwall.core.log

/** One logged row: a try, or repeat tries to the same place within a minute. */
data class LogEntry(
    val time: Long,
    val packageName: String?,
    val protocol: Int,
    val address: String,
    val port: Int,
    val host: String?,
    val attempts: Int,
)

/** Per-app totals for the grouped log view. */
data class AppSummary(
    val packageName: String?,
    val attempts: Int,
    val destinations: Int,
    val lastTime: Long,
)

/** One place an app tried to reach: a domain name it looked up, or an address it connected to. */
data class Destination(
    val name: String,
    /** Only a name lookup was seen (the app never got an address to connect to). */
    val isLookup: Boolean,
    val ports: List<Int>,
    val attempts: Int,
    val lastTime: Long,
)
