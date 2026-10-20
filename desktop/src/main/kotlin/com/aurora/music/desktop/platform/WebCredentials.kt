package com.aurora.music.desktop.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// browser logins for streaming services; windows keeps them under the user's DPAPI key, linux under the keyring's
// with no keyring on linux they only last until aurora closes
class WebCredentials(private val root: File, name: String, private val vault: CredentialVault? = if (HostPlatform.isLinux) CredentialVault.system else null) {
    private val directory = File(root, "credentials/$name")
    private val memory = ConcurrentHashMap<String, String>()

    fun save(session: String): String = UUID.randomUUID().toString().also { update(it, session) }

    // rewrites a saved login in place, for sessions whose cookies rotate
    @Synchronized fun update(id: String, session: String) {
        val target = file(id)
        val sealed = seal(session.toByteArray(Charsets.UTF_8))
        if (sealed == null) {
            memory[id] = session
            target.delete()
            return
        }
        memory.remove(id)
        directory.mkdirs()
        val temp = File(directory, "$id.tmp")
        temp.writeBytes(sealed)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun read(id: String): String {
        memory[id]?.let { return it }
        if (!HostPlatform.isWindows) migrateLegacy(root, vault)
        return runCatching { open(file(id).readBytes())?.let { String(it, Charsets.UTF_8) } }.getOrNull() ?: ""
    }

    fun remove(id: String) {
        memory.remove(id)
        file(id).delete()
    }

    private fun file(id: String): File {
        require(id.matches(ID)) { "bad credential id" }
        return File(directory, "$id.bin")
    }

    private fun seal(plain: ByteArray): ByteArray? =
        if (HostPlatform.isWindows) Crypt32Util.cryptProtectData(plain) else vault?.seal(plain)

    private fun open(sealed: ByteArray): ByteArray? =
        if (HostPlatform.isWindows) Crypt32Util.cryptUnprotectData(sealed) else vault?.open(sealed)

    companion object {
        private val ID = Regex("[0-9a-fA-F-]{36}")
        private val migration = Any()
        fun youtubeMusic(root: File) = WebCredentials(root, "youtube-music")
        fun spotify(root: File) = WebCredentials(root, "spotify-web")
        fun soundcloud(root: File) = WebCredentials(root, "soundcloud-web")

        // earlier linux builds sealed logins with a key file kept beside them; move them under the keyring and drop the file
        internal fun migrateLegacy(root: File, vault: CredentialVault?) = synchronized(migration) {
            val keyFile = File(root, "credentials/.key")
            if (vault == null || !keyFile.isFile) return@synchronized
            val key = SecretKeySpec(keyFile.readBytes(), "AES")
            val files = File(root, "credentials").listFiles(File::isDirectory).orEmpty()
                .flatMap { dir -> dir.listFiles { file -> file.name.endsWith(".bin") }.orEmpty().toList() }
            val pending = files.filter { file ->
                val sealed = file.readBytes()
                if (vault.open(sealed) != null) return@filter false
                val plain = runCatching {
                    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
                        .doFinal(sealed, 12, sealed.size - 12)
                }.getOrNull() ?: return@filter false
                val resealed = vault.seal(plain) ?: return@filter true
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeBytes(resealed)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                false
            }
            if (pending.isEmpty()) keyFile.delete()
        }
    }
}
