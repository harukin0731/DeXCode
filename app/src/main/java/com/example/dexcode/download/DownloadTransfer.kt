package com.example.dexcode.download

import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/** One bounded, ordered transfer. Only a complete file is handed to the document picker. */
class DownloadTransfer(val file: File, val name: String, private val expectedSize: Long) {
    init { require(expectedSize in 0..134217728) }
    private val output = FileOutputStream(file)
    private var received = 0L
    private var finished = false

    fun append(encoded: String) {
        check(!finished)
        require(encoded.length <= 65536)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= 49152 && received + bytes.size <= expectedSize)
        output.write(bytes)
        received += bytes.size
    }

    fun complete() {
        check(!finished && received == expectedSize)
        output.close()
        finished = true
    }

    fun abort() {
        finished = true
        runCatching { output.close() }
        file.delete()
    }
}
