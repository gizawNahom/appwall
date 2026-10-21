package dev.appwall

import dev.appwall.core.health.Probed
import dev.appwall.storage.SqlBlockLog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture rules for :app, checked by reading the source (no library needed). Rules get
 * stricter as the migration in docs/feature/app-architecture/feature-delta.md moves code around.
 */
class ArchitectureRulesTest {

    private val root = File("src/main/java/dev/appwall")
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** A source's path under dev/appwall, such as "storage/SqlBlockLog.kt", so a rule also pins its package. */
    private val File.where: String get() = relativeTo(root).invariantSeparatorsPath

    @Test
    fun eachPackageMatchesItsFolder() {
        // Kotlin doesn't require it, but finding your way around does.
        val misplaced = sources.filter { file ->
            val folder = file.parentFile.relativeTo(root).invariantSeparatorsPath
            val expected = "package dev.appwall" + if (folder.isEmpty()) "" else "." + folder.replace('/', '.')
            file.readLines().first { it.startsWith("package ") } != expected
        }
        assertEquals(emptyList<String>(), misplaced.map { it.where })
    }

    @Test
    fun theTopPackageHoldsOnlyTheCompositionRoot() {
        // Everything else sits in a package named for its screen or role.
        val top = sources.filter { it.parentFile == root }
        assertEquals(listOf("AppWallApplication.kt"), top.map { it.where })
    }

    @Test
    fun storageAndAppsDontReachIntoScreensOrTheService() {
        // The adapters sit below everything that uses them: they import only :core.
        val upward = sources.filter { it.where.startsWith("storage/") || it.where.startsWith("apps/") }.flatMap { file ->
            file.readLines().filter { it.startsWith("import dev.appwall.") && !it.startsWith("import dev.appwall.core.") }
                .map { "${file.where}: $it" }
        }
        assertEquals(emptyList<String>(), upward)
    }

    @Test
    fun onlyTheLogStorageUsesTheDatabase() {
        val users = sources.filter { file -> file.readLines().any { it.startsWith("import android.database") } }
        assertEquals(listOf("storage/AndroidSqlDb.kt"), users.map { it.where }.sorted())
    }

