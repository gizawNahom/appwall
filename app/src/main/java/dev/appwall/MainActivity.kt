package dev.appwall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import dev.appwall.core.apps.AppEntry
import dev.appwall.core.screens.CardLine
import dev.appwall.core.screens.HomeItem
import dev.appwall.core.screens.HomeNotice
import dev.appwall.core.screens.HomeState
import dev.appwall.core.screens.systemAppWarning
import dev.appwall.core.screens.homeState
import dev.appwall.core.state.Subscription

class MainActivity : Activity() {

    private val settings get() = appWall.settings
    private lateinit var adapter: AppListAdapter
    private lateinit var firewallSwitch: Switch
    private lateinit var icons: AppIcons
    private lateinit var search: EditText

    // The screen's inputs; render() turns them into a HomeState and draws it.
    private var apps: List<AppEntry>? = null
    /** When a blocked app last tried to connect, for the card's one calm line. */
    private var lastAttempt: Long? = null
    private var query = ""
    /** The ticked packages while choosing several apps to block (long-press a row); null otherwise. */
    private var selected: MutableSet<String>? = null
    /** What was last drawn, for taps that act on it (the ticked apps). */
    private var shown: HomeState? = null
    private var undo: PendingUndo? = null
    private val hideUndo = Runnable {
        undo = null
        findViewById<View>(R.id.undo_bar).visibility = View.GONE
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Keeps the card's "last blocked" current while the screen is visible. */
    private val lastAttemptLoader by lazy {
        logLoader({ fun(log: SqlBlockLog) = log.lastAttemptTime() }) { time ->
            lastAttempt = time
            render()
        }
    }
    private var updatingSwitch = false
    private var consentRequestedAt = 0L

    /** PackageManager's change counter when the list was last loaded; used to spot installs/uninstalls. */
    private var packagesSequence = 0
    private val subscriptions = ArrayList<Subscription>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        icons = AppIcons(this)

        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.open_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<View>(R.id.undo_button).setOnClickListener {
            val action = undo?.action
            hideUndo.run()
            if (action != null) runUndo(action)
        }

        firewallSwitch = findViewById(R.id.firewall_switch)
        firewallSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) requestStart() else stopFirewall()
        }

        adapter = AppListAdapter(this, icons) { app, action -> onRowAction(app, action) }
        findViewById<ListView>(R.id.app_list).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val row = this@MainActivity.adapter.getItem(position) as? HomeItem.Row ?: return@setOnItemClickListener
                val ticked = selected
                when {
                    ticked == null -> onRowAction(row.app, AppListAdapter.Action.OPEN)
                    row.selected != null -> {
                        if (!ticked.remove(row.app.packageName)) ticked += row.app.packageName
                        render()
                    }
                }
            }
            // Long-press an allowed app to choose several to block at once.
            setOnItemLongClickListener { _, _, position, _ ->
                val row = this@MainActivity.adapter.getItem(position) as? HomeItem.Row
                if (row == null || row.blocked || selected != null) return@setOnItemLongClickListener false
                selected = mutableSetOf(row.app.packageName)
                hideUndo.run()
                render()
                true
            }
        }
        findViewById<View>(R.id.select_cancel).setOnClickListener { stopSelecting() }
        findViewById<View>(R.id.select_block).setOnClickListener { block(shown?.selectedApps.orEmpty()) }

        search = findViewById(R.id.search)
        findViewById<View>(R.id.search_clear).setOnClickListener { search.setText("") }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                findViewById<View>(R.id.search_clear).visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                render()
            }
        })

        // The service may have died (app update, killed by Android) while the firewall was on.
        if (settings.policy.enabled) {
            if (VpnService.prepare(this) == null) appWall.firewall.ensureRunning() else appWall.firewall.turnOff()
        }
        if (settings.policy.enabled) requestNotificationPermission()

        // Rotation, split-screen, a font change: Android rebuilds the screen. Carry on where it was.
        val kept = lastNonConfigurationInstance as? Kept
        if (kept != null) {
            apps = kept.apps
            lastAttempt = kept.lastAttempt
            packagesSequence = kept.packagesSequence
            selected = kept.selected
            kept.undo?.let(::showUndo)
        } else {
            selected = savedInstanceState?.getStringArray(STATE_SELECTED)?.toMutableSet()
        }
        if (apps == null) loadApps() else revealList(animate = false)
        render()
        if (savedInstanceState == null) askAboutNewApp(intent)
    }

    override fun onRetainNonConfigurationInstance(): Any = Kept(apps, packagesSequence, lastAttempt, selected, undo)

    /** Only the selection outlives the process; the app list reloads and an Undo has expired by then. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selected?.let { outState.putStringArray(STATE_SELECTED, it.toTypedArray()) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideUndo)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askAboutNewApp(intent)
    }

    /** Opened from a "New app" notification: ask Block or Allow right away. Dismissing means allow. */
    private fun askAboutNewApp(intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_NEW_APP) ?: return
        intent.removeExtra(EXTRA_NEW_APP)
        // Home is the launcher screen, so any app can open it with this extra. Only ask when
        // AppWall's own notification for that app is showing: then the tap came from it.
        if (!NewAppNotifier.isShowing(this, packageName)) return
        NewAppNotifier.cancel(this, packageName)
        val info = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return // uninstalled again before the user got here
        }
        val label = info.loadLabel(packageManager)
        AppSheet(
            label = getString(R.string.new_app_label),
            title = getString(R.string.new_app_dialog_title, label),
            body = getString(R.string.new_app_dialog_text),
            choices = listOf(
                AppSheet.Choice(getString(R.string.warn_keep), AppSheet.Role.SECONDARY),
                AppSheet.Choice(getString(R.string.block), AppSheet.Role.MAIN) { appWall.firewall.block(listOf(packageName)) },
            ),
        ).show(this)
    }

    override fun onStart() {
        super.onStart()
        // The firewall's state changes in the service (revoked, failed, started), and rules change
        // from notifications too: show both live.
        subscriptions += appWall.firewallStatus.changed.subscribe(::render)
        subscriptions += settings.policyChanged.subscribe(::render)
        subscriptions += settings.screenPrefsChanged.subscribe(::render)
        lastAttemptLoader.start(appWall.logChanged)
    }

    override fun onStop() {
        lastAttemptLoader.stop()
        subscriptions.forEach(Subscription::unsubscribe)
        subscriptions.clear()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Reload the list if apps were installed or removed while we were away.
        if (apps != null && packageManager.getChangedPackages(packagesSequence) != null) loadApps()
        render()
    }

    private fun loadApps() {
        val sequence = packageManager.getChangedPackages(0)?.sequenceNumber ?: 0
        appWall.apps.load { loaded ->
            if (apps == null) revealList()
            apps = loaded
            packagesSequence = sequence
            render()
        }
    }

    /** Asks the user for VPN permission the first time, then starts the firewall. */
    private fun requestStart() {
        if (appWall.anotherVpnActive()) {
            showOtherVpnDialog()
            render() // flips the switch back off
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            consentRequestedAt = SystemClock.elapsedRealtime()
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            startFirewall()
        }
    }

    @Deprecated("Platform Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            startFirewall()
            return
        }
        // While another app holds Always-on, Android closes the consent dialog before anyone
        // could tap it, and offers no API to ask who holds it. A refusal that fast means that.
        if (SystemClock.elapsedRealtime() - consentRequestedAt < INSTANT_REFUSAL_MS) showOtherVpnDialog()
        render()
    }

    /** Explains that Android allows one VPN at a time and another app has it. */
    private fun showOtherVpnDialog() {
        AppSheet(
            title = getString(R.string.other_vpn_title),
            body = getString(R.string.other_vpn_message),
            choices = listOf(
                AppSheet.Choice(getString(android.R.string.cancel), AppSheet.Role.SECONDARY),
                AppSheet.Choice(getString(R.string.always_on_guide_open), AppSheet.Role.MAIN) {
                    startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                },
            ),
        ).show(this)
    }

    private fun startFirewall() {
        appWall.firewall.turnOn()
        render()
        requestNotificationPermission()
    }

    /** Android 13+ hides our status notification unless the user allows notifications. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-post the status so the notification appears now that it's allowed.
        if (requestCode == REQUEST_NOTIFICATIONS && settings.policy.enabled) sendToService(FirewallVpnService.ACTION_APPLY)
    }

    /** Turning the wall off frees every blocked app at once, so it gets an Undo like any other change. */
    private fun stopFirewall() {
        appWall.firewall.turnOff()
        render()
        showUndo(getString(R.string.undo_wall_off), UndoAction.TurnOn)
    }

    /** A row's button changes its rule (with a few seconds to undo it); a tap on the row opens its log. */
    private fun onRowAction(app: AppEntry, action: AppListAdapter.Action) {
        if (action == AppListAdapter.Action.OPEN) {
            startActivity(AppLogActivity.intent(this, app.packageName))
            return
        }
        if (action == AppListAdapter.Action.BLOCK) {
            block(listOf(app))
            return
        }
        appWall.firewall.allow(listOf(app.packageName))
        render()
        showUndo(getString(R.string.undo_allowed, app.label), UndoAction.Block(listOf(app.packageName)))
    }

    /** Blocks [toBlock], asking first (in a sheet) if any of it is part of Android. */
    private fun block(toBlock: List<AppEntry>) {
        if (toBlock.isEmpty()) return
        val warning = systemAppWarning(toBlock)
        if (warning == null) blockNow(toBlock) else SystemWarningSheet.show(this, warning, ::blockNow)
    }

    /** Blocks them all with one Undo for the lot, and ends choosing several. */
    private fun blockNow(toBlock: List<AppEntry>) {
        val packages = toBlock.map { it.packageName }
        appWall.firewall.block(packages)
        selected = null
        render()
        val message = if (toBlock.size == 1) {
            getString(R.string.undo_blocked, toBlock[0].label)
        } else {
            resources.getQuantityString(R.plurals.undo_blocked_n, toBlock.size, toBlock.size)
        }
        showUndo(message, UndoAction.Allow(packages))
    }

    private fun stopSelecting() {
        selected = null
        render()
    }

    @Deprecated("Platform back handling; still delivered when no back callback is registered")
    override fun onBackPressed() {
        if (selected != null) stopSelecting() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun showUndo(message: String, action: UndoAction) {
        showUndo(PendingUndo(message, action, SystemClock.elapsedRealtime() + UNDO_MS))
    }

    /** Shows [pending] until its time is up; a rebuilt screen shows the rest of that time. */
    private fun showUndo(pending: PendingUndo) {
        val left = pending.until - SystemClock.elapsedRealtime()
        if (left <= 0) return
        undo = pending
        findViewById<TextView>(R.id.undo_message).text = pending.message
        findViewById<View>(R.id.undo_bar).visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, left)
    }

    private fun runUndo(action: UndoAction) {
        when (action) {
            is UndoAction.Block -> appWall.firewall.block(action.packages)
            is UndoAction.Allow -> appWall.firewall.allow(action.packages)
            UndoAction.TurnOn -> requestStart()
        }
        render()
    }

    /** Swaps the loading placeholders for the real list; at once when the list is already known. */
    private fun revealList(animate: Boolean = true) {
        val placeholders = listOf(findViewById<View>(R.id.loading_bar), findViewById(R.id.skeleton))
        if (!animate) {
            placeholders.forEach { it.visibility = View.GONE }
            findViewById<View>(R.id.app_list).alpha = 1f
            return
        }
        placeholders.forEach { view -> view.animate().alpha(0f).withEndAction { view.visibility = View.GONE } }
        findViewById<View>(R.id.app_list).animate().alpha(1f)
    }

    private fun sendToService(action: String) {
        startService(Intent(this, FirewallVpnService::class.java).setAction(action))
    }

    /** Draws the screen from its current inputs. */
    private fun render() {
        val status = appWall.firewallStatus.status
        val state = homeState(
            apps, settings.policy, settings.screenPrefs.showSystemApps, status, appWall.anotherVpnActive(), lastAttempt, query, selected,
        )
        shown = state
        adapter.show(state)

        findViewById<View>(R.id.select_bar).visibility = if (state.selecting) View.VISIBLE else View.GONE
        if (state.selecting) {
            val count = state.selectedApps.size
            findViewById<TextView>(R.id.select_count).text =
                if (count == 0) getString(R.string.select_none) else resources.getQuantityString(R.plurals.select_count, count, count)
            findViewById<Button>(R.id.select_block).apply {
                isEnabled = count > 0
                text = if (count <= 1) getString(R.string.select_block) else resources.getQuantityString(R.plurals.select_block_n, count, count)
                setTextColor(getColor(if (count > 0) R.color.ground else R.color.text_secondary))
            }
        }

        updatingSwitch = true
        firewallSwitch.isChecked = state.switchOn
        updatingSwitch = false

        // The card says what the firewall is actually doing.
        findViewById<ImageView>(R.id.status_dot).setImageLevel(if (state.running) 1 else 0)
        findViewById<TextView>(R.id.wall_state).setText(if (state.running) R.string.wall_up else R.string.wall_off)
        val line = state.cardLine
        findViewById<TextView>(R.id.status_line).apply {
            text = when (line) {
                is CardLine.Blocking -> {
                    val blocked = resources.getQuantityString(R.plurals.headline_blocked, line.apps, line.apps)
                    val last = line.lastAttempt
                    when {
                        line.apps == 0 -> getString(R.string.card_none)
                        last == null -> blocked
                        else -> getString(R.string.card_last_blocked, blocked, TimeText.ago(this@MainActivity, last))
                    }
                }
                CardLine.OtherVpn -> getString(R.string.status_other_vpn_short)
                CardLine.Failed -> getString(R.string.status_off_failed)
                CardLine.Revoked -> getString(R.string.status_off_revoked)
                CardLine.NoPermission -> getString(R.string.status_off_no_permission)
                CardLine.SettingsUnreadable -> getString(R.string.status_off_settings)
                CardLine.Off -> getString(R.string.card_off)
            }
            // While off, blocked apps are online: that's worth the coral.
            setTextColor(getColor(if (line is CardLine.Blocking) R.color.text_secondary else R.color.blocked))
        }
        findViewById<TextView>(R.id.health_notice).apply {
            visibility = if (state.notices.isEmpty()) View.GONE else View.VISIBLE
            text = state.notices.joinToString(" · ") { notice ->
                when (notice) {
                    HomeNotice.LogUnavailable -> getString(R.string.notice_log_unavailable)
                    is HomeNotice.DroppedTries ->
                        resources.getQuantityString(R.plurals.notice_dropped, notice.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), notice.count)
                    HomeNotice.LimitedAttribution -> getString(R.string.notice_limited)
                }
            }
            // Coral only for a real problem; the others are just worth knowing.
            setTextColor(getColor(if (HomeNotice.LogUnavailable in state.notices) R.color.blocked else R.color.text_secondary))
        }
        findViewById<View>(R.id.list_empty).visibility = if (state.noMatch) View.VISIBLE else View.GONE
    }

    /** What Undo puts back: the opposite of the change just made. */
    private sealed interface UndoAction {
        data class Block(val packages: List<String>) : UndoAction
        data class Allow(val packages: List<String>) : UndoAction
        data object TurnOn : UndoAction
    }

    /** The Undo bar's offer, on screen until [until] (elapsed-realtime clock). */
    private data class PendingUndo(val message: String, val action: UndoAction, val until: Long)

    /** What Home hands to its rebuilt self across a configuration change. */
    private class Kept(
        val apps: List<AppEntry>?,
        val packagesSequence: Int,
        val lastAttempt: Long?,
        val selected: MutableSet<String>?,
        val undo: PendingUndo?,
    )

    companion object {
        /** Package name of a just-installed app to ask about; set by the "New app" notification. */
        const val EXTRA_NEW_APP = "dev.appwall.NEW_APP"
        private const val REQUEST_VPN = 1
        private const val REQUEST_NOTIFICATIONS = 2
        private const val UNDO_MS = 5000L
        private const val INSTANT_REFUSAL_MS = 700L
        private const val STATE_SELECTED = "selected"
    }
}
