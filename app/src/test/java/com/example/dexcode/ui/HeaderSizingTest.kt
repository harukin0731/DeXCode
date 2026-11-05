package com.example.dexcode.ui

import org.junit.Assert.*
import org.junit.Test

class HeaderSizingTest {
    @Test fun smallWindowsRetainTabsAndStatusAccess() {
        val sizing = HeaderSizing.forWidth(320)
        assertTrue(sizing.compactSearch)
        assertTrue(sizing.compactStatus)
        assertTrue(36 + 30 + 34 + sizing.searchWidth + 20 + sizing.statusWidth + 32 + 20 + 70 <= 320)
    }
    @Test fun mediumWindowsKeepSearchAndCompactStatus() {
        val sizing = HeaderSizing.forWidth(500)
        assertFalse(sizing.compactSearch)
        assertTrue(sizing.compactStatus)
        assertEquals(170, sizing.searchWidth)
    }
    @Test fun wideWindowsShowStatusWithoutUnboundedSearchGrowth() {
        val sizing = HeaderSizing.forWidth(1400)
        assertFalse(sizing.compactStatus)
        assertFalse(sizing.compactSearch)
        assertEquals(300, sizing.searchWidth)
        assertEquals(110, sizing.statusWidth)
    }
}
