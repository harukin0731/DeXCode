package com.example.dexcode

import java.net.URI

/** In-memory metadata only: never record OAuth queries, fragments, bodies or console text. */
internal class ConnectionDiagnostics {
    private val lines = ArrayDeque<String>()

    fun record(event: String, url: String? = null) {
        if (lines.size >= 100) lines.removeFirst()
        lines.addLast(event + (url?.let { " ${safeTarget(it)}" } ?: ""))
    }

    fun report(): String = lines.joinToString("\n")

    companion object {
        fun safeTarget(rawUrl: String): String {
            val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return "invalid-url"
            val scheme = uri.scheme?.lowercase()
            if (scheme !in setOf("http", "https")) return "non-http-page"
            val host = uri.host ?: return "invalid-host"
            val route = when {
                uri.path == "/msal" -> "msal"
                uri.path == "/callback" -> "callback"
                uri.path == "/redirect" -> "redirect"
                uri.path.orEmpty().startsWith("/tunnel/") -> "tunnel"
                else -> "page"
            }
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return "$scheme://$host$port ($route)"
        }

        const val SNAPSHOT_SCRIPT = """
            JSON.stringify({
              readyState: document.readyState,
              secureContext: window.isSecureContext === true,
              online: navigator.onLine === true,
              crypto: !!(window.crypto && window.crypto.subtle),
              webSocket: typeof WebSocket === 'function',
              broadcastChannel: typeof BroadcastChannel === 'function',
              serviceWorker: 'serviceWorker' in navigator,
              serviceWorkerControlled: !!(window.isSecureContext && 'serviceWorker' in navigator && navigator.serviceWorker.controller),
              workbench: !!document.querySelector('.monaco-workbench'),
              splash: !!document.getElementById('monaco-parts-splash')
            })
        """
    }
}
