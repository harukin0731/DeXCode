package com.example.dexcode.runtime

data class RuntimeWindow(
    val id: String, val taskId: Int, val title: String, val type: String,
    val connectionId: String?, val visibility: String = "Starting", val phase: String = "Starting",
)

enum class ForwardKind { SSH, PROXY_URL }

data class RuntimeForward(
    val id: String, val ownerId: String, val windowId: String,
    val connectionName: String, val url: String, val remotePort: Int?,
    val localPort: Int?, val editor: Boolean = false, val kind: ForwardKind = ForwardKind.SSH,
    val fixed: Boolean = false,
)

interface ForwardSource {
    val sourceId: String
    val windowId: String
    val connected: Boolean
    fun snapshotForwards(): List<RuntimeForward>
    fun stopForward(localPort: Int): Boolean
}

/** Process-local live objects, never a list of persisted/recent Android tasks. */
class RuntimeStateStore {
    private val lock = Any()
    private val windows = linkedMapOf<String, RuntimeWindow>()
    private val sources = linkedMapOf<String, ForwardSource>()
    private val proxyUrls = linkedMapOf<String, RuntimeForward>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun changed() { listeners.forEach { it() } }

    fun putWindow(window: RuntimeWindow) {
        val updated = synchronized(lock) { val old = windows.put(window.id, window); old != window }
        if (updated) changed()
    }

    fun updateWindow(id: String, visibility: String? = null, phase: String? = null) {
        val updated = synchronized(lock) {
            val old = windows[id] ?: return@synchronized false
            val next = old.copy(visibility = visibility ?: old.visibility, phase = phase ?: old.phase)
            if (next == old) false else { windows[id] = next; true }
        }
        if (updated) changed()
    }

    fun removeWindow(id: String) {
        val updated = synchronized(lock) {
            val removed = windows.remove(id) != null
            proxyUrls.entries.removeAll { it.value.windowId == id } || removed
        }
        if (updated) changed()
    }

    fun snapshotWindows(): List<RuntimeWindow> = synchronized(lock) { windows.values.toList() }
    fun addSource(source: ForwardSource) { synchronized(lock) { sources[source.sourceId] = source }; changed() }
    fun removeSource(id: String) {
        val removed = synchronized(lock) { sources.remove(id) != null }
        if (removed) changed()
    }

    fun addProxyUrl(windowId: String, name: String, url: String) {
        val id = "$windowId:$url"
        synchronized(lock) {
            proxyUrls[id] = RuntimeForward(id, windowId, windowId, name, url, null, null, kind = ForwardKind.PROXY_URL)
        }
        changed()
    }

    fun snapshotForwards(): List<RuntimeForward> {
        val current = synchronized(lock) { sources.values.toList() }
        val live = current.flatMap { source ->
            if (!source.connected) {
                removeSource(source.sourceId)
                updateWindow(source.windowId, phase = "SSH disconnected")
                emptyList()
            } else source.snapshotForwards()
        }
        return live + synchronized(lock) { proxyUrls.values.toList() }
    }

    fun stop(forward: RuntimeForward): Boolean {
        if (forward.kind == ForwardKind.PROXY_URL) {
            val removed = synchronized(lock) { proxyUrls.remove(forward.id) != null }
            if (removed) changed()
            return removed
        }
        if (forward.editor) return false
        val source = synchronized(lock) { sources[forward.ownerId] } ?: return false
        return source.stopForward(forward.localPort ?: return false)
    }
}

object RuntimeState { val store = RuntimeStateStore() }
