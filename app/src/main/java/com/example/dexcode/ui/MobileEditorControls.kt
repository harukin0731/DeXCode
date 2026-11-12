package com.example.dexcode.ui

import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.dexcode.R
import com.example.dexcode.util.dp

/** Touch editing: six visible primary actions, optional cursor row, and explicit selection state. */
class MobileEditorControls(private val activity: AppCompatActivity, private val root: LinearLayout,
    private val editor: () -> WebView, private val files: () -> Unit, private val commands: () -> Unit
) : DefaultLifecycleObserver, InputManager.InputDeviceListener {
    private data class Cell(val view: LinearLayout, val icon: ImageView, val label: TextView, val glyph: HeaderIcon,
        val text: Int, var presentation: Triple<Boolean, Boolean, Boolean>? = null)
    private val preferences = activity.getSharedPreferences("desktop_header", 0)
    private val inputs = activity.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val bar = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val primary = LinearLayout(activity)
    private val cursor = LinearLayout(activity)
    private val cells = mutableListOf<Cell>()
    private var ready = false
    private var cursorOpen = false
    private var selecting = false
    private var selectionContext: String? = null
    private var holding = false
    private var holdContext: String? = null
    private var holdGeneration = 0
    private var repeatTask: Runnable? = null
    private val keyGate = MobileKeyGate()
    private var keyTimeout: Runnable? = null
    private var checkingContext = false
    private var popup: PopupWindow? = null
    private lateinit var keyboardCell: Cell
    private lateinit var cursorCell: Cell
    private lateinit var selectCell: Cell
    private lateinit var moreCell: Cell
    private val script by lazy { activity.assets.open("mobile-editor.js").bufferedReader().use { it.readText() } }
    val visible get() = bar.visibility == View.VISIBLE

    init {
        bar.addView(primary, LinearLayout.LayoutParams(-1, activity.dp(48)))
        bar.addView(cursor, LinearLayout.LayoutParams(-1, activity.dp(48)))
        root.addView(bar, LinearLayout.LayoutParams(-1, activity.dp(48)))
        cell(primary, R.string.mobile_files, HeaderIcon.SEARCH, action = files)
        keyboardCell = cell(primary, R.string.mobile_input, HeaderIcon.KEYBOARD) { keyboard() }
        cell(primary, R.string.mobile_save, HeaderIcon.SAVE) { key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON, "save") }
        cell(primary, R.string.mobile_undo, HeaderIcon.UNDO) { key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON, "text") }
        cursorCell = cell(primary, R.string.mobile_cursor, HeaderIcon.CURSOR) {
            cursorOpen = !cursorOpen
            resetSelection()
            refresh(ready)
        }
        moreCell = cell(primary, R.string.mobile_more, HeaderIcon.MORE) { showTools() }
        arrow(R.string.mobile_left, HeaderIcon.BACK, KeyEvent.KEYCODE_DPAD_LEFT)
        arrow(R.string.mobile_down, HeaderIcon.DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        arrow(R.string.mobile_up, HeaderIcon.UP, KeyEvent.KEYCODE_DPAD_UP)
        arrow(R.string.mobile_right, HeaderIcon.FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT)
        selectCell = cell(cursor, R.string.mobile_range, HeaderIcon.SELECT_TEXT) {
            cancelKeys(); stopRepeat()
            if (selecting) { resetSelection(); refresh(ready) } else evaluate("editor") { result ->
                if (result == "\"ok\"") evaluate("context") { token ->
                    if (token.startsWith("\"context:")) { selectionContext = token; selecting = true; refresh(ready) }
                } else unavailable(result)
            }
        }
        cell(cursor, R.string.mobile_close_cursor, HeaderIcon.CLOSE) {
            cursorOpen = false
            cancelKeys()
            resetSelection() // The actual text selection in Monaco is preserved.
            refresh(ready)
        }
        // Only page-local composition/context listeners, no bridge or clipboard access.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(editor(), "if (window.top === window) { ($script)('install'); }", setOf("*"))
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh(ready) }
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh(ready) }
        activity.lifecycle.addObserver(this)
        refresh(false)
    }

    private fun cell(parent: LinearLayout, text: Int, glyph: HeaderIcon, register: Boolean = true, action: () -> Unit): Cell {
        val icon = ImageView(activity)
        val label = TextView(activity).apply { textSize = 11f; gravity = Gravity.CENTER; maxLines = 1 }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = false
            contentDescription = activity.getString(text)
            tooltipText = contentDescription
            addView(icon, LinearLayout.LayoutParams(activity.dp(20), activity.dp(20)))
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(2) })
            setOnClickListener {
                if (ready) action() else Toast.makeText(activity, R.string.header_editor_loading, Toast.LENGTH_SHORT).show()
            }
        }
        val cell = Cell(view, icon, label, glyph, text)
        if (register) cells += cell
        parent.addView(view, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        return cell
    }

    private fun arrow(label: Int, glyph: HeaderIcon, code: Int) {
        val cell = cell(cursor, label, glyph) { arrowKey(code) }
        var repeated = false
        var cancelled = false
        cell.view.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopRepeat()
                    if (ready) {
                        holding = true; repeated = false; cancelled = false; view.isPressed = true
                        val generation = holdGeneration
                        evaluate("context") { if (holding && holdGeneration == generation) holdContext = it }
                        val task = object : Runnable {
                            override fun run() {
                                if (!holding || !view.isPressed || !view.isAttachedToWindow || !ready) return
                                if (holdContext != null) { repeated = true; arrowKey(code, generation) }
                                if (holding) handler.postDelayed(this, 120)
                            }
                        }
                        repeatTask = task
                        handler.postDelayed(task, 400)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x !in 0f..view.width.toFloat() || event.y !in 0f..view.height.toFloat()) {
                        cancelled = true; view.isPressed = false; stopRepeat()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false; stopRepeat()
                    if (!cancelled && !repeated) view.performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelled = true; view.isPressed = false; stopRepeat(); resetSelection(); refresh(ready)
                }
            }
            true
        }
    }

    fun toggle() { preferences.edit().putBoolean("touch_controls", !visible).apply(); refresh(ready) }
    fun automatic() { preferences.edit().remove("touch_controls").apply(); refresh(ready) }

    fun refresh(editorReady: Boolean) {
        ready = editorReady
        val config = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val width = (root.width / density).toInt().takeIf { it > 0 } ?: config.screenWidthDp
        val keyboard = config.keyboard == Configuration.KEYBOARD_QWERTY && config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        val mouse = InputDevice.getDeviceIds().any {
            InputDevice.getDevice(it)?.let { device -> !device.isVirtual && device.supportsSource(InputDevice.SOURCE_MOUSE) } == true
        }
        val override = if (preferences.contains("touch_controls")) preferences.getBoolean("touch_controls", false) else null
        val show = MobilePolicy.visible(override, minOf(width, config.smallestScreenWidthDp.takeIf { it > 0 } ?: width), keyboard, mouse)
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || !ready) { cancelKeys(); stopRepeat(); selecting = false; selectionContext = null; cursorOpen = false }
        if (selecting && !checkingContext) {
            checkingContext = true
            val expected = selectionContext
            evaluate("context") { token ->
                checkingContext = false
                if (selectionContext == expected && expected != token) { stopRepeat(); resetSelection(); refresh(ready) }
            }
        }
        val available = ((root.height - root.paddingTop - root.paddingBottom) / density).toInt()
        val compact = !MobilePolicy.cursorInline(available)
        primary.visibility = if (cursorOpen && compact) View.GONE else View.VISIBLE
        cursor.visibility = if (cursorOpen) View.VISIBLE else View.GONE
        val height = activity.dp(if (cursorOpen && !compact) 96 else 48)
        if (bar.layoutParams.height != height) bar.layoutParams = bar.layoutParams.apply { this.height = height }
        val dark = preferences.getBoolean("dark", true)
        val colors = AppSurface(activity, dark)
        bar.setBackgroundColor(colors.background)
        val ime = ViewCompat.getRootWindowInsets(editor())?.isVisible(WindowInsetsCompat.Type.ime()) == true
        cells.forEach { item ->
            val selected = when (item) { selectCell -> selecting; cursorCell -> cursorOpen; keyboardCell -> ime; else -> false }
            val state = Triple(dark, selected, ready)
            if (item.presentation != state) {
                item.presentation = state
                item.icon.setImageDrawable(HeaderIconDrawable(item.glyph, if (selected) colors.accent else colors.ink, activity.dp(20)))
                item.label.setTextColor(if (selected) colors.accent else colors.ink)
                item.view.background = colors.ripple(if (selected) colors.card else colors.background)
                item.view.alpha = if (ready) 1f else .38f
                item.view.isSelected = selected
            }
            val label = activity.getString(if (item == selectCell && selecting) R.string.mobile_range_active else item.text)
            item.label.text = label
            item.label.visibility = if (config.fontScale > 1.3f || item.label.paint.measureText(label) > (item.view.width.takeIf { it > 0 } ?: root.width / 6) - activity.dp(4)) View.GONE else View.VISIBLE
            item.view.contentDescription = label
            item.view.tooltipText = label
        }
    }

    private fun evaluate(operation: String, callback: (String) -> Unit) {
        val view = editor()
        val page = view.url
        view.requestFocus()
        view.post {
            if (view.url != page || !view.isAttachedToWindow || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { callback("cancelled"); return@post }
            view.evaluateJavascript("($script)('$operation')") { result ->
                callback(if (view.url == page && view.isAttachedToWindow && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) result else "cancelled")
            }
        }
    }

    private fun unavailable(result: String) {
        if (result == "cancelled") return
        stopRepeat(); resetSelection()
        Toast.makeText(activity, if (result == "\"composing\"") R.string.mobile_finish_composition else R.string.mobile_tap_editor, Toast.LENGTH_SHORT).show()
        refresh(ready)
    }

    private fun beginKey(repeat: Int? = null): Int? {
        val ticket = keyGate.begin(repeat) ?: return null
        val timeout = Runnable {
            if (keyGate.accepts(ticket, holdGeneration)) {
                finishKey(ticket); stopRepeat(); resetSelection(); refresh(ready)
                Toast.makeText(activity, R.string.mobile_action_timeout, Toast.LENGTH_SHORT).show()
            }
        }
        keyTimeout = timeout
        handler.postDelayed(timeout, 1500)
        return ticket
    }

    private fun finishKey(ticket: Int) {
        if (keyGate.finish(ticket)) {
            keyTimeout?.let { handler.removeCallbacks(it) }
            keyTimeout = null
        }
    }

    private fun cancelKeys() {
        keyGate.cancel()
        keyTimeout?.let { handler.removeCallbacks(it) }
        keyTimeout = null
    }

    private fun key(code: Int, meta: Int = 0, operation: String = "navigation") {
        val ticket = beginKey() ?: return
        evaluate(operation) result@ { result ->
            if (!keyGate.accepts(ticket, holdGeneration)) return@result
            finishKey(ticket)
            if (result == "\"ok\"") send(editor(), code, meta) else unavailable(result)
        }
    }

    private fun paste() {
        val ticket = beginKey() ?: return
        evaluate("paste") result@ { result ->
            if (!keyGate.accepts(ticket, holdGeneration)) return@result
            finishKey(ticket)
            // Use VS Code's terminal paste command: it handles multiline confirmation
            // and bracketed paste. Never send raw clipboard text or terminal Ctrl+V.
            when (result) {
                "\"terminal\"" -> send(editor(), KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                "\"ok\"" -> send(editor(), KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                else -> unavailable(result)
            }
        }
    }

    private fun arrowKey(code: Int, repeat: Int? = null) {
        val ticket = beginKey(repeat) ?: return
        val selectedContext = selectionContext.takeIf { selecting }
        evaluate(if (selectedContext != null) "editor" else "navigation") result@ { result ->
            if (!keyGate.accepts(ticket, holdGeneration)) return@result
            if (result != "\"ok\"") { finishKey(ticket); unavailable(result); return@result }
            evaluate("context") context@ { context ->
                if (!keyGate.accepts(ticket, holdGeneration)) return@context
                finishKey(ticket)
                if (!context.startsWith("\"context:")) { unavailable(context); return@context }
                if (repeat != null && holdContext != context) {
                    stopRepeat(); resetSelection(); refresh(ready); return@context
                }
                if (selectedContext != null && selectedContext != context) {
                    resetSelection(); refresh(ready); return@context
                }
                send(editor(), code, if (selectedContext != null) KeyEvent.META_SHIFT_ON else 0)
                refresh(ready)
            }
        }
    }

    private fun send(view: WebView, code: Int, meta: Int) {
        if (!ready || !view.hasWindowFocus()) { stopRepeat(); resetSelection(); refresh(ready); return }
        if (!view.hasFocus()) { unavailable("\"tap-editor\""); return }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            view.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, KeyEvent.normalizeMetaState(meta),
                KeyCharacterMap.VIRTUAL_KEYBOARD, MobileKeyCodes.scanCode(code), KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD))
        }
    }

    private fun keyboard() {
        cancelKeys(); stopRepeat()
        val view = editor()
        val ime = activity.getSystemService(InputMethodManager::class.java)
        if (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            ime.hideSoftInputFromWindow(view.windowToken, 0)
            return
        }
        evaluate("keyboard") { result ->
            if (result != "\"ok\"") { unavailable(result); return@evaluate }
            val page = view.url
            view.postDelayed({
                if (ready && view.isAttachedToWindow && view.hasWindowFocus() && view.url == page && !ime.showSoftInput(view, 0)) {
                    Toast.makeText(activity, R.string.mobile_tap_editor, Toast.LENGTH_SHORT).show()
                }
            }, 100)
        }
    }

    private fun showTools() {
        cancelKeys(); stopRepeat(); resetSelection(); refresh(ready)
        val colors = AppSurface(activity, preferences.getBoolean("dark", true))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            background = colors.shape()
        }
        content.addView(colors.label(activity.getString(R.string.mobile_tools), 13f).apply {
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(8))
        })
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val actions = listOf<Triple<Int, HeaderIcon, () -> Unit>>(
            Triple(R.string.mobile_commands, HeaderIcon.COMMAND, commands),
            Triple(R.string.mobile_redo, HeaderIcon.REDO) { key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, "text") },
            Triple(R.string.mobile_tab, HeaderIcon.FORWARD) { key(KeyEvent.KEYCODE_TAB, 0, "editor") },
            Triple(R.string.mobile_outdent, HeaderIcon.BACK) { key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON, "editor") },
            Triple(R.string.mobile_copy, HeaderIcon.COPY) { key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON, "read") },
            Triple(R.string.mobile_paste, HeaderIcon.COPY) { paste() },
            Triple(R.string.mobile_select_all, HeaderIcon.SELECT_TEXT) { key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, "read") },
            Triple(R.string.mobile_escape, HeaderIcon.CLOSE) { key(KeyEvent.KEYCODE_ESCAPE) }
        )
        val menu = PopupWindow(content, minOf(root.width - activity.dp(16), activity.dp(360)), -2, true).apply {
            isOutsideTouchable = true; elevation = activity.dp(12).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(colors.shape())
        }
        actions.chunked(3).forEach { items ->
            val line = LinearLayout(activity)
            items.forEach { (label, glyph, action) ->
                val item = cell(line, label, glyph, register = false) { menu.dismiss(); afterFocus(action) }
                item.icon.setImageDrawable(HeaderIconDrawable(glyph, colors.accent, activity.dp(20)))
                item.label.text = activity.getString(label)
                item.label.setTextColor(colors.ink)
                item.view.layoutParams.height = activity.dp(if (activity.resources.configuration.fontScale > 1.3f) 72 else 64)
                item.view.background = colors.ripple()
            }
            repeat(3 - items.size) { line.addView(View(activity), LinearLayout.LayoutParams(0, activity.dp(48), 1f)) }
            grid.addView(line)
        }
        content.addView(ScrollView(activity).apply { addView(grid) }, LinearLayout.LayoutParams(-1, -2))
        content.measure(View.MeasureSpec.makeMeasureSpec(menu.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        menu.height = minOf(content.measuredHeight, (root.height - root.paddingTop - root.paddingBottom - activity.dp(56)).coerceAtLeast(activity.dp(96)))
        content.getChildAt(content.childCount - 1).layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        menu.setOnDismissListener { popup = null }
        popup?.dismiss(); popup = menu
        menu.showAsDropDown(moreCell.view, 0, -menu.height - moreCell.view.height, Gravity.END)
    }

    private fun afterFocus(action: () -> Unit) {
        val view = editor()
        val page = view.url
        val deadline = SystemClock.uptimeMillis() + 600
        fun attempt() {
            if (!ready || !view.isAttachedToWindow || view.url != page || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
            if (view.hasWindowFocus()) { view.requestFocus(); action() }
            else if (SystemClock.uptimeMillis() < deadline) view.postOnAnimation { attempt() }
            else Toast.makeText(activity, R.string.mobile_tap_editor, Toast.LENGTH_SHORT).show()
        }
        view.postOnAnimation { attempt() }
    }

    private fun resetSelection() { selecting = false; selectionContext = null; checkingContext = false }
    private fun stopRepeat() {
        holding = false; holdContext = null; holdGeneration++
        if (keyGate.cancelRepeat()) {
            keyTimeout?.let { handler.removeCallbacks(it) }
            keyTimeout = null
        }
        repeatTask?.let { handler.removeCallbacks(it) }; repeatTask = null
    }
    override fun onStart(owner: LifecycleOwner) {
        inputs.registerInputDeviceListener(this, handler); refresh(ready)
        evaluate("install") { }
    }
    override fun onStop(owner: LifecycleOwner) {
        inputs.unregisterInputDeviceListener(this); cancelKeys(); stopRepeat(); resetSelection(); popup?.dismiss()
    }
    override fun onInputDeviceAdded(deviceId: Int) { refresh(ready) }
    override fun onInputDeviceRemoved(deviceId: Int) { refresh(ready) }
    override fun onInputDeviceChanged(deviceId: Int) { refresh(ready) }
}
