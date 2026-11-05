package com.example.dexcode.ssh

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class ForwardTableTest {
    @Test fun explicitLocalPortCanCoexistWithEditorAndIsReused() {
        val table = ForwardTable()
        table.record(20000, 8080, editor = true)
        assertEquals(18080, table.getOrCreate(8080, 18080) { 18080 })
        assertEquals(18080, table.getOrCreate(8080, 18080) { fail("Must reuse fixed listener"); -1 })
        assertEquals(2, table.snapshot().size)
        assertTrue(table.snapshot().first { it.localPort == 18080 }.fixed)
        assertThrows(IllegalStateException::class.java) {
            table.getOrCreate(3000, 18080) { fail("Must reject collision before allocation"); -1 }
        }
    }

    @Test fun occupiedFixedPortFailsWithoutRecordingAFallback() {
        ServerSocket(0).use { occupied ->
            val table = ForwardTable()
            assertThrows(java.net.BindException::class.java) {
                table.getOrCreate(3000, occupied.localPort) { ServerSocket(occupied.localPort).use { it.localPort } }
            }
            assertTrue(table.snapshot().isEmpty())
        }
    }

    @Test fun fixedPortCanBeRestoredInANewSession() {
        val port = ServerSocket(0).use { it.localPort }
        repeat(2) {
            val table = ForwardTable()
            ServerSocket(port).use { listener ->
                assertEquals(port, table.getOrCreate(3000, port) { listener.localPort })
                assertTrue(table.snapshot().single().fixed)
            }
        }
    }

    @Test fun creationStartsAListenerWithoutOpeningAPageAndReusesIt() {
        val table = ForwardTable()
        val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val local = table.getOrCreate(3000) { socket.localPort }
            assertFalse(socket.isClosed)
            Socket("127.0.0.1", local).use { assertTrue(it.isConnected) }
            assertEquals(local, table.getOrCreate(3000) { fail("Must reuse the active forward"); -1 })
            assertEquals(1, table.snapshot().size)
            assertTrue(table.stop(local) { socket.close() })
            assertTrue(socket.isClosed)
            assertTrue(table.snapshot().isEmpty())
        } finally { socket.close() }
    }

    @Test fun editorForwardCannotBeStoppedAndCanBeReused() {
        val table = ForwardTable()
        table.record(20000, 8080, editor = true)
        assertFalse(table.stop(20000) { fail("Editor forward must remain active") })
        assertEquals(20000, table.getOrCreate(8080) { fail("Must reuse editor listener"); -1 })
        assertTrue(table.snapshot().single().editor)
    }

    @Test fun concurrentRequestsCreateOnlyOneForward() {
        val table = ForwardTable()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..20).map {
                executor.submit<Int> { table.getOrCreate(5173) { calls.incrementAndGet(); 25173 } }
            }.map { it.get() }
            assertEquals(setOf(25173), results.toSet())
            assertEquals(1, calls.get())
        } finally { executor.shutdownNow() }
    }
}
