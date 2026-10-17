package dev.appwall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var rules: Rules
    private lateinit var adapter: AppListAdapter
    private lateinit var firewallSwitch: Switch
    private lateinit var status: TextView
    private lateinit var icons: AppIcons
    private var apps: List<AppEntry> = emptyList()
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

        icons = AppIcons(this)
        adapter = AppListAdapter(this, rules, icons) { onRulesChanged() }
        adapter.setShowSystem(rules.showSystemApps)
        findViewById<ListView>(R.id.app_list).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> this@MainActivity.adapter.toggle(position) }
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = adapter.setQuery(s?.toString().orEmpty())
        })

        findViewById<CheckBox>(R.id.show_system).apply {
            isChecked = rules.showSystemApps
            setOnCheckedChangeListener { _, show ->
                rules.showSystemApps = show
                adapter.setShowSystem(show)
            }
        }

        // The service may have died (app update, killed by Android) while the firewall was on.
        if (rules.enabled) {
            if (VpnService.prepare(this) == null) sendToService(FirewallVpnService.ACTION_APPLY) else rules.enabled = false
        }
        if (rules.enabled) requestNotificationPermission()

        Thread {
            val loaded = InstalledApps.load(this)
            runOnUiThread {
                apps = loaded
                adapter.setApps(loaded)
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
        refreshState()
    }

    /** Rebuilds the wall: one brick per blocked app, including system apps hidden from the list. */
    private fun renderWall() {
        val wall = findViewById<LinearLayout>(R.id.wall)
        wall.removeAllViews()
        val blocked = rules.blocked()
        val blockedApps = apps.filter { it.packageName in blocked }
        for (app in blockedApps) {
            val brick = layoutInflater.inflate(R.layout.item_brick, wall, false)
            brick.findViewById<ImageView>(R.id.brick_icon).setImageDrawable(icons[app.packageName])
            brick.findViewById<TextView>(R.id.brick_label).text = app.label
            brick.contentDescription = getString(R.string.unblock_app, app.label)
            brick.setOnClickListener {
                rules.setBlocked(app.packageName, false)
                adapter.notifyDataSetChanged()
                onRulesChanged()
            }
            wall.addView(brick)
        }
        val loadedAndEmpty = appsLoaded && blockedApps.isEmpty()
        findViewById<View>(R.id.wall_empty).visibility = if (loadedAndEmpty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.wall_scroll).visibility = if (blockedApps.isEmpty()) View.GONE else View.VISIBLE
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
        renderWall()
    }

    private companion object {
        const val REQUEST_VPN = 1
        const val REQUEST_NOTIFICATIONS = 2
    }
}
