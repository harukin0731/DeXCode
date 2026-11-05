package com.example.dexcode.data

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/** Small persistence layer for the MVP; it is deliberately synchronous and dependency-free. */
class ConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun getAll(): List<Connection> {
        val raw = preferences.getString(CONNECTIONS_KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList(json.length()) {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        Connection(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            url = item.optString("url"),
                            kind = runCatching {
                                Kind.valueOf(item.optString("kind", Kind.WEB.name))
                            }.getOrDefault(Kind.WEB),
                            sshHost = item.optString("sshHost"),
                            sshPort = item.optInt("sshPort", 22),
                            sshUsername = item.optString("sshUsername"),
                            workspacePath = item.optString("workspacePath"),
                            remotePort = item.optInt("remotePort", 8080),
                            sshLocalPort = item.optInt("sshLocalPort", 0),
                            sshKeyUri = item.optString("sshKeyUri").takeIf { it.isNotBlank() },
                            remoteCommandPrefix = item.optString("remoteCommandPrefix"),
                            lastUsedAt = item.optLong("lastUsedAt", 0L),
                            fixedPortBindings = PortBindings.decode(item.optJSONObject("fixedPortBindings")),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Connection? = getAll().firstOrNull { it.id == id }

    fun rememberPortBinding(id: String, remotePort: Int, localPort: Int): Connection {
        val connection = checkNotNull(get(id)) { "Connection settings were removed. Save the connection again first." }
        return upsert(connection.copy(fixedPortBindings = PortBindings.updated(connection.fixedPortBindings, remotePort, localPort)))
    }

    fun upsert(name: String, url: String, id: String? = null): Connection {
        return upsert(
            Connection(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            url = url.trim(),
            ),
        )
    }

    fun upsert(connection: Connection): Connection {
        val updated = getAll().filterNot { it.id == connection.id } + connection
        saveAll(updated)
        return connection
    }

    fun remove(id: String) {
        saveAll(getAll().filterNot { it.id == id })
        if (preferences.getString(LAST_USED_KEY, null) == id) {
            preferences.edit().remove(LAST_USED_KEY).apply()
        }
    }

    fun markLastUsed(id: String) {
        val now = System.currentTimeMillis()
        val connections = getAll()
        val updated = connections.map { connection ->
            if (connection.id == id) connection.copy(lastUsedAt = now) else connection
        }
        if (updated != connections) saveAll(updated)
        preferences.edit().putString(LAST_USED_KEY, id).apply()
    }

    fun getLastUsed(): Connection? {
        val id = preferences.getString(LAST_USED_KEY, null) ?: return null
        return get(id)
    }

    private fun saveAll(connections: List<Connection>) {
        val json = SettingsExport.connectionsJson(connections)
        preferences.edit().putString(CONNECTIONS_KEY, json.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "connections"
        const val CONNECTIONS_KEY = "items"
        const val LAST_USED_KEY = "last_used_id"
    }
}
