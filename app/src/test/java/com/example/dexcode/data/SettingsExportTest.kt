package com.example.dexcode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsExportTest {
    @Test fun exportsVersionedSettingsWithAllConnectionFieldsAndUnicode() {
        val connection = Connection("id", "開発用", "", Kind.SSH, "machine.test", 2222,
            "user", "/home/user/日本語\"folder", 8080, 20000, "content://keys/reference", "wsl.exe -d Ubuntu --", 123L, mapOf(3000 to 13000))
        val document = SettingsExport.build(listOf(connection), "id", true, "host ssh-ed25519 public-key", "2026-10-26T00:00:00Z", darkHeader = false, mergeTitlebar = false)
        val parsed = JSONObject(document.toString(2))
        assertEquals("DeXCode.settings", parsed.getString("format"))
        assertEquals(1, parsed.getInt("schemaVersion"))
        val row = parsed.getJSONArray("connections").getJSONObject(0)
        assertEquals(14, row.length())
        assertEquals(connection.fixedPortBindings, PortBindings.decode(row.getJSONObject("fixedPortBindings")))
        assertEquals(connection.workspacePath, row.getString("workspacePath"))
        assertEquals(connection.sshKeyUri, row.getString("sshKeyUri"))
        assertEquals(2222, row.getInt("sshPort"))
        assertEquals(20000, row.getInt("sshLocalPort"))
        assertEquals(connection.remoteCommandPrefix, row.getString("remoteCommandPrefix"))
        assertTrue(parsed.getJSONObject("preferences").getBoolean("portDeveloperMode"))
        assertFalse(parsed.getJSONObject("preferences").getBoolean("darkHeader"))
        assertFalse(parsed.getJSONObject("preferences").getBoolean("mergeTitlebar"))
        assertEquals("id", parsed.getJSONObject("preferences").getString("lastUsedConnectionId"))
        assertEquals("host ssh-ed25519 public-key", parsed.getString("sshKnownHosts"))
        assertFalse(row.has("password"))
        assertFalse(row.has("privateKey"))
        assertFalse(parsed.has("secure_secrets"))
        assertFalse(parsed.has("cookies"))
    }

    @Test fun handlesEmptySettingsAndKeepsPersistenceSchemaCompatible() {
        val empty = SettingsExport.build(emptyList(), null, false, "")
        assertEquals(0, empty.getJSONArray("connections").length())
        assertTrue(empty.getJSONObject("preferences").isNull("lastUsedConnectionId"))
        val row = SettingsExport.connectionsJson(listOf(Connection("web", "Web", "https://example.test/?a=1&b=2"))).getJSONObject(0)
        assertEquals("WEB", row.getString("kind"))
        assertEquals("", row.getString("sshKeyUri"))
        assertEquals("https://example.test/?a=1&b=2", row.getString("url"))
    }
}
