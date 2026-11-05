package com.example.dexcode.ssh

import android.content.Context
import android.net.Uri
import com.example.dexcode.data.Connection
import com.example.dexcode.data.ConnectionStore
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import com.example.dexcode.runtime.ForwardSource
import com.example.dexcode.runtime.RuntimeForward
import com.example.dexcode.runtime.RuntimeState
import java.util.UUID

/** Owns one SSH session and the local forwards used by an SSH workspace window. */
class SshSessionManager(
    private val context: Context,
    private val connection: Connection,
    private val interaction: Interaction,
    override val windowId: String = "",
) : ForwardSource {
    override val sourceId: String = UUID.randomUUID().toString()
    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    private val lifecycleLock = Any()
    private var sshClient: JSch? = null
    private val forwards = ForwardTable()
    override val connected: Boolean get() = !closed && session?.isConnected == true
    var resolvedWorkspacePath: String? = null
        private set
    var forwardingRestoreErrors: List<String> = emptyList()
        private set

    fun connect(): Int {
        check(session == null) { "SSH session is already connected" }
        check(!closed) { "SSH session is closed" }
        interaction.status("Connecting to ${connection.sshHost}:${connection.sshPort}")

        val jsch = JSch()
        SshPrivateKeyLoader.configureAndroidCrypto()
        val knownHosts = File(context.filesDir, "ssh/known_hosts")
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)

        val keyAlgorithm = loadPrivateKey(jsch)

        val sshSession = jsch.getSession(
            connection.sshUsername,
            connection.sshHost,
            connection.sshPort,
        )
        val userInfo = PromptingUserInfo(interaction)
        sshSession.userInfo = userInfo
        sshSession.setConfig("StrictHostKeyChecking", "ask")
        sshSession.setConfig(
            "PreferredAuthentications",
            SshPrivateKeyLoader.preferredAuthentications(keyAlgorithm != null),
        )
        sshSession.setServerAliveInterval(5_000)
        sshSession.setServerAliveCountMax(2)

        try {
            synchronized(lifecycleLock) {
                check(!closed) { "SSH session is closed" }
                session = sshSession
                sshClient = jsch
            }
            if (keyAlgorithm != null) interaction.status("Authenticating with the selected SSH key ($keyAlgorithm)")
            sshSession.connect(15_000)
            saveNewHostKey(jsch, sshSession)
            check(!closed) { "SSH session is closed" }

            val remotePort = connection.remotePort
            interaction.status("Preparing the remote VS Code server")
            val result = executeBootstrap(sshSession)
            resolvedWorkspacePath = result.stdout.lineSequence()
                .firstOrNull { it.startsWith("DEXCODE:workspace:") }
                ?.removePrefix("DEXCODE:workspace:")
                ?.takeIf { it.isNotBlank() }
            if (result.exitStatus != 0) {
                throw IllegalStateException(
                    result.stderr.ifBlank { result.stdout }.takeLast(MAX_ERROR_LENGTH),
                )
            }
            val localPort = synchronized(lifecycleLock) {
                check(!closed && sshSession.isConnected) { "SSH session is disconnected" }
                bindWebForward(sshSession, remotePort).also { forwards.record(it, remotePort, editor = true) }
            }
            val restoreErrors = mutableListOf<String>()
            ConnectionStore(context).get(connection.id)?.fixedPortBindings.orEmpty().toSortedMap().forEach { (remote, local) ->
                try {
                    forwardRemotePort(remote, local)
                } catch (error: Exception) {
                    restoreErrors += "Remote $remote → Android $local: ${error.message.orEmpty()}"
                }
            }
            forwardingRestoreErrors = restoreErrors
            synchronized(lifecycleLock) {
                check(!closed) { "SSH session is closed" }
                RuntimeState.store.addSource(this)
            }
            interaction.status("Remote VS Code is ready")
            return localPort
        } catch (error: Exception) {
            sshSession.disconnect()
            jsch.removeAllIdentity()
            session = null
            sshClient = null
            forwards.clear()
            RuntimeState.store.removeSource(sourceId)
            if (keyAlgorithm != null && error is JSchException &&
                error.message.orEmpty().startsWith("Auth fail")
            ) {
                throw IllegalStateException(
                    "The server did not complete authentication with the selected key ($keyAlgorithm). " +
                        "Check the SSH username and the matching authorized_keys entry. " +
                        "The server may require an additional authentication factor. " +
                        "Password fallback was not attempted. ${error.message}",
                    error,
                )
            }
            throw error
        }
    }

    fun forwardRemotePort(remotePort: Int, requestedLocalPort: Int? = null): Int {
        require(remotePort in 1..65535) { "Invalid remote port" }
        val requested = requestedLocalPort ?: ConnectionStore(context).get(connection.id)?.fixedPortBindings?.get(remotePort) ?: 0
        require(requested in 0..65535) { "Invalid Android port" }
        val localPort = synchronized(lifecycleLock) {
            val sshSession = checkNotNull(session?.takeIf { connected }) { "SSH session is not connected" }
            forwards.getOrCreate(remotePort, requested) {
                // A nonzero request is exact. Never fall back to an ephemeral port.
                sshSession.setPortForwardingL("127.0.0.1", requested, "127.0.0.1", remotePort)
            }
        }
        RuntimeState.store.changed()
        forwardingRestoreErrors = forwardingRestoreErrors.filterNot { it.startsWith("Remote $remotePort →") }
        return localPort
    }

    fun close() {
        synchronized(lifecycleLock) {
            closed = true
            session?.disconnect()
            sshClient?.removeAllIdentity()
            sshClient = null
            session = null
            forwards.clear()
        }
        RuntimeState.store.removeSource(sourceId)
    }

    override fun snapshotForwards(): List<RuntimeForward> = synchronized(lifecycleLock) {
        if (!connected) return@synchronized emptyList()
        forwards.snapshot().map { mapping ->
            RuntimeForward("$sourceId:${mapping.localPort}", sourceId, windowId, connection.name,
                "http://127.0.0.1:${mapping.localPort}/", mapping.remotePort, mapping.localPort, mapping.editor, fixed = mapping.fixed)
        }
    }

    override fun stopForward(localPort: Int): Boolean {
        val stopped = synchronized(lifecycleLock) {
            val sshSession = session?.takeIf { connected } ?: return@synchronized false
            forwards.stop(localPort) { sshSession.delPortForwardingL(it) }
        }
        if (stopped) RuntimeState.store.changed()
        return stopped
    }

    private fun bindWebForward(sshSession: Session, remotePort: Int): Int {
        val preferred = connection.sshLocalPort.takeIf { it in 1024..65535 }
            ?: stableLocalPort(connection.id)
        return runCatching {
            sshSession.setPortForwardingL(
                "127.0.0.1",
                preferred,
                "127.0.0.1",
                remotePort,
            )
        }.getOrElse {
            // A second DeX window for the same connection, or another local app, may
            // already own the preferred port. Keep that window usable; the normal case
            // still gets a stable origin and therefore stable WebView storage.
            sshSession.setPortForwardingL(
                "127.0.0.1",
                0,
                "127.0.0.1",
                remotePort,
            )
        }
    }

    private fun stableLocalPort(connectionId: String): Int {
        val positiveHash = connectionId.hashCode() and Int.MAX_VALUE
        return 20_000 + (positiveHash % 20_000)
    }

    private fun loadPrivateKey(jsch: JSch): String? {
        val keyUri = connection.sshKeyUri ?: return null
        val keyBytes = try {
            context.contentResolver.openInputStream(Uri.parse(keyUri))?.use { it.readBytes() }
                ?: throw IllegalStateException("Selected key has no readable contents")
        } catch (error: Exception) {
            throw IllegalStateException("Unable to read the selected SSH private key. Select the key file again in connection settings.", error)
        }
        return SshPrivateKeyLoader.load(jsch, keyBytes) {
            interaction.askSecret(SecretKind.PASSPHRASE)
        }
    }

    private fun saveNewHostKey(jsch: JSch, sshSession: Session) {
        val known = jsch.hostKeyRepository.getHostKey(connection.sshHost, null)
        if (known.isNullOrEmpty()) {
            jsch.hostKeyRepository.add(sshSession.hostKey, null)
        }
    }

    private fun executeBootstrap(sshSession: Session): CommandResult {
        val channel = sshSession.openChannel("exec") as ChannelExec
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val prefix = connection.remoteCommandPrefix.trim()
        val command = if (prefix.isBlank()) "sh -s" else "$prefix sh -s"
        channel.setCommand(command)
        channel.setInputStream(null)
        channel.setErrStream(errors)

        try {
            val input = channel.inputStream
            channel.connect(15_000)
            channel.outputStream.use { remoteInput ->
                remoteInput.write(buildBootstrapScript().toByteArray(StandardCharsets.UTF_8))
                remoteInput.flush()
            }
            input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.write('\n'.code)
                    if (line.startsWith("DEXCODE:")) {
                        interaction.status(statusText(line))
                    }
                }
            }
            while (!channel.isClosed) Thread.sleep(50)
            return CommandResult(
                stdout = output.toString(StandardCharsets.UTF_8.name()),
                stderr = errors.toString(StandardCharsets.UTF_8.name()),
                exitStatus = channel.exitStatus,
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun buildBootstrapScript(): String {
        val workspacePath = connection.workspacePath.trim()
        val workspaceAssignment = if (workspacePath.isBlank()) {
            "WORKSPACE=\"" + '$' + "HOME\""
        } else {
            """
            WORKSPACE_INPUT=${shellQuote(workspacePath)}
            case "${'$'}WORKSPACE_INPUT" in
              "~") WORKSPACE="${'$'}HOME" ;;
              "~/"*) WORKSPACE="${'$'}HOME/${'$'}{WORKSPACE_INPUT#~/}" ;;
              *) WORKSPACE="${'$'}HOME/${'$'}WORKSPACE_INPUT" ;;
            esac
            """.trimIndent()
        }
        val port = connection.remotePort
        val extensionsGallery = shellQuote(EXTENSIONS_GALLERY_JSON)
        return """
            set -eu
            # This is a non-interactive POSIX shell. Do not source the user's interactive
            # startup files: .profile/.bashrc often assume bash and may reference
            # BASH_VERSION, which is unset in `sh` and fatal under `set -u`.
            PATH="${'$'}HOME/.local/bin:${'$'}HOME/bin:${'$'}{PATH:-}"
            export PATH
            EXTENSIONS_GALLERY=$extensionsGallery
            export EXTENSIONS_GALLERY
            PORT=$port
            $workspaceAssignment
            if [ ! -d "${'$'}WORKSPACE" ]; then
              echo "Workspace does not exist: ${'$'}WORKSPACE" >&2
              exit 22
            fi
            echo "DEXCODE:workspace:${'$'}WORKSPACE"
            LOG="${'$'}HOME/.cache/dexcode/code-server-${'$'}PORT.log"
            # Include the marketplace configuration in the marker name. This makes an
            # already-running server restart once after upgrading DeXCode, then reuse the
            # configured process on later connections without editing versioned product.json.
            TRUST_MARKER="${'$'}HOME/.cache/dexcode/workspace-trust-disabled-${'$'}PORT-ms-marketplace"
            PID_FILE="${'$'}HOME/.cache/dexcode/code-server-${'$'}PORT.pid"
            mkdir -p "${'$'}HOME/.cache/dexcode"

            http_code() {
              if command -v curl >/dev/null 2>&1; then
                curl -sS --max-time 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${'$'}PORT/" 2>/dev/null || true
              elif command -v wget >/dev/null 2>&1; then
                if wget -q -O /dev/null -T 2 "http://127.0.0.1:${'$'}PORT/" 2>/dev/null; then
                  echo 200
                else
                  echo 000
                fi
              else
                echo 000
              fi
            }
            ready() {
              case "${'$'}(http_code)" in
                2*|3*|401|403) return 0 ;;
                *) return 1 ;;
              esac
            }

            if ready && [ -f "${'$'}TRUST_MARKER" ]; then
              echo "DEXCODE:reusing"
              exit 0
            fi

            if ready; then
              echo "DEXCODE:restarting"
              if ! command -v pkill >/dev/null 2>&1; then
                echo "An existing server is using port ${'$'}PORT, but pkill is unavailable to replace it" >&2
                exit 23
              fi
              pkill -TERM -f "code-server.*${'$'}PORT" 2>/dev/null || true
              pkill -TERM -f "openvscode-server.*${'$'}PORT" 2>/dev/null || true
              i=0
              while ready && [ "${'$'}i" -lt 10 ]; do
                i=${'$'}((i + 1))
                sleep 1
              done
              if ready; then
                echo "Unable to replace the existing VS Code server on port ${'$'}PORT" >&2
                exit 23
              fi
            fi

            SERVER=""
            SERVER_KIND=""
            SERVER_PID=""
            if command -v code-server >/dev/null 2>&1; then
              SERVER="${'$'}(command -v code-server)"
              SERVER_KIND="code-server"
            elif command -v openvscode-server >/dev/null 2>&1; then
              SERVER="${'$'}(command -v openvscode-server)"
              SERVER_KIND="openvscode-server"
            else
              echo "DEXCODE:installing"
              if command -v curl >/dev/null 2>&1; then
                curl -fsSL https://code-server.dev/install.sh | sh -s -- --method=standalone
              elif command -v wget >/dev/null 2>&1; then
                wget -qO- https://code-server.dev/install.sh | sh -s -- --method=standalone
              else
                echo "Neither curl nor wget is available on the remote device" >&2
                exit 20
              fi
              SERVER="${'$'}HOME/.local/bin/code-server"
              SERVER_KIND="code-server"
            fi

            echo "DEXCODE:starting:${'$'}SERVER_KIND"
            if [ "${'$'}SERVER_KIND" = "code-server" ]; then
              nohup "${'$'}SERVER" "${'$'}WORKSPACE" \
                --bind-addr 127.0.0.1:${'$'}PORT \
                --auth none \
                --disable-workspace-trust \
                --disable-telemetry \
                --disable-update-check >"${'$'}LOG" 2>&1 </dev/null &
              SERVER_PID=${'$'}!
            else
              nohup "${'$'}SERVER" \
                --host 127.0.0.1 \
                --port "${'$'}PORT" \
                --without-connection-token \
                --disable-workspace-trust \
                --default-folder "${'$'}WORKSPACE" >"${'$'}LOG" 2>&1 </dev/null &
              SERVER_PID=${'$'}!
            fi
            echo "${'$'}SERVER_PID" >"${'$'}PID_FILE"

            i=0
            while [ "${'$'}i" -lt 45 ]; do
              if ready; then
                : >"${'$'}TRUST_MARKER"
                echo "DEXCODE:ready"
                exit 0
              fi
              i=${'$'}((i + 1))
              sleep 1
            done
            echo "Remote VS Code server did not become ready; log: ${'$'}LOG" >&2
            tail -n 80 "${'$'}LOG" 2>/dev/null || true
            exit 21
        """.trimIndent() + "\n"
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    private fun statusText(line: String): String = when {
        line == "DEXCODE:installing" -> "Installing the remote code-server"
        line == "DEXCODE:reusing" -> "Reusing the remote VS Code server"
        line.startsWith("DEXCODE:workspace:") -> "Preparing the remote workspace"
        line == "DEXCODE:restarting" -> "Replacing the remote VS Code server"
        line.startsWith("DEXCODE:starting:") -> "Starting ${line.substringAfterLast(':')}"
        line == "DEXCODE:ready" -> "Remote VS Code is ready"
        else -> line.removePrefix("DEXCODE:")
    }

    private data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitStatus: Int,
    )

    private class PromptingUserInfo(private val interaction: Interaction) :
        UserInfo,
        UIKeyboardInteractive {
        private var password: String? = null
        private var passphrase: String? = null

        override fun getPassword(): String? = password

        override fun promptPassword(message: String): Boolean {
            password = interaction.askSecret(SecretKind.PASSWORD)
            return password != null
        }

        override fun getPassphrase(): String? = passphrase

        override fun promptPassphrase(message: String): Boolean {
            passphrase = interaction.askSecret(SecretKind.PASSPHRASE)
            return passphrase != null
        }

        override fun promptYesNo(message: String): Boolean = interaction.confirmHostKey(message)

        override fun showMessage(message: String) = interaction.status(message)

        override fun promptKeyboardInteractive(
            destination: String,
            name: String,
            instruction: String,
            prompt: Array<out String>,
            echo: BooleanArray,
        ): Array<String>? {
            val answers = prompt.mapIndexed { index, item ->
                if (echo.getOrNull(index) == true) {
                    interaction.askSecret(SecretKind.PASSWORD, item)
                } else {
                    interaction.askSecret(SecretKind.PASSWORD, item)
                }
            }
            return if (answers.all { it != null }) answers.map { it.orEmpty() }.toTypedArray() else null
        }
    }

    enum class SecretKind { PASSWORD, PASSPHRASE }

    interface Interaction {
        fun status(message: String)
        fun confirmHostKey(message: String): Boolean
        fun askSecret(kind: SecretKind, prompt: String? = null): String?
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 4_000
        private const val EXTENSIONS_GALLERY_JSON =
            "{\"serviceUrl\":\"https://marketplace.visualstudio.com/_apis/public/gallery\",\"cacheUrl\":\"https://vscode.blob.core.windows.net/gallery/index\",\"itemUrl\":\"https://marketplace.visualstudio.com/items\"}"
    }
}
