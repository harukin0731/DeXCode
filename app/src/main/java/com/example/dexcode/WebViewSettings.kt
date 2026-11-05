package com.example.dexcode

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicReference

object WebViewSettings {
    @Suppress("SetJavaScriptEnabled")
    fun apply(
        webView: WebView,
        initialAllowedUrl: String? = null,
        allowLocalhost: Boolean = false,
    ): NativeClipboardBridge {
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
            webView.addJavascriptInterface(it, NativeClipboardBridge.JS_NAME)
        }
    }
}

/** Provides text clipboard access to trusted WebView pages that lack navigator.clipboard. */
class NativeClipboardBridge(
    context: Context,
    initialAllowedUrl: String? = null,
    private val allowLocalhost: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val allowedOrigin = AtomicReference(originOf(initialAllowedUrl))
    private val currentOrigin = AtomicReference<String?>(null)

    fun setAllowedUrl(url: String?) {
        allowedOrigin.set(originOf(url))
    }

    fun setPageUrl(url: String?) {
        currentOrigin.set(originOf(url))
    }

    @JavascriptInterface
    fun isAllowed(): Boolean {
        val current = currentOrigin.get() ?: return false
        val configured = allowedOrigin.get()
        if (configured != null) return configured == current
        if (!allowLocalhost) return false
        val host = Uri.parse(current).host?.lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }

    @JavascriptInterface
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

    @JavascriptInterface
    fun writeText(value: String?): Boolean {
        if (!isAllowed()) return false
        return runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager ?: return@runCatching false
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("DeXCode", value.orEmpty()),
            )
            true
        }.getOrDefault(false)
    }

    companion object {
        const val JS_NAME = "DeXCodeClipboard"
        const val INSTALL_SCRIPT = """
            (function() {
              const bridge = window.DeXCodeClipboard;
              if (!bridge || typeof navigator === "undefined") return;
              const clipboard = navigator.clipboard;
              const originalReadText = clipboard && clipboard.readText
                ? clipboard.readText.bind(clipboard) : null;
              const originalWriteText = clipboard && clipboard.writeText
                ? clipboard.writeText.bind(clipboard) : null;
              const originalRead = clipboard && clipboard.read
                ? clipboard.read.bind(clipboard) : null;
              const originalWrite = clipboard && clipboard.write
                ? clipboard.write.bind(clipboard) : null;
              const denied = function() {
                const error = typeof DOMException !== "undefined"
                  ? new DOMException("Clipboard access denied", "NotAllowedError")
                  : new Error("Clipboard access denied");
                return Promise.reject(error);
              };
              const allowed = function() {
                try { return bridge.isAllowed(); } catch (_) { return false; }
              };
              const readText = function() {
                if (!allowed()) return originalReadText ? originalReadText() : denied();
                return Promise.resolve(bridge.readText());
              };
              const writeText = function(value) {
                if (!allowed()) return originalWriteText
                  ? originalWriteText(value) : denied();
                return bridge.writeText(String(value)) ? Promise.resolve() : denied();
              };
              const read = function() {
                if (!allowed()) return originalRead ? originalRead() : denied();
                if (typeof ClipboardItem === "undefined") return denied();
                return readText().then(function(text) {
                  return [new ClipboardItem({
                    "text/plain": new Blob([text], {type: "text/plain"})
                  })];
                });
              };
              const write = function(items) {
                if (!allowed()) return originalWrite ? originalWrite(items) : denied();
                return Promise.resolve(items).then(async function(values) {
                  if (!values || !values.length) return;
                  const item = values[0];
                  if (!item || !item.types || item.types.indexOf("text/plain") < 0) {
                    return denied();
                  }
                  const blob = await item.getType("text/plain");
                  return writeText(await blob.text());
                });
              };
              const install = function(target) {
                try { Object.defineProperty(target, "readText", {
                  value: readText, configurable: true
                }); } catch (_) {}
                try { Object.defineProperty(target, "writeText", {
                  value: writeText, configurable: true
                }); } catch (_) {}
                try { Object.defineProperty(target, "read", {
                  value: read, configurable: true
                }); } catch (_) {}
                try { Object.defineProperty(target, "write", {
                  value: write, configurable: true
                }); } catch (_) {}
              };
              if (clipboard) {
                install(clipboard);
              } else {
                try { Object.defineProperty(navigator, "clipboard", {
                  value: {readText: readText, writeText: writeText,
                    read: read, write: write}, configurable: true
                }); } catch (_) {}
              }
            })();
        """
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
