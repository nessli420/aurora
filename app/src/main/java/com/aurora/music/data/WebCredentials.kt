package com.aurora.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saved accounts contain only an opaque reference; browser sessions stay encrypted outside backups. */
class WebCredentials(context: Context, name: String, private val alias: String) {
    private val directory = File(context.noBackupFilesDir, name)
    // one keystore decrypt per login; filled under update's lock so a rotated token never reverts
    private val cache = HashMap<String, String>()
    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(directory, id)
    }
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(session: String): String = UUID.randomUUID().toString().also { update(it, session) }
    // rewrites a saved login in place, for sessions whose cookies rotate
    @Synchronized fun update(id: String, session: String) {
        directory.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val atomic = AtomicFile(file(id))
        val output = atomic.startWrite()
        try {
            output.write(cipher.iv + cipher.doFinal(session.toByteArray(Charsets.UTF_8)))
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
        cache[id] = session
    }
    @Synchronized fun read(id: String): String = cache[id] ?: runCatching {
        val bytes = AtomicFile(file(id)).readFully()
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrDefault("").also { if (it.isNotEmpty()) cache[id] = it }
    @Synchronized fun remove(id: String) { cache.remove(id); runCatching { AtomicFile(file(id)).delete() } }

    companion object {
        fun youtubeMusic(context: Context) = WebCredentials(context, "youtube-music", "aurora.youtube-music.account")
        fun spotify(context: Context) = WebCredentials(context, "spotify-web", "aurora.spotify-web.account")
        fun soundcloud(context: Context) = WebCredentials(context, "soundcloud-web", "aurora.soundcloud-web.account")
    }
}
