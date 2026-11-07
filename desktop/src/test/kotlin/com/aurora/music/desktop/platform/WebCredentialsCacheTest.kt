package com.aurora.music.desktop.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebCredentialsCacheTest {
    private val root: File = Files.createTempDirectory("aurora-web-credentials-cache").toFile()
    private val vault = CredentialVault(object : SecretStore {
        var secret: ByteArray? = null
        override fun read(): ByteArray? = secret
        override fun write(secret: ByteArray): Boolean = true.also { this.secret = secret }
    })

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun rotatedAndRemovedLoginsNeverComeBackFromTheCache() {
        val credentials = WebCredentials(root, "soundcloud-web", vault)
        val id = credentials.save("old")
        assertEquals("old", credentials.read(id))
        credentials.update(id, "new")
        File(root, "credentials/soundcloud-web").deleteRecursively()
        assertEquals("new", credentials.read(id))
        credentials.remove(id)
        assertEquals("", credentials.read(id))
    }
}
