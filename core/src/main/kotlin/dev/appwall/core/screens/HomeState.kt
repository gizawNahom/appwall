package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry
import dev.appwall.core.settings.FirewallPolicy
import dev.appwall.core.state.FirewallState
import dev.appwall.core.state.FirewallStatus
import dev.appwall.core.state.OffReason

/** BLOCKED and ALLOWED are the tabs; SEARCH replaces both while searching. */
enum class HomeTab { BLOCKED, ALLOWED, SEARCH }

/** The log's numbers Home shows, read together on the database thread. */
data class HomeLogStats(
    /** Attempts today per app (null = unattributed). */
    val today: Map<String?, Int>,
    val sparkline: List<Int>,
    val lastAttempt: Long?,
)

/** The card's big line. */
sealed interface Headline {
    data object Down : Headline
    data object NothingBlocked : Headline
    data class Blocking(val apps: Int) : Headline
}

/** The card's second line: today's total while running, otherwise why it's off. */
sealed interface CardLine {
    data class AttemptsToday(val attempts: Int) : CardLine
    data object OtherVpn : CardLine
    data object Failed : CardLine
    data object Revoked : CardLine
    data object NoPermission : CardLine
    data class TurnOnToBlock(val apps: Int) : CardLine
}

/** Why the list is empty, if it is (and the apps have loaded). */
enum class HomeEmpty { NONE, SEARCH_PROMPT, NO_MATCH, NO_BLOCKED, NO_ALLOWED }

/** The open row's recent activity, as read from the log on the database thread. */
data class AppActivity(
    val packageName: String,
    val lastAttempt: Long?,
    /** Attempts over the last 7 days. */
    val week: Int,
    /** Attempts per hour over the last 24 hours, oldest first. */
    val hourly: List<Int>,
    /** The most tried destinations this week, most tried first. */
    val topDestinations: List<String>,
    /** How many destinations there were this week in all. */
    val destinationCount: Int,
)

/** The first line of an open row. */
sealed interface DetailsSummary {
    /** The row's activity hasn't been read yet: show only its actions. */
    data object Loading : DetailsSummary
    data object NoAttempts : DetailsSummary
    data class Activity(val lastAttempt: Long, val week: Int) : DetailsSummary
    data object Allowed : DetailsSummary
}

/** What an open (expanded) row shows. Its main button allows a blocked app or blocks an allowed one. */
data class RowDetails(
    val summary: DetailsSummary,
    /** The last 24 hours, or null for no chart. */
    val hourly: List<Int>?,
    val topDestinations: List<String>,
    /** Destinations beyond [topDestinations]: "+N more". */
    val moreDestinations: Int,
    /** The log is only worth offering when there's something in it. */
    val showFullLog: Boolean,
)

data class HomeRow(
    val app: AppEntry,
    val blocked: Boolean,
    val attemptsToday: Int,
    /** Only for the open row. */
    val details: RowDetails? = null,
)

data class HomeState(
    /** The switch shows what the user chose... */
    val switchOn: Boolean,
    /** ...the card what the firewall is actually doing. */
    val running: Boolean,
    val headline: Headline,
    val cardLine: CardLine,
    val sparkline: List<Int>,
    val lastAttempt: Long?,
    val blockedCount: Int,
    /** Null until the apps have loaded. */
    val allowedCount: Int?,
    val tab: HomeTab,
    val rows: List<HomeRow>,
    /** The most attempts today among the rows: a full meter. At least 1. */
    val meterMax: Int,
    val loading: Boolean,
    val empty: HomeEmpty,
)

/**
 * The home screen. On the Blocked tab: blocked apps, busiest today first, then by name, system
 * services included. On the Allowed tab: everything else (background services only with
 * [showSystem]), by name. Searching matches name or package across every app, blocked or not, and
 * shows nothing until something is typed. [apps] is null while loading; [stats] until first read.
 */
