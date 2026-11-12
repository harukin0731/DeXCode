package com.example.dexcode.download

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class DownloadTransferTest {
    @Test fun multipleChunksProduceTheExactOriginalFile() {
        val file = File.createTempFile("dexcode-test", ".bin")
        val bytes = ByteArray(100123) { (it % 251).toByte() }
        val transfer = DownloadTransfer(file, "data.bin", bytes.size.toLong())
        try {
            bytes.toList().chunked(49152).forEach { transfer.append(Base64.getEncoder().encodeToString(it.toByteArray())) }
            transfer.complete()
            assertArrayEquals(bytes, file.readBytes())
            assertThrows(IllegalStateException::class.java) { transfer.append("") }
        } finally { transfer.abort() }
    }
    @Test fun incompleteFileIsRejectedAndAbortDeletesIt() {
        val file = File.createTempFile("dexcode-test", ".bin")
        val transfer = DownloadTransfer(file, "data.bin", 10)
        transfer.append("YQ==")
        assertThrows(IllegalStateException::class.java) { transfer.complete() }
        transfer.abort()
        assertFalse(file.exists())
    }
    @Test fun invalidAndOversizedChunksAreRejected() {
        val file = File.createTempFile("dexcode-test", ".bin")
        val transfer = DownloadTransfer(file, "data.bin", 1)
        try {
            assertThrows(IllegalArgumentException::class.java) { transfer.append("%bad") }
            assertThrows(IllegalArgumentException::class.java) { transfer.append("YWI=") }
            assertThrows(IllegalArgumentException::class.java) { transfer.append("a".repeat(65540)) }
        } finally { transfer.abort() }
    }
    @Test fun emptyFileCanBeCompleted() {
        val file = File.createTempFile("dexcode-test", ".bin")
        val transfer = DownloadTransfer(file, "empty.bin", 0)
        try { transfer.complete(); assertEquals(0L, file.length()) } finally { transfer.abort() }
    }
}
