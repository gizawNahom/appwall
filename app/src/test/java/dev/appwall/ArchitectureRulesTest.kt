package dev.appwall

import dev.appwall.core.health.Probed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture rules for :app, checked by reading the source (no library needed). Rules get
 * stricter as the migration in docs/feature/app-architecture/feature-delta.md moves code around.
 */
class ArchitectureRulesTest {

    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun onlyTheLogStorageUsesTheDatabase() {
        val users = sources.filter { file -> file.readLines().any { it.startsWith("import android.database") } }
        assertEquals(listOf("AndroidSqlDb.kt"), users.map { it.name }.sorted())
    }

    @Test
    fun sharedObjectsComeFromTheApplication() {
        // Step 4: no process-wide singletons; AppWallApplication builds and hands them out.
        val forbidden = listOf("BlockLog.get(", "InstalledApps.", "object InstalledApps")
        val offenders = sources.flatMap { file ->
            file.readLines().filter { line -> forbidden.any { it in line } }.map { "${file.name}: ${it.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun onlyTheServiceSaysWhetherTheFirewallRuns() {
        // Step 6: the status board is published by FirewallVpnService alone, so no screen can
        // claim "on" from a saved setting; and no static back door to the service remains.
        val publishers = sources.filter { file -> file.readText().contains(Regex("""(firewallStatus|status)\.(publish|stopped)\(""")) }
        assertEquals(listOf("FirewallVpnService.kt"), publishers.map { it.name })
        // Step 8b: the database thread adds the dropped-tries count, and only that.
        val counters = sources.filter { it.readText().contains(".countDropped(") }
        assertEquals(listOf("AppWallApplication.kt"), counters.map { it.name })
        val statics = sources.filter { it.readText().contains("FirewallVpnService.running") || it.readText().contains("alwaysOnStatus()") }
        assertEquals(emptyList<String>(), statics.map { it.name })
    }

    @Test
    fun screensWatchTheLogInsteadOfPolling() {
        // Step 9: screens reload on logChanged; the only delayed tasks left are the undo bar's
        // timeout and the main-thread scheduler itself.
        val delayers = sources.filter { it.readText().contains("postDelayed(") }
        assertEquals(listOf("AppWallApplication.kt", "MainActivity.kt"), delayers.map { it.name }.sorted())
        val mainDelays = File("src/main/java/dev/appwall/MainActivity.kt").readLines().filter { "postDelayed(" in it }
        assertEquals(listOf("handler.postDelayed(hideUndo, UNDO_MS)"), mainDelays.map { it.trim() })
    }

    @Test
    fun settingsGoThroughTheirPorts() {
        // Step 10a: Rules is gone; the tunnel session and screen loaders can only read settings.
        val offenders = sources.filter { it.readText().contains(Regex("""\bRules\(""")) }
        assertEquals(emptyList<String>(), offenders.map { it.name })
        val readOnly = listOf("tunnel/TunnelSession.kt", "state/ScreenLoader.kt").map { File("../core/src/main/kotlin/dev/appwall/core/$it") }
        assertEquals(true, readOnly.all { it.exists() })
        val writing = readOnly.filter { it.readText().contains(Regex("""Writer|StoredSettings|\.set[A-Z]""")) }
        assertEquals(emptyList<String>(), writing.map { it.name })
    }

    @Test
    fun onlyTheCommandsAndTheServiceChangeThePolicy() {
        // Step 10b: screens and notifications go through FirewallCommands, so a rule is never
        // saved but not applied. The service writes too (off on revoke, the notification buttons).
        val writers = sources.filter { it.readText().contains(Regex("""policyWriter|FirewallPolicyWriter""")) }
        assertEquals(listOf("AppWallApplication.kt", "FirewallVpnService.kt"), writers.map { it.name }.sorted())
        val applies = sources.filter { it.readText().contains("ACTION_APPLY") }.map { it.name }.sorted()
        // Left: starting the service (commands, boot), and re-posting its notification once allowed.
        assertEquals(listOf("AppWallApplication.kt", "FirewallVpnService.kt", "MainActivity.kt", "StartupReceiver.kt"), applies)
    }

    @Test
    fun theHomeListOnlyDraws() {
        // Step 14b: the adapter renders HomeState; its rows' data is read by the screen's loader.
        val adapter = File("src/main/java/dev/appwall/AppListAdapter.kt").readText()
        val reaching = listOf("SqlBlockLog", "onDb(", "appWall", "settings.").filter { it in adapter }
        assertEquals(emptyList<String>(), reaching)
    }

    @Test
    fun criticalPortsAreCheckedAtStartUp() {
        // Step 16a: the block log and the settings readers can't be used unchecked. (The readers
        // extend Probed in :core, so the compiler enforces it there.)
        assertTrue(Probed::class.java.isAssignableFrom(SqlBlockLog::class.java))
        val healthWriters = sources.filter { it.readText().contains(".setLogHealth(") }
        assertEquals(listOf("AppWallApplication.kt"), healthWriters.map { it.name })
        // Step 16b: attribution is the service's to say, like whether the firewall runs.
        val attributionWriters = sources.filter { it.readText().contains(".setAttribution(") }
        assertEquals(listOf("FirewallVpnService.kt"), attributionWriters.map { it.name })
    }

    @Test
    fun sourcesWereFound() {
        // Guards the rules above against passing vacuously if the source path is wrong.
        assertEquals(true, sources.any { it.name == "MainActivity.kt" })
    }
}
