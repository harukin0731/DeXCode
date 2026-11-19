package com.example.dexcode

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.dexcode.data.Connection
import com.example.dexcode.data.ConnectionStore
import com.example.dexcode.data.Kind
import com.example.dexcode.ssh.SshSessionManager
import com.example.dexcode.ui.AppSurface
import com.example.dexcode.ui.ProjectFilesDialog
import com.example.dexcode.util.dp
import java.util.concurrent.Executors

class ProjectFilesActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var manager: SshSessionManager? = null
    private var files: ProjectFilesDialog? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) files?.setFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connection = intent.getStringExtra(CodeActivity.EXTRA_CONNECTION_ID)
            ?.let { ConnectionStore(this).get(it) }?.takeIf { it.kind == Kind.SSH }
        if (connection == null) {
            finish()
            return
        }
        title = getString(R.string.project_files) + " · " + connection.name
        connect(connection)
    }

    private fun connect(connection: Connection) {
        manager?.close()
        val colors = AppSurface(this, getSharedPreferences("desktop_header", 0).getBoolean("dark", true))
        val status = colors.label(getString(R.string.files_working))
        val retry = colors.button(getString(R.string.files_refresh)) { connect(connection) }.apply { isEnabled = false }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(colors.label(title.toString(), 20f))
            addView(status)
            addView(retry)
        })
        val prompts = SshPrompts(this, connection.id)
        val session = SshSessionManager(applicationContext, connection, object : SshSessionManager.Interaction {
            override fun status(message: String) { runOnUiThread { status.text = message } }
            override fun confirmHostKey(message: String) = prompts.confirmHostKey(message)
            override fun askSecret(kind: SshSessionManager.SecretKind, prompt: String?) = prompts.askSecret(kind, prompt)
        })
        manager = session
        executor.execute {
            val result = runCatching { session.connectFiles() }
            runOnUiThread {
                if (isFinishing || isDestroyed || manager !== session) return@runOnUiThread
                result.onSuccess {
                    val path = intent.getStringExtra(EXTRA_PROJECT_PATH)
                        ?: connection.workspacePath.trim().removePrefix("~/").takeUnless { it.isBlank() || it == "~" }
                        ?: "."
                    files = ProjectFilesDialog(this, session, executor, path) { folderPicker.launch(null) }
                        .also { it.show() }
                }.onFailure {
                    status.text = getString(R.string.files_failed, it.message.orEmpty())
                    retry.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        manager?.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROJECT_PATH = "project_files_path"

        fun open(activity: Activity, connectionId: String, path: String? = null) {
            activity.startActivity(Intent(activity, ProjectFilesActivity::class.java)
                .putExtra(CodeActivity.EXTRA_CONNECTION_ID, connectionId)
                .putExtra(EXTRA_PROJECT_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
    }
}