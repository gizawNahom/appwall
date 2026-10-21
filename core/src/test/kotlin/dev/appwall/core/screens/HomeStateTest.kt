package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry
import dev.appwall.core.screens.HomeItem.Heading
import dev.appwall.core.screens.HomeItem.Row
import dev.appwall.core.screens.HomeSection.ALLOWED
import dev.appwall.core.screens.HomeSection.BLOCKED
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
        query: String = "",
        blocked: Set<String> = setOf("com.game", "com.android.chrome"),
        showSystem: Boolean = false,
        status: FirewallStatus = on,
        otherVpn: Boolean = false,
        lastAttempt: Long? = 42L,
        apps: List<AppEntry>? = this.apps,
        selected: Set<String>? = null,
    ) = homeState(apps, FirewallPolicy(enabled = true, blocked = blocked), showSystem, status, otherVpn, lastAttempt, query, selected)

    @Test
    fun oneListBlockedFirstThenAllowedEachByName() {
        assertEquals(
            listOf(Heading(BLOCKED, 2), Row(chrome, true), Row(game, true), Heading(ALLOWED, 2), Row(bank, false), Row(maps, false)),
            state().items,
        )
    }

    @Test
    fun servicesStayOutOfAllowedUnlessAskedForOrSearchedOrBlocked() {
        assertEquals(listOf(bank, maps, svc), allowed(state(showSystem = true)))
        assertEquals(listOf(svc), allowed(state(query = " TELE "))) // search finds them anyway
        assertEquals(Row(svc, true), state(blocked = setOf("com.android.svc")).items[1]) // a blocked one always shows
    }

    @Test
    fun searchNarrowsBothSections() {
        assertEquals(listOf(Heading(BLOCKED, 1), Row(chrome, true)), state(query = "chrome").items)
        assertEquals(listOf(Heading(ALLOWED, 1), Row(maps, false)), state(query = "com.ma").items)
        assertEquals(true, state(query = "zzz").noMatch)
        assertEquals(false, state().noMatch)
    }

    @Test
    fun aSectionWithNothingInItHasNoHeading() {
        assertEquals(Heading(ALLOWED, 4), state(blocked = emptySet()).items.first())
        val everything = apps.map { it.packageName }.toSet()
        assertEquals(listOf(Heading(BLOCKED, 5)), state(blocked = everything).items.filterIsInstance<Heading>())
    }

    @Test
    fun whileLoading() {
        val loading = state(apps = null)
        assertEquals(true, loading.loading)
        assertEquals(emptyList<HomeItem>(), loading.items)
        assertEquals(false, loading.noMatch) // not "nothing matches" while still loading
        assertEquals(CardLine.Blocking(2, 42L), loading.cardLine) // the count comes from the policy until the apps are known
    }

    @Test
    fun cardForEachStatus() {
        val table = listOf(
            Triple(on, false, CardLine.Blocking(2, 42L)),
            Triple(FirewallStatus(FirewallState.Starting), false, CardLine.Blocking(2, 42L)),
            Triple(FirewallStatus(FirewallState.Off(OffReason.TURNED_OFF)), true, CardLine.OtherVpn),
            Triple(FirewallStatus(FirewallState.Off(OffReason.TUNNEL_FAILED)), false, CardLine.Failed),
            Triple(FirewallStatus(FirewallState.Off(OffReason.REVOKED)), false, CardLine.Revoked),
            Triple(FirewallStatus(FirewallState.Off(OffReason.NO_PERMISSION)), false, CardLine.NoPermission),
            Triple(FirewallStatus(FirewallState.Off(OffReason.SETTINGS_UNREADABLE)), false, CardLine.SettingsUnreadable),
            Triple(FirewallStatus(FirewallState.Off(OffReason.TURNED_OFF)), false, CardLine.Off),
            Triple(FirewallStatus(FirewallState.Off(OffReason.NOT_RUNNING)), false, CardLine.Off),
        )
        for ((status, otherVpn, expected) in table) {
            assertEquals("$status, other VPN $otherVpn", expected, state(status = status, otherVpn = otherVpn).cardLine)
        }
        assertEquals(CardLine.Blocking(0, null), state(blocked = emptySet(), lastAttempt = null).cardLine)
        assertEquals(CardLine.Blocking(1, 42L), state(blocked = setOf("com.game", "com.uninstalled")).cardLine) // installed apps only
    }

    @Test
    fun choosingSeveralTicksAllowedRowsOnly() {
        val s = state(selected = setOf("com.maps", "com.bank"))
        assertEquals(true, s.selecting)
        assertEquals(listOf(Row(chrome, true), Row(game, true), Row(bank, false, true), Row(maps, false, true)), s.items.filterIsInstance<Row>())
        assertEquals(listOf(bank, maps), s.selectedApps)
        val none = state(selected = emptySet())
        assertEquals(true, none.selecting) // started, nothing ticked yet
        assertEquals(listOf(false, false), none.items.filterIsInstance<Row>().filter { !it.blocked }.map { it.selected })
        assertEquals(false, state().selecting)
        assertEquals(null, state().items.filterIsInstance<Row>().last().selected)
    }

    @Test
    fun aTickedAppStaysChosenWhenSearchHidesItButNotOnceBlocked() {
        assertEquals(listOf(bank, maps), state(selected = setOf("com.maps", "com.bank"), query = "maps").selectedApps)
        assertEquals(listOf(maps), state(selected = setOf("com.maps", "com.game")).selectedApps) // com.game is already blocked
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

    private fun allowed(s: HomeState) = s.items.filterIsInstance<Row>().filter { !it.blocked }.map { it.app }

    private fun app(label: String, pkg: String, system: Boolean = false, service: Boolean = false) =
        AppEntry(label, pkg, system, service, 0)
}
