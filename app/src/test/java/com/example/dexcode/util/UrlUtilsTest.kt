package com.example.dexcode.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    private val server = "https://vscode.dev/tunnel/machine/workspace"

    @Test fun resolvesBarePortsAndPreservesProxyPathQueryAndFragment() {
        org.junit.Assert.assertEquals("https://code.test/base/proxy/3000/", UrlUtils.resolvePortOrUrl("3000", "https://code.test/base/"))
        org.junit.Assert.assertEquals("https://code.test/base/proxy/5173/path?a=1#anchor", UrlUtils.resolvePortOrUrl("http://localhost:5173/path?a=1#anchor", "https://code.test/base/"))
        for (port in listOf("0", "65536", "999999999999")) org.junit.Assert.assertNull(UrlUtils.resolvePortOrUrl(port, "https://code.test/"))
        org.junit.Assert.assertNull(UrlUtils.resolvePortOrUrl("3000", server))
        org.junit.Assert.assertNull(UrlUtils.resolvePortOrUrl("localhost:3000", server))
    }

    @Test fun sameOriginAuthenticationPagesRemainPopups() {
        for (path in listOf("/msal?clientId=test", "/redirect?url=test", "/callback?vscode-reqid=1", "/")) {
            assertFalse(path, UrlUtils.shouldPromoteEditorPopup(server, "https://vscode.dev$path", false))
        }
    }

    @Test fun authenticationReturnNeverBecomesANewEditorTask() {
        assertFalse(UrlUtils.shouldPromoteEditorPopup(server, server, true))
        assertFalse(UrlUtils.shouldPromoteEditorPopup(server, "https://github.com/login", false))
        assertFalse(UrlUtils.shouldPromoteEditorPopup(server, "https://vscode.dev.evil.example/tunnel/host", false))
    }

    @Test fun intentionalEditorWindowsStillPromote() {
        assertTrue(UrlUtils.shouldPromoteEditorPopup(server, "https://vscode.dev/tunnel/other/workspace", false))
        assertTrue(UrlUtils.shouldPromoteEditorPopup("https://insiders.vscode.dev/tunnel/host", "https://insiders.vscode.dev/tunnel/other", false))
        assertTrue(UrlUtils.shouldPromoteEditorPopup("http://localhost:8080/", "http://localhost:8080/?folder=test", false))
        assertFalse(UrlUtils.shouldPromoteEditorPopup(server, "http://vscode.dev/tunnel/host", false))
    }
}
