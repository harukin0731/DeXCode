package com.example.dexcode

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicReference

object WebViewSettings {
    private val bridges = java.util.WeakHashMap<WebView, NativeClipboardBridge>()
    fun clipboardBridge(view: WebView): NativeClipboardBridge? = bridges[view]
    @Suppress("SetJavaScriptEnabled")
    fun apply(
        webView: WebView,
        initialAllowedUrl: String? = null,
        allowLocalhost: Boolean = false,
    ): NativeClipboardBridge {
        NativeClipboardBridge.loadScript(webView.context)
        webView.setBackgroundColor(Color.WHITE)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            defaultTextEncodingName = "UTF-8"
            textZoom = 100
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // DeX is a desktop-style target. The Android WebView UA makes VS Code Web
            // select its mobile path, which can omit the desktop workbench and remote UI.
            // Keep the embedded Chromium version so sites still receive a realistic UA.
            val chromeToken = Regex("Chrome/[0-9.]+").find(userAgentString)?.value
                ?: "Chrome/131.0.0.0"
            userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) $chromeToken Safari/537.36 DeXCode/0.1"
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        // Some code-server installations use an external SSO provider. This is scoped to
        // the app's WebView profile and is required by a number of embedded login flows.
        cookies.setAcceptThirdPartyCookies(webView, true)

        return NativeClipboardBridge(
            webView.context.applicationContext,
            initialAllowedUrl,
            allowLocalhost,
        ).also {
            bridges[webView] = it
            it.attach(webView)
        }
    }
}

/** Origin-checked text clipboard access, installed before editor and extension page scripts. */
class NativeClipboardBridge(
    context: Context,
    initialAllowedUrl: String? = null,
    private val allowLocalhost: Boolean = false,
) {
    private val appContext = context.applicationContext
    private var requests = 0
    private var nativeStatus = "waiting"
    fun diagnostics(): String = "Native requests: $requests\nNative result: $nativeStatus\nOrigin allowed: ${isAllowed()}\nAutomatic copy: ${appContext.getSharedPreferences("desktop_header", Context.MODE_PRIVATE).getBoolean("selection_copy", true)}"
    private val allowedOrigin = AtomicReference(originOf(initialAllowedUrl))
    private val currentOrigin = AtomicReference<String?>(null)

    fun setAllowedUrl(url: String?) {
        allowedOrigin.set(originOf(url))
    }

    fun setPageUrl(url: String?) {
        currentOrigin.set(originOf(url))
    }

    fun isAllowed(): Boolean {
        val current = currentOrigin.get() ?: return false
        val configured = allowedOrigin.get()
        if (configured != null) return configured == current
        if (!allowLocalhost) return false
        val host = Uri.parse(current).host?.lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }

    fun readText(): String {
        if (!isAllowed()) return ""
        return runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager ?: return@runCatching ""
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(appContext)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    fun writeText(value: String?): Boolean {
        if (!isAllowed()) return false
        return runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager ?: return@runCatching false
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("DeXCode", value.orEmpty()),
            )
            nativeStatus = runCatching {
                val clip = clipboard.primaryClip
                when {
                    clip == null || clip.itemCount == 0 -> "write_sent_readback_unavailable"
                    clip.getItemAt(0).text?.toString() == value.orEmpty() -> "write_confirmed"
                    else -> "write_sent_readback_mismatch"
                }
            }.getOrDefault("write_sent_readback_unavailable")
            true
        }.onFailure { nativeStatus = "write_failed_${it.javaClass.simpleName}" }.getOrDefault(false)
    }

    fun attach(view: WebView) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return
        androidx.webkit.WebViewCompat.addWebMessageListener(view, JS_NAME, setOf("*")) { _, message, sourceOrigin, _, reply ->
            if (message.type != androidx.webkit.WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            setPageUrl(view.url)
            if (message.data.orEmpty().length > 600000) return@addWebMessageListener
            requests++
            nativeStatus = "received"
            val result = org.json.JSONObject()
            runCatching {
                val request = org.json.JSONObject(message.data ?: "")
                result.put("id", request.getInt("id"))
                // Validate the actual sending frame, not just the URL of the top-level page.
                nativeStatus = "origin_denied"
                check(originOf(sourceOrigin.toString()) == currentOrigin.get() && isAllowed())
                nativeStatus = "authorized"
                when (request.getString("op")) {
                    "selection_mode" -> {
                        val enabled = appContext.getSharedPreferences("desktop_header", Context.MODE_PRIVATE).getBoolean("terminal_selection_mode", false)
                        result.put("text", if (enabled) "enabled" else "disabled").put("ok", true)
                        nativeStatus = if (enabled) "terminal_selection_mode_enabled" else "terminal_selection_mode_disabled"
                    }
                    "auto" -> {
                        val enabled = appContext.getSharedPreferences("desktop_header", Context.MODE_PRIVATE).getBoolean("selection_copy", true)
                        result.put("text", if (enabled) "enabled" else "disabled").put("ok", true)
                        nativeStatus = if (enabled) "automatic_copy_enabled" else "automatic_copy_disabled"
                    }
                    "read" -> { result.put("text", readText()).put("ok", true); nativeStatus = "read_complete" }
                    "write", "select" -> {
                        nativeStatus = "automatic_copy_disabled"
                        if (request.getString("op") == "select") check(appContext.getSharedPreferences("desktop_header", Context.MODE_PRIVATE).getBoolean("selection_copy", true))
                        val text = request.getString("text")
                        nativeStatus = "selection_too_large"
                        check(text.length <= 262144)
                        val written = writeText(text)
                        if (!written && !nativeStatus.startsWith("write_failed_")) nativeStatus = "clipboard_write_failed"
                        result.put("ok", written)
                    }
                    else -> result.put("ok", false)
                }
            }.onFailure { result.put("ok", false) }
            runCatching { reply.postMessage(result.toString()) }
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, INSTALL_SCRIPT, setOf("*"))
        }
    }

    companion object {
        const val JS_NAME = "DeXCodeClipboard"
        // Loaded once per process; kept available for WebViews without document-start injection.
        var INSTALL_SCRIPT: String = ""
            private set
        fun loadScript(context: Context) {
            if (INSTALL_SCRIPT.isEmpty()) INSTALL_SCRIPT = listOf("clipboard.js", "terminal-touch.js").joinToString("\n") { name ->
                context.assets.open(name).bufferedReader().use { it.readText() }
            }
        }
    }

    private fun originOf(url: String?): String? {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }
}
