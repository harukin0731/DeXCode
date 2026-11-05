package com.example.dexcode.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeStateTest {
    private class Source : ForwardSource {
        override val sourceId = "session"
        override val windowId = "window"
        override var connected = true
        private var active = true
        override fun snapshotForwards() = if (active) listOf(RuntimeForward(
            "session:20001", sourceId, windowId, "SSH test", "http://127.0.0.1:20001/", 3000, 20001,
        )) else emptyList()
        override fun stopForward(localPort: Int): Boolean { active = false; return true }
    }

    @Test fun windowsNotifyImmediatelyAndAreRemovedOnDestruction() {
        val store = RuntimeStateStore()
        var changes = 0
        val listener: () -> Unit = { changes++ }
        store.addListener(listener)
        store.putWindow(RuntimeWindow("window", 1, "Editor", "SSH", "connection"))
        store.updateWindow("window", visibility = "Background", phase = "SSH connected")
        assertEquals(2, changes)
        assertEquals("Background", store.snapshotWindows().single().visibility)
        store.removeWindow("window")
        assertTrue(store.snapshotWindows().isEmpty())
        store.removeListener(listener)
        store.changed()
        assertEquals(3, changes)
    }

    @Test fun disconnectedSessionsNeverAppearAsActiveForwards() {
        val store = RuntimeStateStore()
        val source = Source()
        store.putWindow(RuntimeWindow("window", 1, "Editor", "SSH", "connection"))
        store.addSource(source)
        assertEquals(1, store.snapshotForwards().size)
        source.connected = false
        assertTrue(store.snapshotForwards().isEmpty())
        assertEquals("SSH disconnected", store.snapshotWindows().single().phase)
        source.connected = true // An unregistered stale session must not reappear.
        assertTrue(store.snapshotForwards().isEmpty())
    }

    @Test fun stopAndProxyRemovalHaveDifferentSemanticsAndNoPhantomWindows() {
        val store = RuntimeStateStore()
        store.addSource(Source())
        assertTrue(store.snapshotWindows().isEmpty())
        assertTrue(store.stop(store.snapshotForwards().single()))
        assertTrue(store.snapshotForwards().isEmpty())
        store.addProxyUrl("web", "Web test", "https://example.test/proxy/3000/")
        store.addProxyUrl("web", "Web test", "https://example.test/proxy/3000/")
        assertEquals(1, store.snapshotForwards().size)
        assertEquals(ForwardKind.PROXY_URL, store.snapshotForwards().single().kind)
        store.removeWindow("web")
        assertTrue(store.snapshotForwards().isEmpty())
    }
}
