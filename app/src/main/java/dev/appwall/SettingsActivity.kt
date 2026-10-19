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

class SettingsActivity : Activity() {

    private lateinit var rules: Rules

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        rules = Rules(this)

        // Firewall
        findViewById<Switch>(R.id.start_on_boot).apply {
            isChecked = rules.startOnBoot
            setOnCheckedChangeListener { _, on -> rules.startOnBoot = on }
        }
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
        val log = BlockLog.get(this)
        findViewById<Switch>(R.id.logging).apply {
            isChecked = rules.loggingEnabled
            setOnCheckedChangeListener { _, on -> rules.loggingEnabled = on }
        }
        findViewById<RadioGroup>(R.id.retention).apply {
            check(
                when (rules.logRetentionDays) {
                    1 -> R.id.retention_1
                    30 -> R.id.retention_30
                    else -> R.id.retention_7
                }
            )
            setOnCheckedChangeListener { _, id ->
                rules.logRetentionDays = when (id) {
                    R.id.retention_1 -> 1
                    R.id.retention_30 -> 30
                    else -> 7
                }
                log.prune()
            }
        }
        findViewById<View>(R.id.clear_log).setOnClickListener {
            log.clear()
            Toast.makeText(this, R.string.settings_log_cleared, Toast.LENGTH_SHORT).show()
        }

        // About
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = getString(R.string.settings_version, version)
        findViewById<View>(R.id.source_code).setOnClickListener { openUrl(getString(R.string.repo_url)) }
        findViewById<View>(R.id.license).setOnClickListener { openUrl(getString(R.string.license_url)) }
    }

    override fun onResume() {
        super.onResume()
        // Refreshed on every return, so coming back from Android's settings shows the result.
        val status = FirewallVpnService.alwaysOnStatus()
        findViewById<TextView>(R.id.always_on_status).text = when {
            status == null && FirewallVpnService.anotherVpnActive(this) -> getString(R.string.settings_always_on_other_vpn)
            status == null -> getString(R.string.settings_always_on_unknown)
            status.blocksWithoutVpn -> getString(R.string.settings_always_on_lockdown)
            status.alwaysOn -> getString(R.string.settings_always_on_on)
            else -> getString(R.string.settings_always_on_off)
        }
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
