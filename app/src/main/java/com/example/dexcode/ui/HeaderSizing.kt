package com.example.dexcode.ui

/** Budget for the custom row after Android's overflow/menu space has been measured. */
data class HeaderSizing(val searchWidth: Int, val statusWidth: Int, val compactSearch: Boolean, val compactStatus: Boolean) {
    companion object {
        fun forWidth(widthDp: Int): HeaderSizing = when {
            widthDp < 460 -> HeaderSizing(36, 32, true, true)
            widthDp < 700 -> HeaderSizing(170, 32, false, true)
            else -> HeaderSizing(minOf(300, widthDp / 3), 110, false, false)
        }
    }
}
