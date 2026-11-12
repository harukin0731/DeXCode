package com.example.dexcode.ui

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class MobileKeyCodesTest {
    @Test fun shortcutKeysHaveLinuxEvdevScanCodesForChromiumDomCode() {
        assertEquals(31,MobileKeyCodes.scanCode(KeyEvent.KEYCODE_S))
        assertEquals(44,MobileKeyCodes.scanCode(KeyEvent.KEYCODE_Z))
        assertEquals(46,MobileKeyCodes.scanCode(KeyEvent.KEYCODE_C))
        assertEquals(47,MobileKeyCodes.scanCode(KeyEvent.KEYCODE_V))
        assertEquals(30,MobileKeyCodes.scanCode(KeyEvent.KEYCODE_A))
    }
    @Test fun navigationAndIndentKeysHaveDistinctNativeCodes() {
        val keys=listOf(KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_TAB,KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_DOWN)
        val scans=keys.map(MobileKeyCodes::scanCode)
        assertEquals(listOf(1,15,103,105,106,108),scans)
        assertEquals(scans.size,scans.toSet().size)
        assertEquals(0,MobileKeyCodes.scanCode(KeyEvent.KEYCODE_UNKNOWN))
    }
}
