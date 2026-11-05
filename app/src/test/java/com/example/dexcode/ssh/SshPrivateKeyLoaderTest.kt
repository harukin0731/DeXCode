package com.example.dexcode.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test

class SshPrivateKeyLoaderTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/ssh/$name")).use { it.readBytes() }

    private fun verify(name: String, algorithm: String, passphrase: String? = null) {
        val jsch = JSch()
        val bytes = fixture(name)
        var prompts = 0
        assertEquals(algorithm, SshPrivateKeyLoader.load(jsch, bytes) { prompts++; passphrase })
        assertEquals(if (passphrase == null) 0 else 1, prompts)
        assertTrue(bytes.all { it == 0.toByte() })
        val identity = jsch.identityRepository.identities.single()
        val signingAlgorithm = if (algorithm == "ssh-rsa") "rsa-sha2-512" else algorithm
        val data = "synthetic signing test".toByteArray()
        val signature = identity.getSignature(data, signingAlgorithm)
        assertNotNull(signature)
        val pair = KeyPair.load(jsch, fixture(name), null)
        try {
            if (passphrase != null) assertTrue(pair.decrypt(passphrase.toByteArray()))
            val verifier = pair.getVerifier(signingAlgorithm)
            verifier.update(data)
            assertTrue(verifier.verify(signature))
        } finally {
            pair.dispose()
            jsch.removeAllIdentity()
        }
    }

    @Test fun signsWithEd25519UsingTheAndroidBcPath() {
        verify("test_ed25519", "ssh-ed25519")
        assertEquals("com.jcraft.jsch.bc.SignatureEd25519", JSch.getConfig("ssh-ed25519"))
    }

    @Test fun decryptsEncryptedOpenSshEd25519WithPassphrase() {
        verify("test_ed25519_encrypted", "ssh-ed25519", "dexcode-test-passphrase")
    }

    @Test fun signsWithRsaSha2() = verify("test_rsa", "ssh-rsa")

    @Test fun rejectsWrongPassphraseAndRemovesTheIdentity() {
        val jsch = JSch()
        val bytes = fixture("test_ed25519_encrypted")
        val error = runCatching { SshPrivateKeyLoader.load(jsch, bytes) { "wrong" } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("passphrase"))
        assertTrue(jsch.identityRepository.identities.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun rejectsPublicKeyWithoutAskingForAPassphrase() {
        val jsch = JSch()
        val error = runCatching {
            SshPrivateKeyLoader.load(jsch, fixture("test_ed25519.pub")) { fail("A public key must not trigger a passphrase prompt"); null }
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(jsch.identityRepository.identities.isEmpty())
    }

    @Test fun selectedKeyNeverSilentlyFallsBackToAccountPassword() {
        assertEquals("publickey", SshPrivateKeyLoader.preferredAuthentications(true))
        assertTrue(SshPrivateKeyLoader.preferredAuthentications(false).contains("password"))
    }
}
