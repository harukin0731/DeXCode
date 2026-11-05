package com.example.dexcode.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException

/** Uses BC directly through JSch; leaves Android's platform/Keystore providers intact. */
internal object SshPrivateKeyLoader {
    fun configureAndroidCrypto() {
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
    }

    fun preferredAuthentications(hasSelectedKey: Boolean): String =
        if (hasSelectedKey) "publickey" else "none,publickey,keyboard-interactive,password"

    fun load(jsch: JSch, bytes: ByteArray, askPassphrase: () -> String?): String {
        configureAndroidCrypto()
        try {
            try {
                jsch.addIdentity("selected-private-key", bytes, null, null)
            } catch (error: JSchException) {
                throw IllegalArgumentException(
                    "Cannot read this SSH private key. Select the private key, not its .pub file; the format may also be unsupported.",
                    error,
                )
            }
            val identity = jsch.identityRepository.identities.singleOrNull()
                ?: error("No SSH private key was loaded")
            if (identity.isEncrypted) {
                val passphrase = askPassphrase()?.toByteArray(Charsets.UTF_8)
                    ?: throw IllegalStateException("SSH private-key passphrase entry was cancelled")
                try {
                    check(identity.setPassphrase(passphrase) && !identity.isEncrypted) {
                        "The private-key passphrase is incorrect or the key encryption format is unsupported. This is not the server account password."
                    }
                } finally {
                    passphrase.fill(0)
                }
            }
            val publicKey = identity.publicKeyBlob
            check(publicKey != null && publicKey.isNotEmpty()) { "The selected file does not contain a usable SSH private key" }
            val algorithm = identity.algName
            val signingAlgorithm = if (algorithm == "ssh-rsa") "rsa-sha2-512" else algorithm
            // JSch returns null on missing signing implementations and would otherwise
            // silently fall through to password authentication. Fail before connecting.
            val probe = "DeXCode local SSH key validation".toByteArray(Charsets.UTF_8)
            val signature = identity.getSignature(probe, signingAlgorithm)
            check(signature != null && signature.isNotEmpty()) {
                "Cannot sign with the selected SSH key ($algorithm). The key format or algorithm is unsupported."
            }
            return algorithm
        } catch (error: Exception) {
            jsch.removeAllIdentity()
            throw error
        } finally {
            bytes.fill(0)
        }
    }
}
