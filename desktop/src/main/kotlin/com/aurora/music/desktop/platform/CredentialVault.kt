package com.aurora.music.desktop.platform

import com.aurora.music.util.AppLog
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SecretStore {
    fun read(): ByteArray?
    fun write(secret: ByteArray): Boolean
}

class CredentialVault(private val store: SecretStore, private val random: SecureRandom = SecureRandom()) {
    private var key: SecretKey? = null
    private var failed = false

    @Synchronized
    fun seal(plain: ByteArray): ByteArray? {
        val key = key ?: load() ?: create() ?: return null
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return iv + cipher.doFinal(plain)
    }

    @Synchronized
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        val key = key ?: load() ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)) }
                .doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.getOrNull()
    }

    private fun load(): SecretKey? {
        if (failed) return null
        return attempt { store.read() }?.takeIf { it.size == KEY_BYTES }?.let { SecretKeySpec(it, "AES") }?.also { key = it }
    }

    private fun create(): SecretKey? {
        if (failed) return null
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        if (attempt { store.write(bytes) } != true) {
            failed = true
            return null
        }
        return SecretKeySpec(bytes, "AES").also { key = it }
    }

    private fun <T> attempt(block: () -> T): T? = runCatching(block)
        .onFailure {
            failed = true
            AppLog.w(TAG, "The system keyring is unavailable", it)
        }
        .getOrNull()

    private companion object {
        const val TAG = "CredentialVault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
