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
import dev.appwall.core.state.AlwaysOn
import dev.appwall.core.state.Subscription

class SettingsActivity : Activity() {

    private lateinit var rules: Rules
    private var statusSubscription: Subscription? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        rules = Rules(this)
        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }

        // Firewall
        bindSwitch(R.id.start_on_boot_row, R.id.start_on_boot, rules.startOnBoot) { rules.startOnBoot = it }
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
        findViewById<RadioGroup>(R.id.new_app_mode).apply {
            check(
                when (rules.newAppMode) {
                    NewAppMode.ASK -> R.id.new_app_ask
                    NewAppMode.ALLOW -> R.id.new_app_allow
                    NewAppMode.BLOCK -> R.id.new_app_block
                }
            )
            setOnCheckedChangeListener { _, id ->
                rules.newAppMode = when (id) {
                    R.id.new_app_allow -> NewAppMode.ALLOW
                    R.id.new_app_block -> NewAppMode.BLOCK
                    else -> NewAppMode.ASK
                }
            }
        }

        // Blocked traffic log
        val log = appWall.log
        bindSwitch(R.id.logging_row, R.id.logging, rules.loggingEnabled) { rules.loggingEnabled = it }
        val segments = listOf(R.id.retention_1 to 1, R.id.retention_7 to 7, R.id.retention_30 to 30)
        fun showRetention() = segments.forEach { (id, days) ->
            findViewById<View>(id).isSelected = days == rules.logRetentionDays
        }
        segments.forEach { (id, days) ->
            findViewById<View>(id).setOnClickListener {
                rules.logRetentionDays = days
                log.prune()
                showRetention()
            }
        }
        showRetention()
        findViewById<View>(R.id.open_log).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<View>(R.id.clear_log).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_text)
                .setPositiveButton(R.string.log_clear) { _, _ ->
                    log.clear()
                    Toast.makeText(this, R.string.settings_log_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // About
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = getString(R.string.settings_version, version)
        findViewById<View>(R.id.source_code).setOnClickListener { openUrl(getString(R.string.repo_url)) }
        findViewById<View>(R.id.license).setOnClickListener { openUrl(getString(R.string.license_url)) }
    }

    override fun onStart() {
        super.onStart()
        statusSubscription = appWall.firewallStatus.changed.subscribe(::showAlwaysOn)
    }

    override fun onStop() {
        statusSubscription?.unsubscribe()
        statusSubscription = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        showAlwaysOn()
        // Coming back from Android's settings: ask the running firewall to re-check Always-on.
        // Its answer arrives through the status signal.
        if (appWall.firewallStatus.status.running) {
            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_REFRESH_STATUS))
        }
    }

    private fun showAlwaysOn() {
        // Text, then the dot and text color: teal when on, coral for a problem, grey otherwise.
        val (text, level) = when (appWall.firewallStatus.status.alwaysOn) {
            AlwaysOn.UNKNOWN -> if (appWall.anotherVpnActive()) {
                R.string.settings_always_on_other_vpn to Level.PROBLEM
            } else {
                R.string.settings_always_on_unknown to Level.NEUTRAL
            }
            AlwaysOn.LOCKDOWN -> R.string.settings_always_on_lockdown to Level.PROBLEM
            AlwaysOn.ON -> R.string.settings_always_on_on to Level.ON
            AlwaysOn.OFF -> R.string.settings_always_on_off to Level.NEUTRAL
        }
        findViewById<TextView>(R.id.always_on_status).apply {
            setText(text)
            setTextColor(getColor(if (level == Level.PROBLEM) R.color.blocked else R.color.text))
            val dot = when (level) {
                Level.ON -> R.drawable.dot_accent
                Level.PROBLEM -> R.drawable.dot_blocked
                Level.NEUTRAL -> R.drawable.dot_neutral
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
        }
    }

    private enum class Level { ON, NEUTRAL, PROBLEM }

    /** A row with a switch at its end: tapping anywhere on the row flips the switch. */
    private fun bindSwitch(rowId: Int, switchId: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val switch = findViewById<Switch>(switchId)
        switch.isChecked = value
        switch.setOnCheckedChangeListener { _, on -> onChange(on) }
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