    @Test
    fun sharedObjectsComeFromTheApplication() {
        // Step 4: no process-wide singletons; AppWallApplication builds and hands them out.
        val forbidden = listOf("BlockLog.get(", "InstalledApps.", "object InstalledApps")
        val offenders = sources.flatMap { file ->
            file.readLines().filter { line -> forbidden.any { it in line } }.map { "${file.where}: ${it.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun onlyTheServiceSaysWhetherTheFirewallRuns() {
        // Step 6: the status board is published by FirewallVpnService alone, so no screen can
        // claim "on" from a saved setting; and no static back door to the service remains.
        val publishers = sources.filter { file -> file.readText().contains(Regex("""(firewallStatus|status)\.(publish|stopped)\(""")) }
        assertEquals(listOf("firewall/FirewallVpnService.kt"), publishers.map { it.where })
        // Step 8b: the database thread adds the dropped-tries count, and only that.
        val counters = sources.filter { it.readText().contains(".countDropped(") }
        assertEquals(listOf("AppWallApplication.kt"), counters.map { it.where })
        val statics = sources.filter { it.readText().contains("FirewallVpnService.running") || it.readText().contains("alwaysOnStatus()") }
        assertEquals(emptyList<String>(), statics.map { it.where })
    }

    @Test
    fun screensWatchTheLogInsteadOfPolling() {
        // Step 9: screens reload on logChanged; the only delayed tasks left are the undo bar's
        // timeout and the main-thread scheduler itself.
        val delayers = sources.filter { it.readText().contains("postDelayed(") }
        assertEquals(listOf("AppWallApplication.kt", "home/HomeActivity.kt"), delayers.map { it.where }.sorted())
        val mainDelays = File(root, "home/HomeActivity.kt").readLines().filter { "postDelayed(" in it }
        assertEquals(listOf("handler.postDelayed(hideUndo, left)"), mainDelays.map { it.trim() })
    }

    @Test
    fun settingsGoThroughTheirPorts() {
        // Step 10a: Rules is gone; the tunnel session and screen loaders can only read settings.
        val offenders = sources.filter { it.readText().contains(Regex("""\bRules\(""")) }
        assertEquals(emptyList<String>(), offenders.map { it.where })
        val readOnly = listOf("tunnel/TunnelSession.kt", "state/ScreenLoader.kt").map { File("../core/src/main/kotlin/dev/appwall/core/$it") }
        assertEquals(true, readOnly.all { it.exists() })
        val writing = readOnly.filter { it.readText().contains(Regex("""Writer|StoredSettings|\.set[A-Z]""")) }
        assertEquals(emptyList<String>(), writing.map { it.where })
    }

    @Test
    fun onlyTheCommandsAndTheServiceChangeThePolicy() {
        // Step 10b: screens and notifications go through FirewallCommands, so a rule is never
        // saved but not applied. The service writes too (off on revoke, the notification buttons).
        val writers = sources.filter { it.readText().contains(Regex("""policyWriter|FirewallPolicyWriter""")) }
        assertEquals(listOf("AppWallApplication.kt", "firewall/FirewallVpnService.kt"), writers.map { it.where }.sorted())
        val applies = sources.filter { it.readText().contains("ACTION_APPLY") }.map { it.where }.sorted()
        // Left: starting the service (commands, boot), and re-posting its notification once allowed.
        assertEquals(listOf("AppWallApplication.kt", "firewall/FirewallVpnService.kt", "firewall/StartupReceiver.kt", "home/HomeActivity.kt"), applies)
    }

    @Test
    fun theHomeListOnlyDraws() {
        // Step 14b: the adapter renders HomeState; its rows' data is read by the screen's loader.
        val adapter = File(root, "home/AppListAdapter.kt").readText()
        val reaching = listOf("SqlBlockLog", "onDb(", "appWall", "settings.").filter { it in adapter }
        assertEquals(emptyList<String>(), reaching)
    }

    @Test
    fun criticalPortsAreCheckedAtStartUp() {
        // Step 16a: the block log and the settings readers can't be used unchecked. (The readers
        // extend Probed in :core, so the compiler enforces it there.)
        assertTrue(Probed::class.java.isAssignableFrom(SqlBlockLog::class.java))
        val healthWriters = sources.filter { it.readText().contains(".setLogHealth(") }
        assertEquals(listOf("AppWallApplication.kt"), healthWriters.map { it.where })
        // Step 16b: attribution is the service's to say, like whether the firewall runs.
        val attributionWriters = sources.filter { it.readText().contains(".setAttribution(") }
        assertEquals(listOf("firewall/FirewallVpnService.kt"), attributionWriters.map { it.where })
    }

    @Test
    fun sqlIsNeverBuiltFromValues() {
        // Values an app controls (its package name, hosts it looks up) must only ever be bound
        // `?` arguments. So: SQL text lives in SqlBlockLog alone, and every statement it runs is a
        // literal or a compile-time constant (const val can't hold run-time data), never a template.
        val sqlWords = Regex("""\b(SELECT|INSERT INTO|UPDATE|DELETE FROM|CREATE|DROP|PRAGMA)\b""")
        val literal = Regex(""""(?:[^"\\]|\\.)*"""")
        val withSql = sources.filter { file -> literal.findAll(file.readText()).any { sqlWords.containsMatchIn(it.value) } }
        assertEquals(listOf("storage/SqlBlockLog.kt"), withSql.map { it.where })

        val log = File(root, "storage/SqlBlockLog.kt").readText()
        val templated = literal.findAll(log).map { it.value }.filter { sqlWords.containsMatchIn(it) && '$' in it }.toList()
        assertEquals(emptyList<String>(), templated)

        val constant = Regex("""[A-Z][A-Z0-9_]*""")
        val allowed = listOf(
            Regex("""(?:"[^"$]*"\s*\+\s*)*"[^"$]*""""), // a literal, maybe split over lines
            constant,
            Regex("""if \([^()]*\) [A-Z][A-Z0-9_]* else [A-Z][A-Z0-9_]*"""), // a choice between two constants
        )
        val calls = Regex("""\bdb\.(query|execute|insert)\(""").findAll(log).toList()
        assertTrue(calls.size > 10) // guards against a regex that silently matches nothing
        val unsafe = calls.map { firstArgument(log, it.range.last + 1) }.filter { arg -> allowed.none { it.matches(arg) } }
        assertEquals(emptyList<String>(), unsafe)
    }

    /** The source text of the first argument of the call whose `(` ends just before [start]. */
    private fun firstArgument(text: String, start: Int): String {
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> {}
                c == '(' || c == '[' || c == '{' -> depth++
                (c == ')' || c == ']' || c == '}') && depth > 0 -> depth--
                (c == ',' || c == ')') && depth == 0 -> return text.substring(start, i).trim()
            }
            i++
        }
        return text.substring(start).trim()
    }

    @Test
    fun everyQuestionIsAskedInTheSheet() {
        // One way to ask something, so every question looks and behaves the same (AppSheet).
        val dialogs = sources.filter { it.readText().contains("AlertDialog") }
        assertEquals(emptyList<String>(), dialogs.map { it.where })
    }

    @Test
    fun sourcesWereFound() {
        // Guards the rules above against passing vacuously if the source path is wrong.
        assertEquals(true, sources.any { it.where == "home/HomeActivity.kt" })
    }
}
