package com.example.dexcode.util

import android.net.Uri
import java.net.URI

object UrlUtils {
    /** Auth pages must retain their opener, storage and BroadcastChannel listeners. */
    fun shouldPromoteEditorPopup(serverUrl: String, popupUrl: String, authenticationStarted: Boolean): Boolean {
        if (authenticationStarted || !isSameOrigin(serverUrl, popupUrl)) return false
        if (isVsCodeTunnelUrl(serverUrl)) return isVsCodeTunnelUrl(popupUrl)
        return true
    }

    fun isVsCodeTunnelUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host in setOf("vscode.dev", "insiders.vscode.dev") &&
            uri.path.orEmpty().startsWith("/tunnel/")
    }

    fun normalizeServerUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        return withScheme.trimEnd('/')
    }

    fun isLocalhost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }

    /**
     * Maps a localhost link emitted by the remote VS Code workspace to code-server's
     * built-in path proxy. This is intentionally only a URL transform; Android never
     * opens a raw remote port or implements a second tunnel.
     */
    fun mapLocalhostToCodeServer(localUrl: String, codeServerUrl: String): String? {
        if (isVsCodeTunnelUrl(codeServerUrl)) return null
        val local = runCatching { URI(localUrl) }.getOrNull() ?: return null
        if (local.scheme !in listOf("http", "https") || !isLocalhost(localUrl)) return null
        val port = local.port.takeIf { it in 1..65535 } ?: return null

        val server = runCatching { URI(codeServerUrl) }.getOrNull() ?: return null
        val basePath = server.path.orEmpty().trimEnd('/')
        val proxyPath = "$basePath/proxy/$port/".replace("//", "/")
        val query = local.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = local.rawFragment?.let { "#$it" }.orEmpty()
        return URI(server.scheme, server.userInfo, server.host, server.port, proxyPath, null, null)
            .toString()
            .trimEnd('/') + "/${local.rawPath.orEmpty().trimStart('/')}" + query + fragment
    }

    fun resolvePortOrUrl(input: String, codeServerUrl: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        if (raw.all { it.isDigit() }) {
            val port = raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return mapLocalhostToCodeServer("http://localhost:$port/", codeServerUrl)
        }

        if (raw.startsWith("/")) {
            val base = codeServerUrl.trimEnd('/')
            return "$base$raw"
        }

        val normalized = if ("://" in raw) raw else "http://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (uri.scheme in listOf("http", "https") && isLocalhost(normalized)) {
            return mapLocalhostToCodeServer(normalized, codeServerUrl)
        }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        return normalized
    }

    fun isSameOrigin(first: String, second: String): Boolean {
        val a = runCatching { URI(first) }.getOrNull() ?: return false
        val b = runCatching { URI(second) }.getOrNull() ?: return false
        val portA = if (a.port == -1) if (a.scheme == "https") 443 else 80 else a.port
        val portB = if (b.port == -1) if (b.scheme == "https") 443 else 80 else b.port
        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            portA == portB
    }

    fun toUri(url: String): Uri = Uri.parse(url)
}
