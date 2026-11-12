package com.example.dexcode.ui

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
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
    private val onServerManagement: (() -> Unit)? = null,
    private val editor: () -> WebView,
) : DefaultLifecycleObserver {
    private lateinit var mobileControls: MobileEditorControls
    private val titlebarBridge by lazy { TitlebarBridge(editor()) }
    private val actionButtons = linkedMapOf<HeaderIcon, android.widget.ImageButton>()
    private val actionPresentations = mutableMapOf<HeaderIcon, Triple<Boolean, Boolean, Boolean>>()
    private var selectionPresentation: Pair<Boolean, Boolean>? = null
    private val fixedIcons = linkedMapOf<android.widget.ImageButton, HeaderIcon>()
    private var currentSizing = HeaderSizing.forWidth(1000)
    private lateinit var selectionButton: android.widget.ImageButton
    private var mirroredControls = emptyList<TitlebarBridge.Control>()
    private var mirroredPalette: Boolean? = null
    private val clipboardControls by lazy { ClipboardControls(activity, editor()) }
    private val editorActions by lazy { EditorActions(editor()) }
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences("desktop_header", 0)
    private var dark = preferences.getBoolean("dark", true)
    private val ink get() = if (dark) Color.rgb(232, 237, 246) else Color.rgb(31, 43, 62)
    private val surface get() = if (dark) Color.rgb(23, 28, 37) else Color.rgb(242, 246, 252)
    private val accent get() = if (dark) Color.rgb(145, 187, 255) else Color.rgb(40, 94, 194)
    private val tabs = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val status = TextView(activity).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
        setOnClickListener { showStatus() }
    }
    private lateinit var commandButton: android.widget.ImageButton
    private lateinit var more: android.widget.ImageButton
    private val clusters = mutableListOf<LinearLayout>()
    private lateinit var search: TextView
    private lateinit var home: android.widget.ImageButton
    private lateinit var add: android.widget.ImageButton
    private lateinit var chooser: android.widget.ImageButton
    private lateinit var row: LinearLayout
    private var currentWorkspaceTitle = title
    private var lastPhase = ""
    private var forwardCount = 0
    private var compactStatus = false
    private var statusPresentation: Triple<String, Int, Boolean>? = null
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
        row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(8), 0, activity.dp(8), 0)
        }
        val workspaces = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        home = iconButton(HeaderIcon.CONNECTIONS, activity.getString(R.string.header_connections)) { connections() }
        workspaces.addView(home, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        workspaces.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        chooser = iconButton(HeaderIcon.WORKSPACES, activity.getString(R.string.header_all_workspaces)) { showWorkspaces() }
        workspaces.addView(chooser, LinearLayout.LayoutParams(activity.dp(28), activity.dp(36)))
        add = iconButton(HeaderIcon.ADD, activity.getString(R.string.new_window)) { newWindow() }
        workspaces.addView(add, LinearLayout.LayoutParams(activity.dp(32), activity.dp(36)))
        val navigation = cluster()
        for (icon in listOf(HeaderIcon.BACK, HeaderIcon.FORWARD)) navigation.addView(mirroredButton(icon), LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        workspaces.addView(navigation, LinearLayout.LayoutParams(-2, activity.dp(38)))
        row.addView(workspaces, LinearLayout.LayoutParams(0, -1, 1f).apply {rightMargin=activity.dp(8)})
        search = button(activity.getString(R.string.header_search), activity.getString(R.string.header_search_description)) {
            editorActions.run(EditorActions.Action.FILES)
        }.apply { setOnLongClickListener { editorActions.run(EditorActions.Action.COMMANDS); true } }
        row.addView(search, LinearLayout.LayoutParams(activity.dp(220), activity.dp(34)))
        commandButton=iconButton(HeaderIcon.COMMAND, activity.getString(R.string.header_commands_description)) {
            editorActions.run(EditorActions.Action.COMMANDS)
        }
        row.addView(commandButton,LinearLayout.LayoutParams(activity.dp(36),activity.dp(36)).apply {leftMargin=activity.dp(4);rightMargin=activity.dp(4)})
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val panels = cluster()
        for (icon in listOf(HeaderIcon.SIDEBAR_LEFT, HeaderIcon.PANEL, HeaderIcon.SIDEBAR_RIGHT)) panels.addView(mirroredButton(icon), LinearLayout.LayoutParams(activity.dp(36), activity.dp(34)))
        actions.addView(panels,LinearLayout.LayoutParams(-2,activity.dp(38)).apply {rightMargin=activity.dp(4)})
        selectionButton = iconButton(HeaderIcon.SELECT_TEXT, activity.getString(R.string.terminal_selection_mode)) { toggleSelectionMode() }
        actions.addView(selectionButton, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        actions.addView(status, LinearLayout.LayoutParams(activity.dp(76), activity.dp(32)))
        more=iconButton(HeaderIcon.MORE, activity.getString(R.string.header_unified_menu)) { showStatus() }
        actions.addView(more, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        row.addView(actions, LinearLayout.LayoutParams(-2, -1))
        toolbar.addView(row, Toolbar.LayoutParams(-1, -1))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val nextSizing = HeaderSizing.forWidth((row.width / activity.resources.displayMetrics.density).toInt())
            val sizingChanged = nextSizing != currentSizing
            currentSizing = nextSizing
            compactStatus = currentSizing.compactStatus
            val label = if (currentSizing.compactSearch) "" else activity.getString(R.string.header_search)
            if (search.text.toString() != label) search.text = label
            fun resize(view: View, width: Int) {
                if (view.layoutParams.width != activity.dp(width)) view.layoutParams = view.layoutParams.apply { this.width = activity.dp(width) }
            }
            resize(search, currentSizing.searchWidth); resize(status, currentSizing.statusWidth)
            home.visibility = if (currentSizing.showHome) View.VISIBLE else View.GONE
            add.visibility = if (currentSizing.showAdd) View.VISIBLE else View.GONE
            navigation.visibility = if (currentSizing.showNavigation) View.VISIBLE else View.GONE
            panels.visibility = if (currentSizing.showPanels) View.VISIBLE else View.GONE
            selectionButton.visibility = if (currentSizing.showSelection) View.VISIBLE else View.GONE
            status.visibility = if (currentSizing.statusWidth > 0) View.VISIBLE else View.GONE
            if (sizingChanged) { styleSearch(); updateStatus() }
            updateExclusions()
        }
        mobileControls=MobileEditorControls(activity,root,editor,
            files={editorActions.run(EditorActions.Action.FILES)},commands={editorActions.run(EditorActions.Action.COMMANDS)})
        applyPalette()
        toolbar.layoutParams = toolbar.layoutParams.apply { height = activity.dp(48) }
        configureCaption()
        activity.lifecycle.addObserver(this)
        refresh()
    }

    private fun cluster() = LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL
        setPadding(activity.dp(3),activity.dp(2),activity.dp(3),activity.dp(2))
        clusters += this
    }

    private fun pill(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = activity.dp(8).toFloat()
        setStroke(activity.dp(1), stroke)
    }

    private fun button(label: String, description: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        contentDescription = description
        tooltipText = description
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        setPadding(activity.dp(10), 0, activity.dp(10), 0)
        setTextColor(ink)
        background = RippleDrawable(ColorStateList.valueOf(0x337faaff), pill(Color.TRANSPARENT), null)
        setOnClickListener { action() }
    }

    private fun iconLabel(icon: HeaderIcon): String = activity.getString(when(icon) {
        HeaderIcon.BACK -> R.string.header_history_back
        HeaderIcon.FORWARD -> R.string.header_history_forward
        HeaderIcon.SIDEBAR_LEFT -> R.string.header_primary_sidebar
        HeaderIcon.PANEL -> R.string.header_bottom_panel
        HeaderIcon.SIDEBAR_RIGHT -> R.string.header_secondary_sidebar
        else -> R.string.header_unified_menu
    })

    private fun iconButton(icon: HeaderIcon, description: String, action: () -> Unit) = android.widget.ImageButton(activity).apply {
        contentDescription = description; tooltipText = description
        minimumWidth = 0; minimumHeight = 0
        setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        imageTintList = null
        backgroundTintList = null
        setImageDrawable(HeaderIconDrawable(icon, ink, activity.dp(18)))
        background = RippleDrawable(ColorStateList.valueOf(0x337faaff), pill(Color.TRANSPARENT), null)
        setOnClickListener { action() }
        fixedIcons[this] = icon
    }

    private fun mirroredButton(icon: HeaderIcon): android.widget.ImageButton = iconButton(icon, iconLabel(icon)) {
        if (HeaderActions.isLayout(icon)) {
            titlebarBridge.clickLayout(icon)
        } else mirroredControls.firstOrNull { HeaderActions.icon(it.icon) == icon && it.enabled }
            ?.let { titlebarBridge.click(it) }
    }.also { actionButtons[icon] = it }

    private fun updateActions() {
        mobileControls.refresh(titlebarBridge.layoutReady)
        search.isEnabled=titlebarBridge.layoutReady
        commandButton.isEnabled=titlebarBridge.layoutReady
        search.alpha=if(search.isEnabled)1f else .45f
        commandButton.alpha=if(commandButton.isEnabled)1f else .45f
        actionButtons.forEach { (icon, button) ->
            val control = mirroredControls.firstOrNull { HeaderActions.icon(it.icon) == icon && it.enabled }
                ?: mirroredControls.firstOrNull { HeaderActions.icon(it.icon) == icon }
            button.isEnabled = HeaderActions.available(icon, titlebarBridge.layoutReady, control?.enabled == true)
            val selected = HeaderActions.isLayout(icon) && control != null && !control.icon.endsWith("-off") && !control.icon.contains("-off-layout-icon")
            val presentation=Triple(button.isEnabled,selected,dark)
            if(actionPresentations.put(icon,presentation)!=presentation) {
                button.alpha=if(button.isEnabled)1f else .4f
                button.isSelected=selected
                button.setImageDrawable(HeaderIconDrawable(icon,if(selected)accent else ink,activity.dp(18)))
                button.background=RippleDrawable(ColorStateList.valueOf(0x227faaff),pill(if(selected) {if(dark)Color.rgb(47,65,91) else Color.rgb(216,231,255)} else Color.TRANSPARENT),null)
            }
            val label = control?.label?.takeUnless { it.startsWith("codicon-") } ?: iconLabel(icon)
            button.contentDescription = label
            button.tooltipText = if (button.isEnabled) label else activity.getString(R.string.header_control_unavailable, iconLabel(icon))
        }
        val selecting = preferences.getBoolean("terminal_selection_mode", false)
        if(selectionPresentation!=(selecting to dark)) {
            selectionPresentation=selecting to dark
            selectionButton.isSelected=selecting
            selectionButton.setImageDrawable(HeaderIconDrawable(HeaderIcon.SELECT_TEXT,if(selecting)accent else ink,activity.dp(18)))
            selectionButton.background=RippleDrawable(ColorStateList.valueOf(0x227faaff),pill(if(selecting) {if(dark)Color.rgb(47,65,91) else Color.rgb(216,231,255)} else Color.TRANSPARENT),null)
        }
        selectionButton.tooltipText = activity.getString(if (selecting) R.string.header_selection_on else R.string.header_selection_off)
        selectionButton.contentDescription = selectionButton.tooltipText
    }

    private fun styleSearch() {
        search.setTextColor(ink)
        val icon = HeaderIconDrawable(HeaderIcon.SEARCH, ink, activity.dp(18)).apply { setBounds(0,0,intrinsicWidth,intrinsicHeight) }
        search.setCompoundDrawablesRelative(icon, null, null, null)
        search.compoundDrawablePadding = if (currentSizing.compactSearch) 0 else activity.dp(8)
        search.gravity = if (currentSizing.compactSearch) Gravity.CENTER else Gravity.CENTER_VERTICAL
        search.setPadding(activity.dp(if (currentSizing.compactSearch) 9 else 10),0,activity.dp(8),0)
    }

    private fun toggleSelectionMode() {
        val enabled = !preferences.getBoolean("terminal_selection_mode", false)
        preferences.edit().putBoolean("terminal_selection_mode", enabled).apply()
        clipboardControls.selectionMode(enabled)
        updateActions()
        RuntimeState.store.changed()
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
        actionPresentations.clear()
        selectionPresentation=null
        fixedIcons.forEach { (button, icon) -> button.setImageDrawable(HeaderIconDrawable(icon, ink, activity.dp(18))) }
        clusters.forEach {it.background=pill(if(dark)Color.rgb(32,39,51) else Color.rgb(230,236,246))}
        styleSearch()
        updateActions()
        search.background = RippleDrawable(ColorStateList.valueOf(0x337faaff),
            pill(if (dark) Color.rgb(33, 41, 55) else Color.WHITE, if (dark) Color.rgb(57, 70, 91) else Color.rgb(211, 222, 238)), null)
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
        val windows=RuntimeState.store.snapshotWindows().filter {it.type=="SSH workspace" || it.type=="Web editor"}
        val ownId=RuntimeTracker.id(activity)
        WorkspaceMenu(activity,dark).show(chooser,root.width,root.height-root.paddingTop-root.paddingBottom-toolbar.height,
            activity.getString(R.string.header_all_workspaces),activity.getString(R.string.header_workspace_count,windows.size),
            listOf(WorkspaceMenu.Action(activity.getString(R.string.new_window),HeaderIcon.ADD){newWindow()},
                WorkspaceMenu.Action(activity.getString(R.string.connections),HeaderIcon.CONNECTIONS){connections()}),
            listOf(WorkspaceMenu.Section(activity.getString(R.string.header_workspace_label),windows.map {window->
                WorkspaceMenu.Action(window.title,HeaderIcon.WINDOW,window.phase,selected=window.id==ownId){if(window.id!=ownId)focusWindow(window.taskId)}
            })))
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
        fun label(id: Int) = activity.getString(id)
        fun action(id: Int, icon: HeaderIcon, hint: String? = null, enabled: Boolean = true, run: () -> Unit) =
            WorkspaceMenu.Action(label(id),icon,hint,enabled,run=run)
        fun toggle(id: Int, icon: HeaderIcon, key: String, default: Boolean, run: () -> Unit) =
            WorkspaceMenu.Action(label(id),icon,checked={preferences.getBoolean(key,default)},closeOnClick=false,run=run)
        val ready=titlebarBridge.layoutReady
        val sections=listOf(
            WorkspaceMenu.Section(label(R.string.header_editor_menu),listOf(
                action(R.string.header_primary_sidebar,HeaderIcon.SIDEBAR_LEFT,enabled=ready){titlebarBridge.clickLayout(HeaderIcon.SIDEBAR_LEFT)},
                action(R.string.header_bottom_panel,HeaderIcon.PANEL,enabled=ready){titlebarBridge.clickLayout(HeaderIcon.PANEL)},
                action(R.string.header_secondary_sidebar,HeaderIcon.SIDEBAR_RIGHT,enabled=ready){titlebarBridge.clickLayout(HeaderIcon.SIDEBAR_RIGHT)},
                action(R.string.header_terminal,HeaderIcon.TERMINAL,enabled=ready){editorActions.run(EditorActions.Action.TERMINAL)},
                toggle(R.string.header_selection_short,HeaderIcon.SELECT_TEXT,"terminal_selection_mode",false){toggleSelectionMode()},
                action(R.string.copy_terminal_selection,HeaderIcon.COPY){clipboardControls.copyTerminalSelection()}
            )),
            WorkspaceMenu.Section(label(R.string.header_connection_menu),buildList {
                onServerManagement?.let { add(action(R.string.server_management,HeaderIcon.PORT){it()}) }
                addAll(listOf(
                action(R.string.header_ports,HeaderIcon.PORT,activity.getString(R.string.header_forward_count,forwardCount)){onPorts()},
                action(R.string.header_reconnect,HeaderIcon.RELOAD){onReconnect()},
                action(R.string.reload,HeaderIcon.RELOAD){onReload()}
                ))
            }),
            WorkspaceMenu.Section(label(R.string.header_window_menu),listOf(
                action(R.string.new_window,HeaderIcon.ADD){newWindow()},
                action(R.string.header_all_workspaces,HeaderIcon.WINDOW){showWorkspaces()},
                action(R.string.connections,HeaderIcon.CONNECTIONS){connections()}
            )),
            WorkspaceMenu.Section(label(R.string.header_preferences),listOf(
                WorkspaceMenu.Action(label(R.string.mobile_controls),HeaderIcon.TERMINAL,checked={mobileControls.visible},closeOnClick=false){mobileControls.toggle()},
                action(R.string.mobile_automatic,HeaderIcon.SETTINGS){mobileControls.automatic()},
                toggle(R.string.header_autocopy_short,HeaderIcon.COPY,"selection_copy",true){
                    preferences.edit().putBoolean("selection_copy",!preferences.getBoolean("selection_copy",true)).apply();RuntimeState.store.changed()
                },
                toggle(R.string.header_merge_titlebar,HeaderIcon.WINDOW,"merge_titlebar",true){
                    preferences.edit().putBoolean("merge_titlebar",!preferences.getBoolean("merge_titlebar",true)).apply();RuntimeState.store.changed()
                },
                WorkspaceMenu.Action(label(R.string.header_dark),HeaderIcon.THEME,checked={preferences.getBoolean("dark",true)}){
                    preferences.edit().putBoolean("dark",!dark).apply();RuntimeState.store.changed()
                },
                action(R.string.settings,HeaderIcon.SETTINGS){onSettings()}
            )),
            WorkspaceMenu.Section(label(R.string.header_more_tools),buildList {
                if(mirroredControls.isNotEmpty())add(action(R.string.header_vscode_controls,HeaderIcon.MORE){showVsControls()})
                add(action(R.string.clipboard_diagnostics,HeaderIcon.INFO){clipboardControls.diagnostics()})
                if(onDiagnostics!=null)add(action(R.string.connection_diagnostics,HeaderIcon.INFO){onDiagnostics.invoke()})
                add(action(R.string.header_back,HeaderIcon.BACK){activity.onBackPressedDispatcher.onBackPressed()})
                add(WorkspaceMenu.Action(label(R.string.header_close_workspace),HeaderIcon.CLOSE,destructive=true){activity.finishAndRemoveTask()})
            })
        )
        WorkspaceMenu(activity,dark).show(more,root.width,root.height-root.paddingTop-root.paddingBottom-toolbar.height,currentWorkspaceTitle,
            lastPhase+" · "+activity.getString(R.string.header_forward_count,forwardCount),
            listOf(action(R.string.header_file_search,HeaderIcon.SEARCH,"Ctrl+P",ready){editorActions.run(EditorActions.Action.FILES)},
                action(R.string.header_command_palette,HeaderIcon.COMMAND,"Ctrl+Shift+P",ready){editorActions.run(EditorActions.Action.COMMANDS)}),sections)
    }

    private fun showVsControls() {
        val controls=mirroredControls.toList()
        val popup=PopupMenu(activity,more)
        controls.forEachIndexed {index,control->popup.menu.add(0,index,index,control.label).isEnabled=control.enabled}
        popup.setOnMenuItemClickListener {item->controls.getOrNull(item.itemId)?.let{titlebarBridge.click(it)};true}
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
        val presentation = Triple(label, color, compactStatus)
        if (statusPresentation != presentation) {
            statusPresentation = presentation
            status.text = if (compactStatus) "" else label
            val dot = HeaderIconDrawable(HeaderIcon.STATUS, color, activity.dp(16)).apply { setBounds(0,0,intrinsicWidth,intrinsicHeight) }
            status.setCompoundDrawablesRelative(dot, null, null, null)
            status.compoundDrawablePadding = if (compactStatus) 0 else activity.dp(2)
            status.setTextColor(color)
        }
        status.contentDescription = "$lastPhase · ${activity.getString(R.string.header_forward_count, forwardCount)}"
        status.tooltipText = status.contentDescription
    }

    private fun connections() {
        activity.startActivity(Intent(activity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_CONNECTIONS, true))
    }

    private fun refresh() {
        clipboardControls.selectionMode(preferences.getBoolean("terminal_selection_mode", false))
        titlebarBridge.enabled = preferences.getBoolean("merge_titlebar", true)
        titlebarBridge.refresh { controls ->
            if (controls != mirroredControls || mirroredPalette != dark) {
                mirroredControls = controls
                mirroredPalette = dark
                updateActions()
                updateExclusions()
            }
            updateActions()
        }
        updateActions()
        val store = RuntimeState.store
        val forwards = store.snapshotForwards() // Also detects SSH sessions that have disconnected.
        val windows = store.snapshotWindows().filter { it.type == "SSH workspace" || it.type == "Web editor" }
        val ownId = RuntimeTracker.id(activity)
        val own = windows.firstOrNull { it.id == ownId }
        val nextDark = preferences.getBoolean("dark", true)
        if (dark != nextDark) { dark = nextDark; applyPalette() }
        currentWorkspaceTitle=own?.title ?: title
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
                minWidth = activity.dp(96)
                maxWidth = activity.dp(210)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isSelected = selected
                setTextColor(if (selected) ink else if (dark) Color.rgb(160, 170, 186) else Color.rgb(96, 108, 126))
                background = RippleDrawable(ColorStateList.valueOf(0x337faaff),
                    pill(if (selected) { if (dark) Color.rgb(43, 56, 77) else Color.WHITE } else Color.TRANSPARENT,
                        if (selected) {if(dark)Color.rgb(72,97,136) else Color.rgb(180,202,234)} else Color.TRANSPARENT), null)
                val windowIcon=HeaderIconDrawable(HeaderIcon.WINDOW,if(selected)accent else ink,activity.dp(14)).apply{setBounds(0,0,intrinsicWidth,intrinsicHeight)}
                setCompoundDrawablesRelative(windowIcon,null,null,null)
                compoundDrawablePadding=activity.dp(7)
                tooltipText = "${window.title} · ${window.type}"

            }, LinearLayout.LayoutParams(-2, activity.dp(34)).apply { leftMargin = activity.dp(2); rightMargin = activity.dp(2) })
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
        if (Build.VERSION.SDK_INT < 35) {
            return
        }
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
            val height = if (integrated) maxOf(activity.dp(48), caption.top) else activity.dp(48)
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
        if (Build.VERSION.SDK_INT < 35) {
            return
        }
        val rects = mutableListOf<Rect>()
        // getGlobalVisibleRect is relative to the View root, NOT to the display.
        // Subtracting getLocationOnScreen shifts every exclusion when a freeform window moves.
        val origin = Point()
        if (!root.getGlobalVisibleRect(Rect(), origin)) return
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) {
                return
            }
            if (view.isClickable || view is HorizontalScrollView) {
                val rect = Rect()
                if (view.getGlobalVisibleRect(rect)) {
                    rect.offset(-origin.x, -origin.y)
                    rects += rect
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))
                }
            }
        }
        if (integrated) {
            visit(toolbar)
        }
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
