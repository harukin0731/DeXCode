package com.example.dexcode.ssh

data class ForwardMapping(val localPort: Int, val remotePort: Int, val editor: Boolean, val fixed: Boolean = false)

/** Automatic requests reuse a remote listener; explicit ports can add aliases. Editor listeners stay protected. */
class ForwardTable {
    private val mappings = linkedMapOf<Int, ForwardMapping>()
    @Synchronized fun record(localPort: Int, remotePort: Int, editor: Boolean = false, fixed: Boolean = false) {
        mappings[localPort] = ForwardMapping(localPort, remotePort, editor, fixed)
    }
    @Synchronized fun getOrCreate(remotePort: Int, requestedLocalPort: Int = 0, create: () -> Int): Int {
        require(remotePort in 1..65535 && requestedLocalPort in 0..65535) { "Invalid forwarding port" }
        if (requestedLocalPort > 0) {
            mappings[requestedLocalPort]?.let {
                check(it.remotePort == remotePort) { "Android port $requestedLocalPort already forwards to remote port ${it.remotePort}" }
                if (!it.fixed) mappings[requestedLocalPort] = it.copy(fixed = true)
                return it.localPort
            }
        } else mappings.values.firstOrNull { it.remotePort == remotePort }?.let { return it.localPort }
        return create().also { record(it, remotePort, fixed = requestedLocalPort > 0) }
    }
    @Synchronized fun stop(localPort: Int, release: (Int) -> Unit): Boolean {
        val mapping = mappings[localPort] ?: return false
        if (mapping.editor) return false
        release(localPort)
        mappings.remove(localPort)
        return true
    }
    @Synchronized fun snapshot(): List<ForwardMapping> = mappings.values.toList()
    @Synchronized fun clear() { mappings.clear() }
}
