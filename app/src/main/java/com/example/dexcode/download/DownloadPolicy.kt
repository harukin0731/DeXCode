package com.example.dexcode.download

import java.net.URI

object DownloadPolicy {
    fun scheme(url: String?): String = runCatching { URI(url.orEmpty()).scheme.orEmpty().lowercase() }.getOrDefault("")
    fun filename(value: String?): String = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.').take(160).ifBlank { "download" }
    fun sameOrigin(a: String?, b: String?): Boolean {
        fun origin(raw: String?): String? {
            val uri = runCatching { URI(raw.orEmpty()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
            return "$scheme://$host:$port"
        }
        val first = origin(a) ?: return false
        return first == origin(b)
    }
}
