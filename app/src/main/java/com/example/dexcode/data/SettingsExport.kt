package com.example.dexcode.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Versioned, explicit allowlist: never serializes secure_secrets or WebView profile files. */
object SettingsExport {
    fun build(
        connections: List<Connection>, lastUsedId: String?, developerMode: Boolean,
        knownHosts: String, exportedAt: String = Instant.now().toString(), darkHeader: Boolean = true, mergeTitlebar: Boolean = true,
    ): JSONObject = JSONObject()
        .put("format", "DeXCode.settings")
        .put("schemaVersion", 1)
        .put("exportedAt", exportedAt)
        .put("connections", connectionsJson(connections))
        .put("preferences", JSONObject()
            .put("lastUsedConnectionId", lastUsedId ?: JSONObject.NULL)
            .put("portDeveloperMode", developerMode)
            .put("darkHeader", darkHeader)
            .put("mergeTitlebar", mergeTitlebar))
        .put("sshKnownHosts", knownHosts)
        .put("excluded", JSONArray(listOf("savedPasswords", "privateKeyContents", "browserLoginState")))
        .put("keyReferenceNote", "sshKeyUri is a reference only. Reselect private-key files on another device.")

    fun connectionsJson(connections: List<Connection>): JSONArray = JSONArray().apply {
        connections.forEach { connection ->
            put(JSONObject()
                .put("id", connection.id)
                .put("name", connection.name)
                .put("url", connection.url)
                .put("kind", connection.kind.name)
                .put("sshHost", connection.sshHost)
                .put("sshPort", connection.sshPort)
                .put("sshUsername", connection.sshUsername)
                .put("workspacePath", connection.workspacePath)
                .put("remotePort", connection.remotePort)
                .put("sshLocalPort", connection.sshLocalPort)
                .put("sshKeyUri", connection.sshKeyUri ?: "")
                .put("remoteCommandPrefix", connection.remoteCommandPrefix)
                .put("lastUsedAt", connection.lastUsedAt)
                .put("fixedPortBindings", PortBindings.encode(connection.fixedPortBindings)))
        }
    }
}