fun homeState(
    apps: List<AppEntry>?,
    policy: FirewallPolicy,
    showSystem: Boolean,
    status: FirewallStatus,
    anotherVpnActive: Boolean,
    stats: HomeLogStats?,
    tab: HomeTab,
    query: String,
    /** The open row's app, if any, and its [activity] once read (ignored if it's another app's). */
    expanded: String? = null,
    activity: AppActivity? = null,
): HomeState {
    val today = stats?.today.orEmpty()
    val blockedCount = apps?.count { policy.isBlocked(it.packageName) } ?: policy.blocked.size
    val allowedCount = apps?.count { !policy.isBlocked(it.packageName) && (showSystem || !it.isSystemService) }
    val running = status.running

    val search = query.trim()
    fun matches(app: AppEntry) =
        search.isEmpty() || app.label.contains(search, ignoreCase = true) || app.packageName.contains(search, ignoreCase = true)
    val shown = apps.orEmpty().let { all ->
        when (tab) {
            HomeTab.BLOCKED -> all
                .filter { policy.isBlocked(it.packageName) && matches(it) }
                .sortedWith(compareByDescending<AppEntry> { today[it.packageName] ?: 0 }.thenBy { it.label.lowercase() })
            HomeTab.ALLOWED -> all.filter { !policy.isBlocked(it.packageName) && (showSystem || !it.isSystemService) && matches(it) }
            HomeTab.SEARCH -> if (search.isEmpty()) emptyList() else all.filter(::matches)
        }
    }
    val openActivity = activity?.takeIf { it.packageName == expanded }
    val rows = shown.map {
        val blocked = policy.isBlocked(it.packageName)
        val details = if (it.packageName == expanded) rowDetails(blocked, openActivity) else null
        HomeRow(it, blocked, today[it.packageName] ?: 0, details)
    }

    val firewall = status.firewall
    val cardLine = when {
        firewall !is FirewallState.Off -> CardLine.AttemptsToday(today.values.sum())
        anotherVpnActive -> CardLine.OtherVpn
        firewall.reason == OffReason.TUNNEL_FAILED -> CardLine.Failed
        firewall.reason == OffReason.REVOKED -> CardLine.Revoked
        firewall.reason == OffReason.NO_PERMISSION -> CardLine.NoPermission
        else -> CardLine.TurnOnToBlock(blockedCount)
    }
    return HomeState(
        switchOn = policy.enabled,
        running = running,
        headline = when {
            !running -> Headline.Down
            blockedCount == 0 -> Headline.NothingBlocked
            else -> Headline.Blocking(blockedCount)
        },
        cardLine = cardLine,
        sparkline = stats?.sparkline.orEmpty(),
        lastAttempt = stats?.lastAttempt,
        blockedCount = blockedCount,
        allowedCount = allowedCount,
        tab = tab,
        rows = rows,
        meterMax = rows.maxOfOrNull { it.attemptsToday }?.coerceAtLeast(1) ?: 1,
        loading = apps == null,
        empty = when {
            apps == null || rows.isNotEmpty() -> HomeEmpty.NONE
            tab == HomeTab.SEARCH && search.isEmpty() -> HomeEmpty.SEARCH_PROMPT
            tab == HomeTab.SEARCH -> HomeEmpty.NO_MATCH
            tab == HomeTab.BLOCKED -> HomeEmpty.NO_BLOCKED
            else -> HomeEmpty.NO_ALLOWED
        },
    )
}

/** An open row: recent activity for a blocked app; for an allowed one, why there's none. */
fun rowDetails(blocked: Boolean, activity: AppActivity?): RowDetails {
    val lastAttempt = activity?.lastAttempt
    val showFullLog = lastAttempt != null
    if (!blocked) return RowDetails(DetailsSummary.Allowed, null, emptyList(), 0, showFullLog)
    if (activity == null) return RowDetails(DetailsSummary.Loading, null, emptyList(), 0, showFullLog = false)
    return RowDetails(
        summary = if (lastAttempt == null) DetailsSummary.NoAttempts else DetailsSummary.Activity(lastAttempt, activity.week),
        hourly = if (lastAttempt != null) activity.hourly else null,
        topDestinations = activity.topDestinations,
        moreDestinations = (activity.destinationCount - activity.topDestinations.size).coerceAtLeast(0),
        showFullLog = showFullLog,
    )
}
