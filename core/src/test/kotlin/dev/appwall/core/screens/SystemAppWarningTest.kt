package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemAppWarningTest {

    private val pool = app("8 Ball Pool")
    private val bandlab = app("BandLab")
    private val a11y = app("Accessibility", system = true)
    private val care = app("Device Care", system = true)
    private val ims = app("Carrier Services", system = true)
    private val keys = app("Key Verifier", system = true)

    @Test
    fun onlyAppsThatArePartOfAndroidAreWarnedAbout() {
        assertNull(systemAppWarning(listOf(pool, bandlab)))
        assertNull(systemAppWarning(emptyList()))
        assertEquals(SystemAppWarning(listOf(a11y), emptyList()), systemAppWarning(listOf(a11y))) // one app, on its own row
    }

    @Test
    fun theSafeChoiceBlocksOnlyTheOthers() {
        val mixed = systemAppWarning(listOf(pool, a11y, bandlab))!!
        assertEquals(listOf(a11y), mixed.system)
        assertEquals(listOf(pool, bandlab), mixed.others)
        assertEquals(true, mixed.canBlockOthers)
        assertEquals(false, systemAppWarning(listOf(a11y, care))!!.canBlockOthers) // only system apps: nothing else to block
    }

    @Test
    fun upToThreeAreNamedMoreAreCounted() {
        val table = listOf(
            listOf(a11y) to true,
            listOf(a11y, care) to true,
            listOf(a11y, care, ims) to true,
            listOf(a11y, care, ims, keys) to false,
        )
        for ((system, inline) in table) assertEquals("${system.size} system apps", inline, systemAppWarning(system + pool)!!.namesInline)
    }

    @Test
    fun namesReadLikeASentence() {
        assertEquals("", joinNames(emptyList(), "and"))
        assertEquals("Accessibility", joinNames(listOf("Accessibility"), "and"))
        assertEquals("Accessibility and Device Care", joinNames(listOf("Accessibility", "Device Care"), "and"))
        assertEquals("Accessibility, Device Care and Carrier Services", joinNames(listOf("Accessibility", "Device Care", "Carrier Services"), "and"))
    }

    private fun app(label: String, system: Boolean = false) = AppEntry(label, "pkg." + label.lowercase().replace(" ", ""), system, false)
}
