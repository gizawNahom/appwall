package dev.appwall

import java.io.File
import org.junit.Assert.assertEquals
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
        assertEquals(listOf("BlockLog.kt"), users.map { it.name }.sorted())
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
        val statics = sources.filter { it.readText().contains("FirewallVpnService.running") || it.readText().contains("alwaysOnStatus()") }
        assertEquals(emptyList<String>(), statics.map { it.name })
    }

    @Test
    fun sourcesWereFound() {
        // Guards the rules above against passing vacuously if the source path is wrong.
        assertEquals(true, sources.any { it.name == "MainActivity.kt" })
    }
}
