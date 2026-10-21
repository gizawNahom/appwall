package dev.appwall.core.settings

import dev.appwall.core.firewall.NewAppMode
import dev.appwall.core.health.ProbeResult
import dev.appwall.core.health.Probed
import dev.appwall.core.state.ChangeSignal

/** What the firewall enforces: whether it's on, which apps are blocked, and what to do about new apps. */
data class FirewallPolicy(
    val enabled: Boolean = false,
    val blocked: Set<String> = emptySet(),
    val newAppMode: NewAppMode = NewAppMode.ASK,
    val startOnBoot: Boolean = true,
) {
    fun isBlocked(packageName: String) = packageName in blocked
}

/** Whether blocked tries are logged, and for how long they're kept. */
data class LogSettings(val loggingEnabled: Boolean = true, val retentionDays: Int = 7)

/** How the screens show things; nothing here affects blocking. */
data class ScreenPrefs(val showSystemApps: Boolean = false)

/** The keys in the stored settings file. They predate this code: never rename one. */
object SettingsKeys {
    const val ENABLED = "enabled"
    const val BLOCKED = "blocked"
    const val NEW_APP_MODE = "new_app_mode"
    const val START_ON_BOOT = "start_on_boot"
    const val LOGGING = "logging"
    const val LOG_RETENTION_DAYS = "log_retention_days"
    const val SHOW_SYSTEM_APPS = "show_system_apps"
}

/** Reads the policy from stored values; a missing or mistyped value gets its default. */
fun decodeFirewallPolicy(values: Map<String, Any?>): FirewallPolicy {
    val defaults = FirewallPolicy()
    return FirewallPolicy(
        enabled = values[SettingsKeys.ENABLED] as? Boolean ?: defaults.enabled,
        blocked = when (val stored = values[SettingsKeys.BLOCKED]) {
            is Set<*> -> packageNames(stored)
            else -> defaults.blocked
        },
        newAppMode = newAppModeNamed(values[SettingsKeys.NEW_APP_MODE]) ?: defaults.newAppMode,
        startOnBoot = values[SettingsKeys.START_ON_BOOT] as? Boolean ?: defaults.startOnBoot,
    )
}

/** The package names in a stored set: its strings (anything else in it can't be one). */
private fun packageNames(stored: Set<*>): Set<String> = stored.filterIsInstance<String>().toSet()

/** The new-app mode a stored value names, or null if it names none. */
private fun newAppModeNamed(stored: Any?): NewAppMode? = NewAppMode.entries.firstOrNull { it.name == stored }

fun decodeLogSettings(values: Map<String, Any?>): LogSettings {
    val defaults = LogSettings()
    return LogSettings(
        loggingEnabled = values[SettingsKeys.LOGGING] as? Boolean ?: defaults.loggingEnabled,
        retentionDays = values[SettingsKeys.LOG_RETENTION_DAYS] as? Int ?: defaults.retentionDays,
    )
}

fun decodeScreenPrefs(values: Map<String, Any?>): ScreenPrefs =
    ScreenPrefs(showSystemApps = values[SettingsKeys.SHOW_SYSTEM_APPS] as? Boolean ?: ScreenPrefs().showSystemApps)

/**
 * What's wrong with the stored values, if anything: a known key holding the wrong type, or a
 * new-app mode no version wrote. Missing keys are fine (they get defaults). Decoding is lenient so
 * the screens always work; this strict check is what start-up uses to decide whether to trust it.
 */
fun settingsProblems(values: Map<String, Any?>): List<String> {
    fun wrongType(key: String, ok: (Any) -> Boolean) = values[key]?.let { if (ok(it)) null else "$key is ${it::class.simpleName}" }
    return listOfNotNull(
        wrongType(SettingsKeys.ENABLED) { it is Boolean },
        wrongType(SettingsKeys.BLOCKED) { it is Set<*> && packageNames(it).size == it.size },
        wrongType(SettingsKeys.NEW_APP_MODE) { newAppModeNamed(it) != null },
        wrongType(SettingsKeys.START_ON_BOOT) { it is Boolean },
        wrongType(SettingsKeys.LOGGING) { it is Boolean },
        wrongType(SettingsKeys.LOG_RETENTION_DAYS) { it is Int },
        wrongType(SettingsKeys.SHOW_SYSTEM_APPS) { it is Boolean },
    )
}

interface FirewallPolicyReader : Probed {
    val policy: FirewallPolicy
    val policyChanged: ChangeSignal
}

