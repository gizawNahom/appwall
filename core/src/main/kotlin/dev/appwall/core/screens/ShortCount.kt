package dev.appwall.core.screens

private val UNITS = listOf(1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")

/**
 * A count short enough for a narrow column: in full up to 9999, then "12.3K", "123K", "1.2M".
 * Rounds down, so it never claims more than was counted.
 */
fun shortCount(n: Long): String {
    if (n < 10_000) return n.toString()
    val (unit, suffix) = UNITS.first { (unit, _) -> n >= unit }
    val whole = n / unit
    if (whole >= 100) return "$whole$suffix"
    val tenth = n % unit * 10 / unit
    return if (tenth == 0L) "$whole$suffix" else "$whole.$tenth$suffix"
}
