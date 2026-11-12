package com.example.dexcode.ui

object MobilePolicy {
    fun cursorInline(availableHeightDp: Int): Boolean = availableHeightDp >= 480
    fun visible(override: Boolean?, widthDp: Int, hasKeyboard: Boolean, hasMouse: Boolean): Boolean =
        override ?: (widthDp < 600 && !hasKeyboard && !hasMouse)
}
