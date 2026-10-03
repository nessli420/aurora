package com.aurora.music.desktop.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialVaultTest {
    private class MemoryStore(var secret: ByteArray? = null, var available: Boolean = true) : SecretStore {
        var reads = 0
        var writes = 0

        override fun read(): ByteArray? {
            reads++
            check(available)
            return secret
        }

        override fun write(secret: ByteArray): Boolean {
            writes++
            if (available) this.secret = secret
            return available
        }
    }

    @Test fun aKeyIsCreatedOnceAndReusedAcrossRuns() {
        val store = MemoryStore()
        val vault = CredentialVault(store)
        val first = vault.seal("token=a".toByteArray())!!
        val second = vault.seal("token=a".toByteArray())!!
        assertFalse(first.contentEquals(second))
        assertEquals(1, store.writes)
        assertEquals(1, store.reads)
        assertArrayEquals("token=a".toByteArray(), CredentialVault(store).open(first))
        assertEquals(1, store.writes)
    }

    @Test fun tamperedOrForeignDataDoesNotOpen() {
        val store = MemoryStore()
        val sealed = CredentialVault(store).seal("token=a".toByteArray())!!
        sealed[sealed.lastIndex] = (sealed.last() + 1).toByte()
        assertNull(CredentialVault(store).open(sealed))
        assertNull(CredentialVault(MemoryStore(ByteArray(32))).open(CredentialVault(store).seal("x".toByteArray())!!))
        assertNull(CredentialVault(MemoryStore()).open(ByteArray(8)))
    }

    @Test fun anUnavailableKeyringIsNotAskedAgain() {
        val store = MemoryStore(available = false)
        val vault = CredentialVault(store)
        assertNull(vault.seal("token".toByteArray()))
        assertNull(vault.seal("token".toByteArray()))
        assertNull(vault.open(ByteArray(40)))
        assertEquals(1, store.reads)
        assertEquals(0, store.writes)
    }

    @Test fun aRefusedWriteKeepsNothing() {
        val store = object : SecretStore {
            override fun read(): ByteArray? = null
            override fun write(secret: ByteArray) = false
        }
        val vault = CredentialVault(store)
        assertNull(vault.seal("token".toByteArray()))
        assertNotNull(CredentialVault(MemoryStore()).seal("token".toByteArray()))
    }
}