interface FirewallPolicyWriter {
    fun setEnabled(enabled: Boolean)
    /** Blocks (or allows) all of [packages] in one write. */
    fun setBlocked(packages: Collection<String>, blocked: Boolean)
    fun setNewAppMode(mode: NewAppMode)
    fun setStartOnBoot(start: Boolean)
}

interface LogSettingsReader : Probed {
    val logSettings: LogSettings
    val logSettingsChanged: ChangeSignal
}

interface LogSettingsWriter {
    fun setLoggingEnabled(enabled: Boolean)
    fun setLogRetentionDays(days: Int)
}

interface ScreenPrefsReader : Probed {
    val screenPrefs: ScreenPrefs
    val screenPrefsChanged: ChangeSignal
}

interface ScreenPrefsWriter {
    fun setShowSystemApps(show: Boolean)
}

/**
 * Everything about settings except changing the firewall policy, which goes through
 * [dev.appwall.core.firewall.FirewallCommands] so a change is never saved but not applied.
 */
interface ScreenSettings : FirewallPolicyReader, LogSettingsReader, LogSettingsWriter, ScreenPrefsReader, ScreenPrefsWriter

/** Where settings are kept: a flat file of keys and values (on Android, SharedPreferences). */
interface KeyValueStore {
    fun all(): Map<String, Any?>
    /** Saves these values (Boolean, Int, String or Set<String>), leaving other keys alone. */
    fun write(values: Map<String, Any>)
}

/**
 * The three kinds of settings over one [store]. Reads are snapshots, decoded once and kept up to
 * date by the writers, so they're cheap enough for any thread to read often. Each write call
 * saves once and fires its kind's signal once.
 */
class StoredSettings(private val store: KeyValueStore, newSignal: () -> ChangeSignal) : ScreenSettings, FirewallPolicyWriter {

    override val policyChanged = newSignal()
    override val logSettingsChanged = newSignal()
    override val screenPrefsChanged = newSignal()

    private val values = store.all().toMutableMap()

    @Volatile override var policy = decodeFirewallPolicy(values)
        private set
    @Volatile override var logSettings = decodeLogSettings(values)
        private set
    @Volatile override var screenPrefs = decodeScreenPrefs(values)
        private set

    override fun setEnabled(enabled: Boolean) = write(SettingsKeys.ENABLED to enabled)
    override fun setNewAppMode(mode: NewAppMode) = write(SettingsKeys.NEW_APP_MODE to mode.name)
    override fun setStartOnBoot(start: Boolean) = write(SettingsKeys.START_ON_BOOT to start)

    @Synchronized
    override fun setBlocked(packages: Collection<String>, blocked: Boolean) {
        val updated = if (blocked) policy.blocked + packages else policy.blocked - packages.toSet()
        write(SettingsKeys.BLOCKED to updated)
    }

    /** Checks what's stored now, strictly; one check serves all three kinds. */
    override fun probe(): ProbeResult {
        val problems = settingsProblems(store.all())
        return if (problems.isEmpty()) ProbeResult.Ok else ProbeResult.Failed(problems.joinToString())
    }

    override fun setLoggingEnabled(enabled: Boolean) = write(SettingsKeys.LOGGING to enabled)
    override fun setLogRetentionDays(days: Int) = write(SettingsKeys.LOG_RETENTION_DAYS to days)
    override fun setShowSystemApps(show: Boolean) = write(SettingsKeys.SHOW_SYSTEM_APPS to show)

    @Synchronized
    private fun write(value: Pair<String, Any>) {
        store.write(mapOf(value))
        values += value
        when (value.first) {
            in POLICY_KEYS -> {
                policy = decodeFirewallPolicy(values)
                policyChanged.notifyChanged()
            }
            in LOG_KEYS -> {
                logSettings = decodeLogSettings(values)
                logSettingsChanged.notifyChanged()
            }
            else -> {
                screenPrefs = decodeScreenPrefs(values)
                screenPrefsChanged.notifyChanged()
            }
        }
    }

}

/** Which kind a written key belongs to, so a write updates and signals only that kind. */
private val POLICY_KEYS = setOf(SettingsKeys.ENABLED, SettingsKeys.BLOCKED, SettingsKeys.NEW_APP_MODE, SettingsKeys.START_ON_BOOT)
private val LOG_KEYS = setOf(SettingsKeys.LOGGING, SettingsKeys.LOG_RETENTION_DAYS)
