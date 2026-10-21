package dev.appwall

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.appwall.core.firewall.NewAppMode
import dev.appwall.core.screens.AlwaysOnText
import dev.appwall.core.screens.StatusLevel
import dev.appwall.core.screens.settingsState
import dev.appwall.core.state.Subscription

/** Settings; what it shows comes from [settingsState], and changes go to settings or commands. */
class SettingsActivity : Activity() {

    private val settings get() = appWall.settings
    private var statusSubscription: Subscription? = null
    private val retentionSegments = listOf(R.id.retention_1 to 1, R.id.retention_7 to 7, R.id.retention_30 to 30)
    private val newAppModes = listOf(R.id.new_app_ask to NewAppMode.ASK, R.id.new_app_allow to NewAppMode.ALLOW, R.id.new_app_block to NewAppMode.BLOCK)
    /** True while [render] sets the controls, so their listeners don't save what was just shown. */
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }

        // Firewall
        bindSwitch(R.id.start_on_boot_row, R.id.start_on_boot) { appWall.firewall.setStartOnBoot(it) }
        // Apps can't turn on Always-on VPN themselves, and Android has no link to one VPN's own
        // page, only to the list of VPNs. So explain the steps first, then open that list.
        findViewById<View>(R.id.always_on).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.always_on_guide_title)
                .setMessage(R.string.always_on_guide_steps)
                .setPositiveButton(R.string.always_on_guide_open) { _, _ -> open(Intent(Settings.ACTION_VPN_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // New apps
        findViewById<RadioGroup>(R.id.new_app_mode).setOnCheckedChangeListener { _, id ->
            if (rendering) return@setOnCheckedChangeListener
            newAppModes.firstOrNull { it.first == id }?.let { appWall.firewall.setNewAppMode(it.second) }
        }

        // Blocked traffic log
        bindSwitch(R.id.logging_row, R.id.logging) { settings.setLoggingEnabled(it) }
        bindSwitch(R.id.show_system_row, R.id.show_system) { settings.setShowSystemApps(it) }
        retentionSegments.forEach { (id, days) ->
            findViewById<View>(id).setOnClickListener {
                settings.setLogRetentionDays(days)
                onDb({ it.prune() }) {}
                render()
            }
        }
        findViewById<View>(R.id.open_log).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<View>(R.id.clear_log).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_text)
                .setPositiveButton(R.string.log_clear) { _, _ ->
                    onDb({ it.clear() }) { Toast.makeText(this, R.string.settings_log_cleared, Toast.LENGTH_SHORT).show() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // About
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = getString(R.string.settings_version, version)
        findViewById<View>(R.id.source_code).setOnClickListener { openUrl(getString(R.string.repo_url)) }
        findViewById<View>(R.id.license).setOnClickListener { openUrl(getString(R.string.license_url)) }
        render()
    }

    override fun onStart() {
        super.onStart()
        statusSubscription = appWall.firewallStatus.changed.subscribe(::render)
    }

    override fun onStop() {
        statusSubscription?.unsubscribe()
        statusSubscription = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
        // Coming back from Android's settings: ask the running firewall to re-check Always-on.
        // Its answer arrives through the status signal.
        if (appWall.firewallStatus.status.running) {
            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_REFRESH_STATUS))
        }
    }

    private fun render() {
        val status = appWall.firewallStatus.status
        val state = settingsState(settings.policy, settings.logSettings, status.alwaysOn, appWall.anotherVpnActive(), settings.screenPrefs)
        rendering = true
        findViewById<Switch>(R.id.start_on_boot).isChecked = state.startOnBoot
        findViewById<RadioGroup>(R.id.new_app_mode).check(newAppModes.first { it.second == state.newAppMode }.first)
        findViewById<Switch>(R.id.logging).isChecked = state.loggingEnabled
        findViewById<Switch>(R.id.show_system).isChecked = state.showSystemApps
        retentionSegments.forEach { (id, days) -> findViewById<View>(id).isSelected = days == state.retentionDays }
        rendering = false

        // Text, then the dot and text color: teal when on, coral for a problem, grey otherwise.
        findViewById<TextView>(R.id.always_on_status).apply {
            setText(
                when (state.alwaysOnText) {
                    AlwaysOnText.ON -> R.string.settings_always_on_on
                    AlwaysOnText.OFF -> R.string.settings_always_on_off
                    AlwaysOnText.LOCKDOWN -> R.string.settings_always_on_lockdown
                    AlwaysOnText.UNKNOWN -> R.string.settings_always_on_unknown
                    AlwaysOnText.OTHER_VPN -> R.string.settings_always_on_other_vpn
                }
            )
            setTextColor(getColor(if (state.alwaysOnLevel == StatusLevel.PROBLEM) R.color.blocked else R.color.text))
            val dot = when (state.alwaysOnLevel) {
                StatusLevel.ON -> R.drawable.dot_accent
                StatusLevel.PROBLEM -> R.drawable.dot_blocked
                StatusLevel.NEUTRAL -> R.drawable.dot_neutral
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
        }
    }

    /** A row with a switch at its end: tapping anywhere on the row flips the switch. */
    private fun bindSwitch(rowId: Int, switchId: Int, onChange: (Boolean) -> Unit) {
        val switch = findViewById<Switch>(switchId)
        switch.setOnCheckedChangeListener { _, on -> if (!rendering) onChange(on) }
        findViewById<View>(rowId).setOnClickListener { switch.toggle() }
    }

    private fun openUrl(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }
}
