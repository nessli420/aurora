package com.aurora.music.desktop.linux

import com.aurora.music.desktop.platform.CredentialVault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SecretServiceStoreTest {
    @Test fun theKeyIsStoredInTheKeyringAndReadBack() {
        PrivateBus.assumeAvailable("gnome-keyring-daemon", "gdbus", "secret-tool")
        PrivateBus().use { bus ->
            bus.startKeyring()
            val connection = bus.connect()
            val store = SecretServiceStore(connection = { connection })
            assertNull(store.read())
            val key = ByteArray(32) { it.toByte() }
            assertTrue(store.write(key))
            assertArrayEquals(key, store.read())
            assertArrayEquals(key, SecretServiceStore(connection = { bus.connect() }).read())
            val lookup = bus.run("secret-tool", "lookup", "application", "com.aurora.music", "purpose", "settings-key")
                .inputStream.readBytes()
            assertArrayEquals(key, lookup)
        }
    }

    @Test fun aVaultSealedOnOneRunOpensOnTheNext() {
        PrivateBus.assumeAvailable("gnome-keyring-daemon", "gdbus")
        PrivateBus().use { bus ->
            bus.startKeyring()
            val sealed = CredentialVault(SecretServiceStore(connection = { bus.connect() })).seal("token=secret-42".toByteArray())!!
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret-42"))
            val opened = CredentialVault(SecretServiceStore(connection = { bus.connect() })).open(sealed)
            assertEquals("token=secret-42", opened?.toString(Charsets.UTF_8))
            assertNull(CredentialVault(SecretServiceStore(connection = { bus.connect() })).open(Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
        }
    }

    @Test fun withoutAKeyringNothingIsStored() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val store = SecretServiceStore(connection = { bus.connect() })
            val vault = CredentialVault(store)
            assertNull(vault.seal("token".toByteArray()))
            assertNull(vault.open(ByteArray(40)))
            assertNull(SecretServiceStore(connection = { null }).read())
            assertFalse(SecretServiceStore(connection = { null }).write(ByteArray(32)))
        }
    }
}
