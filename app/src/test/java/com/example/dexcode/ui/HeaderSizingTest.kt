package com.example.dexcode.ui

import org.junit.Assert.*
import org.junit.Test

class HeaderSizingTest {
    @Test fun workspaceAndExplicitCommandButtonFitAtEverySupportedWidth() {
        // Include each breakpoint and both neighbors: these often expose a clipped menu or tab.
        val widths=(240..1920).filter {it % 40 == 0 || it in listOf(319,321,399,401,639,641,719,721,839,841,959,961,1119,1121,1159,1161,1279,1281)}
        for(width in widths) {
            val s=HeaderSizing.forWidth(width)
            val fixed=16+8+s.searchWidth+36+8+28+(if(s.showHome)36 else 0)+(if(s.showAdd)32 else 0)+
                (if(s.showNavigation)78 else 0)+(if(s.showPanels)118 else 0)+(if(s.showSelection)36 else 0)+s.statusWidth+36
            assertTrue("Workspace tab and menu remain usable at $width (fixed=$fixed)",width-fixed>=64)
        }
    }
    @Test fun narrowWindowsPreserveWorkspaceSearchCommandsAndMenu() {
        val s=HeaderSizing.forWidth(320)
        assertTrue(s.compactSearch);assertTrue(s.compactStatus)
        assertFalse(s.showNavigation);assertFalse(s.showPanels);assertFalse(s.showSelection)
        assertEquals(36,s.searchWidth)
    }
    @Test fun wideWindowsExposeGroupedLayoutAndHistoryControls() {
        val s=HeaderSizing.forWidth(1280)
        assertTrue(s.showNavigation);assertTrue(s.showPanels);assertTrue(s.showSelection)
        assertFalse(s.compactSearch);assertFalse(s.compactStatus)
        assertEquals(280,s.searchWidth)
    }
}
