package com.example.dexcode

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test fun authenticationSecretsAndWorkspacePathsNeverAppearInDiagnostics() {
        assertEquals("https://vscode.dev (callback)", ConnectionDiagnostics.safeTarget(
            "https://user:password@vscode.dev/callback?code=secret-token&state=secret#access_token=secret",
        ))
        assertEquals("https://vscode.dev (tunnel)", ConnectionDiagnostics.safeTarget(
            "https://vscode.dev/tunnel/private-machine/private-folder?token=secret",
        ))
        assertEquals("non-http-page", ConnectionDiagnostics.safeTarget("vscode://auth?token=secret"))
        assertEquals("https://example.com (page)", ConnectionDiagnostics.safeTarget("https://example.com/secret-token"))
    }
}
