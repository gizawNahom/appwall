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
) {
    companion object {
        /** A destination from the log's grouped query, whose [portList] is its ports joined by commas. */
        fun fromPortList(name: String, isLookup: Boolean, portList: String, attempts: Int, lastTime: Long) =
            Destination(name, isLookup, ports(isLookup, portList), attempts, lastTime)

        /**
         * A lookup's port is always DNS's, which says nothing about the app, and port 0 stands for
         * a protocol without ports (ICMP): neither is shown. Anything not a number is skipped.
         */
        private fun ports(isLookup: Boolean, portList: String): List<Int> =
            if (isLookup) emptyList() else portList.split(',').mapNotNull(String::toIntOrNull).filter { it != 0 }.sorted()
    }
}
