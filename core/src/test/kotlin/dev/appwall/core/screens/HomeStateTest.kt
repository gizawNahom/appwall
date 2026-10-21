package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry
import dev.appwall.core.settings.FirewallPolicy
import dev.appwall.core.state.Attribution
import dev.appwall.core.state.FirewallState
import dev.appwall.core.state.FirewallStatus
import dev.appwall.core.state.LogHealth
import dev.appwall.core.state.OffReason
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStateTest {

    private val chrome = app("Chrome", "com.android.chrome", system = true)
    private val game = app("game", "com.game")
    private val bank = app("Bank", "com.bank")
    private val maps = app("Maps", "com.maps")
    private val svc = app("Telephony", "com.android.svc", system = true, service = true)
    private val apps = listOf(chrome, game, bank, maps, svc)
    private val on = FirewallStatus(FirewallState.On(3))

    private fun state(
        tab: HomeTab = HomeTab.BLOCKED,
        query: String = "",
        blocked: Set<String> = setOf("com.android.chrome", "com.game", "com.bank", "com.android.svc"),
        showSystem: Boolean = false,
        status: FirewallStatus = on,
        otherVpn: Boolean = false,
        today: Map<String?, Int> = mapOf("com.game" to 5, "com.bank" to 5, "com.android.chrome" to 9, null to 1),
        apps: List<AppEntry>? = this.apps,
    ) = homeState(apps, FirewallPolicy(enabled = true, blocked = blocked), showSystem, status, otherVpn, HomeLogStats(today, List(12) { 0 }, 42L), tab, query)

    @Test
    fun blockedTabIsBusiestFirstThenByNameWithServices() {
        val s = state()
        assertEquals(listOf(chrome, bank, game, svc), s.rows.map { it.app }) // 9, then the 5s by name (any case), then 0
        assertEquals(listOf(9, 5, 5, 0), s.rows.map { it.attemptsToday })
        assertEquals(9, s.meterMax)
    }

    @Test
    fun allowedTabHidesServicesUnlessAsked() {
        val blocked = setOf("com.game")
        assertEquals(listOf(chrome, bank, maps), state(HomeTab.ALLOWED, blocked = blocked).rows.map { it.app })
        assertEquals(listOf(chrome, bank, maps, svc), state(HomeTab.ALLOWED, blocked = blocked, showSystem = true).rows.map { it.app })
        assertEquals(3, state(HomeTab.ALLOWED, blocked = blocked).allowedCount)
        assertEquals(4, state(HomeTab.ALLOWED, blocked = blocked, showSystem = true).allowedCount)
    }

    @Test
    fun searchLooksEverywhereButOnlyOnceSomethingIsTyped() {
        assertEquals(emptyList<HomeRow>(), state(HomeTab.SEARCH).rows)
        assertEquals(HomeEmpty.SEARCH_PROMPT, state(HomeTab.SEARCH).empty)
        assertEquals(listOf(svc), state(HomeTab.SEARCH, query = " TELE ").rows.map { it.app })
        assertEquals(listOf(true, true, true, false, true), state(HomeTab.SEARCH, query = "com.").rows.map { it.blocked }) // blocked or not
        assertEquals(HomeEmpty.NO_MATCH, state(HomeTab.SEARCH, query = "zzz").empty)
    }

    @Test
    fun emptyTabs() {
        assertEquals(HomeEmpty.NO_BLOCKED, state(blocked = emptySet()).empty)
        assertEquals(HomeEmpty.NO_ALLOWED, state(HomeTab.ALLOWED, blocked = apps.map { it.packageName }.toSet()).empty)
        val loading = state(apps = null)
        assertEquals(true, loading.loading)
        assertEquals(HomeEmpty.NONE, loading.empty) // not "nothing here" while still loading
        assertEquals(4, loading.blockedCount) // from the policy until the apps are known
        assertEquals(null, loading.allowedCount)
    }

    @Test
    fun countsOnlyInstalledBlockedApps() {
        assertEquals(1, state(blocked = setOf("com.game", "com.uninstalled")).blockedCount)
    }

    @Test
    fun cardForEachStatus() {
        val table = listOf(
            Triple(on, false, CardLine.AttemptsToday(20) to Headline.Blocking(4)),
            Triple(FirewallStatus(FirewallState.Starting), false, CardLine.AttemptsToday(20) to Headline.Blocking(4)),
            Triple(FirewallStatus(FirewallState.Off(OffReason.TURNED_OFF)), true, CardLine.OtherVpn to Headline.Down),
            Triple(FirewallStatus(FirewallState.Off(OffReason.TUNNEL_FAILED)), false, CardLine.Failed to Headline.Down),
            Triple(FirewallStatus(FirewallState.Off(OffReason.REVOKED)), false, CardLine.Revoked to Headline.Down),
            Triple(FirewallStatus(FirewallState.Off(OffReason.NO_PERMISSION)), false, CardLine.NoPermission to Headline.Down),
            Triple(FirewallStatus(FirewallState.Off(OffReason.SETTINGS_UNREADABLE)), false, CardLine.SettingsUnreadable to Headline.Down),
            Triple(FirewallStatus(FirewallState.Off(OffReason.TURNED_OFF)), false, CardLine.TurnOnToBlock(4) to Headline.Down),
        )
        for ((status, otherVpn, expected) in table) {
            val s = state(status = status, otherVpn = otherVpn)
            assertEquals("$status, other VPN $otherVpn", expected, s.cardLine to s.headline)
        }
        assertEquals(Headline.NothingBlocked, state(blocked = emptySet()).headline)
    }

    @Test
    fun healthNotices() {
        assertEquals(emptyList<HomeNotice>(), state(status = on.copy(logHealth = LogHealth.Ok)).notices)
        assertEquals(emptyList<HomeNotice>(), state(status = on.copy(logHealth = LogHealth.Checking)).notices)
        assertEquals(listOf(HomeNotice.LogUnavailable), state(status = on.copy(logHealth = LogHealth.Unavailable("disk full"))).notices)
        assertEquals(listOf(HomeNotice.DroppedTries(12)), state(status = on.copy(droppedTries = 12)).notices)
        assertEquals(listOf(HomeNotice.LimitedAttribution), state(status = on.copy(attribution = Attribution.LIMITED)).notices)
        // Attribution only matters while blocking; a broken log matters always.
        val off = FirewallStatus(FirewallState.Off(OffReason.TURNED_OFF), logHealth = LogHealth.Unavailable("x"), attribution = Attribution.LIMITED)
        assertEquals(listOf(HomeNotice.LogUnavailable), state(status = off).notices)
    }

    @Test
    fun onlyTheOpenRowHasDetails() {
        val activity = AppActivity("com.game", 1_000, 12, List(24) { 1 }, listOf("a.example", "b.example", "c.example"), 5)
        val rows = homeState(apps, FirewallPolicy(blocked = setOf("com.game", "com.bank")), false, on, false, null, HomeTab.BLOCKED, "", "com.game", activity).rows
        assertEquals(listOf("com.game"), rows.filter { it.details != null }.map { it.app.packageName })
        // Another app's activity (the open row just changed) isn't shown as this one's.
        val stale = homeState(apps, FirewallPolicy(blocked = setOf("com.game", "com.bank")), false, on, false, null, HomeTab.BLOCKED, "", "com.bank", activity).rows
        assertEquals(DetailsSummary.Loading, stale.first { it.app.packageName == "com.bank" }.details?.summary)
    }

    @Test
    fun detailRows() {
        val busy = AppActivity("x", 1_000, 12, List(24) { 1 }, listOf("a.example", "b.example", "c.example"), 5)
        assertEquals(
            RowDetails(DetailsSummary.Activity(1_000, 12), List(24) { 1 }, listOf("a.example", "b.example", "c.example"), 2, showFullLog = true),
            rowDetails(blocked = true, activity = busy),
        )
        val quiet = AppActivity("x", null, 0, List(24) { 0 }, emptyList(), 0)
        assertEquals(RowDetails(DetailsSummary.NoAttempts, null, emptyList(), 0, showFullLog = false), rowDetails(true, quiet))
        assertEquals(RowDetails(DetailsSummary.Loading, null, emptyList(), 0, showFullLog = false), rowDetails(true, null))
        // Allowed now, but blocked earlier: no activity shown, yet its old log is still reachable.
        assertEquals(RowDetails(DetailsSummary.Allowed, null, emptyList(), 0, showFullLog = true), rowDetails(false, busy))
        assertEquals(0, rowDetails(true, busy.copy(destinationCount = 3)).moreDestinations)
    }

    private fun app(label: String, pkg: String, system: Boolean = false, service: Boolean = false) =
        AppEntry(label, pkg, system, service, 0)
}
