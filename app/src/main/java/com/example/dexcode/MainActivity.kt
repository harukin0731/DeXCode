package com.example.dexcode

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.dexcode.data.Connection
import com.example.dexcode.data.ConnectionStore
import com.example.dexcode.data.Kind
import com.example.dexcode.data.SettingsExport
import com.example.dexcode.runtime.RuntimeState
import com.example.dexcode.runtime.RuntimeWindow
import com.example.dexcode.runtime.RuntimeForward
import com.example.dexcode.runtime.ForwardKind
import com.example.dexcode.util.copyForwardUrl
import com.example.dexcode.util.UrlUtils
import com.example.dexcode.util.cardBackground
import com.example.dexcode.util.dp
import com.example.dexcode.util.primaryTextStyle
import com.example.dexcode.util.secondaryTextStyle
import java.util.UUID
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var filterQuery = ""
    private var renderedDark: Boolean? = null
    private var homeSearch: EditText? = null
    private var homeToolbar: Toolbar? = null
    private var homeRunning: TextView? = null
    private var homeListScroll: ScrollView? = null
    private val surface get() = com.example.dexcode.ui.AppSurface(this,getSharedPreferences("desktop_header",MODE_PRIVATE).getBoolean("dark",true))
    private lateinit var store: ConnectionStore
    private lateinit var list: LinearLayout
    private var selectedKeyUri: Uri? = null
    private var keyLabel: TextView? = null

    private val keyPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        selectedKeyUri = uri
        keyLabel?.text = uri.lastPathSegment ?: uri.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConnectionStore(this)
        filterQuery=savedInstanceState?.getString("connectionFilter").orEmpty()

        renderConnections()
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var updating = false
    private lateinit var runtimeSection: LinearLayout
    private lateinit var savedRows: LinearLayout
    private var lastRuntime: Pair<List<RuntimeWindow>, List<RuntimeForward>>? = null
    private var lastConnections: List<Connection>? = null
    private val refreshNow = Runnable {
        if (updating && !isFinishing) { refreshRuntime(); refreshSavedConnections() }
    }
    private val runtimeChanged: () -> Unit = {
        refreshHandler.removeCallbacks(refreshNow)
        refreshHandler.post(refreshNow)
    }
    private val refreshTick = object : Runnable {
        override fun run() {
            if (!updating) return
            refreshNow.run()
            refreshHandler.postDelayed(this, 1_000L)
        }
    }
    private val exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val app = applicationContext
            exportExecutor.execute {
                val result = runCatching {
                    val connections = ConnectionStore(app)
                    val knownHosts = java.io.File(app.filesDir, "ssh/known_hosts")
                    val json = SettingsExport.build(
                        connections.getAll(), connections.getLastUsed()?.id,
                        app.getSharedPreferences("PortActivity", MODE_PRIVATE).getBoolean("developer_mode", false),
                        if (knownHosts.exists()) knownHosts.readText() else "",
                        darkHeader = app.getSharedPreferences("desktop_header", MODE_PRIVATE).getBoolean("dark", true),
                        mergeTitlebar = app.getSharedPreferences("desktop_header", MODE_PRIVATE).getBoolean("merge_titlebar", true),
                        selectionCopy = app.getSharedPreferences("desktop_header", MODE_PRIVATE).getBoolean("selection_copy", true),
                        touchControls = app.getSharedPreferences("desktop_header",MODE_PRIVATE).let{if(it.contains("touch_controls"))it.getBoolean("touch_controls",false) else null},
                        terminalSelectionMode = app.getSharedPreferences("desktop_header", MODE_PRIVATE).getBoolean("terminal_selection_mode", false),
                    ).toString(2)
                    checkNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "Unable to open export file" }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, if (result.isSuccess) R.string.export_complete else R.string.export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if(renderedDark!=surface.dark)renderConnections()
        updating = true
        RuntimeState.store.addListener(runtimeChanged)
        refreshHandler.post(refreshTick)
    }

    override fun onStop() {
        updating = false
        RuntimeState.store.removeListener(runtimeChanged)
        refreshHandler.removeCallbacks(refreshTick)
        refreshHandler.removeCallbacks(refreshNow)
        super.onStop()
    }

    override fun onDestroy() {
        exportExecutor.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 502, 0, R.string.add_connection).apply {
            icon=com.example.dexcode.ui.HeaderIconDrawable(com.example.dexcode.ui.HeaderIcon.ADD,surface.accent,dp(24))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, 500, 1, R.string.export_settings)
        menu.add(0, 501, 1, R.string.refresh_status)
        homeToolbar?.overflowIcon?.setTint(surface.ink)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        502 -> { showEditor(null); true }
        500 -> {
            AlertDialog.Builder(this).setTitle(R.string.export_settings)
                .setMessage(R.string.export_settings_description)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.export_settings) { _, _ -> exportDocument.launch("DeXCode-settings.json") }
                .show()
            true
        }
        501 -> { lastRuntime = null; refreshRuntime(); refreshSavedConnections(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderConnections()
    }

    private var showRunning = false

    private fun renderConnections() {
        val colors=surface
        renderedDark=colors.dark
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            isFocusableInTouchMode = true
        }
        val toolbar=Toolbar(this).apply {
            title="DeXCode";setTitleTextColor(colors.ink);setBackgroundColor(colors.background)
            setContentInsetsRelative(dp(12),dp(12));minimumHeight=dp(48)
        }
        homeToolbar = toolbar
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(48)))
        setSupportActionBar(toolbar)
        val search=EditText(this).apply {
            hint=getString(R.string.home_search)
            textSize=14f
            setSingleLine(true)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(colors.ink)
            setHintTextColor(colors.muted)
            background=colors.shape()
            setPadding(dp(16),0,dp(16),0)
            setText(filterQuery)
        }
        homeSearch=search
        root.addView(search,LinearLayout.LayoutParams(-1,dp(48)).apply {
            leftMargin=dp(12);rightMargin=dp(12);topMargin=dp(8);bottomMargin=dp(8)
        })
        val connectionsTab = colors.button(getString(R.string.saved_servers)) { }
        val runningTab = colors.button(getString(R.string.home_running_short)) { }
        homeRunning = runningTab
        root.addView(LinearLayout(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(connectionsTab, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(runningTab, LinearLayout.LayoutParams(0, -2, 1f))
        }, 1, LinearLayout.LayoutParams(-1, -2))
        val scroll=ScrollView(this).apply {isFillViewport=true}
        homeListScroll=scroll
        list=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(24))
        }
        scroll.addView(list)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) {view,insets->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom))
            insets
        }
        androidx.core.view.WindowCompat.getInsetsController(window,root).apply {
            isAppearanceLightStatusBars=!colors.dark
            isAppearanceLightNavigationBars=!colors.dark
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        savedRows=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        list.addView(savedRows)
        lastConnections=null
        refreshSavedConnections()
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filterQuery=s.toString();lastConnections=null;refreshSavedConnections()}
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        addActiveWindowSection()
        fun showPage(running: Boolean) {
            showRunning = running
            savedRows.visibility = if (running) View.GONE else View.VISIBLE
            runtimeSection.visibility = if (running) View.VISIBLE else View.GONE
            search.visibility = if (!running && store.getAll().isNotEmpty()) View.VISIBLE else View.GONE
            connectionsTab.isSelected = !running
            runningTab.isSelected = running
            connectionsTab.background = colors.ripple(if (running) colors.card else colors.line)
            runningTab.background = colors.ripple(if (running) colors.line else colors.card)
            scroll.scrollTo(0, 0)
        }
        connectionsTab.setOnClickListener { showPage(false) }
        runningTab.setOnClickListener {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(search.windowToken, 0)
            root.requestFocus()
            showPage(true)
        }
        showPage(showRunning)
        root.requestFocus()
    }

    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        val editingSearch=homeSearch?.hasFocus()==true
        val scrollPosition=homeListScroll?.scrollY ?: 0
        super.onConfigurationChanged(newConfig)
        renderConnections()
        homeListScroll?.post {homeListScroll?.scrollTo(0,scrollPosition)}
        if(editingSearch)homeSearch?.apply{requestFocus();setSelection(text.length)}
    }

    override fun onSaveInstanceState(outState:Bundle){outState.putString("connectionFilter",filterQuery);super.onSaveInstanceState(outState)}

    private fun refreshSavedConnections() {
        if (!::savedRows.isInitialized) return
        val connections = store.getAll().filter { connection ->
            filterQuery.isBlank() || listOf(connection.name,connection.sshHost,connection.workspacePath,
                if(connection.kind==Kind.WEB)ConnectionDiagnostics.safeTarget(connection.url) else "SSH").any{it.contains(filterQuery.trim(),true)}
        }
        if (connections == lastConnections) return
        lastConnections = connections
        savedRows.removeAllViews()
        if (connections.isEmpty()) {
            if (store.getAll().isEmpty()) {
                val colors = surface
                savedRows.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = colors.shape()
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    addView(colors.label(getString(R.string.home_start_title), 21f).apply {
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    addView(colors.label(getString(R.string.home_start_description), 14f, true),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(18) })
                    addView(colors.button(getString(R.string.home_add_ssh), primary = true) {
                        showEditor(null, Kind.SSH)
                    })
                    addView(colors.button(getString(R.string.home_add_url)) {
                        showEditor(null, Kind.WEB)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                })
            } else {
                savedRows.addView(surface.label(getString(R.string.home_no_matches), 14f, true).apply {
                    setPadding(0, dp(24), 0, dp(24))
                })
            }
        } else connections.sortedWith(compareByDescending<Connection> { it.lastUsedAt }
            .thenBy { it.name.lowercase(Locale.getDefault()) }).forEach { addConnectionRow(it) }
    }

    private fun addActiveWindowSection() {
        runtimeSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(runtimeSection, LinearLayout.LayoutParams(-1, -2))
        lastRuntime = null
        refreshRuntime()
    }

    @Suppress("DEPRECATION")
    private fun taskIdOf(info: ActivityManager.RecentTaskInfo): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.id

    private fun refreshRuntime() {
        if (!::runtimeSection.isInitialized) return
        val forwards = RuntimeState.store.snapshotForwards()
        val tasks = runCatching { getSystemService(ActivityManager::class.java).appTasks }
            .getOrDefault(emptyList())
        val taskIds = tasks.mapNotNull { runCatching { taskIdOf(it.taskInfo) }.getOrNull() }.toSet()
        val windows = RuntimeState.store.snapshotWindows().filter { it.taskId in taskIds }
        homeRunning?.apply {
            contentDescription = getString(R.string.home_running_short) + " · " +
                getString(R.string.home_running_summary, windows.size, forwards.size)
        }
        val snapshot = windows to forwards
        if (snapshot == lastRuntime) return
        lastRuntime = snapshot
        runtimeSection.removeAllViews()
        fun heading(label: Int) {
            runtimeSection.addView(TextView(this).apply {
                text = getString(label); setTextColor(surface.ink); textSize = 16f
                setPadding(0, dp(8), 0, dp(5))
            })
        }
        fun note(textValue: String) {
            runtimeSection.addView(TextView(this).apply {
                text = textValue; setTextColor(surface.muted);textSize=12f; setPadding(0, 0, 0, dp(10))
            })
        }
        heading(R.string.active_windows)
        if (windows.isEmpty()) note(getString(R.string.no_active_windows))
        windows.forEach { window ->
            val row = runtimeRow("${window.title} • ${window.type}", "${window.visibility} • ${window.phase}")
            row.addView(Button(this).apply {
                text = getString(R.string.switch_window); isAllCaps = false;setTextColor(surface.accent);backgroundTintList=android.content.res.ColorStateList.valueOf(surface.card)
                setOnClickListener {
                    runCatching {
                        getSystemService(ActivityManager::class.java).appTasks
                            .firstOrNull { taskIdOf(it.taskInfo) == window.taskId }?.moveToFront()
                    }
                }
            })
            runtimeSection.addView(row)
        }
        heading(R.string.home_previews)
        if (forwards.isEmpty()) note(getString(R.string.no_active_forwards))
        forwards.forEach { forward ->
            val detail = when {
                forward.kind == ForwardKind.PROXY_URL -> getString(R.string.proxy_url_status)
                forward.editor -> getString(R.string.editor_forward_status, forward.remotePort)
                else -> getString(R.string.ssh_forward_status, forward.remotePort)
            }
            val fixedLabel = if (forward.fixed) " • ${getString(R.string.fixed_port_label)}" else ""
            val row = runtimeRow(forward.connectionName, "$detail$fixedLabel\n${forward.url}")
            if (!forward.editor) row.addView(surface.button(getString(R.string.home_open_preview)) {
                startActivity(PortActivity.intent(this@MainActivity, forward.url, forward.connectionName))
            })
            row.addView(Button(this).apply {
                text = getString(R.string.copy_address); isAllCaps = false;setTextColor(surface.accent);backgroundTintList=android.content.res.ColorStateList.valueOf(surface.card)
                setOnClickListener { copyForwardUrl(this@MainActivity, forward.url) }
            })
            if (!forward.editor) row.addView(Button(this).apply {
                text = getString(if (forward.kind == ForwardKind.PROXY_URL) R.string.remove_url else R.string.stop_forward)
                isAllCaps = false;setTextColor(surface.accent);backgroundTintList=android.content.res.ColorStateList.valueOf(surface.card)
                setOnClickListener {
                    runCatching { RuntimeState.store.stop(forward) }
                        .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                    lastRuntime = null
                    refreshRuntime()
                }
            })
            runtimeSection.addView(row)
        }
        if (forwards.any { it.kind == ForwardKind.SSH }) note(getString(R.string.forward_lifetime_hint))
    }

    private fun runtimeRow(titleValue: String, detailValue: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        background = surface.shape()
        setPadding(dp(10), dp(6), dp(4), dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = titleValue;setTextColor(surface.ink);textSize=14f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
            addView(TextView(this@MainActivity).apply { text = detailValue;setTextColor(surface.muted);textSize=12f;maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addConnectionRow(connection: Connection) {
        val colors=surface
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = colors.shape()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(colors.label(connection.name, 17f).apply {
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.ImageButton(this@MainActivity).apply {
                contentDescription = getString(R.string.edit) + " " + connection.name
                tooltipText = contentDescription
                scaleType = android.widget.ImageView.ScaleType.CENTER
                setImageDrawable(com.example.dexcode.ui.HeaderIconDrawable(
                    com.example.dexcode.ui.HeaderIcon.EDIT, colors.muted, dp(20),
                ))
                background = colors.ripple()
                setOnClickListener { showEditor(connection) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        val target = if (connection.kind == Kind.SSH) {
            val folder = connection.workspacePath.ifBlank { "~" }
            "${connection.sshUsername}@${connection.sshHost} · $folder"
        } else ConnectionDiagnostics.safeTarget(connection.url).substringBefore(" (")
        row.addView(colors.label(target, 12f, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        row.addView(LinearLayout(this).apply {
            val open = if (connection.kind == Kind.SSH) R.string.home_open_editor else R.string.home_open_web
            addView(colors.button(getString(open), primary = true) { openConnection(connection) }
                .apply { setPadding(dp(6), 0, dp(6), 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (connection.kind == Kind.SSH) {
                addView(colors.button(getString(R.string.home_open_files)) { ProjectFilesActivity.open(this@MainActivity, connection.id) }
                    .apply { setPadding(dp(6), 0, dp(6), 0) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        savedRows.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
    }

    private fun showEditor(existing: Connection?, initialKind: Kind = Kind.WEB) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.connection_name)
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }

        val kindSpinner = Spinner(this)
        val kindAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(getString(R.string.web_url_type), getString(R.string.ssh_workspace_type)),
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        kindSpinner.adapter = kindAdapter
        kindSpinner.setSelection(if ((existing?.kind ?: initialKind) == Kind.SSH) 1 else 0)

        val urlInput = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
        }

        fun numericInput(hintRes: Int, value: Int): EditText = EditText(this).apply {
            hint = getString(hintRes)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(Locale.getDefault(), "%d", value))
        }

        val sshHostInput = EditText(this).apply {
            hint = getString(R.string.ssh_host)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.sshHost.orEmpty())
        }
        val sshPortInput = numericInput(R.string.ssh_port, existing?.sshPort ?: 22)
        val sshUsernameInput = EditText(this).apply {
            hint = getString(R.string.ssh_username)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.sshUsername.orEmpty())
        }
        val workspaceInput = EditText(this).apply {
            hint = getString(R.string.workspace_path)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.workspacePath.orEmpty())
        }
        val remotePortInput = numericInput(R.string.remote_port, existing?.remotePort ?: 8080)
        val commandPrefixInput = EditText(this).apply {
            hint = getString(R.string.remote_command_prefix)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.remoteCommandPrefix.orEmpty())
        }
        selectedKeyUri = existing?.sshKeyUri?.let(Uri::parse)
        val selectKey = Button(this).apply {
            text = getString(R.string.select_ssh_key)
            isAllCaps = false
        }
        keyLabel = TextView(this).apply {
            text = selectedKeyUri?.lastPathSegment ?: getString(R.string.no_ssh_key)
            secondaryTextStyle()
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        selectKey.setOnClickListener {
            keyPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
        }

        val webFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlInput, LinearLayout.LayoutParams(-1, dp(60)))
        }
        val sshFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sshHostInput, LinearLayout.LayoutParams(-1, dp(60)))
            addView(sshUsernameInput, LinearLayout.LayoutParams(-1, dp(60)))
            addView(workspaceInput, LinearLayout.LayoutParams(-1, dp(60)))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.ssh_key)
                secondaryTextStyle()
            }, LinearLayout.LayoutParams(-1, -2))
            addView(selectKey, LinearLayout.LayoutParams(-1, dp(48)))
            addView(keyLabel, LinearLayout.LayoutParams(-1, dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.ssh_setup_note)
                secondaryTextStyle()
                setPadding(0, dp(4), 0, dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(sshPortInput, LinearLayout.LayoutParams(-1, dp(60)))
            addView(remotePortInput, LinearLayout.LayoutParams(-1, dp(60)))
            addView(commandPrefixInput, LinearLayout.LayoutParams(-1, dp(60)))
        }
        sshFields.addView(surface.button(getString(R.string.home_advanced_ssh)) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        sshFields.addView(advanced)

        form.addView(nameInput, LinearLayout.LayoutParams(-1, dp(60)))
        form.addView(TextView(this).apply {
            text = getString(R.string.connection_type)
            secondaryTextStyle()
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
        })
        form.addView(kindSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        form.addView(webFields, LinearLayout.LayoutParams(-1, -2))
        form.addView(sshFields, LinearLayout.LayoutParams(-1, -2))

        fun updateFields(position: Int) {
            val isSsh = position == 1
            webFields.visibility = if (isSsh) View.GONE else View.VISIBLE
            sshFields.visibility = if (isSsh) View.VISIBLE else View.GONE
        }
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFields(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateFields(kindSpinner.selectedItemPosition)

        val scroll = ScrollView(this).apply {
            addView(form)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.add_connection) else getString(R.string.edit))
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val kind = if (kindSpinner.selectedItemPosition == 1) Kind.SSH else Kind.WEB
                val host = sshHostInput.text.toString().trim()
                val sshUsername = sshUsernameInput.text.toString().trim()
                val workspacePath = workspaceInput.text.toString().trim()
                val url = UrlUtils.normalizeServerUrl(urlInput.text.toString())
                val sshPort = sshPortInput.text.toString().toIntOrNull()
                val remotePort = remotePortInput.text.toString().toIntOrNull()
                when {
                    name.isEmpty() -> nameInput.error = getString(R.string.enter_name)
                    kind == Kind.WEB && url == null -> urlInput.error = getString(R.string.invalid_url)
                    kind == Kind.SSH && host.isEmpty() -> sshHostInput.error = getString(R.string.invalid_ssh_host)
                    kind == Kind.SSH && sshUsername.isEmpty() -> sshUsernameInput.error = getString(R.string.invalid_ssh_username)
                    kind == Kind.SSH && sshPort !in 1..65535 -> { advanced.visibility = View.VISIBLE; sshPortInput.error = getString(R.string.invalid_ssh_port) }
                    kind == Kind.SSH && remotePort !in 1..65535 -> { advanced.visibility = View.VISIBLE; remotePortInput.error = getString(R.string.invalid_remote_port) }
                    else -> {
                        store.upsert(
                            Connection(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                name = name,
                                url = if (kind == Kind.WEB) url.orEmpty() else "",
                                kind = kind,
                                sshHost = host,
                                sshPort = sshPort ?: 22,
                                sshUsername = sshUsername,
                                workspacePath = workspacePath,
                                remotePort = remotePort ?: 8080,
                                sshLocalPort = existing?.sshLocalPort ?: 0,
                                sshKeyUri = if (kind == Kind.SSH) selectedKeyUri?.toString() else null,
                                remoteCommandPrefix = commandPrefixInput.text.toString().trim(),
                                lastUsedAt = existing?.lastUsedAt ?: 0L,
                                fixedPortBindings = existing?.fixedPortBindings ?: emptyMap(),
                            ),
                        )
                        dialog.dismiss()
                        renderConnections()
                    }
                }
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    store.remove(existing.id)
                    dialog.dismiss()
                    renderConnections()
                }
            }
        }
        dialog.show()
    }

    private fun openConnection(connection: Connection) {
        store.markLastUsed(connection.id)
        val activity = if (connection.kind == Kind.SSH) {
            SshCodeActivity::class.java
        } else {
            CodeActivity::class.java
        }
        startActivity(
            Intent(this, activity)
                .putExtra(CodeActivity.EXTRA_CONNECTION_ID, connection.id)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
                ),
        )
    }

    companion object {
        const val EXTRA_SHOW_CONNECTIONS = "show_connections"
    }
}
