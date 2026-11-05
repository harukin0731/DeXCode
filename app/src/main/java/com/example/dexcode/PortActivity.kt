package com.example.dexcode

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import com.example.dexcode.util.dp

/** Displays a forwarded development port in its own resizable task/window. */
class PortActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var debugContainer: ScrollView
    private lateinit var debugLog: TextView
    private lateinit var clipboardBridge: NativeClipboardBridge
    private var currentUrl = ""
    private var developerMode = false
    private val debugLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        currentUrl = savedInstanceState?.getString(STATE_CURRENT_URL) ?: url
        developerMode = getPreferences(MODE_PRIVATE)
            .getBoolean(PREF_DEVELOPER_MODE, false)
        if (developerMode) WebView.setWebContentsDebuggingEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val toolbar = Toolbar(this).apply {
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Port"
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

        debugLog = TextView(this).apply {
            setTextColor(Color.rgb(222, 226, 236))
            setBackgroundColor(Color.rgb(28, 31, 38))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = getString(R.string.developer_log_empty)
        }
        debugContainer = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(28, 31, 38))
            addView(debugLog)
            visibility = if (developerMode) View.VISIBLE else View.GONE
        }
        root.addView(debugContainer, LinearLayout.LayoutParams(-1, dp(120)))

        webView = DesktopWebView(this)
        var pageError: String? = null
        clipboardBridge = WebViewSettings.apply(
            webView,
            initialAllowedUrl = currentUrl,
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageError = null
                com.example.dexcode.runtime.RuntimeTracker.phase(this@PortActivity, "Loading page")
                currentUrl = url
                clipboardBridge.setPageUrl(url)
                appendDebug("PAGE START $url")
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                com.example.dexcode.runtime.RuntimeTracker.phase(this@PortActivity, pageError ?: "Page loaded")
                currentUrl = url
                clipboardBridge.setPageUrl(url)
                view.evaluateJavascript(NativeClipboardBridge.INSTALL_SCRIPT, null)
                appendDebug("PAGE FINISH $url")
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                return handleScheme(request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleScheme(url)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                appendDebug("REQ ${request.method} ${request.url}")
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    pageError = "HTTP ${errorResponse.statusCode}"
                    com.example.dexcode.runtime.RuntimeTracker.phase(this@PortActivity, pageError!!)
                }
                appendDebug(
                    "HTTP ${errorResponse.statusCode} ${request.method} ${request.url} " +
                        errorResponse.reasonPhrase.orEmpty(),
                )
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    pageError = "Page load failed (${error.errorCode})"
                    com.example.dexcode.runtime.RuntimeTracker.phase(this@PortActivity, pageError!!)
                }
                appendDebug("ERROR ${request.url} ${error.errorCode}: ${error.description}")
                super.onReceivedError(view, request, error)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                super.onProgressChanged(view, newProgress)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                appendDebug(
                    "CONSOLE ${consoleMessage.messageLevel()} " +
                        "${consoleMessage.message()} @${consoleMessage.sourceId()}:" +
                        consoleMessage.lineNumber(),
                )
                return super.onConsoleMessage(consoleMessage)
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        val restoredState = savedInstanceState?.let { webView.restoreState(it) }
        if (restoredState == null) {
            webView.loadUrl(currentUrl)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EDIT_URL, 0, R.string.edit_port_url)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_DEVELOPER_MODE, 1, R.string.developer_mode)
            .setCheckable(true)
            .setChecked(developerMode)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_CLEAR_LOG, 2, R.string.clear_debug_log)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            MENU_EDIT_URL -> {
                showEditUrlDialog()
                true
            }
            MENU_DEVELOPER_MODE -> {
                setDeveloperMode(!developerMode)
                item.isChecked = developerMode
                true
            }
            MENU_CLEAR_LOG -> {
                debugLines.clear()
                if (::debugLog.isInitialized) debugLog.text = getString(R.string.developer_log_empty)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showEditUrlDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl)
            setSelection(text.length)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.edit_port_url)
            .setMessage(R.string.edit_port_url_hint)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = input.text.toString().trim()
                if (!isHttpUrl(candidate)) {
                    input.error = getString(R.string.invalid_port_url)
                    return@setOnClickListener
                }
                currentUrl = candidate
                clipboardBridge.setAllowedUrl(candidate)
                appendDebug("LOAD $candidate")
                webView.loadUrl(candidate)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun isHttpUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase()
        return scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()
    }

    private fun setDeveloperMode(enabled: Boolean) {
        developerMode = enabled
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean(PREF_DEVELOPER_MODE, enabled)
            .apply()
        WebView.setWebContentsDebuggingEnabled(enabled)
        debugContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        appendDebug(if (enabled) "Developer mode enabled" else "Developer mode disabled")
    }

    private fun appendDebug(message: String) {
        if (!developerMode) return
        runOnUiThread {
            if (!developerMode || !::debugLog.isInitialized) return@runOnUiThread
            if (debugLines.size >= MAX_DEBUG_LINES) debugLines.removeAt(0)
            debugLines += message
            debugLog.text = debugLines.joinToString("\n")
            debugContainer.post { debugContainer.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun handleScheme(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "about") return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_link, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onPause() {
        android.webkit.CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_URL, currentUrl)
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "port_url"
        const val EXTRA_TITLE = "port_title"
        private const val MENU_EDIT_URL = 300
        private const val MENU_DEVELOPER_MODE = 301
        private const val MENU_CLEAR_LOG = 302
        private const val PREF_DEVELOPER_MODE = "developer_mode"
        private const val MAX_DEBUG_LINES = 300
        private const val STATE_CURRENT_URL = "current_url"

        fun intent(activity: Activity, url: String, title: String): Intent =
            Intent(activity, PortActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
