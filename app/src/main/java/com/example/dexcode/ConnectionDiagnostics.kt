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

        private val consolePatterns = linkedMapOf(
            "remote-authority" to Regex("remoteauthority|remote authority|resolvererror|no resolver"),
            "worker-startup" to Regex("worker|extension host"),
            "worker-timeout" to Regex("worker.{0,100}(not started|timed out|timeout)|extension host.{0,100}(not started|timed out|timeout)"),
            "extension-fetch" to Regex("failed to fetch.{0,100}extension|error.{0,100}(fetching|loading|scanning).{0,100}extension"),
            "network" to Regex("network|failed to fetch|load failed"),
            "websocket" to Regex("websocket|socket closed"),
            "cors" to Regex("cors|access-control-allow-origin"),
            "csp" to Regex("content security policy|content-security-policy|refused to"),
            "isolation" to Regex("sharedarraybuffer|cross.origin.isolat|cross-origin-(opener|embedder)-policy"),
            "authentication" to Regex("authentication|unauthori[sz]ed|sign.in|oauth|msal|access denied"),
            "storage" to Regex("indexeddb|localstorage|cookie|storage access|quotaexceeded"),
            "crypto" to Regex("crypto|decrypt|encrypt|subtle"),
            "tunnel" to Regex("tunnel|relay"),
            "timeout" to Regex("timeout|timed out"),
            "module" to Regex("module script|dynamic import|mime type"),
            "javascript" to Regex("typeerror|referenceerror|syntaxerror|not implemented|not supported|undefined|exception"),
            "permissions" to Regex("permissions.policy|notallowederror|securityerror"),
        )

        private val chromiumCodes = setOf(
            "FAILED", "ABORTED", "ACCESS_DENIED", "BLOCKED_BY_CLIENT", "BLOCKED_BY_RESPONSE",
            "CONNECTION_CLOSED", "CONNECTION_RESET", "CONNECTION_REFUSED", "NAME_NOT_RESOLVED",
            "TIMED_OUT", "INTERNET_DISCONNECTED", "CERT_AUTHORITY_INVALID", "SSL_PROTOCOL_ERROR",
            "HTTP2_PROTOCOL_ERROR", "NETWORK_CHANGED", "INVALID_RESPONSE",
        )
        private val codePattern = Regex("net::err_([a-z0-9_]+)")

        fun consoleDetails(message: String): String {
            val text = message.lowercase(java.util.Locale.ROOT)
            val tags = consolePatterns.filterValues { it.containsMatchIn(text) }.keys
            val error = codePattern.find(text)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)
                ?.takeIf { it in chromiumCodes }
            val registrationTimeout = "waiting for authentication provider" in text && "to register" in text
            val category = (tags + if (registrationTimeout) setOf("auth-provider-registration") else emptySet())
                .joinToString(",").ifEmpty { "other" }
            return if (error == null) "category=$category" else "category=$category net=ERR_$error"
        }

        private val extensionHostResourcePattern = Regex(
            "https://v--[a-z0-9]{52}\\.vscode-cdn\\.net/(stable|insider)/[a-f0-9]{40}/out/vs/workbench/services/extensions/worker/webWorkerExtensionHostIframe\\.html",
        )

        fun probeExtensionHost(resource: String): String {
            if (!extensionHostResourcePattern.matches(resource)) return "unavailable"
            val connection = java.net.URL(resource).openConnection() as javax.net.ssl.HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                val code = connection.responseCode
                if (code != 200) return "http=$code"
                val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(16_384)
                    var offset = 0
                    while (offset < buffer.size) {
                        val count = reader.read(buffer, offset, buffer.size - offset)
                        if (count <= 0) break
                        offset += count
                    }
                    String(buffer, 0, offset)
                }
                return "http=200 bootstrapScript=${"vscode.bootstrap.nls" in html}"
            } catch (_: java.net.UnknownHostException) {
                return "dns-failed"
            } catch (_: javax.net.ssl.SSLException) {
                return "tls-failed"
            } catch (_: java.net.SocketTimeoutException) {
                return "timeout"
            } catch (_: java.io.IOException) {
                return "io-failed"
            } finally {
                connection.disconnect()
            }
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
              splash: !!document.getElementById('monaco-parts-splash'),
              crossOriginIsolated: window.crossOriginIsolated === true,
              worker: typeof Worker === 'function',
              extensionHostFrame: Array.from(document.querySelectorAll('iframe')).some(
                frame => frame.src.includes('/webWorkerExtensionHostIframe.html')),
              extensionHostResource: Array.from(document.querySelectorAll('iframe'))
                .map(frame => frame.src.split(/[?#]/, 1)[0])
                .find(src => src.endsWith('/webWorkerExtensionHostIframe.html')) || '',
              extensionHostStartup: typeof window.__dexcodeExtensionHostStartup === "function" ? window.__dexcodeExtensionHostStartup() : null,
              transport: typeof window.__dexcodeTunnelTransport === 'function' ? window.__dexcodeTunnelTransport() : null
            })
        """
    }
}
