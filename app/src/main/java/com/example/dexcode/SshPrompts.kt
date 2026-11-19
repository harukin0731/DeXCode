package com.example.dexcode

import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.dexcode.data.SecureSecretStore
import com.example.dexcode.ssh.SshSessionManager
import com.example.dexcode.util.dp
import java.util.concurrent.CountDownLatch

internal class SshPrompts(private val activity: AppCompatActivity, private val connectionId: String) {
    private val secretStore by lazy { SecureSecretStore(activity.applicationContext) }
    fun confirmHostKey(message: String): Boolean = with(activity) {
        if (isFinishing || isDestroyed) return@with false
        val latch = CountDownLatch(1)
        var answer = false
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.host_key_title)
                .setMessage(getString(R.string.host_key_message_with_details, getString(R.string.host_key_message), message))
                .setNegativeButton(R.string.cancel) { _, _ -> latch.countDown() }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer = true
                    latch.countDown()
                }
                .create()
            dialog.setOnDismissListener { latch.countDown() }
            dialog.show()
        }
        latch.await()
        answer
    }

    fun askSecret(
        kind: SshSessionManager.SecretKind,
        prompt: String?,
    ): String? = with(activity) {
        if (isFinishing || isDestroyed) return@with null
        val isPassword = kind == SshSessionManager.SecretKind.PASSWORD
        val rememberedPassword = if (isPassword) {
            secretStore.getPassword(connectionId)
        } else {
            null
        }
        val latch = CountDownLatch(1)
        var answer: String? = null
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                // setSingleLine replaces the transformation, so masking must be applied last.
                transformationMethod = SecretMask
                isSaveEnabled = false
                imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                if (rememberedPassword != null) {
                    setText(rememberedPassword)
                    setSelection(text.length)
                }
                hint = getString(
                    if (!isPassword) {
                        R.string.ssh_passphrase
                    } else {
                        R.string.ssh_password
                    },
                )
            }
            val remember = CheckBox(this).apply {
                text = getString(R.string.remember_ssh_password)
                isChecked = rememberedPassword != null
            }
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(4), dp(22), 0)
                addView(input, LinearLayout.LayoutParams(-1, dp(58)))
                if (isPassword) addView(remember, LinearLayout.LayoutParams(-1, dp(48)))
            }
            val builder = AlertDialog.Builder(this)
                .setTitle(R.string.ssh_secret_title)
                .setView(form)
                .setNegativeButton(R.string.cancel) { _, _ -> latch.countDown() }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer = input.text.toString()
                    if (isPassword) {
                        if (remember.isChecked && !answer.isNullOrEmpty()) {
                            secretStore.putPassword(connectionId, answer.orEmpty()).onFailure {
                                Toast.makeText(
                                    activity,
                                    R.string.ssh_password_save_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } else if (!remember.isChecked || answer.isNullOrEmpty()) {
                            secretStore.removePassword(connectionId)
                        }
                    }
                    latch.countDown()
                }
            if (!prompt.isNullOrBlank()) {
                builder.setMessage(prompt)
            } else if (!isPassword) {
                builder.setMessage(R.string.ssh_key_passphrase_note)
            }
            val dialog = builder.create()
            dialog.setOnDismissListener { latch.countDown() }
            dialog.show()
        }
        latch.await()
        answer
    }

}
