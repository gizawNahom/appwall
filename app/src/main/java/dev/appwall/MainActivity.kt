package dev.appwall

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var rules: Rules
    private lateinit var log: BlockLog
    private lateinit var adapter: AppListAdapter
    private lateinit var firewallSwitch: Switch
    private lateinit var icons: AppIcons
    private lateinit var search: EditText
    private var tabBeforeSearch = AppListAdapter.Tab.BLOCKED
    private var undo: (() -> Unit)? = null
    private val hideUndo = Runnable { findViewById<View>(R.id.undo_bar).visibility = View.GONE }

    /** Keeps the card's numbers and the list's counts current while the screen is visible. */
    private val handler = Handler(Looper.getMainLooper())
    private val statsRefresher = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, STATS_REFRESH_MS)
        }
    }
    private var updatingSwitch = false
    private var appsLoaded = false
    private var consentRequestedAt = 0L

    /** PackageManager's change counter when the list was last loaded; used to spot installs/uninstalls. */
    private var packagesSequence = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rules = Rules(this)
        log = BlockLog.get(this)
        icons = AppIcons(this)

        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.open_search).setOnClickListener { enterSearch() }
        findViewById<View>(R.id.search_back).setOnClickListener { exitSearch() }
        findViewById<View>(R.id.search_clear).setOnClickListener { search.setText("") }
        findViewById<View>(R.id.undo_button).setOnClickListener {
            undo?.invoke()
            undo = null
            hideUndo.run()
        }
        findViewById<View>(R.id.last_attempt_cell).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        firewallSwitch = findViewById(R.id.firewall_switch)
        firewallSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) requestStart() else stopFirewall()
        }

        adapter = AppListAdapter(this, rules, icons, log) { app, action -> onRowAction(app, action) }
        adapter.setShowSystem(rules.showSystemApps)
        findViewById<ListView>(R.id.app_list).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onAppTapped(this@MainActivity.adapter.getItem(position)) }
        }

        findViewById<View>(R.id.tab_blocked).setOnClickListener { selectTab(AppListAdapter.Tab.BLOCKED) }
        findViewById<View>(R.id.tab_allowed).setOnClickListener { selectTab(AppListAdapter.Tab.ALLOWED) }
        findViewById<View>(R.id.block_app).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, PickerActivity::class.java), REQUEST_PICK)
        }

        search = findViewById(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString().orEmpty())
                findViewById<View>(R.id.search_clear).visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                updateEmptyState()
            }
        })

        findViewById<CheckBox>(R.id.show_system).apply {
            isChecked = rules.showSystemApps
            setOnCheckedChangeListener { _, show ->
                rules.showSystemApps = show
                adapter.setShowSystem(show)
                refreshState()
            }
        }
        selectTab(AppListAdapter.Tab.BLOCKED)

        // The service may have died (app update, killed by Android) while the firewall was on.
        if (rules.enabled) {
            if (VpnService.prepare(this) == null) sendToService(FirewallVpnService.ACTION_APPLY) else rules.enabled = false
        }
        if (rules.enabled) requestNotificationPermission()

        loadApps()
        if (savedInstanceState == null) askAboutNewApp(intent)
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
        NewAppNotifier.cancel(this, packageName)
        val info = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return // uninstalled again before the user got here
        }
        val label = info.loadLabel(packageManager)
        AlertDialog.Builder(this)
            .setIcon(icons[packageName])
            .setTitle(getString(R.string.new_app_dialog_title, label))
            .setMessage(R.string.new_app_dialog_text)
            .setPositiveButton(R.string.block) { _, _ ->
                rules.setBlocked(packageName, true)
                onRulesChanged()
            }
            .setNegativeButton(R.string.allow, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Reload the list if apps were installed or removed while we were away.
        if (appsLoaded && packageManager.getChangedPackages(packagesSequence) != null) loadApps()
        refreshState()
        handler.post(statsRefresher)
    }

    override fun onPause() {
        handler.removeCallbacks(statsRefresher)
        super.onPause()
    }

    private fun loadApps() {
        val sequence = packageManager.getChangedPackages(0)?.sequenceNumber ?: 0
        Thread {
            val loaded = InstalledApps.load(this)
            runOnUiThread {
                adapter.setApps(loaded)
                packagesSequence = sequence
                if (!appsLoaded) revealList()
                appsLoaded = true
                refreshState()
            }
        }.start()
    }

    /** Asks the user for VPN permission the first time, then starts the firewall. */
    private fun requestStart() {
        if (FirewallVpnService.anotherVpnActive(this)) {
            showOtherVpnDialog()
            refreshState() // flips the switch back off
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
        if (requestCode == REQUEST_PICK) {
            if (resultCode == RESULT_OK) blockPicked(data?.getStringArrayExtra(PickerActivity.EXTRA_PACKAGES)?.toList().orEmpty())
            return
        }
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            startFirewall()
            return
        }
        // While another app holds Always-on, Android closes the consent dialog before anyone
        // could tap it, and offers no API to ask who holds it. A refusal that fast means that.
        if (SystemClock.elapsedRealtime() - consentRequestedAt < INSTANT_REFUSAL_MS) showOtherVpnDialog()
        refreshState()
    }

    /** Explains that Android allows one VPN at a time and another app has it. */
    private fun showOtherVpnDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.other_vpn_title)
            .setMessage(R.string.other_vpn_message)
            .setPositiveButton(R.string.always_on_guide_open) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startFirewall() {
        rules.enabled = true
        sendToService(FirewallVpnService.ACTION_APPLY)
        refreshState()
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
        if (requestCode == REQUEST_NOTIFICATIONS && rules.enabled) sendToService(FirewallVpnService.ACTION_APPLY)
    }

    private fun stopFirewall() {
        rules.enabled = false
        sendToService(FirewallVpnService.ACTION_STOP)
        refreshState()
    }

    private fun onRulesChanged() {
        if (rules.enabled) sendToService(FirewallVpnService.ACTION_APPLY)
        adapter.refresh()
        refreshState()
    }

    /** A button in a row: change the app's rule (with a few seconds to undo it), or open its log. */
    private fun onRowAction(app: AppEntry, action: AppListAdapter.Action) {
        val wasBlocked = rules.isBlocked(app.packageName)
        when (action) {
            AppListAdapter.Action.FULL_LOG -> {
                startActivity(AppLogActivity.intent(this, app.packageName))
                return
            }
            AppListAdapter.Action.TOGGLE -> rules.setBlocked(app.packageName, !wasBlocked)
            AppListAdapter.Action.ALLOW -> rules.setBlocked(app.packageName, false)
            AppListAdapter.Action.BLOCK -> rules.setBlocked(app.packageName, true)
        }
        onRulesChanged()
        val message = getString(if (rules.isBlocked(app.packageName)) R.string.undo_blocked else R.string.undo_allowed, app.label)
        showUndo(message) {
            rules.setBlocked(app.packageName, wasBlocked)
            onRulesChanged()
        }
    }

    /** Apps chosen in the picker: block them all, with one Undo for the lot. */
    private fun blockPicked(packages: List<String>) {
        if (packages.isEmpty()) return
        packages.forEach { rules.setBlocked(it, true) }
        onRulesChanged()
        selectTab(AppListAdapter.Tab.BLOCKED)
        val message = if (packages.size == 1) {
            val label = adapter.labelOf(packages[0]) ?: packages[0]
            getString(R.string.undo_blocked, label)
        } else {
            resources.getQuantityString(R.plurals.undo_blocked_n, packages.size, packages.size)
        }
        showUndo(message) {
            packages.forEach { rules.setBlocked(it, false) }
            onRulesChanged()
        }
    }

    private fun showUndo(message: String, action: () -> Unit) {
        undo = action
        findViewById<TextView>(R.id.undo_message).text = message
        findViewById<View>(R.id.undo_bar).visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, UNDO_MS)
    }

    /** Tapping a row opens it in place to show what's known about the app; tapping again closes it. */
    private fun onAppTapped(app: AppEntry) = adapter.toggleExpanded(app)

    private fun selectTab(tab: AppListAdapter.Tab) {
        adapter.tab = tab
        val blocked = tab == AppListAdapter.Tab.BLOCKED
        findViewById<View>(R.id.tab_blocked).isSelected = blocked
        findViewById<View>(R.id.tab_allowed).isSelected = !blocked
        findViewById<View>(R.id.column_today).visibility = if (blocked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.show_system).visibility = if (blocked) View.GONE else View.VISIBLE
        findViewById<View>(R.id.block_app).visibility = if (blocked) View.VISIBLE else View.GONE
        findViewById<ListView>(R.id.app_list).setSelection(0)
        if (tab != AppListAdapter.Tab.SEARCH) tabBeforeSearch = tab
        refreshTabs()
        updateEmptyState()
    }

    /**
     * Search covers every app, so the card and tabs step aside and one results list takes over.
     * The back arrow, or the phone's back gesture, returns to the tab you were on.
     */
    private fun enterSearch() {
        if (adapter.tab == AppListAdapter.Tab.SEARCH) return
        setSearchMode(true)
        adapter.tab = AppListAdapter.Tab.SEARCH
        updateEmptyState()
        search.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitSearch() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        search.clearFocus()
        setSearchMode(false)
        selectTab(tabBeforeSearch)
    }

    private fun setSearchMode(searching: Boolean) {
        val normal = if (searching) View.GONE else View.VISIBLE
        for (id in listOf(R.id.header, R.id.status_card, R.id.tabs, R.id.columns, R.id.block_app)) {
            findViewById<View>(id).visibility = normal
        }
        findViewById<View>(R.id.search_bar).visibility = if (searching) View.VISIBLE else View.GONE
        if (!searching && adapter.tab != AppListAdapter.Tab.BLOCKED) findViewById<View>(R.id.block_app).visibility = View.GONE
    }

    @Deprecated("Platform back handling; still delivered when no back callback is registered")
    override fun onBackPressed() {
        if (adapter.tab == AppListAdapter.Tab.SEARCH) exitSearch() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Swaps the loading placeholders for the real list. */
    private fun revealList() {
        findViewById<View>(R.id.loading_bar).animate().alpha(0f).withEndAction {
            findViewById<View>(R.id.loading_bar).visibility = View.GONE
        }
        findViewById<View>(R.id.skeleton).animate().alpha(0f).withEndAction {
            findViewById<View>(R.id.skeleton).visibility = View.GONE
        }
        findViewById<View>(R.id.app_list).animate().alpha(1f)
    }

    private fun updateEmptyState() {
        val empty = findViewById<TextView>(R.id.list_empty)
        val show = appsLoaded && adapter.count == 0
        empty.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        empty.setText(
            when {
                adapter.tab == AppListAdapter.Tab.SEARCH && search.text.isBlank() -> R.string.search_prompt
                adapter.tab == AppListAdapter.Tab.SEARCH -> R.string.empty_search
                adapter.tab == AppListAdapter.Tab.BLOCKED -> R.string.empty_blocked
                else -> R.string.empty_allowed
            }
        )
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun sendToService(action: String) {
        startService(Intent(this, FirewallVpnService::class.java).setAction(action))
    }

    /** "91 attempts stopped today", with the number in Geist Mono and the main text color. */
    private fun withMonoNumber(text: String, number: String): CharSequence {
        val start = text.indexOf(number)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(FontSpan(resources.getFont(R.font.geist_mono)), start, start + number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text)), start, start + number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun refreshState() {
        updatingSwitch = true
        firewallSwitch.isChecked = rules.enabled
        updatingSwitch = false

        val on = rules.enabled
        val blockedCount = if (appsLoaded) adapter.blockedCount() else rules.blocked().size
        findViewById<ImageView>(R.id.status_dot).setImageLevel(if (on) 1 else 0)
        findViewById<TextView>(R.id.wall_state).setText(if (on) R.string.wall_up else R.string.wall_down)
        findViewById<TextView>(R.id.headline).text = when {
            !on -> getString(R.string.headline_down)
            blockedCount == 0 -> getString(R.string.headline_none)
            else -> resources.getQuantityString(R.plurals.headline_blocked, blockedCount, blockedCount)
        }
        refreshTabs()
        refreshStats()
    }

    /** The parts that change as attempts come in: today's total, the sparkline, last attempt, counts. */
    private fun refreshStats() {
        val today = log.attemptsSince(startOfToday())
        adapter.setAttemptsToday(today)
        val total = today.values.sum()
        val blockedCount = if (appsLoaded) adapter.blockedCount() else rules.blocked().size

        findViewById<TextView>(R.id.attempts_today).text = when {
            rules.enabled -> withMonoNumber(resources.getQuantityString(R.plurals.attempts_today, total, total), total.toString())
            FirewallVpnService.anotherVpnActive(this) -> getString(R.string.status_other_vpn_short)
            else -> resources.getQuantityString(R.plurals.turn_on_to_block, blockedCount, blockedCount)
        }
        val now = System.currentTimeMillis()
        findViewById<SparklineView>(R.id.sparkline).apply {
            values = log.attemptsPerBucket(now - DAY_MS, DAY_MS / SPARK_BARS, SPARK_BARS)
            contentDescription = getString(R.string.sparkline_description)
        }
        findViewById<TextView>(R.id.last_attempt).text = TimeText.shortAgo(this, log.lastAttemptTime(), now)
        findViewById<TextView>(R.id.allowed_count).text = if (appsLoaded) adapter.allowedCount().toString() else "—"
        updateEmptyState()
    }

    private fun refreshTabs() {
        val blocked = if (appsLoaded) adapter.blockedCount() else rules.blocked().size
        val allowed = adapter.allowedCount()
        findViewById<TextView>(R.id.tab_blocked).text = tabLabel(R.string.tab_blocked, blocked)
        findViewById<TextView>(R.id.tab_allowed).text = tabLabel(R.string.tab_allowed, if (appsLoaded) allowed else null)
    }

    /** "Blocked 5", with the count in Geist Mono, secondary color. */
    private fun tabLabel(label: Int, count: Int?): CharSequence =
        if (count == null) getString(label) else FontSpan.labelWithCount(this, getString(label), count)

    companion object {
        /** Package name of a just-installed app to ask about; set by the "New app" notification. */
        const val EXTRA_NEW_APP = "dev.appwall.NEW_APP"
        private const val REQUEST_VPN = 1
        private const val REQUEST_NOTIFICATIONS = 2
        private const val REQUEST_PICK = 3
        private const val STATS_REFRESH_MS = 5000L
        private const val UNDO_MS = 5000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val SPARK_BARS = 12 // two-hour bars over the last day
        private const val INSTANT_REFUSAL_MS = 700L
    }
}
