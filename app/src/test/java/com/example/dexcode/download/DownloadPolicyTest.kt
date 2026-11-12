package com.example.dexcode.download

import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun browserLocalUrlsAreNotHttpDownloads() {
        assertEquals("blob", DownloadPolicy.scheme("blob:https://server.test/id"))
        assertEquals("data", DownloadPolicy.scheme("data:text/plain;base64,YQ=="))
        assertEquals("", DownloadPolicy.scheme(null))
        assertEquals("", DownloadPolicy.scheme("not a valid url"))
        assertEquals("https", DownloadPolicy.scheme("HTTPS://server.test/file"))
    }
    @Test fun filenamesCannotEscapeDownloadDirectory() {
        assertEquals("secret.txt", DownloadPolicy.filename("../../secret.txt"))
        assertEquals("file.txt", DownloadPolicy.filename("C:\\folder\\file.txt"))
        assertEquals("download", DownloadPolicy.filename(".."))
        assertEquals("_bad_.txt", DownloadPolicy.filename("\nbad?.txt"))
        assertEquals("日本語.txt", DownloadPolicy.filename("日本語.txt"))
        assertEquals(160, DownloadPolicy.filename("a".repeat(1000)).length)
    }
    @Test fun frameOriginsIncludeSchemeHostAndPort() {
        assertTrue(DownloadPolicy.sameOrigin("https://server.test", "https://server.test:443/editor"))
        assertFalse(DownloadPolicy.sameOrigin("https://evil.test", "https://server.test"))
        assertFalse(DownloadPolicy.sameOrigin("http://127.0.0.1:1234", "http://127.0.0.1:5678"))
        assertFalse(DownloadPolicy.sameOrigin("null", "https://server.test"))
        assertFalse(DownloadPolicy.sameOrigin("file:///a", "file:///b"))
    }
}
