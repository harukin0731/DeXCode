package com.example.dexcode.ui

import org.junit.Assert.*
import org.junit.Test

class HeaderActionsTest {
    @Test fun modernRegisteredIconAliasesUseTheSameActions() {
        for (icon in listOf("codicon-panel-left", "codicon-panel-left-off", "codicon-panel-right", "codicon-panel-right-off"))
            assertEquals(HeaderIcon.SIDEBAR_LEFT, HeaderActions.icon(icon))
        for (icon in listOf("codicon-panel-layout-icon", "codicon-panel-layout-icon-off"))
            assertEquals(HeaderIcon.PANEL, HeaderActions.icon(icon))
        for (icon in listOf("codicon-auxiliarybar-right-layout-icon", "codicon-auxiliarybar-right-layout-icon-off", "codicon-auxiliarybar-left-layout-icon",
            "codicon-auxiliarybar-right-off-layout-icon", "codicon-auxiliarybar-left-off-layout-icon"))
            assertEquals(HeaderIcon.SIDEBAR_RIGHT, HeaderActions.icon(icon))
    }
    @Test fun fixedLayoutButtonsDoNotDependOnMirroredIcons() {
        for (icon in listOf(HeaderIcon.SIDEBAR_LEFT, HeaderIcon.PANEL, HeaderIcon.SIDEBAR_RIGHT)) {
            assertTrue(HeaderActions.available(icon, workbenchReady = true, sourceEnabled = false))
            assertFalse(HeaderActions.available(icon, workbenchReady = false, sourceEnabled = true))
        }
        assertFalse(HeaderActions.available(HeaderIcon.BACK, workbenchReady = true, sourceEnabled = false))
        assertTrue(HeaderActions.available(HeaderIcon.BACK, workbenchReady = true, sourceEnabled = true))
    }
    @Test fun onAndOffPanelVariantsShareOneStableSlot() {
        assertEquals(HeaderIcon.SIDEBAR_LEFT,HeaderActions.icon("codicon-layout-sidebar-left"))
        assertEquals(HeaderIcon.SIDEBAR_LEFT,HeaderActions.icon("codicon-layout-sidebar-left-off"))
        assertEquals(HeaderIcon.SIDEBAR_RIGHT,HeaderActions.icon("codicon-layout-sidebar-right-off"))
        assertEquals(HeaderIcon.PANEL,HeaderActions.icon("codicon-layout-panel-off"))
    }
    @Test fun arbitraryExtensionAndLayoutCommandsAreNotMislabelledAsPanelToggles() {
        assertNull(HeaderActions.icon("codicon-layout-panel-center"))
        assertNull(HeaderActions.icon("codicon-copilot"))
        assertNull(HeaderActions.icon(""))
        assertEquals(HeaderIcon.BACK,HeaderActions.icon("codicon-arrow-left"))
        assertEquals(HeaderIcon.FORWARD,HeaderActions.icon("codicon-arrow-right"))
    }
}
