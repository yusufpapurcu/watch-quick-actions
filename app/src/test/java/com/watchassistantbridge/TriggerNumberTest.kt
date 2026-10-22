package com.watchassistantbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerNumberTest {
    @Test
    fun normalizeStripsFormattingButKeepsLeadingPlus() {
        assertEquals("05551234567", TriggerNumber.normalize(" 0555 123-45 (67) "))
        assertEquals("+905551234567", TriggerNumber.normalize("+90 555 123 45 67"))
        assertEquals("905551234567", TriggerNumber.normalize("90+555 123 45 67"))
        assertEquals("", TriggerNumber.normalize("abc"))
    }

    @Test
    fun dummyTriggerMatchesItselfThroughFormatting() {
        for (dialed in listOf("0000", "00-00", " 0 0 0 0 ", "(00) 00", "00.00")) {
            assertTrue("'$dialed' should match", TriggerNumber.matches(dialed, "0000"))
        }
    }

    @Test
    fun dummyTriggerRejectsLongerShorterAndInternationalLookalikes() {
        for (dialed in listOf("00000", "000", "+0000", "0090000", "+90 000", "000000", "")) {
            assertFalse("'$dialed' should not match", TriggerNumber.matches(dialed, "0000"))
        }
    }

    @Test
    fun blankTriggerNeverMatches() {
        for (trigger in listOf("", "   ", "-", "+")) {
            assertFalse(TriggerNumber.matches("", trigger))
            assertFalse(TriggerNumber.matches("0000", trigger))
            assertFalse(TriggerNumber.matches("+", trigger))
        }
    }

    @Test
    fun trunkZeroTriggerMatchesInternationalForm() {
        val trigger = "05551234567"
        assertTrue(TriggerNumber.matches("+90 555 123 45 67", trigger))
        assertTrue(TriggerNumber.matches("0090 555 123 45 67", trigger))
        assertTrue(TriggerNumber.matches("0555 123 45 67", trigger))
        assertFalse(TriggerNumber.matches("+90 555 123 45 68", trigger))
        assertFalse(TriggerNumber.matches("555 123 45 67", trigger))
    }

    @Test
    fun countryCodeMayBeOneToThreeDigits() {
        val trigger = "02071234567"
        assertTrue(TriggerNumber.matches("+1 2071234567", trigger))
        assertTrue(TriggerNumber.matches("+44 2071234567", trigger))
        assertTrue(TriggerNumber.matches("+353 2071234567", trigger))
        assertFalse(TriggerNumber.matches("+3535 2071234567", trigger))
    }

    @Test
    fun internationalTriggerMatchesBothPrefixSpellings() {
        assertTrue(TriggerNumber.matches("00905551234567", "+905551234567"))
        assertTrue(TriggerNumber.matches("+90 555 123 45 67", "+905551234567"))
        assertFalse(TriggerNumber.matches("05551234567", "+905551234567"))
    }

    @Test
    fun nationalNumberWithoutPrefixIsNotStrippedWithoutInternationalMarker() {
        assertFalse(TriggerNumber.matches("905551234567", "05551234567"))
    }
}
