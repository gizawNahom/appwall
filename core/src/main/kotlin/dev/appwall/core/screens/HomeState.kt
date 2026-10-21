package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry
import dev.appwall.core.settings.FirewallPolicy
import dev.appwall.core.state.Attribution
import dev.appwall.core.state.FirewallState
import dev.appwall.core.state.FirewallStatus
import dev.appwall.core.state.LogHealth
import dev.appwall.core.state.OffReason

/** The card's line under "Wall up" / "Wall is off": what's blocked while running, otherwise why it's off. */
sealed interface CardLine {
    /** Running. [lastAttempt] (when a blocked app last tried) is null if nothing is logged. */
    data class Blocking(val apps: Int, val lastAttempt: Long?) : CardLine
    data object OtherVpn : CardLine
    data object Failed : CardLine
    data object Revoked : CardLine
    data object NoPermission : CardLine
    data object SettingsUnreadable : CardLine
    /** Turned off (or not started): blocked apps can use the internet. */
    data object Off : CardLine
}

/** Something about the firewall's health worth knowing, shown under the card's line. */
sealed interface HomeNotice {
    /** The log failed its start-up check: blocking works, but nothing is recorded. A problem. */
    data object LogUnavailable : HomeNotice
    /** Tries the log had no room for (they were still blocked). */
    data class DroppedTries(val count: Long) : HomeNotice
    /** Android 8–9: only a single blocked app's tries can be put down to it. */
    data object LimitedAttribution : HomeNotice
}

enum class HomeSection { BLOCKED, ALLOWED }

/** One line of Home's list: a section heading, or an app with the one action that fits it. */
sealed interface HomeItem {
    data class Heading(val section: HomeSection, val count: Int) : HomeItem
    /** [blocked] decides the button: Unblock for a blocked app, Block for an allowed one. */
    data class Row(val app: AppEntry, val blocked: Boolean) : HomeItem
}

data class HomeState(
    /** The switch shows what the user chose... */
    val switchOn: Boolean,
    /** ...the card what the firewall is actually doing. */
    val running: Boolean,
    val cardLine: CardLine,
    val items: List<HomeItem>,
    val loading: Boolean,
    /** A search that matches nothing. */
    val noMatch: Boolean,
    val notices: List<HomeNotice> = emptyList(),
)

/**
 * The home screen: one list with blocked apps first, then allowed ones, each A–Z, and a calm card
 * with no counts or charts. A search narrows both sections by name or package. Background system
 * services are left out of the allowed section unless [showSystem], or a search finds them; a
 * blocked app always shows. [apps] is null while loading.
 */
fun homeState(
    apps: List<AppEntry>?,
    policy: FirewallPolicy,
    showSystem: Boolean,
    status: FirewallStatus,
    anotherVpnActive: Boolean,
    lastAttempt: Long?,
    query: String,
): HomeState {
    val search = query.trim()
    fun matches(app: AppEntry) =
        search.isEmpty() || app.label.contains(search, ignoreCase = true) || app.packageName.contains(search, ignoreCase = true)
    val byName = compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName }
    val shown = apps.orEmpty().filter(::matches)
    val blocked = shown.filter { policy.isBlocked(it.packageName) }.sortedWith(byName)
    val allowed = shown
        .filter { !policy.isBlocked(it.packageName) && (showSystem || search.isNotEmpty() || !it.isSystemService) }
        .sortedWith(byName)

    val items = buildList {
        if (blocked.isNotEmpty()) {
            add(HomeItem.Heading(HomeSection.BLOCKED, blocked.size))
            blocked.forEach { add(HomeItem.Row(it, blocked = true)) }
        }
        if (allowed.isNotEmpty()) {
            add(HomeItem.Heading(HomeSection.ALLOWED, allowed.size))
            allowed.forEach { add(HomeItem.Row(it, blocked = false)) }
        }
    }

    val running = status.running
    val blockedCount = apps?.count { policy.isBlocked(it.packageName) } ?: policy.blocked.size
    val firewall = status.firewall
    val cardLine = when {
        firewall !is FirewallState.Off -> CardLine.Blocking(blockedCount, lastAttempt)
        anotherVpnActive -> CardLine.OtherVpn
        firewall.reason == OffReason.TUNNEL_FAILED -> CardLine.Failed
        firewall.reason == OffReason.REVOKED -> CardLine.Revoked
        firewall.reason == OffReason.NO_PERMISSION -> CardLine.NoPermission
        firewall.reason == OffReason.SETTINGS_UNREADABLE -> CardLine.SettingsUnreadable
        else -> CardLine.Off
    }
    return HomeState(
        switchOn = policy.enabled,
        running = running,
        cardLine = cardLine,
        items = items,
        loading = apps == null,
        noMatch = apps != null && search.isNotEmpty() && items.isEmpty(),
        notices = listOfNotNull(
            HomeNotice.LogUnavailable.takeIf { status.logHealth is LogHealth.Unavailable },
            HomeNotice.DroppedTries(status.droppedTries).takeIf { status.droppedTries > 0 },
            HomeNotice.LimitedAttribution.takeIf { running && status.attribution == Attribution.LIMITED },
        ),
    )
}
