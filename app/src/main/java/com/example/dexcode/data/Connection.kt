package com.example.dexcode.data

data class Connection(
    val id: String,
    val name: String,
    val url: String,
    val kind: Kind = Kind.WEB,
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUsername: String = "",
    val workspacePath: String = "",
    val remotePort: Int = 8080,
    /** Stable Android-side port keeps the SSH WebView origin stable across reconnects. */
    val sshLocalPort: Int = 0,
    val sshKeyUri: String? = null,
    val remoteCommandPrefix: String = "",
    val lastUsedAt: Long = 0L,
    val fixedPortBindings: Map<Int, Int> = emptyMap(),
)

enum class Kind {
    WEB,
    SSH,
}
