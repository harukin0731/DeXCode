package com.example.dexcode.ui

/** Stable destinations. Unknown or extension-specific controls remain named menu entries. */
object HeaderActions {
    fun isLayout(icon: HeaderIcon) = icon == HeaderIcon.SIDEBAR_LEFT || icon == HeaderIcon.PANEL || icon == HeaderIcon.SIDEBAR_RIGHT

    fun available(icon: HeaderIcon, workbenchReady: Boolean, sourceEnabled: Boolean) =
        if (isLayout(icon)) workbenchReady else sourceEnabled

    fun icon(codicon: String): HeaderIcon? = when {
        codicon.startsWith("codicon-arrow-left") -> HeaderIcon.BACK
        codicon.startsWith("codicon-arrow-right") -> HeaderIcon.FORWARD
        codicon in setOf("codicon-panel-left", "codicon-panel-left-off", "codicon-panel-right", "codicon-panel-right-off") -> HeaderIcon.SIDEBAR_LEFT
        codicon in setOf("codicon-auxiliarybar-left-layout-icon", "codicon-auxiliarybar-right-layout-icon",
            "codicon-auxiliarybar-left-off-layout-icon", "codicon-auxiliarybar-right-off-layout-icon",
            "codicon-auxiliarybar-left-layout-icon-off", "codicon-auxiliarybar-right-layout-icon-off") -> HeaderIcon.SIDEBAR_RIGHT
        codicon == "codicon-panel-layout-icon" || codicon == "codicon-panel-layout-icon-off" -> HeaderIcon.PANEL
        codicon.startsWith("codicon-layout-sidebar-left") -> HeaderIcon.SIDEBAR_LEFT
        codicon.startsWith("codicon-layout-sidebar-right") -> HeaderIcon.SIDEBAR_RIGHT
        codicon == "codicon-layout-panel" || codicon == "codicon-layout-panel-off" -> HeaderIcon.PANEL
        else -> null
    }
}
