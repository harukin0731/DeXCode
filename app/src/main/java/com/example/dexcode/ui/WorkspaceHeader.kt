package com.example.dexcode.ui

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.widget.PopupMenu
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.dexcode.MainActivity
import com.example.dexcode.R
import com.example.dexcode.runtime.RuntimeState
import com.example.dexcode.runtime.RuntimeTracker
import com.example.dexcode.util.dp

/** App-owned workspace tabs, never a duplicate of VS Code's file/editor tabs. */
class WorkspaceHeader(
    private val activity: AppCompatActivity,
    private val root: LinearLayout,
    private val toolbar: Toolbar,
    private val title: String,
    private val onPorts: () -> Unit,
    private val onReconnect: () -> Unit,
    private val onReload: () -> Unit,
    private val onSettings: () -> Unit,
    private val onDiagnostics: (() -> Unit)? = null,
    private val editor: () -> WebView,
) : DefaultLifecycleObserver {
    private val titlebarBridge by lazy { TitlebarBridge(editor()) }
    private val titleControls = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private var mirroredControls = emptyList<TitlebarBridge.Control>()
    private var mirroredPalette: Boolean? = null
    private val editorActions by lazy { EditorActions(editor()) }
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences("desktop_header", 0)
    private var dark = preferences.getBoolean("dark", true)
    private val ink get() = if (dark) Color.rgb(225, 229, 238) else Color.rgb(35, 42, 55)
    private val surface get() = if (dark) Color.rgb(35, 39, 47) else Color.rgb(242, 244, 249)
    private val accent get() = if (dark) Color.rgb(126, 180, 255) else Color.rgb(36, 97, 185)
    private val tabs = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val status = TextView(activity).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
        setOnClickListener { showStatus() }
    }
    private lateinit var search: TextView
    private lateinit var home: TextView
    private lateinit var add: TextView
    private lateinit var chooser: TextView
    private lateinit var row: LinearLayout
    private var lastPhase = ""
    private var forwardCount = 0
    private var compactStatus = false
    private var lastWindows: Any? = null
    private var active = false
    private var integrated = false
    private val update: () -> Unit = { handler.post { if (active) refresh() } }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        activity.supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.title = ""
        toolbar.navigationIcon = null
        toolbar.setContentInsetsRelative(0, 0)
        row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        home = button("▦", activity.getString(R.string.header_connections)) { connections() }
        row.addView(home, LinearLayout.LayoutParams(activity.dp(36), -1))
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        chooser = button("▾", activity.getString(R.string.header_all_workspaces)) { showWorkspaces() }
        row.addView(chooser, LinearLayout.LayoutParams(activity.dp(30), -1))
        add = button("+", activity.getString(R.string.new_window)) { newWindow() }
        row.addView(add, LinearLayout.LayoutParams(activity.dp(34), -1))
        search = button(activity.getString(R.string.header_search), activity.getString(R.string.header_search_description)) {
            editorActions.run(EditorActions.Action.FILES)
        }.apply {
            setOnLongClickListener { editorActions.run(EditorActions.Action.COMMANDS); true }
        }
        row.addView(search, LinearLayout.LayoutParams(activity.dp(230), activity.dp(30)).apply {
            leftMargin = activity.dp(10); rightMargin = activity.dp(10)
        })
        val controlsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(titleControls)
        }
        row.addView(controlsScroll, LinearLayout.LayoutParams(activity.dp(150), -1))
        row.addView(status, LinearLayout.LayoutParams(activity.dp(110), activity.dp(30)))
        row.addView(button("⋮", activity.getString(R.string.header_unified_menu)) { showStatus() }, LinearLayout.LayoutParams(activity.dp(32), -1))
        // This blank strip is deliberately excluded from interactive gesture regions.
        row.addView(View(activity), LinearLayout.LayoutParams(activity.dp(20), -1))
        toolbar.addView(row, Toolbar.LayoutParams(-1, -1))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val sizing = HeaderSizing.forWidth((row.width / activity.resources.displayMetrics.density).toInt())
            compactStatus = sizing.compactStatus
            val label = activity.getString(if (sizing.compactSearch) R.string.header_commands_compact else R.string.header_search)
            if (search.text.toString() != label) search.text = label
            fun resize(view: View, width: Int) {
                if (view.layoutParams.width != activity.dp(width)) {
                    view.layoutParams = view.layoutParams.apply { this.width = activity.dp(width) }
                }
            }
            resize(search, sizing.searchWidth)
            resize(status, sizing.statusWidth)
            val showControls = row.width >= activity.dp(850)
            controlsScroll.visibility = if (showControls) View.VISIBLE else View.GONE
            updateStatus()
            updateExclusions()
        }
        applyPalette()
        toolbar.layoutParams = toolbar.layoutParams.apply { height = activity.dp(44) }
        configureCaption()
        activity.lifecycle.addObserver(this)
        refresh()
    }

    private fun pill(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = activity.dp(6).toFloat()
        setStroke(activity.dp(1), stroke)
    }

    private fun button(label: String, description: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        contentDescription = description
        tooltipText = description
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        setPadding(activity.dp(10), 0, activity.dp(10), 0)
        setTextColor(ink)
        background = RippleDrawable(ColorStateList.valueOf(0x337faaff), pill(Color.TRANSPARENT), null)
        setOnClickListener { action() }
    }

    private fun applyPalette() {
        toolbar.setBackgroundColor(surface)
        root.background = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint()
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawColor(Color.WHITE)
                paint.color = surface
                canvas.drawRect(0f, 0f, bounds.width().toFloat(), (root.paddingTop + toolbar.height).toFloat(), paint)
            }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }
        toolbar.overflowIcon?.setTint(ink)
        listOf(home, add, chooser, search).forEach { it.setTextColor(ink) }
        search.background = RippleDrawable(ColorStateList.valueOf(0x337faaff),
            pill(if (dark) Color.rgb(48, 54, 65) else Color.WHITE, if (dark) Color.rgb(73, 80, 94) else Color.rgb(207, 214, 226)), null)
        status.background = RippleDrawable(ColorStateList.valueOf(0x337faaff), pill(Color.TRANSPARENT), null)
        if (Build.VERSION.SDK_INT >= 35) {
            val mask = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val appearance = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
                if (dark) 0 else (WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            activity.window.insetsController?.setSystemBarsAppearance(appearance, mask)
        }
        lastWindows = null
    }

    private fun newWindow() {
        val id = activity.intent.getStringExtra(com.example.dexcode.CodeActivity.EXTRA_CONNECTION_ID) ?: return connections()
        activity.startActivity(Intent(activity, activity.javaClass)
            .putExtra(com.example.dexcode.CodeActivity.EXTRA_CONNECTION_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
    }

    private fun showWorkspaces() {
        val windows = RuntimeState.store.snapshotWindows().filter { it.type == "SSH workspace" || it.type == "Web editor" }
        val popup = PopupMenu(activity, chooser)
        windows.forEachIndexed { index, window ->
            popup.menu.add(0, index, index, "${window.title} · ${window.phase}").apply {
                isCheckable = true
                isChecked = window.id == RuntimeTracker.id(activity)
            }
        }
        popup.setOnMenuItemClickListener { item -> focusWindow(windows[item.itemId].taskId); true }
        popup.show()
    }

    private fun focusWindow(id: Int) {
        val moved = runCatching {
            val task = activity.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                @Suppress("DEPRECATION")
                val taskId = if (Build.VERSION.SDK_INT >= 29) it.taskInfo.taskId else it.taskInfo.id
                taskId == id
            }
            if (task == null) false else { task.moveToFront(); true }
        }.getOrDefault(false)
        if (!moved) Toast.makeText(activity, R.string.header_window_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun showStatus() {
        val popup = PopupMenu(activity, status)
        popup.menu.add(lastPhase).isEnabled = false
        popup.menu.add(0, 1, 1, activity.getString(R.string.header_forward_count, forwardCount))
        val editorMenu = popup.menu.addSubMenu(R.string.header_editor_menu)
        editorMenu.add(0, 7, 0, R.string.header_file_search)
        editorMenu.add(0, 2, 1, R.string.header_command_palette)
        editorMenu.add(0, 3, 2, R.string.header_terminal)
        val connectionMenu = popup.menu.addSubMenu(R.string.header_connection_menu)
        connectionMenu.add(0, 1, 0, R.string.open_port_url)
        connectionMenu.add(0, 4, 1, R.string.header_reconnect)
        connectionMenu.add(0, 8, 2, R.string.reload)
        connectionMenu.add(0, 9, 3, R.string.connections)
        val windowMenu = popup.menu.addSubMenu(R.string.header_window_menu)
        windowMenu.add(0, 10, 0, R.string.new_window)
        windowMenu.add(0, 11, 1, R.string.header_all_workspaces)
        windowMenu.add(0, 12, 2, R.string.header_back)
        windowMenu.add(0, 6, 3, R.string.header_close_workspace)
        val vsMenu = popup.menu.addSubMenu(R.string.header_vscode_controls)
        mirroredControls.forEachIndexed { index, control ->
            vsMenu.add(2, 1000 + index, index, control.label).isEnabled = control.enabled
        }
        popup.menu.add(0, 15, 5, R.string.header_merge_titlebar).apply {
            isCheckable = true
            isChecked = preferences.getBoolean("merge_titlebar", true)
        }
        popup.menu.add(0, 5, 6, R.string.header_dark).apply { isCheckable = true; isChecked = dark }
        popup.menu.add(0, 13, 7, R.string.settings)
        if (onDiagnostics != null) popup.menu.add(0, 14, 8, R.string.connection_diagnostics)
        popup.setOnMenuItemClickListener {
            if (it.groupId == 2) {
                mirroredControls.getOrNull(it.itemId - 1000)?.let { control -> titlebarBridge.click(control) }
                return@setOnMenuItemClickListener true
            }
            when (it.itemId) {
                1 -> onPorts()
                2 -> editorActions.run(EditorActions.Action.COMMANDS)
                3 -> editorActions.run(EditorActions.Action.TERMINAL)
                4 -> onReconnect()
                5 -> {
                    preferences.edit().putBoolean("dark", !dark).apply()
                    RuntimeState.store.changed()
                }
                6 -> activity.finishAndRemoveTask()
                7 -> editorActions.run(EditorActions.Action.FILES)
                8 -> onReload()
                9 -> connections()
                10 -> newWindow()
                11 -> showWorkspaces()
                12 -> activity.onBackPressedDispatcher.onBackPressed()
                13 -> onSettings()
                14 -> onDiagnostics?.invoke()
                15 -> {
                    preferences.edit().putBoolean("merge_titlebar", !preferences.getBoolean("merge_titlebar", true)).apply()
                    RuntimeState.store.changed()
                }
            }
            true
        }
        popup.show()
    }

    private fun updateStatus() {
        val failed = lastPhase.contains("disconnected", true) || lastPhase.contains("error", true) || lastPhase.contains("failed", true)
        val ready = !failed && lastPhase.contains("loaded", true)
        val color = when {
            failed -> if (dark) Color.rgb(231, 116, 116) else Color.rgb(171, 46, 46)
            ready -> if (dark) Color.rgb(96, 193, 152) else Color.rgb(22, 112, 80)
            else -> accent
        }
        val label = activity.getString(when { failed -> R.string.header_error; ready -> R.string.header_ready; else -> R.string.header_loading })
        status.text = if (compactStatus) "●" else "●  $label  ▾"
        status.setTextColor(color)
        status.contentDescription = "$lastPhase · ${activity.getString(R.string.header_forward_count, forwardCount)}"
        status.tooltipText = status.contentDescription
    }

    private fun connections() {
        activity.startActivity(Intent(activity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_CONNECTIONS, true))
    }

    private fun refresh() {
        titlebarBridge.enabled = preferences.getBoolean("merge_titlebar", true)
        titlebarBridge.refresh { controls ->
            if (controls != mirroredControls || mirroredPalette != dark) {
                mirroredControls = controls
                mirroredPalette = dark
                titleControls.removeAllViews()
                controls.forEach { control ->
                    val glyph = when {
                        control.icon.contains("arrow-left") -> "←"
                        control.icon.contains("arrow-right") -> "→"
                        control.icon.contains("sidebar-left") -> "◧"
                        control.icon.contains("sidebar-right") -> "◨"
                        control.icon.contains("panel") -> "⬒"
                        control.icon.contains("layout") -> "▥"
                        control.icon.contains("menu") -> "☰"
                        else -> control.label.take(2)
                    }
                    titleControls.addView(button(glyph, control.label) { titlebarBridge.click(control) }.apply {
                        isEnabled = control.enabled
                        alpha = if (control.enabled) 1f else 0.4f
                    }, LinearLayout.LayoutParams(activity.dp(34), -1))
                }
                updateExclusions()
            }
        }
        val store = RuntimeState.store
        val forwards = store.snapshotForwards() // Also detects SSH sessions that have disconnected.
        val windows = store.snapshotWindows().filter { it.type == "SSH workspace" || it.type == "Web editor" }
        val ownId = RuntimeTracker.id(activity)
        val own = windows.firstOrNull { it.id == ownId }
        val nextDark = preferences.getBoolean("dark", true)
        if (dark != nextDark) { dark = nextDark; applyPalette() }
        lastPhase = own?.phase ?: activity.getString(R.string.header_starting)
        forwardCount = forwards.count { it.windowId == ownId }
        updateStatus()
        val signature = windows.map { Triple(it.id, it.title, it.taskId) }
        if (lastWindows == signature) return
        lastWindows = signature
        tabs.removeAllViews()
        if (windows.isEmpty()) {
            tabs.addView(button(title, title) {}, LinearLayout.LayoutParams(-2, -1))
        } else windows.forEachIndexed { index, window ->
            val selected = window.id == ownId
            val duplicates = windows.count { it.title == window.title } > 1
            val label = window.title + if (duplicates) " · ${index + 1}" else ""
            tabs.addView(button(label, activity.getString(R.string.header_switch_workspace, window.title)) {
                if (!selected) focusWindow(window.taskId)
            }.apply {
                minWidth = activity.dp(105)
                maxWidth = activity.dp(190)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isSelected = selected
                setTextColor(if (selected) ink else if (dark) Color.rgb(160, 170, 186) else Color.rgb(96, 108, 126))
                background = RippleDrawable(ColorStateList.valueOf(0x337faaff),
                    pill(if (selected) { if (dark) Color.rgb(55, 65, 82) else Color.WHITE } else Color.TRANSPARENT,
                        if (selected) accent else Color.TRANSPARENT), null)
                tooltipText = "${window.title} · ${window.type}"

            }, LinearLayout.LayoutParams(-2, activity.dp(32)).apply { leftMargin = activity.dp(3); rightMargin = activity.dp(3) })
        }
        tabs.post {
            for (index in 0 until tabs.childCount) {
                val tab = tabs.getChildAt(index)
                if (tab.isSelected) tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), true)
            }
            updateExclusions()
        }
    }

    private fun configureCaption() {
        if (Build.VERSION.SDK_INT < 35) return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val caption = insets.getInsets(WindowInsets.Type.captionBar())
            val system = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val rects = insets.getBoundingRects(WindowInsets.Type.captionBar()).filter { it.top < caption.top && it.bottom > 0 }
            val gap = CaptionSpace.widestGap(root.width, rects.map { it.left to it.right })
            // Missing bounds on older DeX implementations must never put controls under system buttons.
            integrated = caption.top > 0 && rects.isNotEmpty() && gap != null && gap.count() >= activity.dp(360)
            val top = if (integrated) maxOf(0, system.top - caption.top) else system.top
            root.setPadding(system.left, top, system.right, maxOf(system.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom))
            val params = toolbar.layoutParams as LinearLayout.LayoutParams
            val height = if (integrated) maxOf(activity.dp(44), caption.top) else activity.dp(44)
            val left = if (integrated) maxOf(0, gap!!.first - system.left) else 0
            val right = if (integrated) maxOf(0, root.width - gap!!.last - 1 - system.right) else 0
            if (params.height != height || params.leftMargin != left || params.rightMargin != right) {
                params.height = height; params.leftMargin = left; params.rightMargin = right
                toolbar.layoutParams = params
            }
            root.invalidate()
            updateExclusions()
            WindowInsets.CONSUMED
        }
        root.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) root.requestApplyInsets()
        }
        root.requestApplyInsets()
    }

    private fun updateExclusions() {
        if (Build.VERSION.SDK_INT < 35) return
        val rects = mutableListOf<Rect>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view.isClickable || view is HorizontalScrollView) {
                val rect = Rect()
                if (view.getGlobalVisibleRect(rect)) {
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    rect.offset(-location[0], -location[1])
                    rects += rect
                }
            } else if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        if (integrated) visit(toolbar)
        root.systemGestureExclusionRects = rects
    }

    override fun onStart(owner: LifecycleOwner) {
        active = true
        RuntimeState.store.addListener(update)
        handler.post(tick)
    }
    override fun onStop(owner: LifecycleOwner) {
        active = false
        RuntimeState.store.removeListener(update)
        handler.removeCallbacksAndMessages(null)
    }
    override fun onDestroy(owner: LifecycleOwner) { activity.lifecycle.removeObserver(this) }
}
