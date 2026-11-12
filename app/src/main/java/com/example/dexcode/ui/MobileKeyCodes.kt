package com.example.dexcode.ui

import android.view.KeyEvent

/** Linux evdev scan codes (AOSP KeyEvent contract / Chromium dom_code_data.inc). */
object MobileKeyCodes {
    fun scanCode(key: Int): Int = when(key) {
        KeyEvent.KEYCODE_ESCAPE -> 1
        KeyEvent.KEYCODE_TAB -> 15
        KeyEvent.KEYCODE_A -> 30
        KeyEvent.KEYCODE_S -> 31
        KeyEvent.KEYCODE_Z -> 44
        KeyEvent.KEYCODE_C -> 46
        KeyEvent.KEYCODE_V -> 47
        KeyEvent.KEYCODE_DPAD_UP -> 103
        KeyEvent.KEYCODE_DPAD_LEFT -> 105
        KeyEvent.KEYCODE_DPAD_RIGHT -> 106
        KeyEvent.KEYCODE_DPAD_DOWN -> 108
        else -> 0
    }
}
