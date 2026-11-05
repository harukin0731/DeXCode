package com.example.dexcode.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores optional SSH passwords encrypted with an Android Keystore AES key. */
class SecureSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun getPassword(connectionId: String): String? {
        val encoded = preferences.getString(keyFor(connectionId), null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > IV_LENGTH) { "Invalid encrypted secret" }
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun putPassword(connectionId: String, password: String): Result<Unit> =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Keystore keys require randomized encryption by default. Let the provider
            // generate the IV; caller-supplied IVs are rejected for encryption.
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "Unexpected GCM IV length" }
            val ciphertext = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
            check(preferences.edit()
                .putString(keyFor(connectionId), Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP))
                .commit()) { "Unable to persist encrypted password" }
        }

    fun removePassword(connectionId: String) {
        preferences.edit().remove(keyFor(connectionId)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyFor(connectionId: String): String = PASSWORD_PREFIX + connectionId

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dexcode_ssh_passwords"
        const val PREFERENCES = "secure_secrets"
        const val PASSWORD_PREFIX = "ssh_password_"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
