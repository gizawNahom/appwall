package dev.appwall

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var rules: Rules
    private lateinit var adapter: AppListAdapter
    private lateinit var firewallSwitch: Switch
    private lateinit var status: TextView
    private var updatingSwitch = false
    private var appsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rules = Rules(this)

        firewallSwitch = findViewById(R.id.firewall_switch)
        status = findViewById(R.id.status)
        firewallSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) requestStart() else stopFirewall()
        }

        adapter = AppListAdapter(this, rules) { onRulesChanged() }
        findViewById<ListView>(R.id.app_list).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> this@MainActivity.adapter.toggle(position) }
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = adapter.setQuery(s?.toString().orEmpty())
        })

        Thread {
            val apps = InstalledApps.load(this)
            runOnUiThread {
                adapter.setApps(apps)
                appsLoaded = true
                refreshState()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    /** Asks the user for VPN permission the first time, then starts the firewall. */
    private fun requestStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
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
        if (resultCode == RESULT_OK) startFirewall() else refreshState()
    }

    private fun startFirewall() {
        rules.enabled = true
        sendToService(FirewallVpnService.ACTION_APPLY)
        refreshState()
    }

    private fun stopFirewall() {
        rules.enabled = false
        sendToService(FirewallVpnService.ACTION_STOP)
        refreshState()
    }

    private fun onRulesChanged() {
        if (rules.enabled) sendToService(FirewallVpnService.ACTION_APPLY)
        refreshState()
    }

    private fun sendToService(action: String) {
        startService(Intent(this, FirewallVpnService::class.java).setAction(action))
    }

    private fun refreshState() {
        updatingSwitch = true
        firewallSwitch.isChecked = rules.enabled
        updatingSwitch = false

        val count = rules.blocked().size
        status.text = when {
            !appsLoaded -> getString(R.string.loading_apps)
            !rules.enabled -> resources.getQuantityString(R.plurals.status_off, count, count)
            count == 0 -> getString(R.string.status_on_none)
            else -> resources.getQuantityString(R.plurals.status_on, count, count)
        }
    }

    private companion object {
        const val REQUEST_VPN = 1
    }
}
