package com.aurora.music.desktop.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebCredentialsTest {
    private val root: File = Files.createTempDirectory("aurora-web-credentials").toFile()
    private val session = "SAPISID=secret-cookie; __Secure-3PSID=another-secret"

    private class Keyring(var available: Boolean = true) : SecretStore {
        var secret: ByteArray? = null
        override fun read(): ByteArray? = secret.also { check(available) }
        override fun write(secret: ByteArray): Boolean = available.also { if (it) this.secret = secret }
    }

    @Before fun notWindows() {
        assumeFalse(HostPlatform.isWindows)
    }

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun stored(name: String) = File(root, "credentials/$name").listFiles().orEmpty().toList()

    @Test fun loginsAreSealedWithTheKeyringAndNoKeyFileIsWritten() {
        val keyring = Keyring()
        val id = WebCredentials(root, "youtube-music", CredentialVault(keyring)).save(session)
        val files = stored("youtube-music")
        assertEquals(1, files.size)
        assertFalse(String(files.single().readBytes(), Charsets.ISO_8859_1).contains("secret-cookie"))
        assertFalse(File(root, "credentials/.key").exists())
        assertEquals(session, WebCredentials(root, "youtube-music", CredentialVault(keyring)).read(id))
        assertEquals("", WebCredentials(root, "youtube-music", CredentialVault(Keyring())).read(id))
    }

    @Test fun withoutAKeyringLoginsOnlyLastForTheSession() {
        val credentials = WebCredentials(root, "spotify-web", CredentialVault(Keyring(available = false)))
        val id = credentials.save(session)
        assertEquals(session, credentials.read(id))
        assertTrue(stored("spotify-web").isEmpty())
        assertEquals("", WebCredentials(root, "spotify-web", CredentialVault(Keyring(available = false))).read(id))
        credentials.remove(id)
        assertEquals("", credentials.read(id))
    }

    @Test fun loginsFromTheOldKeyFileMoveUnderTheKeyring() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        File(root, "credentials").mkdirs()
        File(root, "credentials/.key").writeBytes(key)
        val ids = listOf("youtube-music", "soundcloud-web").associateWith { name ->
            val id = "0f8f1a6e-2c1b-4a59-9b8e-5d1c2e3f4a5b"
            File(root, "credentials/$name").mkdirs()
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val sealed = iv + Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
                .doFinal("$name:$session".toByteArray())
            File(root, "credentials/$name/$id.bin").writeBytes(sealed)
            id
        }

        val locked = Keyring(available = false)
        assertEquals("", WebCredentials(root, "youtube-music", CredentialVault(locked)).read(ids.getValue("youtube-music")))
        assertTrue(File(root, "credentials/.key").isFile)

        val keyring = Keyring()
        assertEquals("youtube-music:$session", WebCredentials(root, "youtube-music", CredentialVault(keyring)).read(ids.getValue("youtube-music")))
        assertFalse(File(root, "credentials/.key").exists())
        assertNotNull(keyring.secret)
        assertEquals("soundcloud-web:$session", WebCredentials(root, "soundcloud-web", CredentialVault(keyring)).read(ids.getValue("soundcloud-web")))
    }
}
