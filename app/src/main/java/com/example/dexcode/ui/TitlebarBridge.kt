package com.example.dexcode.ui

import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import com.example.dexcode.R
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

/** Keep the original workbench DOM alive, but expose its title controls in native window chrome. */
class TitlebarBridge(private val view: WebView) {
    data class Control(val id: Int, val label: String, val icon: String, val enabled: Boolean)
    private val script by lazy { view.context.assets.open("titlebar-controls.js").bufferedReader().use { it.readText() } }
    private val layoutScript by lazy { view.context.assets.open("layout-controls.js").bufferedReader().use { it.readText() } }
    private var toggling = false
    private val frame get() = view.parent as? FrameLayout
    private var crop = 0
    private var pending = false
    private var page: String? = null
    var layoutReady = false
        private set
    var enabled = true
        set(value) { field = value; if (!value) applyCrop(0) }

    init {
        frame?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCrop(crop) }
    }

    fun refresh(callback: (List<Control>) -> Unit) {
        if (!view.isAttachedToWindow) return
        if (view.progress < 100) { layoutReady = false; applyCrop(0); callback(emptyList()); return }
        if (pending) return
        pending = true
        val expectedUrl = view.url
        view.evaluateJavascript("($script)('snapshot', ${if (enabled) 1 else 0})") { result ->
            pending = false
            if (view.url != expectedUrl || !view.isAttachedToWindow) { layoutReady = false; applyCrop(0); return@evaluateJavascript }
            val snapshot = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
            page = expectedUrl
            layoutReady = snapshot?.optBoolean("ready", false) == true
            val cssWidth = snapshot?.optDouble("width", 0.0) ?: 0.0
            val cssHeight = snapshot?.optDouble("height", 0.0) ?: 0.0
            val controls = snapshot?.optJSONArray("controls")
            val items = buildList {
                if (controls != null) for (i in 0 until controls.length()) {
                    val item = controls.getJSONObject(i)
                    add(Control(item.getInt("id"), item.getString("label"), item.optString("icon"), item.optBoolean("enabled")))
                }
            }
            val pixels = if (enabled && cssWidth > 0 && items.isNotEmpty()) (cssHeight * view.width / cssWidth).roundToInt() else 0
            applyCrop(pixels.coerceIn(0, (96 * view.resources.displayMetrics.density).roundToInt()))
            callback(items)
        }
    }

    fun click(control: Control) {
        if (view.url != page || !control.enabled) return
        view.requestFocus()
        val action = when (HeaderActions.icon(control.icon)) {
            HeaderIcon.SIDEBAR_LEFT -> "sidebar"
            HeaderIcon.PANEL -> "panel"
            HeaderIcon.SIDEBAR_RIGHT -> "auxiliary"
            else -> null
        }
        if (action != null) {
            toggleLayout(action, control.id)
            return
        }
        view.evaluateJavascript("($script)('click', ${control.id})") { result ->
            if (result != "\"sent\"") Toast.makeText(view.context, R.string.header_action_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Fixed layout actions must work even when VS Code hides or changes its titlebar icons. */
    fun clickLayout(icon: HeaderIcon) {
        if (!layoutReady || view.url != page) return
        val action = when (icon) {
            HeaderIcon.SIDEBAR_LEFT -> "sidebar"
            HeaderIcon.PANEL -> "panel"
            HeaderIcon.SIDEBAR_RIGHT -> "auxiliary"
            else -> return
        }
        view.requestFocus()
        toggleLayout(action, 0)
    }

    private fun toggleLayout(action: String, id: Int) {
        if (toggling) return
        toggling = true
        val expectedUrl = view.url
        fun run(stage: Int) {
            if (!view.isAttachedToWindow || view.url != expectedUrl) { toggling = false; return }
            view.evaluateJavascript("($layoutScript)('$action', $stage, $id)") { result ->
                if (view.url != expectedUrl) { toggling = false; return@evaluateJavascript }
                if (result == "\"sent\"" && stage < 2) {
                    view.postDelayed({ run(stage + 1) }, 300)
                } else {
                    toggling = false
                    if (result != "\"ok\"") Toast.makeText(view.context, R.string.header_action_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
        view.post { run(0) }
    }

    private fun applyCrop(pixels: Int) {
        crop = if (enabled) pixels else 0
        val parent = frame ?: return
        if (parent.height <= 0) return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val height = if (crop > 0) parent.height + crop else FrameLayout.LayoutParams.MATCH_PARENT
        if (params.height == height && params.topMargin == -crop) return
        params.height = height
        params.topMargin = -crop
        view.layoutParams = params
        // Enlarging the real WebView viewport lets VS Code relayout normally, including its status bar.
        // The parent clips only the original titlebar; no CSS grid or remote settings are modified.
        parent.clipChildren = true
    }
}
