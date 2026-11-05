package com.example.dexcode

import android.content.Context
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.webkit.WebView

/** WebView input surface tuned for a physical keyboard in Samsung DeX. */
class DesktopWebView(context: Context) : WebView(context) {
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = rawPunctuationFor(event, keyCode)
        if (symbol != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val inputConnection = onCreateInputConnection(android.view.inputmethod.EditorInfo())
                if (inputConnection?.commitText(symbol.toString(), 1) == true) return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                return true
            }
        }
        if (isHardwareEditorKey(event, keyCode)) {
            // InputConnection.sendKeyEvent() is an IME text-editing path in WebView. Sending
            // hardware keys through it can leave composing text behind and duplicate later
            // characters. Invoke WebView's normal key path directly so Chromium receives the
            // key event without creating a second composition.
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> super.onKeyDown(keyCode, event)
                KeyEvent.ACTION_UP -> super.onKeyUp(keyCode, event)
                KeyEvent.ACTION_MULTIPLE -> super.onKeyMultiple(
                    keyCode,
                    event.repeatCount,
                    event,
                )
                else -> false
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun isHardwareEditorKey(event: KeyEvent, keyCode: Int): Boolean {
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        return keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
            keyCode == KeyEvent.KEYCODE_MOVE_END ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN
    }

    private fun rawPunctuationFor(event: KeyEvent, keyCode: Int): Char? {
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD ||
            keyCode != KeyEvent.KEYCODE_GRAVE || event.isAltPressed ||
            event.isCtrlPressed || event.isMetaPressed) {
            return null
        }
        return if (event.isShiftPressed) '~' else '`'
    }
}
