package com.example.dexcode.ui

import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.graphics.Typeface
import android.graphics.Color
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import com.example.dexcode.RemoteFileProvider
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import com.example.dexcode.util.dp
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.dexcode.R
import com.example.dexcode.ssh.SshSessionManager
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.util.UUID
import java.util.concurrent.Executor

class ProjectFilesDialog(
    private val activity: AppCompatActivity,
    private val manager: SshSessionManager,
    private val executor: Executor,
    initialPath: String,
    private val chooseFolder: () -> Unit,
) {
    private data class Entry(val name: String, val directory: Boolean, val uri: Uri? = null)

    private data class CopiedFile(val entry: Entry, val remoteSource: String?)
    private var copied: CopiedFile? = null

    private val resolver = activity.contentResolver
    private var remotePath = initialPath
    private var tree: Uri? = null
    private val localPath = ArrayList<Entry>()
    private val colors = AppSurface(activity, activity.getSharedPreferences("desktop_header", 0).getBoolean("dark", true))
    private val status = colors.label("", 12f, secondary = true).apply {
        setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
        visibility = View.GONE
    }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        visibility = View.INVISIBLE
        indeterminateTintList = android.content.res.ColorStateList.valueOf(colors.accent)
    }
    private val refresh = iconButton(HeaderIcon.RELOAD, R.string.files_refresh) { reload() }
    private val folder = iconButton(HeaderIcon.FOLDER, R.string.files_choose) { chooseFolder() }
    private val close = iconButton(HeaderIcon.CLOSE, android.R.string.cancel) { dismiss() }
    private var busy = false
    private var remoteLoaded = false
    private var attached = false
    private var showPhone = false
    private var refreshPaneLayout: () -> Unit = {}

    private fun iconButton(glyph: HeaderIcon, label: Int, action: () -> Unit) = ImageView(activity).apply {
        setImageDrawable(HeaderIconDrawable(glyph, colors.accent, activity.dp(21)))
        scaleType = ImageView.ScaleType.CENTER
        background = colors.ripple(colors.background)
        contentDescription = activity.getString(label)
        tooltipText = contentDescription
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(activity.dp(48), activity.dp(48))
    }

    private inner class Pane(title: Int, action: Int, private val server: Boolean, click: () -> Unit) {
        val label = colors.label("", 12f, secondary = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        val upButton = iconButton(HeaderIcon.UP, R.string.files_parent) { open(null) }
        val pasteButton = iconButton(HeaderIcon.PASTE, R.string.files_paste) { paste(server) }
        val menu = iconButton(HeaderIcon.MORE, R.string.files_actions) { showActions(this, server) }
        val list = ListView(activity).apply {
            divider = null
            clipToPadding = false
            setPadding(activity.dp(4), 0, activity.dp(4), 0)
        }
        val transfer = colors.button(activity.getString(action), primary = true, action = click)
        val selectionActions = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(transfer, LinearLayout.LayoutParams(0, -2, 1f))
            addView(menu)
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = colors.shape()
            setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(8))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(upButton)
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(colors.label(activity.getString(title), 15f).apply { setTypeface(null, Typeface.BOLD) })
                    addView(label)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (!server) addView(folder)
                addView(pasteButton)
            })
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(selectionActions)
        }
        var selected: Entry? = null
        private var open: (Entry?) -> Unit = {}
        private var canGoUp = false

        fun updateControls() {
            selectionActions.visibility = if (selected != null) View.VISIBLE else View.GONE
            menu.isEnabled = !busy && selected != null
            menu.alpha = if (menu.isEnabled) 1f else 0.35f
            pasteButton.isEnabled = !busy && copied != null && (if (server) remoteLoaded else tree != null)
            pasteButton.alpha = if (pasteButton.isEnabled) 1f else 0.35f
            upButton.isEnabled = canGoUp && !busy
            upButton.alpha = if (upButton.isEnabled) 1f else 0.35f
            transfer.alpha = if (transfer.isEnabled) 1f else 0.35f
        }

        fun render(items: List<Entry>, up: Boolean, path: String, onOpen: (Entry?) -> Unit) {
            selected = null
            open = onOpen
            canGoUp = up
            label.text = path
            label.tooltipText = path
            val adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(position: Int) = items[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val entry = getItem(position)
                    val active = entry == selected
                    val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = activity.dp(54)
                        setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
                        addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER },
                            LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
                        addView(LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(activity.dp(10), 0, 0, 0)
                            addView(colors.label("", 14f).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.MIDDLE
                            })
                            addView(colors.label("", 11f, secondary = true))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    row.background = colors.ripple(if (active) colors.line else colors.card)
                    row.isSelected = active
                    val (badge, tint) = typeBadge(entry)
                    (row.getChildAt(0) as ImageView).apply {
                        background = colors.shape(android.graphics.Color.argb(55, Color.red(tint), Color.green(tint), Color.blue(tint)), 10, false)
                        setImageDrawable(HeaderIconDrawable(
                            if (entry.directory) HeaderIcon.FOLDER else HeaderIcon.FILE,
                            tint, activity.dp(20),
                        ))
                    }
                    val details = row.getChildAt(1) as LinearLayout
                    (details.getChildAt(0) as TextView).apply {
                        text = entry.name
                        tooltipText = entry.name
                    }
                    (details.getChildAt(1) as TextView).apply {
                        text = badge
                        setTextColor(tint)
                        visibility = if (entry.directory) View.GONE else View.VISIBLE
                    }
                    return row
                }
            }
            list.adapter = adapter
            list.setOnItemLongClickListener { _, _, index, _ ->
                selected = items[index]
                adapter.notifyDataSetChanged()
                controls()
                showActions(this, server)
                true
            }
            list.setOnItemClickListener { _, _, index, _ ->
                val entry = items[index]
                if (entry.directory) open(entry) else {
                    selected = entry.takeUnless { it == selected }
                    adapter.notifyDataSetChanged()
                }
                controls()
            }
        }
    }

    private val remote = Pane(R.string.files_remote, R.string.files_download, true) { download() }
    private val local = Pane(R.string.files_local, R.string.files_upload, false) { upload() }

    fun show() {
        val panes = LinearLayout(activity).apply {
            addView(remote.view, LinearLayout.LayoutParams(0, -1, 1f))
            addView(local.view, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = activity.dp(10) })
        }
        val remoteTab = colors.button(activity.getString(R.string.files_remote)) { showPhone = false; panes.requestLayout() }
        val localTab = colors.button(activity.getString(R.string.files_local)) { showPhone = true; panes.requestLayout() }
        val tabs = LinearLayout(activity).apply {
            addView(remoteTab, LinearLayout.LayoutParams(0, -2, 1f))
            addView(localTab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = colors.shape(colors.background, 20, false)
            clipToOutline = true
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(12))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(colors.label(activity.getString(R.string.project_files), 20f).apply {
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(refresh)
                addView(close)
            })
            addView(progress, LinearLayout.LayoutParams(-1, activity.dp(3)))
            addView(tabs)
            addView(status)
            addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        fun updateLayout() {
            val wide = content.width >= activity.dp(620)
            tabs.visibility = if (wide) View.GONE else View.VISIBLE
            remote.view.visibility = if (wide || !showPhone) View.VISIBLE else View.GONE
            local.view.visibility = if (wide || showPhone) View.VISIBLE else View.GONE
            val params = local.view.layoutParams as LinearLayout.LayoutParams
            val gap = if (wide) activity.dp(10) else 0
            if (params.marginStart != gap) { params.marginStart = gap; local.view.layoutParams = params }
            remoteTab.background = colors.ripple(if (showPhone) colors.card else colors.line)
            localTab.background = colors.ripple(if (showPhone) colors.line else colors.card)
            remoteTab.isSelected = !showPhone
            localTab.isSelected = showPhone
        }
        remoteTab.setOnClickListener { showPhone = false; updateLayout() }
        localTab.setOnClickListener { showPhone = true; updateLayout() }
        content.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateLayout()
        }
        refreshPaneLayout = ::updateLayout
        activity.setContentView(content)
        attached = true
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!busy) activity.finish() }
        })
        updateLayout()
        reload()
    }

    fun dismiss() { if (!busy) activity.finish() }

    fun setFolder(uri: Uri) {
        if (!attached || activity.isFinishing) return
        local.render(emptyList(), false, "", {})
        showPhone = true
        refreshPaneLayout()
        tree = uri
        localPath.clear()
        reload()
    }

    private fun controls() {
        refresh.isEnabled = !busy
        folder.isEnabled = !busy
        remote.list.isEnabled = !busy
        local.list.isEnabled = !busy
        remote.transfer.isEnabled = !busy && remote.selected != null && tree != null
        local.transfer.isEnabled = !busy && local.selected != null && remoteLoaded
        remote.updateControls()
        local.updateControls()
        close.isEnabled = !busy
        for (button in listOf(refresh, folder, close)) button.alpha = if (button.isEnabled) 1f else 0.35f
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun <T> work(success: Int? = null, task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        progress.isIndeterminate = true
        status.setText(R.string.files_working)
        controls()
        executor.execute {
            val result = runCatching(task)
            activity.runOnUiThread {
                if (!attached || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess {
                    status.text = success?.let { activity.getString(it) }.orEmpty()
                    done(it)
                }.onFailure {
                    status.text = activity.getString(R.string.files_failed, it.message.orEmpty())
                }
                controls()
            }
        }
    }

    private fun localDirectory(root: Uri): Uri =
        localPath.lastOrNull()?.uri ?: Docs.buildDocumentUriUsingTree(root, Docs.getTreeDocumentId(root))

    private fun reload(loaded: Int? = null, path: String = remotePath): Unit = work(loaded, {
        val remoteResult = manager.withSftp { channel ->
            channel.cd(literal(path))
            val resolved = channel.pwd()
            val entries = channel.ls(literal(resolved)).filter { it.filename !in setOf(".", "..") && !it.attrs.isLink }
                .map { Entry(it.filename, it.attrs.isDir) }
            resolved to sorted(entries)
        }
        val localEntries = tree?.let { root ->
            val parent = localDirectory(root)
            val children = Docs.buildChildDocumentsUriUsingTree(root, Docs.getDocumentId(parent))
            val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
            val cursor = checkNotNull(resolver.query(children, columns, null, null, null))
            cursor.use {
                buildList {
                    while (it.moveToNext()) add(Entry(it.getString(1), it.getString(2) == Docs.Document.MIME_TYPE_DIR,
                        Docs.buildDocumentUriUsingTree(root, it.getString(0))))
                }
            }
        }.orEmpty()
        remoteResult to sorted(localEntries)
    }, { (server, phone) ->
        remotePath = server.first
        remoteLoaded = true
        remote.render(server.second, remotePath != "/", remotePath) { entry ->
            val path = entry?.let { child(remotePath, it.name) }
                ?: remotePath.substringBeforeLast('/').ifEmpty { "/" }
            reload(path = path)
        }
        local.render(phone, localPath.isNotEmpty(), if (tree == null) activity.getString(R.string.files_choose)
            else localPath.joinToString("/", prefix = "/") { it.name }) { entry ->
            if (entry != null) localPath.add(entry) else localPath.removeAt(localPath.lastIndex)
            local.selected = null
            local.transfer.isEnabled = false
            // Clear the old listing before using the new destination.
            reloadLocal()
        }
    })

    private fun reloadLocal() {
        // Hide the previous listing immediately so it cannot be used with a new destination.
        local.render(emptyList(), false, "", {})
        reload()
    }

    private fun transferMonitor(name: String, expectedBytes: Long?) = object : com.jcraft.jsch.SftpProgressMonitor {
        private var bytes = 0L
        private var lastUpdate = 0L
        private var total: Long? = expectedBytes

        override fun init(op: Int, src: String?, dest: String?, max: Long) {
            if (total == null && max >= 0L) total = max
            report()
        }

        override fun count(count: Long): Boolean {
            if (activity.isDestroyed) return false
            bytes += count
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 150) {
                lastUpdate = now
                report()
            }
            return true
        }

        override fun end() { report() }

        private fun report() {
            val transferred = bytes
            val size = total
            activity.runOnUiThread {
                if (!attached || activity.isFinishing || !busy) return@runOnUiThread
                val sent = android.text.format.Formatter.formatShortFileSize(activity, transferred)
                progress.isIndeterminate = size == null
                if (size != null) {
                    progress.progress = if (size > 0L) (100.0 * transferred / size).toInt().coerceIn(0, 99) else 0
                    status.text = activity.getString(R.string.files_progress_total, name, sent,
                        android.text.format.Formatter.formatShortFileSize(activity, size), progress.progress)
                } else {
                    status.text = activity.getString(R.string.files_progress_unknown, name, sent)
                }
            }
        }
    }

    private fun typeBadge(entry: Entry): Pair<String, Int> {
        val extension = entry.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val color = when {
            entry.directory -> 0xFFFFBD69
            extension in setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "avif") -> 0xFFE6A2FF
            extension in setOf("mp4", "webm", "mov", "mp3", "wav", "flac") -> 0xFFFF9DB0
            extension in setOf("zip", "gz", "tar", "7z", "rar", "apk", "aab") -> 0xFFFFCC80
            extension in setOf("pdf", "docx", "md", "txt", "xlsx", "csv") -> 0xFF82DDB3
            extension in setOf("html", "js", "ts", "tsx", "json", "yml", "yaml", "kt", "py", "css", "sh") -> 0xFF8CCAFF
            else -> 0xFFB3BED1
        }
        val tint = if (colors.dark) color.toInt() else Color.rgb(
            Color.red(color.toInt()) * 52 / 100, Color.green(color.toInt()) * 52 / 100, Color.blue(color.toInt()) * 52 / 100,
        )
        return extension.take(12).uppercase(java.util.Locale.ROOT).ifEmpty { activity.getString(R.string.files_generic) } to tint
    }

    private fun showActions(pane: Pane, server: Boolean) {
        if (busy) return
        val item = pane.selected ?: return
        PopupMenu(activity, pane.menu).apply {
            fun action(label: Int, enabled: Boolean = true, callback: () -> Unit) {
                menu.add(label).setEnabled(enabled).setOnMenuItemClickListener { callback(); true }
            }
            if (!item.directory) {
                action(R.string.files_open_with) { openWith(item, server) }
                action(R.string.files_copy) {
                    copied = CopiedFile(item, if (server) child(remotePath, item.name) else null)
                    status.text = activity.getString(R.string.files_copied, item.name)
                    controls()
                }
            }
            action(R.string.files_delete) { confirmDelete(item, server) }
            show()
        }
    }

    private fun confirmDelete(item: Entry, server: Boolean) {
        val source = child(remotePath, item.name)
        AlertDialog.Builder(activity).setTitle(R.string.files_delete)
            .setMessage(activity.getString(if (item.directory) R.string.files_folder_delete_confirm else R.string.files_delete_confirm, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.files_delete) { _, _ ->
                work(R.string.files_deleted, {
                    if (server) manager.withSftp {
                        requireName(item.name)
                        if (item.directory) it.rmdir(literal(source)) else it.rm(literal(source))
                    } else {
                        check(Docs.deleteDocument(resolver, checkNotNull(item.uri)))
                    }
                }, { reload(R.string.files_deleted) })
            }.show()
    }

    private fun openWith(item: Entry, server: Boolean) {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(item.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
            ?: "application/octet-stream"
        fun launch(uri: Uri) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(Intent.createChooser(view, activity.getString(R.string.files_open_with)))
            } catch (_: ActivityNotFoundException) {
                status.setText(R.string.files_no_viewer)
                controls()
            }
        }
        if (!server) {
            item.uri?.let(::launch)
            return
        }
        val path = literal(child(remotePath, item.name))
        work(task = {
            requireName(item.name)
            manager.withSftp { channel ->
                val attrs = channel.lstat(path)
                check(attrs.isReg)
                RemoteFileProvider.share(activity, manager, path, item.name, mime, attrs.size)
            }
        }, done = ::launch)
    }

    private fun paste(toServer: Boolean) {
        val item = copied ?: return
        val destination = child(remotePath, item.entry.name)
        val phoneFolder = tree?.let(::localDirectory)
        work(R.string.files_done, {
            requireName(item.entry.name)
            if (item.remoteSource != null) {
                manager.withSftp { source ->
                    val path = literal(item.remoteSource)
                    val attrs = source.lstat(path)
                    check(!attrs.isDir && !attrs.isLink)
                    if (toServer) {
                        source.get(path).use { stream -> writeRemote(destination, item.entry.name, attrs.size, stream) }
                    } else {
                        writePhone(checkNotNull(phoneFolder), item.entry.name) {
                            source.get(path, it, transferMonitor(item.entry.name, attrs.size))
                        }
                    }
                }
            } else {
                val uri = checkNotNull(item.entry.uri)
                checkNotNull(resolver.openInputStream(uri)).use { stream ->
                    if (toServer) writeRemote(destination, item.entry.name, phoneSize(uri), stream)
                    else writePhone(checkNotNull(phoneFolder), item.entry.name) { output ->
                        val monitor = transferMonitor(item.entry.name, phoneSize(uri))
                        monitor.init(0, null, null, -1)
                        val buffer = ByteArray(65536)
                        while (true) {
                            val size = stream.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size)
                            check(monitor.count(size.toLong()))
                        }
                        monitor.end()
                    }
                }
            }
        }, { reload(R.string.files_done) })
    }

    private fun writePhone(parent: Uri, name: String, write: (java.io.OutputStream) -> Unit) {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
            ?: "application/octet-stream"
        val target = checkNotNull(Docs.createDocument(resolver, parent, mime, name))
        try {
            checkNotNull(resolver.openOutputStream(target, "w")).use(write)
        } catch (error: Exception) {
            runCatching { Docs.deleteDocument(resolver, target) }
            throw error
        }
    }

    private fun phoneSize(uri: Uri): Long? =
        resolver.query(uri, arrayOf(Docs.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { size -> size >= 0L } else null
        }

    private fun writeRemote(destination: String, name: String, size: Long?, input: java.io.InputStream) {
        val temporary = child(destination.substringBeforeLast('/').ifEmpty { "/" }, ".dexcode-${UUID.randomUUID()}.part")
        manager.withSftp { channel ->
            fun checkDestination() {
                try {
                    channel.lstat(literal(destination))
                    error(activity.getString(R.string.files_exists))
                } catch (error: SftpException) {
                    if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
                }
            }
            checkDestination()
            try {
                channel.put(input, literal(temporary), transferMonitor(name, size), ChannelSftp.OVERWRITE)
                checkDestination()
                channel.rename(literal(temporary), literal(destination))
            } finally {
                runCatching { channel.rm(literal(temporary)) }
            }
        }
    }

    private fun download() {
        val item = remote.selected ?: return
        val root = tree ?: return
        val parent = localDirectory(root)
        val source = child(remotePath, item.name)
        work(R.string.files_done, {
            requireName(item.name)
            manager.withSftp { channel ->
                val attributes = channel.lstat(literal(source))
                check(!attributes.isDir && !attributes.isLink)
                writePhone(parent, item.name) {
                    channel.get(literal(source), it, transferMonitor(item.name, attributes.size))
                }
            }
        }, { reload(R.string.files_done) })
    }

    private fun upload() {
        val item = local.selected ?: return
        val source = item.uri ?: return
        val destination = child(remotePath, item.name)
        work(R.string.files_done, {
            requireName(item.name)
            checkNotNull(resolver.openInputStream(source)).use {
                writeRemote(destination, item.name, phoneSize(source), it)
            }
        }, { reload(R.string.files_done) })
    }

    private fun sorted(items: List<Entry>) = items.sortedWith(
        compareByDescending<Entry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )

    private fun child(parent: String, name: String) = parent.trimEnd('/') + "/" + name

    private fun requireName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name)
    }

    private fun literal(path: String) = buildString {
        for (character in path) {
            if (character in "\\*?") append('\\')
            append(character)
        }
    }
}
