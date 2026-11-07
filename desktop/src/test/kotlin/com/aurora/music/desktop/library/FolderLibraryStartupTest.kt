package com.aurora.music.desktop.library

import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.desktop.platform.desktopFileUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class FolderLibraryStartupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun startupRefreshKeepsAnUnchangedIndexButStillPicksUpChanges() = runBlocking {
        val music = temp.newFolder("music")
        val dir = temp.newFolder("index")
        val index = File(dir, "index.json")
        val wav = File(music, "a.wav").apply { writeBytes(tone()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        suspend fun reopen() = FolderLibrary(dir, folders = { listOf(music.path) }, fileUri = ::desktopFileUri, scope = scope).also {
            it.ensureLoaded()
            scope.coroutineContext.job.children.toList().joinAll()
        }
        try {
            reopen()
            val stray = File(dir, "covers/stray.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
            assertTrue(index.setLastModified(OLD))

            val unchanged = reopen()
            assertEquals(OLD, index.lastModified())
            assertTrue(stray.isFile)
            val key = wav.absoluteFile.normalize().path.replace('\\', '/').lowercase(Locale.ROOT)
            assertEquals("local-" + MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) },
                unchanged.songs.single().id)

            unchanged.refresh()
            assertFalse(stray.exists())
            assertNotEquals(OLD, index.lastModified())

            File(music, "b.wav").writeBytes(tone())
            assertTrue(index.setLastModified(OLD))
            val added = reopen()
            assertEquals(2, added.songs.size)
            assertEquals(1, added.revision.value)
            assertNotEquals(OLD, index.lastModified())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun tone() = wavBytes(44_100, 2, 16, 44_100) { frame, _ -> frame % 64 }

    private companion object {
        const val OLD = 1_000_000L
    }
}
