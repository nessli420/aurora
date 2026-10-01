package com.aurora.music.desktop.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.AccessDeniedException

class ProtectedPreferencesTest {
    @get:Rule val temp = TemporaryFolder()

    private val token = stringPreferencesKey("token")
    private val file by lazy { File(temp.root, "settings.preferences_pb") }

    private fun <T> withStore(serializer: ProtectedPreferencesSerializer = ProtectedPreferencesSerializer(), block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(protectedPreferencesStore(file, scope, serializer))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun stored(text: String): Boolean = text in file.readBytes().toString(Charsets.ISO_8859_1)

    @Test fun settingsAreSealedWithDpapiAndReadBack() {
        withStore { store -> store.edit { it[token] = "secret-token-42" } }
        assertTrue(file.isFile)
        assertFalse(stored("secret-token-42"))
        assertEquals("secret-token-42", withStore { it.data.first()[token] })
    }

    @Test fun aPlaintextFileIsSealedWhenItIsOpened() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            PreferenceDataStoreFactory.create(scope = scope) { file }.edit { it[token] = "legacy-token-7" }
            scope.coroutineContext.job.cancelAndJoin()
        }
        assertTrue(stored("legacy-token-7"))
        assertEquals("legacy-token-7", withStore { it.data.first()[token] })
        assertFalse(stored("legacy-token-7"))
    }

    @Test fun aFileThisUserCannotDecryptStartsEmpty() {
        val sealed = ProtectedPreferencesSerializer(protect = { it.reversedArray() }, unprotect = { it.reversedArray() })
        withStore(sealed) { store -> store.edit { it[token] = "moved-token" } }
        assertFalse(stored("moved-token"))
        assertEquals("moved-token", withStore(sealed) { it.data.first()[token] })
        assertNull(withStore(ProtectedPreferencesSerializer(protect = { it.reversedArray() }, unprotect = { null })) { it.data.first()[token] })
    }

    @Test fun lockedRenamesAreRetriedUntilTheyGoThrough() {
        val source = temp.newFile("a.tmp").apply { writeText("fresh") }.toOkioPath()
        val target = File(temp.root, "a").toOkioPath()
        var locked = 3
        val flaky = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (locked-- > 0) throw AccessDeniedException(source.toString())
                super.atomicMove(source, target)
            }
        }
        RetryingFileSystem(flaky).atomicMove(source, target)
        assertEquals("fresh", target.toFile().readText())
        locked = Int.MAX_VALUE
        assertThrows(AccessDeniedException::class.java) { RetryingFileSystem(flaky).atomicMove(target, source) }
    }
}
