package com.example.dexcode

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.dexcode.data.Connection
import com.example.dexcode.data.ConnectionStore
import com.example.dexcode.data.Kind
import com.example.dexcode.data.SecureSecretStore
import com.example.dexcode.ssh.SshSessionManager
import com.example.dexcode.util.dp
import com.example.dexcode.util.copyForwardUrl
import com.example.dexcode.runtime.RuntimeTracker
import com.example.dexcode.data.PortBindings
import androidx.core.widget.doAfterTextChanged
import android.widget.ScrollView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Connects to a remote workspace, bootstraps code-server, and exposes it through SSH. */
class SshCodeActivity : AppCompatActivity() {
    private lateinit var connection: Connection
    private lateinit var webView: WebView
    private lateinit var webContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var retry: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val popupHolders = mutableMapOf<WebView, View>()
    private val popupExternalOrigins = mutableSetOf<WebView>()
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var manager: SshSessionManager? = null
    private var generation = 0
    private var localWebPort: Int? = null
    private var initialPath: String? = null
    private val secretStore by lazy { SecureSecretStore(applicationContext) }

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
        initialPath = intent.getStringExtra(EXTRA_INITIAL_PATH)
            ?.takeIf { it.startsWith("/") }
        if (connection.kind != Kind.SSH) {
            finish()
            return
        }
        ConnectionStore(this).markLastUsed(connection.id)

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
            visibility = View.VISIBLE
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        status = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        retry = Button(this).apply {
            text = getString(R.string.retry)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { startSshConnection() }
        }
        root.addView(retry, LinearLayout.LayoutParams(-1, dp(48)).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
            bottomMargin = dp(8)
        })

        webContainer = FrameLayout(this)
        webContainer.visibility = View.GONE
        webView = createWebView()
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        com.example.dexcode.ui.WorkspaceHeader(this, root, toolbar, connection.name,
            onPorts = { showOpenPortDialog() },
            onReconnect = { startSshConnection() },
            onReload = { webView.reload() },
            onSettings = { AlertDialog.Builder(this).setTitle(R.string.settings).setMessage(R.string.webview_storage_info).setPositiveButton(android.R.string.ok, null).show() },
        ) { webView }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        startSshConnection()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Do not tear down the SSH session when DeX attaches or detaches a
        // keyboard. The manifest handles the configuration change; the existing
        // WebView only needs a layout pass.
        if (::webView.isInitialized) {
            webView.requestLayout()
            webView.invalidate()
        }
    }

    private fun startSshConnection() {
        val currentGeneration = ++generation
        manager?.close()
        val nextManager = SshSessionManager(
            applicationContext,
            connection,
            object : SshSessionManager.Interaction {
                override fun status(message: String) {
                    runOnUiThread {
                        if (currentGeneration == generation && !isFinishing) status.text = message
                        if (currentGeneration == generation) RuntimeTracker.phase(this@SshCodeActivity, message)
                    }
                }

                override fun confirmHostKey(message: String): Boolean =
                    blockingHostKeyConfirmation(message)

                override fun askSecret(
                    kind: SshSessionManager.SecretKind,
                    prompt: String?,
                ): String? = blockingSecret(kind, prompt)
            },
            windowId = RuntimeTracker.id(this),
        )
        manager = nextManager
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.visibility = View.VISIBLE
        status.text = getString(R.string.ssh_connecting)
        retry.visibility = View.GONE
        webContainer.visibility = View.GONE

        executor.execute {
            try {
                val localPort = nextManager.connect()
                runOnUiThread {
                    if (currentGeneration != generation || isFinishing) return@runOnUiThread
                    localWebPort = localPort
                    progress.visibility = View.GONE
                    status.visibility = View.GONE
                    retry.visibility = View.GONE
                    webContainer.visibility = View.VISIBLE
                    if (nextManager.forwardingRestoreErrors.isNotEmpty()) {
                        Toast.makeText(this, R.string.fixed_forward_restore_failed, Toast.LENGTH_LONG).show()
                    }
                    webView.loadUrl(buildLocalWebUrl(localPort, nextManager.resolvedWorkspacePath))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (currentGeneration != generation || isFinishing) return@runOnUiThread
                    progress.visibility = View.GONE
                    status.visibility = View.VISIBLE
                    status.text = getString(
                        R.string.ssh_failed_detail,
                        getString(R.string.ssh_failed),
                        error.message.orEmpty(),
                    )
                    RuntimeTracker.phase(this@SshCodeActivity, "SSH connection failed")
                    retry.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun blockingHostKeyConfirmation(message: String): Boolean {
        if (isFinishing) return false
        val latch = CountDownLatch(1)
        var answer = false
        runOnUiThread {
            if (isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.host_key_title)
                .setMessage(getString(R.string.host_key_message_with_details, getString(R.string.host_key_message), message))
                .setNegativeButton(R.string.cancel) { _, _ -> latch.countDown() }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer = true
                    latch.countDown()
                }
                .create()
            dialog.setOnCancelListener { latch.countDown() }
            dialog.show()
        }
        latch.await()
        return answer
    }

    private fun blockingSecret(
        kind: SshSessionManager.SecretKind,
        prompt: String?,
    ): String? {
        if (isFinishing) return null
        val isPassword = kind == SshSessionManager.SecretKind.PASSWORD
        val rememberedPassword = if (isPassword) {
            secretStore.getPassword(connection.id)
        } else {
            null
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            // setSingleLine replaces the transformation, so masking must be applied last.
            transformationMethod = SecretMask
            isSaveEnabled = false
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (rememberedPassword != null) {
                setText(rememberedPassword)
                setSelection(text.length)
            }
            hint = getString(
                if (!isPassword) {
                    R.string.ssh_passphrase
                } else {
                    R.string.ssh_password
                },
            )
        }
        val remember = CheckBox(this).apply {
            text = getString(R.string.remember_ssh_password)
            isChecked = rememberedPassword != null
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(58)))
            if (isPassword) addView(remember, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val latch = CountDownLatch(1)
        var answer: String? = null
        runOnUiThread {
            if (isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            val builder = AlertDialog.Builder(this)
                .setTitle(R.string.ssh_secret_title)
                .setView(form)
                .setNegativeButton(R.string.cancel) { _, _ -> latch.countDown() }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer = input.text.toString()
                    if (isPassword) {
                        if (remember.isChecked && !answer.isNullOrEmpty()) {
                            secretStore.putPassword(connection.id, answer.orEmpty()).onFailure {
                                Toast.makeText(
                                    this@SshCodeActivity,
                                    R.string.ssh_password_save_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } else if (!remember.isChecked || answer.isNullOrEmpty()) {
                            secretStore.removePassword(connection.id)
                        }
                    }
                    latch.countDown()
                }
            if (!prompt.isNullOrBlank()) {
                builder.setMessage(prompt)
            } else if (!isPassword) {
                builder.setMessage(R.string.ssh_key_passphrase_note)
            }
            val dialog = builder.create()
            dialog.setOnCancelListener { latch.countDown() }
            dialog.show()
        }
        latch.await()
        return answer
    }

    private fun createWebView(isPopup: Boolean = false): WebView {
        val view = DesktopWebView(this)
        val clipboardBridge = WebViewSettings.apply(view, allowLocalhost = true)
        var pageError: String? = null
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
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(view, url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!isPopup) {
                    pageError = null
                    RuntimeTracker.phase(this@SshCodeActivity, sshPagePhase("loading page"))
                }
                clipboardBridge.setPageUrl(url)
                if (isPopup && promoteSshWindowPopup(view, url)) return
                progress.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!isPopup) RuntimeTracker.phase(this@SshCodeActivity, sshPagePhase(pageError ?: "page loaded"))
                clipboardBridge.setPageUrl(url)
                view.evaluateJavascript(NativeClipboardBridge.INSTALL_SCRIPT, null)
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    if (!isPopup) {
                        pageError = "Page load failed (${error.errorCode})"
                        RuntimeTracker.phase(this@SshCodeActivity, pageError!!)
                    }
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this@SshCodeActivity,
                        getString(R.string.page_load_error, error.description),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame && !isPopup) {
                    pageError = "HTTP ${response.statusCode}"
                    RuntimeTracker.phase(this@SshCodeActivity, pageError!!)
                }
                super.onReceivedHttpError(view, request, response)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
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
                val child = createWebView(isPopup = true)
                val holder = FrameLayout(this@SshCodeActivity)
                holder.setBackgroundColor(Color.WHITE)
                holder.addView(child, FrameLayout.LayoutParams(-1, -1))
                val close = Button(this@SshCodeActivity).apply {
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
                if (window != null) closePopup(window)
                super.onCloseWindow(window)
            }
        }
        view.setDownloadListener(DownloadListener { url, userAgent, disposition, mimeType, _ ->
            enqueueDownload(url, userAgent, disposition, mimeType)
        })
        return view
    }

    private fun handleNavigation(
        view: WebView,
        target: String,
        isRedirect: Boolean = false,
        hasGesture: Boolean = false,
    ): Boolean {
        val uri = Uri.parse(target)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val base = view.url ?: webView.url ?: target
            if (sameOrigin(base, target) ||
                isCurrentTunnelUrl(base, target) ||
                (isLocalHostUrl(uri) && uri.port == localWebPort)
            ) return false
            if ((isRedirect || !hasGesture) && !isLocalHostUrl(uri)) return false
            if (isLocalHostUrl(uri)) {
                val remotePort = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
                try {
                    val localPort = manager?.forwardRemotePort(remotePort)
                        ?: error("SSH session is not connected")
                    view.loadUrl(
                        uri.buildUpon()
                            .scheme("http")
                            .encodedAuthority("127.0.0.1:$localPort")
                            .build()
                            .toString(),
                    )
                } catch (error: Exception) {
                    Toast.makeText(this, error.message.orEmpty(), Toast.LENGTH_LONG).show()
                }
                return true
            }
            showLinkDestinationDialog(target)
            return true
        }
        if (!target.startsWith("about:") && target.isNotBlank()) {
            openInBrowser(target)
            return true
        }
        return false
    }

    private fun isLocalHostUrl(uri: Uri): Boolean {
        return uri.host == "127.0.0.1" || uri.host == "localhost"
    }

    private fun buildLocalWebUrl(localPort: Int, resolvedWorkspacePath: String?): String {
        val requestedPath = initialPath?.takeIf { it.startsWith("/") }
        if (requestedPath != null) {
            return "http://127.0.0.1:$localPort$requestedPath"
        }

        val builder = Uri.parse("http://127.0.0.1:$localPort/").buildUpon()
        // The server reports the path after expanding `~` and relative paths. The
        // query is needed when an already-running server is reused.
        resolvedWorkspacePath?.trim()?.takeIf { it.startsWith("/") }?.let {
            builder.appendQueryParameter("folder", it)
        }
        return builder.build().toString()
    }

    private fun promoteSshWindowPopup(view: WebView, url: String): Boolean {
        if (url.startsWith("about:") || url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!isLocalHostUrl(uri)) {
            popupExternalOrigins += view
            return false
        }
        if (popupExternalOrigins.contains(view)) return false

        val holder = popupHolders.remove(view) ?: return false
        popupExternalOrigins.remove(view)
        webContainer.removeView(holder)
        view.stopLoading()
        view.destroy()

        val path = uri.encodedPath.orEmpty().ifBlank { "/" } +
            uri.encodedQuery?.let { "?$it" }.orEmpty() +
            uri.encodedFragment?.let { "#$it" }.orEmpty()
        startActivity(
            Intent(this, SshCodeActivity::class.java)
                .putExtra(EXTRA_CONNECTION_ID, connection.id)
                .putExtra(EXTRA_INITIAL_PATH, path)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
                ),
        )
        return true
    }

    private fun isCurrentTunnelUrl(base: String, target: String): Boolean {
        val baseUri = runCatching { Uri.parse(base) }.getOrNull() ?: return false
        val targetUri = runCatching { Uri.parse(target) }.getOrNull() ?: return false
        return isLocalHostUrl(targetUri) &&
            isLocalHostUrl(baseUri) &&
            baseUri.port == targetUri.port
    }

    private fun sameOrigin(left: String, right: String): Boolean {
        val a = runCatching { Uri.parse(left) }.getOrNull() ?: return false
        val b = runCatching { Uri.parse(right) }.getOrNull() ?: return false
        return a.scheme.equals(b.scheme, true) &&
            a.host.equals(b.host, true) &&
            a.port == b.port
    }

    private fun sshPagePhase(pageState: String): String {
        val current = manager
        if (current?.connected != true) return "SSH disconnected"
        return "SSH connected • $pageState" + if (current.forwardingRestoreErrors.isEmpty()) "" else " • fixed forward errors"
    }

    private fun showOpenPortDialog() {
        val input = EditText(this).apply {
            hint = "3000"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(22), dp(4), dp(22), 0)
        }
        val fixedInput = EditText(this).apply {
            hint = getString(R.string.android_port_auto_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val remember = CheckBox(this).apply {
            text = getString(R.string.remember_port_binding)
            isChecked = true
        }
        val store = ConnectionStore(this)
        input.doAfterTextChanged { value ->
            val remote = value.toString().toIntOrNull()
            fixedInput.setText(store.get(connection.id)?.fixedPortBindings?.get(remote)?.toString().orEmpty())
        }
        val copy = Button(this).apply { text = getString(R.string.copy_address); isAllCaps = false }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(TextView(this@SshCodeActivity).apply { text = getString(R.string.forward_remote_port) })
            addView(input)
            addView(TextView(this@SshCodeActivity).apply { text = getString(R.string.android_port) })
            addView(fixedInput)
            addView(remember)
            val saved = store.get(connection.id)?.fixedPortBindings.orEmpty()
            if (saved.isNotEmpty()) addView(TextView(this@SshCodeActivity).apply {
                text = getString(R.string.saved_port_bindings) + "\n" + saved.toSortedMap().entries.joinToString("\n") { "${it.key} → 127.0.0.1:${it.value}" }
            })
            manager?.forwardingRestoreErrors?.takeIf { it.isNotEmpty() }?.let { errors ->
                addView(TextView(this@SshCodeActivity).apply { text = errors.joinToString("\n") })
            }
            addView(copy)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.open_port_url)
            .setMessage(R.string.fixed_forward_message)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.open_in_browser, null)
            .setPositiveButton(R.string.open_in_app, null)
            .create()
        dialog.setOnShowListener {
            fun openForwardedPort(destination: Int) {
                val remotePort = input.text.toString().toIntOrNull() ?: 0
                if (remotePort !in 1..65535) {
                    input.error = getString(R.string.invalid_remote_port)
                    return
                }
                val localText = fixedInput.text.toString().trim()
                val requested = if (localText.isEmpty()) 0 else localText.toIntOrNull()
                if (requested == null || (localText.isNotEmpty() && requested !in 1..65535)) {
                    fixedInput.error = getString(R.string.invalid_android_port)
                    return
                }
                try {
                    if (remember.isChecked) {
                        val saved = checkNotNull(store.get(connection.id)) { "Connection settings were removed" }
                        PortBindings.updated(saved.fixedPortBindings, remotePort, requested)
                    }
                    val sshManager = manager ?: error("SSH session is not connected")
                    val localPort = sshManager.forwardRemotePort(remotePort, requested)
                    if (remember.isChecked) connection = store.rememberPortBinding(connection.id, remotePort, requested)
                    val url = "http://127.0.0.1:$localPort/"
                    dialog.dismiss()
                    if (destination == 0) {
                        copyForwardUrl(this, url)
                    } else if (destination == 1) {
                        startActivity(
                            PortActivity.intent(this, url, "Port $remotePort").addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
                            )
                        )
                    } else {
                        openInBrowser(url)
                    }
                } catch (error: Exception) {
                    if (requested > 0) fixedInput.error = getString(R.string.fixed_port_failed, requested, error.message.orEmpty())
                    else input.error = error.message.orEmpty()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                openForwardedPort(destination = 1)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                openForwardedPort(destination = 2)
            }
            copy.setOnClickListener { openForwardedPort(destination = 0) }
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
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setMimeType(mimeType)
            setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@SshCodeActivity,
                Environment.DIRECTORY_DOWNLOADS,
                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    private fun closePopup(child: WebView) {
        val holder = popupHolders.remove(child) ?: return
        popupExternalOrigins.remove(child)
        webContainer.removeView(holder)
        child.stopLoading()
        child.destroy()
    }

    private fun navigateBack() {
        val latestPopup = popupHolders.keys.lastOrNull()
        if (latestPopup != null) {
            closePopup(latestPopup)
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        popupHolders.keys.toList().forEach { closePopup(it) }
        manager?.close()
        executor.shutdownNow()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = CodeActivity.EXTRA_CONNECTION_ID
        private const val MENU_NEW_WINDOW = 200
        private const val MENU_CONNECTIONS = 201
        private const val MENU_RELOAD = 202
        private const val MENU_OPEN_PORT = 203
        private const val MENU_SETTINGS = 204
        const val EXTRA_INITIAL_PATH = "initial_path"
    }
}
