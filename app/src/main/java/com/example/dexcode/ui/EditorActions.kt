package com.example.dexcode.ui

import android.webkit.WebView
import android.widget.Toast
import com.example.dexcode.R

/** User-triggered, page-local controls. No native interface is exposed to the remote page. */
class EditorActions(private val view: WebView) {
    enum class Action { FILES, COMMANDS, TERMINAL }
    private val script by lazy { view.context.assets.open("editor-actions.js").bufferedReader().use { it.readText() } }
    private var busy = false

    fun run(action: Action) {
        if (busy) return
        val url = view.url
        if (url == null || view.progress < 100) {
            Toast.makeText(view.context, R.string.header_editor_loading, Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        view.requestFocus()
        fun fail() {
            busy = false
            Toast.makeText(view.context, R.string.header_action_unavailable, Toast.LENGTH_LONG).show()
        }
        fun evaluate(stage: Int, callback: (String) -> Unit) {
            if (!view.isAttachedToWindow || view.url != url) { busy = false; return }
            view.evaluateJavascript("($script)('${action.name.lowercase(java.util.Locale.ROOT)}', $stage)") { callback(it) }
        }
        fun verify(retry: Boolean) {
            evaluate(2) { result ->
                if (result == "\"ok\"") busy = false
                else if (retry && action != Action.TERMINAL) {
                    evaluate(1) { view.postDelayed({ verify(false) }, 600) }
                } else fail()
            }
        }
        // Give WebView focus before talking to the workbench; clicking native chrome removed it.
        view.post {
            evaluate(0) { result ->
                if (result == "\"not-ready\"" || result == "null") fail()
                else view.postDelayed({ verify(true) }, 400)
            }
        }
    }
}
