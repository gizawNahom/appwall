package dev.appwall.core.screens

import dev.appwall.core.firewall.NewAppMode
import dev.appwall.core.settings.FirewallPolicy
import dev.appwall.core.settings.LogSettings
import dev.appwall.core.settings.ScreenPrefs
import dev.appwall.core.state.AlwaysOn

/** What the Always-on VPN row says. */
enum class AlwaysOnText { ON, OFF, LOCKDOWN, UNKNOWN, OTHER_VPN }

/** How a status reads at a glance: on (accent), neutral (grey) or a problem (coral). */
enum class StatusLevel { ON, NEUTRAL, PROBLEM }

data class SettingsState(
    val startOnBoot: Boolean,
    val newAppMode: NewAppMode,
    val loggingEnabled: Boolean,
    val retentionDays: Int,
    val alwaysOnText: AlwaysOnText,
    val alwaysOnLevel: StatusLevel,
    val showSystemApps: Boolean,
)

/**
 * The Settings screen. Always-on comes from the running service ([AlwaysOn.UNKNOWN] when it isn't
 * running); when unknown because another VPN holds the slot, that's the problem worth showing.
 * Lockdown counts as a problem: it cuts every app off whenever AppWall isn't running.
 */
fun settingsState(
    policy: FirewallPolicy,
    log: LogSettings,
    alwaysOn: AlwaysOn,
    anotherVpnActive: Boolean,
    screen: ScreenPrefs,
): SettingsState {
    val (text, level) = when (alwaysOn) {
        AlwaysOn.UNKNOWN -> if (anotherVpnActive) AlwaysOnText.OTHER_VPN to StatusLevel.PROBLEM else AlwaysOnText.UNKNOWN to StatusLevel.NEUTRAL
        AlwaysOn.LOCKDOWN -> AlwaysOnText.LOCKDOWN to StatusLevel.PROBLEM
        AlwaysOn.ON -> AlwaysOnText.ON to StatusLevel.ON
        AlwaysOn.OFF -> AlwaysOnText.OFF to StatusLevel.NEUTRAL
    }
    return SettingsState(policy.startOnBoot, policy.newAppMode, log.loggingEnabled, log.retentionDays, text, level, screen.showSystemApps)
}
