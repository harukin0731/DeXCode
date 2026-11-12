package com.example.dexcode.ui

/** Prioritize workspace identity and explicit commands; secondary controls move into the menu. */
data class HeaderSizing(
    val searchWidth: Int, val statusWidth: Int, val compactSearch: Boolean, val compactStatus: Boolean,
    val showNavigation: Boolean = false, val showPanels: Boolean = false,
    val showSelection: Boolean = false, val showAdd: Boolean = true, val showHome: Boolean = true,
) {
    companion object {
        fun forWidth(widthDp: Int): HeaderSizing {
            val search = when { widthDp < 640 -> 36; widthDp < 960 -> 160; widthDp < 1280 -> 220; else -> 280 }
            val status = when { widthDp < 320 -> 0; widthDp < 1160 -> 28; else -> 76 }
            return HeaderSizing(search, status, widthDp < 640, widthDp < 1160,
                widthDp >= 1120, widthDp >= 840, widthDp >= 720, widthDp >= 640, widthDp >= 400)
        }
    }
}
