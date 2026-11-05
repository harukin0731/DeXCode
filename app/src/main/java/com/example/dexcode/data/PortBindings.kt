package com.example.dexcode.data

import org.json.JSONObject

/** Saved remote-port -> Android-port settings for one SSH connection. */
object PortBindings {
    fun decode(json: JSONObject?): Map<Int, Int> = buildMap {
        if (json == null) return@buildMap
        json.keys().forEach { key ->
            val remote = key.toIntOrNull()
            val local = json.optInt(key, 0)
            if (remote != null && remote in 1..65535 && local in 1..65535 && local !in values) put(remote, local)
        }
    }

    fun encode(bindings: Map<Int, Int>): JSONObject = JSONObject().apply {
        bindings.toSortedMap().forEach { (remote, local) -> put(remote.toString(), local) }
    }

    fun updated(bindings: Map<Int, Int>, remotePort: Int, localPort: Int): Map<Int, Int> {
        require(remotePort in 1..65535) { "Remote port must be between 1 and 65535" }
        require(localPort in 0..65535) { "Android port must be between 1 and 65535, or automatic" }
        require(localPort == 0 || bindings.none { it.key != remotePort && it.value == localPort }) {
            "Android port $localPort is already saved for another remote port"
        }
        return bindings.toMutableMap().apply {
            if (localPort == 0) remove(remotePort) else put(remotePort, localPort)
        }
    }
}
