package com.example.dexcode

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.DownloadListener
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.dexcode.data.Connection
import com.example.dexcode.data.ConnectionStore
import com.example.dexcode.util.UrlUtils
import com.example.dexcode.util.dp
import com.example.dexcode.util.copyForwardUrl
import com.example.dexcode.runtime.RuntimeTracker
import com.example.dexcode.runtime.RuntimeState
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject

class CodeActivity : AppCompatActivity() {
    private lateinit var connection: Connection
    private lateinit var webView: WebView
    private lateinit var webContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private val popupHolders = mutableMapOf<WebView, View>()
    private val popupExternalOrigins = mutableSetOf<WebView>()
    private val popupOpeners = mutableMapOf<WebView, WebView>()
    private val callbackTransfers = mutableMapOf<WebView, CallbackTransfer>()
    private val deliveredCallbacks = mutableMapOf<WebView, String>()
    private val diagnostics = ConnectionDiagnostics()

    private class CallbackTransfer(val requestId: String, val poll: Runnable) {
        val completions = mutableListOf<() -> Unit>()
    }
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback ?: return@registerForActivityResult
        pendingFileCallback = null
        callback.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        val savedConnection = connectionId?.let { ConnectionStore(this).get(it) }
        if (savedConnection == null) {
            Toast.makeText(this, "Connection not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        connection = savedConnection
        ConnectionStore(this).markLastUsed(connection.id)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val toolbar = Toolbar(this).apply {
            title = connection.name
            setTitleTextColor(Color.rgb(25, 27, 32))
            setBackgroundColor(Color.rgb(247, 247, 251))
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        toolbar.minimumHeight = dp(40)
        toolbar.setContentInsetsRelative(dp(4), dp(4))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(40)))
        setSupportActionBar(toolbar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        webContainer = FrameLayout(this)
        webView = createWebView()
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        com.example.dexcode.ui.WorkspaceHeader(this, root, toolbar, connection.name,
            onPorts = { showOpenPortDialog() },
            onReconnect = { webView.reload() },
            onReload = { webView.reload() },
            onSettings = { showSettings() },
            onDiagnostics = { showDiagnostics() },
        ) { webView }

        val restoredState = savedInstanceState?.let { webView.restoreState(it) }
        if (restoredState == null) {
            val initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL)
                ?.takeIf { UrlUtils.isSameOrigin(connection.url, it) }
                ?: connection.url
            webView.loadUrl(initialUrl)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A detachable keyboard in DeX can produce more than just the keyboard
        // configuration bit. Keep Chromium alive and let it relayout instead of
        // recreating the Activity and loading the editor URL again.
        if (::webView.isInitialized) {
            webView.requestLayout()
            webView.invalidate()
        }
    }

    private fun createWebView(isPopup: Boolean = false): WebView {
        val view = DesktopWebView(this)
        var pageError: String? = null
        val clipboardBridge = WebViewSettings.apply(
            view,
            initialAllowedUrl = connection.url,
        )
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(
                    view,
                    request.url.toString(),
                    isRedirect = request.isRedirect,
                    hasGesture = request.hasGesture(),
                )
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // The old callback has no redirect/gesture metadata. Keep auth redirects
                // inside the WebView; this is required by vscode.dev/tunnel sign-in flows.
                return handleNavigation(view, url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!isPopup) {
                    pageError = null
                    RuntimeTracker.phase(this@CodeActivity, "Loading page")
                }
                diagnostics.record(if (isPopup) "POPUP START" else "PAGE START", url)
                clipboardBridge.setPageUrl(url)
                if (isPopup && promoteCodeWindowPopup(view, url)) return
                progress.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!isPopup) RuntimeTracker.phase(this@CodeActivity, pageError ?: "Page loaded")
                diagnostics.record(if (isPopup) "POPUP FINISH" else "PAGE FINISH", url)
                clipboardBridge.setPageUrl(url)
                view.evaluateJavascript(NativeClipboardBridge.INSTALL_SCRIPT, null)
                progress.visibility = View.GONE
                bridgeVsCodeCallback(view, url)
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                diagnostics.record("LOAD ERROR ${error.errorCode} main=${request.isForMainFrame}", request.url.toString())
                if (request.isForMainFrame) {
                    if (!isPopup) {
                        pageError = "Page load failed (${error.errorCode})"
                        RuntimeTracker.phase(this@CodeActivity, pageError!!)
                    }
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this@CodeActivity,
                        getString(R.string.page_load_error, error.description),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest,
                response: android.webkit.WebResourceResponse,
            ) {
                diagnostics.record("HTTP ${response.statusCode} main=${request.isForMainFrame}", request.url.toString())
                if (request.isForMainFrame && !isPopup) {
                    pageError = "HTTP ${response.statusCode}"
                    RuntimeTracker.phase(this@CodeActivity, pageError!!)
                }
                super.onReceivedHttpError(view, request, response)
            }

            override fun onReceivedSslError(
                view: WebView, handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError,
            ) {
                diagnostics.record("TLS ERROR ${error.primaryError}", error.url)
                if (!isPopup) {
                    pageError = "TLS error (${error.primaryError})"
                    RuntimeTracker.phase(this@CodeActivity, pageError!!)
                }
                // Preserve WebView's default certificate rejection.
                super.onReceivedSslError(view, handler, error)
            }
        }
        view.webChromeClient = createChromeClient()
        view.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        })
        return view
    }

    private fun createChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() in setOf(ConsoleMessage.MessageLevel.ERROR, ConsoleMessage.MessageLevel.WARNING)) {
                val text = message.message().lowercase()
                val category = listOf("websocket", "cors", "fetch", "network", "crypto", "decrypt", "msal", "broadcastchannel", "timeout", "typeerror", "referenceerror")
                    .firstOrNull { it in text } ?: "other"
                diagnostics.record("JS ${message.messageLevel()} category=$category", message.sourceId())
                return true
            }
            return super.onConsoleMessage(message)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            super.onProgressChanged(view, newProgress)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            if (filePathCallback == null) return false
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            return try {
                filePicker.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                pendingFileCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            diagnostics.record("POPUP CREATED gesture=$isUserGesture")
            val child = createWebView(isPopup = true)
            val holder = FrameLayout(this@CodeActivity)
            holder.setBackgroundColor(Color.WHITE)
            holder.addView(child, FrameLayout.LayoutParams(-1, -1))

            val close = Button(this@CodeActivity).apply {
                text = getString(R.string.close)
                isAllCaps = false
                setOnClickListener { closePopup(child) }
            }
            holder.addView(close, FrameLayout.LayoutParams(-2, dp(48)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(8)
                rightMargin = dp(8)
            })
            popupHolders[child] = holder
            popupOpeners[child] = view ?: webView
            webContainer.addView(holder, FrameLayout.LayoutParams(-1, -1))

            val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                closePopup(child)
                return false
            }
            transport.webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            if (window != null) {
                // The VS Code callback page calls window.close() immediately after it
                // writes its encrypted URI to localStorage. Copy that value first; an
                // Android WebView does not reliably deliver the storage event between
                // independently-created WebView instances.
                bridgeVsCodeCallback(window, window.url) {
                    closePopup(window)
                }
            }
            super.onCloseWindow(window)
        }
    }

    private fun bridgeVsCodeCallback(
        view: WebView,
        url: String?,
        onComplete: () -> Unit = {},
    ) {
        if (!UrlUtils.isVsCodeTunnelUrl(connection.url)) {
            onComplete()
            return
        }
        val callbackUri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
        val callbackHost = callbackUri?.host?.lowercase()
        if (callbackUri?.scheme != "https" ||
            callbackHost !in setOf("vscode.dev", "insiders.vscode.dev") ||
            callbackUri?.path != "/callback"
        ) {
            onComplete()
            return
        }
        val requestId = callbackUri.getQueryParameter("vscode-reqid")
            ?.takeIf { it.isNotBlank() && it.length <= 256 }
        if (requestId == null) {
            onComplete()
            return
        }

        val parent = popupOpeners[view]
        if (parent == null || !UrlUtils.isSameOrigin(url.orEmpty(), parent.url.orEmpty())) {
            diagnostics.record("CALLBACK SKIPPED: opener origin differs", url)
            onComplete()
            return
        }
        if (deliveredCallbacks[view] == requestId) {
            onComplete()
            return
        }
        callbackTransfers[view]?.let { transfer ->
            if (transfer.requestId == requestId) {
                transfer.completions += onComplete
                return
            }
            view.removeCallbacks(transfer.poll)
            callbackTransfers.remove(view)
            transfer.completions.forEach { it() }
            onComplete()
            return
        }
        val deadline = SystemClock.uptimeMillis() + CALLBACK_BRIDGE_TIMEOUT_MS
        diagnostics.record("CALLBACK WAIT", url)
        val origin = "https://$callbackHost" +
            if (callbackUri.port != -1 && callbackUri.port != 443) ":${callbackUri.port}" else ""
        fun finishTransfer() {
            callbackTransfers.remove(view)?.let { transfer ->
                view.removeCallbacks(transfer.poll)
                transfer.completions.forEach { it() }
            }
        }
        val poll = object : Runnable {
            override fun run() {
                if (callbackTransfers[view]?.poll !== this) return
                if (SystemClock.uptimeMillis() >= deadline) {
                    diagnostics.record("CALLBACK TIMEOUT", url)
                    Toast.makeText(this@CodeActivity, R.string.tunnel_callback_timeout, Toast.LENGTH_LONG).show()
                    finishTransfer()
                    return
                }
                if (!UrlUtils.isSameOrigin(url.orEmpty(), parent.url.orEmpty()) ||
                    !isVsCodeCallbackUrl(view.url.orEmpty())
                ) {
                    diagnostics.record("CALLBACK CANCELLED: page changed", url)
                    finishTransfer()
                    return
                }
                view.evaluateJavascript(TunnelAuthScripts.read(requestId)) { encodedResult ->
                    if (callbackTransfers[view]?.poll !== this) return@evaluateJavascript
                    val pair = runCatching {
                        val decoded = JSONTokener(encodedResult).nextValue() as? String
                        if (decoded.isNullOrEmpty()) null else JSONArray(decoded)
                    }.getOrNull()
                    if (pair == null || pair.length() != 2) {
                        view.postDelayed(this, CALLBACK_BRIDGE_DELAY_MS)
                        return@evaluateJavascript
                    }
    
                    val key = pair.optString(0)
                    val value = pair.optString(1)
                    if (key !in setOf("vscode-dev.url-callbacks[$requestId]", "vscode-web.url-callbacks[$requestId]") ||
                        value.isEmpty()
                    ) {
                        finishTransfer()
                        return@evaluateJavascript
                    }
                    parent.evaluateJavascript(TunnelAuthScripts.deliver(origin, key, value)) delivered@{ result ->
                        if (callbackTransfers[view]?.poll !== this) return@delivered
                        if (result == "true") {
                            diagnostics.record("CALLBACK DELIVERED", url)
                            deliveredCallbacks[view] = requestId
                            CookieManager.getInstance().flush()
                            finishTransfer()
                        } else {
                            view.postDelayed(this, CALLBACK_BRIDGE_DELAY_MS)
                        }
                    }
                }
            }
        }
        callbackTransfers[view] = CallbackTransfer(requestId, poll).apply {
            completions += onComplete
        }
        // Encryption includes an asynchronous auth-server request. Retry until the
        // ciphertext is ready, and keep the popup alive through window.close().
        view.post(poll)
    }

    private fun promoteCodeWindowPopup(view: WebView, url: String): Boolean {
        if (url.startsWith("about:") || url.isBlank()) return false
        val isSameOrigin = UrlUtils.isSameOrigin(connection.url, url)
        if (!isSameOrigin) {
            popupExternalOrigins += view
            return false
        }
        // OAuth can start on this origin (/msal, /redirect) before visiting its
        // provider. Keep the whole flow alive; only explicit editor URLs can promote.
        if (UrlUtils.isVsCodeTunnelUrl(connection.url) && !UrlUtils.isVsCodeTunnelUrl(url)) {
            // /msal and /redirect start on the editor's own origin. Destroying these
            // popups loses the OAuth opener/BroadcastChannel and leaves the editor waiting.
            popupExternalOrigins += view
            diagnostics.record("AUTH POPUP RETAINED", url)
            return false
        }
        if (!UrlUtils.shouldPromoteEditorPopup(connection.url, url, popupExternalOrigins.contains(view)) ||
            isVsCodeCallbackUrl(url)
        ) return false

        val holder = popupHolders.remove(view) ?: return false
        diagnostics.record("EDITOR POPUP PROMOTED", url)
        popupOpeners.remove(view)
        popupExternalOrigins.remove(view)
        webContainer.removeView(holder)
        view.stopLoading()
        view.destroy()
        startActivity(
            Intent(this, CodeActivity::class.java)
                .putExtra(EXTRA_CONNECTION_ID, connection.id)
                .putExtra(EXTRA_INITIAL_URL, url)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
                ),
        )
        return true
    }

    private fun isVsCodeCallbackUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host?.lowercase() in setOf("vscode.dev", "insiders.vscode.dev") &&
            uri.path == "/callback"
    }

    private fun handleNavigation(
        view: WebView,
        target: String,
        isRedirect: Boolean = false,
        hasGesture: Boolean = false,
    ): Boolean {
        diagnostics.record("NAVIGATION redirect=$isRedirect gesture=$hasGesture", target)
        val mapped = UrlUtils.mapLocalhostToCodeServer(target, connection.url)
        if (mapped != null) {
            RuntimeState.store.addProxyUrl(RuntimeTracker.id(this), connection.name, mapped)
            view.loadUrl(mapped)
            return true
        }
        if (UrlUtils.isVsCodeTunnelUrl(connection.url) && UrlUtils.isLocalhost(target)) {
            Toast.makeText(this, R.string.tunnel_ports_hint, Toast.LENGTH_LONG).show()
            return true
        }

        val uri = runCatching { Uri.parse(target) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (scheme == "about") return false
        if (scheme == "http" || scheme == "https") {
            if (UrlUtils.isSameOrigin(connection.url, target)) return false
            // A VS Code Tunnel intentionally leaves vscode.dev for GitHub OAuth and then
            // comes back through vscode.dev/redirect. Keep that complete flow in the child
            // WebView instead of showing the generic external-link confirmation.
            if (UrlUtils.isVsCodeTunnelUrl(connection.url)) return false
            // OAuth and tunnel hand-offs navigate across origins without a user gesture.
            // Blocking those redirects leaves vscode.dev/tunnel on an endless spinner.
            if (isRedirect || !hasGesture) return false
            showLinkDestinationDialog(target)
            return true
        }

        if (UrlUtils.isVsCodeTunnelUrl(connection.url)) {
            // Tunnel authentication can briefly use a custom URI while the web callback
            // is being completed. Never hand that URI to an external browser/app: doing
            // so loses the callback from the in-app VS Code workbench.
            diagnostics.record("NON-HTTP NAVIGATION BLOCKED", target)
            return true
        }

        if (!target.startsWith("about:") && target.isNotBlank()) {
            openInBrowser(target)
            return true
        }
        return false
    }

    private fun closePopup(child: WebView) {
        val holder = popupHolders.remove(child) ?: return
        diagnostics.record("POPUP CLOSED", child.url)
        popupOpeners.remove(child)
        deliveredCallbacks.remove(child)
        callbackTransfers.remove(child)?.let { child.removeCallbacks(it.poll) }
        popupExternalOrigins.remove(child)
        webContainer.removeView(holder)
        child.stopLoading()
        child.destroy()
    }

    private fun openNewCodeWindow() {
        startActivity(
            Intent(this, CodeActivity::class.java)
                .putExtra(EXTRA_CONNECTION_ID, connection.id)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
                ),
        )
    }

    private fun showConnections() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_CONNECTIONS, true),
        )
    }

    private fun showOpenPortDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(22), dp(4), dp(22), 0)
        }
        val copy = Button(this).apply { text = getString(R.string.copy_address); isAllCaps = false }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(copy)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.open_port_url)
            .setMessage(if (UrlUtils.isVsCodeTunnelUrl(connection.url)) R.string.tunnel_ports_hint else R.string.port_proxy_message)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.open_in_browser, null)
            .setPositiveButton(R.string.open_in_app, null)
            .create()
        dialog.setOnShowListener {
            fun openResolved(destination: Int) {
                val raw = input.text.toString().trim()
                val url = UrlUtils.resolvePortOrUrl(raw, connection.url)
                if (url == null) {
                    input.error = getString(if (UrlUtils.isVsCodeTunnelUrl(connection.url)) R.string.tunnel_ports_hint else R.string.invalid_port_url)
                } else {
                    if (raw.toIntOrNull() != null || UrlUtils.isLocalhost(if ("://" in raw) raw else "http://$raw")) {
                        RuntimeState.store.addProxyUrl(RuntimeTracker.id(this), connection.name, url)
                    }
                    dialog.dismiss()
                    when (destination) {
                        0 -> copyForwardUrl(this, url)
                        1 -> openInApp(url, "Port")
                        else -> openInBrowser(url)
                    }
                }
            }
            copy.setOnClickListener { openResolved(0) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openResolved(1) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openResolved(2) }
        }
        dialog.show()
    }

    private fun showLinkDestinationDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.open_link)
            .setMessage(url)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.open_in_browser) { _, _ -> openInBrowser(url) }
            .setPositiveButton(R.string.open_in_app) { _, _ -> openInApp(url, "Web") }
            .show()
    }

    private fun openInApp(url: String, title: String) {
        startActivity(
            PortActivity.intent(this, url, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            addRequestHeader("User-Agent", userAgent)
            CookieManagerHolder.addCookieHeader(this, url)
            setMimeType(mimeType)
            setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // App-scoped external storage works without broad storage permissions on modern Android.
            setDestinationInExternalFilesDir(
                this@CodeActivity,
                Environment.DIRECTORY_DOWNLOADS,
                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setMessage(R.string.webview_storage_info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDiagnostics() {
        webView.evaluateJavascript(ConnectionDiagnostics.SNAPSHOT_SCRIPT) { encoded ->
            val snapshot = runCatching {
                val decoded = JSONTokener(encoded).nextValue() as? String
                JSONObject(decoded.orEmpty())
            }.getOrNull()
            val state = snapshot?.optString("readyState")
                ?.takeIf { it in setOf("loading", "interactive", "complete") } ?: "unknown"
            val capabilities = listOf("secureContext", "online", "crypto", "webSocket", "broadcastChannel", "serviceWorker", "serviceWorkerControlled", "workbench", "splash")
                .joinToString("\n") { "$it=${snapshot?.optBoolean(it) ?: false}" }
            val version = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "unknown"
            val report = "WebView=$version\nPage=${ConnectionDiagnostics.safeTarget(webView.url.orEmpty())}\nreadyState=$state\n$capabilities\n\n${diagnostics.report()}"
            val text = TextView(this).apply {
                setText(report)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.connection_diagnostics)
                .setView(ScrollView(this).apply { addView(text) })
                .setNeutralButton(R.string.copy_diagnostics) { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("DeXCode diagnostics", report))
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun navigateBack() {
        val latestPopup = popupHolders.keys.lastOrNull()
        if (latestPopup != null) {
            closePopup(latestPopup)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        popupHolders.keys.toList().forEach { closePopup(it) }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private object CookieManagerHolder {
        fun addCookieHeader(request: DownloadManager.Request, url: String) {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
        }
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        private const val MENU_NEW_WINDOW = 100
        private const val MENU_CONNECTIONS = 101
        private const val MENU_RELOAD = 102
        private const val MENU_OPEN_PORT = 103
        private const val MENU_SETTINGS = 104
        private const val MENU_DIAGNOSTICS = 105
        private const val CALLBACK_BRIDGE_DELAY_MS = 250L
        private const val CALLBACK_BRIDGE_TIMEOUT_MS = 30_000L
        const val EXTRA_INITIAL_URL = "initial_url"
    }
}
