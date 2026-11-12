package com.example.dexcode.download

import android.app.DownloadManager
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.dexcode.R
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** HTTP goes to DownloadManager; browser-local data is streamed to a user-selected document. */
class WebDownloads(private val activity: AppCompatActivity) : DefaultLifecycleObserver {
    private data class Saved(val file: File, val name: String)
    private val worker = Executors.newSingleThreadExecutor()
    private val transfers = mutableMapOf<String, DownloadTransfer>() // worker thread only
    private val queue = java.util.ArrayDeque<Saved>() // UI thread only
    private var saving: Saved? = null
    private var failureDialog: AlertDialog? = null
    @Volatile private var closed = false
    private val script = activity.assets.open("download.js").bufferedReader().use { it.readText() }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = saving
        saving = null
        if (item != null && !closed) worker.execute {
            val success = uri != null && runCatching {
                checkNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { output -> item.file.inputStream().use { it.copyTo(output) } }
            }.isSuccess
            item.file.delete()
            activity.runOnUiThread {
                if (uri != null) {
                    if (success) notify(R.string.download_saved) else failure("document_write_failed")
                }
            }
        }
        nextSave()
    }

    init { activity.lifecycle.addObserver(this) }

    fun attach(view: WebView) {
        val messages = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (messages) {
            WebViewCompat.addWebMessageListener(view, "DeXCodeDownload", setOf("*")) { _, message, origin, _, reply ->
                if (message.type != androidx.webkit.WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                val raw = message.data.orEmpty()
                if (closed) return@addWebMessageListener
                if (raw.length > 70000) { failure("message_too_large"); return@addWebMessageListener }
                val trusted = DownloadPolicy.sameOrigin(origin.toString(), view.url)
                worker.execute {
                    val response = JSONObject()
                    val request = runCatching { JSONObject(raw) }.getOrNull()
                    response.put("id", request?.optInt("id", -1) ?: -1)
                    val outcome = runCatching {
                        check(trusted && request != null)
                        process(request)
                    }
                    val success = outcome.isSuccess
                    val phase = request?.optString("op").orEmpty()
                    val code = if (!trusted) "frame_origin_denied" else when (phase) {
                        "begin" -> "receive_begin_failed"
                        "chunk" -> "receive_chunk_failed"
                        "end" -> "receive_end_failed"
                        else -> "invalid_request"
                    }
                    response.put("ok", success)
                    if (!success) {
                        response.put("error", code)
                        response.put("exception", outcome.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
                        if (!trusted) activity.runOnUiThread { failure(code, phase) }
                        request?.optString("transfer")?.let { transfers.remove(it)?.abort() }
                        // The browser reports one abort with the final phase/reason. Avoid duplicate popups.
                    }
                    view.post { if (!closed) runCatching { reply.postMessage(response.toString()) } }
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
            }
        }
        view.setDownloadListener { url, agent, disposition, mime, _ ->
            when (DownloadPolicy.scheme(url)) {
                "http", "https" -> enqueueHttp(url, agent, disposition, mime)
                "blob", "data" -> {
                    if (!messages) {
                        failure("web_message_unavailable")
                    } else {
                        val name = DownloadPolicy.filename(URLUtil.guessFileName(url, disposition, mime))
                        view.evaluateJavascript(script, null)
                        view.evaluateJavascript("window.__dexcodeDownload && window.__dexcodeDownload(${JSONObject.quote(url)},${JSONObject.quote(name)},${JSONObject.quote(mime.orEmpty())})", null)
                    }
                }
                else -> failure("unsupported_scheme")
            }
        }
    }

    fun install(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) view.evaluateJavascript(script, null)
    }

    private fun process(request: JSONObject) {
        val key = request.getString("transfer")
        require(key.length <= 100)
        when (request.getString("op")) {
            "begin" -> {
                val size = request.getLong("size")
                require(size in 0..134217728 && transfers.size < 3 && !transfers.containsKey(key))
                val file = File.createTempFile("dexcode-download-", ".tmp", activity.cacheDir)
                transfers[key] = DownloadTransfer(file, DownloadPolicy.filename(request.optString("filename")), size)
                activity.runOnUiThread { notify(R.string.download_started) }
            }
            "chunk" -> {
                val transfer = checkNotNull(transfers[key])
                transfer.append(request.getString("data"))
            }
            "end" -> {
                val transfer = checkNotNull(transfers[key])
                transfer.complete()
                transfers.remove(key)
                activity.runOnUiThread {
                    if (closed) transfer.file.delete()
                    else { queue.add(Saved(transfer.file, transfer.name)); nextSave() }
                }
            }
            "abort" -> {
                transfers.remove(key)?.abort()
                activity.runOnUiThread { failure(request.optString("reason", "browser_aborted"), request.optString("phase", "unknown"), request.optString("nativeType")) }
            }
            else -> error("Unsupported transfer")
        }
    }

    private fun nextSave() {
        if (saving != null || closed || queue.isEmpty() || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val item = queue.remove()
        saving = item
        runCatching { picker.launch(item.name) }.onFailure {
            saving = null; item.file.delete(); failure("document_picker_unavailable"); nextSave()
        }
    }

    private fun enqueueHttp(url: String?, agent: String?, disposition: String?, mime: String?) {
        val success = runCatching {
            require(DownloadPolicy.scheme(url) in setOf("http", "https"))
            val name = DownloadPolicy.filename(URLUtil.guessFileName(url, disposition, mime))
            val request = DownloadManager.Request(android.net.Uri.parse(url)).apply {
                agent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                mime?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT >= 29) setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                else setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
            }
            checkNotNull(activity.getSystemService(DownloadManager::class.java)).enqueue(request)
        }.isSuccess
        if (success) notify(R.string.download_started) else failure("http_enqueue_failed")
    }

    private fun failure(rawCode: String, rawPhase: String = "native", rawType: String = "") {
        if (closed || activity.isFinishing || activity.isDestroyed || failureDialog?.isShowing == true) return
        val code = rawCode.takeIf { it.matches(Regex("[a-z_]{1,48}")) } ?: "unknown_error"
        val phase = rawPhase.takeIf { it.matches(Regex("[a-z_]{1,16}")) } ?: "unknown"
        val detail = when (code) {
            "temporary_url_unavailable" -> R.string.download_temporary_file_unavailable
            "frame_origin_denied" -> R.string.download_frame_denied
            "file_too_large" -> R.string.download_size_limit
            "document_picker_unavailable", "document_write_failed" -> R.string.download_save_failed
            else -> R.string.download_transfer_failed
        }
        val version = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(activity)?.versionName.orEmpty()
        val type = rawType.takeIf { it.matches(Regex("[A-Za-z_]{1,48}")) }.orEmpty()
        val diagnostics = "DeXCode download\nCode: $code\nPhase: $phase\nException: $type\nWebView: $version"
        failureDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.download_failed_title)
            .setMessage(activity.getString(detail) + "\n\n" + diagnostics)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.copy_diagnostics) { _, _ ->
                activity.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Download diagnostics", diagnostics))
            }.create().also { it.show() }
    }

    private fun notify(message: Int) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }

    override fun onStart(owner: LifecycleOwner) { nextSave() }

    override fun onDestroy(owner: LifecycleOwner) {
        closed = true
        failureDialog?.dismiss()
        saving?.file?.delete()
        queue.forEach { it.file.delete() }; queue.clear()
        worker.execute {
            transfers.values.forEach { it.abort() }
            transfers.clear()
        }
        worker.shutdown()
        activity.lifecycle.removeObserver(this)
    }
}
