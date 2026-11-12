package com.example.dexcode.ui

import org.junit.Assert.*
import org.junit.Test

class MobilePolicyTest {
    @Test fun shortViewportsKeepTheCursorModeToOneRow() {
        assertFalse(MobilePolicy.cursorInline(260))
        assertFalse(MobilePolicy.cursorInline(479))
        assertTrue(MobilePolicy.cursorInline(480))
        assertTrue(MobilePolicy.cursorInline(780))
    }

    @Test fun phoneWithoutPeripheralsGetsTouchControls() {
        assertTrue(MobilePolicy.visible(null,360,false,false))
        assertTrue(MobilePolicy.visible(null,599,false,false))
    }
    @Test fun desktopAndAttachedPeripheralsKeepTheirEditingArea() {
        assertFalse(MobilePolicy.visible(null,1280,false,false))
        assertFalse(MobilePolicy.visible(null,360,true,false))
        assertFalse(MobilePolicy.visible(null,360,false,true))
    }
    @Test fun explicitChoiceOverridesAutomaticDetection() {
        assertTrue(MobilePolicy.visible(true,1280,true,true))
        assertFalse(MobilePolicy.visible(false,360,false,false))
    }
}
