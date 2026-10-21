package dev.appwall.core.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortCountTest {

    @Test
    fun countsUpToFourDigitsAreShownInFull() {
        listOf(0L to "0", 7L to "7", 999L to "999", 9_999L to "9999").forEach { (n, text) -> assertEquals(text, shortCount(n)) }
    }

    @Test
    fun largerCountsKeepAtMostFourCharactersAndASuffix() {
        val table = listOf(
            10_000L to "10K",
            12_345L to "12.3K",
            99_999L to "99.9K",
            123_456L to "123K",
            999_999L to "999K",
            1_000_000L to "1M",
            1_234_567L to "1.2M",
            45_600_000L to "45.6M",
            999_999_999L to "999M",
            2_500_000_000L to "2.5B",
        )
        table.forEach { (n, text) -> assertEquals("$n", text, shortCount(n)) }
    }

    @Test
    fun neverRoundsUpPastWhatWasCounted() {
        // 999,999 is not yet a million; "1000K" or "1M" would both overstate or overflow.
        assertEquals("999K", shortCount(999_999))
        assertEquals("19.9K", shortCount(19_999))
    }
}
