package com.example.dexcode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.dexcode.R
import com.example.dexcode.WebViewSettings
import org.json.JSONObject
import org.json.JSONTokener

class ClipboardControls(private val activity: AppCompatActivity, private val view: WebView) {
    fun selectionMode(enabled: Boolean) {
        if (!activity.isDestroyed && view.isAttachedToWindow) view.evaluateJavascript(
            "window.__dexcodeSetTerminalSelectionMode && window.__dexcodeSetTerminalSelectionMode($enabled)", null)
    }
    fun copyTerminalSelection() {
        view.requestFocus()
        // evaluateJavascript doesn't await Promises. Poll only the safe status, never the selected text.
        view.evaluateJavascript("window.__dexcodeCopyTerminalSelection ? (window.__dexcodeCopyTerminalSelection(), 'started') : 'missing'") { value ->
            if (value != "\"started\"") { diagnostics(); return@evaluateJavascript }
            view.postDelayed({ checkCopy(0) }, 200)
        }
    }
    private fun checkCopy(attempt: Int) {
        if (activity.isDestroyed || activity.isFinishing) return
        view.evaluateJavascript("window.__dexcodeClipboardDiagnostics && window.__dexcodeClipboardDiagnostics().outcome") { value ->
            when {
                value == "\"written\"" -> Toast.makeText(activity, R.string.terminal_selection_copied, Toast.LENGTH_SHORT).show()
                (value == "\"sending\"" || value == "\"vscode_copy_pending\"") && attempt < 35 -> view.postDelayed({ checkCopy(attempt + 1) }, 200)
                else -> diagnostics()
            }
        }
    }
    fun diagnostics() {
        if (activity.isDestroyed || activity.isFinishing) return
        view.evaluateJavascript("JSON.stringify(window.__dexcodeClipboardDiagnostics ? window.__dexcodeClipboardDiagnostics() : {outcome:'clipboard_script_missing'})") { value ->
            val state = runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()
            val fields = listOf("outcome", "region", "gestures", "attempts", "extracted", "writes", "characters", "terminals", "editorTerminals", "method", "fallbackRequests", "selectionMode")
            val browser = fields.mapNotNull { key -> state?.opt(key)?.let { "$key: $it" } }.joinToString("\n")
            val native = WebViewSettings.clipboardBridge(view)?.diagnostics() ?: "Native bridge: missing"
            val version = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(activity)?.versionName.orEmpty()
            val text = "DeXCode clipboard\n$browser\n$native\nWindow focus: ${view.hasWindowFocus()}\nWebView: $version"
            if (activity.isDestroyed || activity.isFinishing) return@evaluateJavascript
            val dialog = AlertDialog.Builder(activity).setTitle(R.string.clipboard_diagnostics).setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.copy_diagnostics) { _, _ ->
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clipboard diagnostics", text))
                }
            val preferences = activity.getSharedPreferences("desktop_header", 0)
            if (!preferences.getBoolean("terminal_selection_mode", false) && state?.optString("outcome") in listOf("no_selection", "vscode_copy_no_text", "terminal_probe_failed")) {
                dialog.setNegativeButton(R.string.enable_terminal_selection_mode) { _, _ ->
                    preferences.edit().putBoolean("terminal_selection_mode", true).apply()
                    selectionMode(true)
                    com.example.dexcode.runtime.RuntimeState.store.changed()
                    Toast.makeText(activity, R.string.terminal_selection_mode_help, Toast.LENGTH_LONG).show()
                }
            }
            dialog.show()
        }
    }
}
