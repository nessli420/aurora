package com.aurora.music.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Isolated app-storage failures exercise awaited writes and cross-store rollback. */
class BackupRestoreDurabilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = (context.applicationContext as AuroraApplication).container.settingsStore
    private val initialLocal = """{"playlists":[{"id":"local:old","title":"Old","trackIds":["one"]}],"likedIds":["one"]}"""
    private val replacementLocal = """{"playlists":[{"id":"local:new","title":"New","trackIds":["two"]}],"likedIds":["two"]}"""
    private fun event(id: String) = PlayEvent(id, id, "Artist", "Album", "album", "artist", "", 120, 1)

    private fun fixture(block: suspend (BackupManager, LocalStore, PlayHistoryStore, ContextWrapper, PrefsBackup) -> Unit) = runBlocking {
        val original = settings.exportPrefs()
        val root = File(context.cacheDir, "backup-durability-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val local = LocalStore(isolated)
        val history = PlayHistoryStore(isolated)
        try {
            local.restoreBackupJson(initialLocal)
            history.restoreBackup(listOf(event("old")))
            block(BackupManager(settings, local, history, isolated), local, history, isolated, original)
        } finally {
            settings.restoreBackupPrefs(original).getOrThrow()
            root.deleteRecursively()
        }
    }

    @Test fun localWriteFailureDoesNotChangePreferencesOrPublishedStores() = fixture { manager, local, history, isolated, original ->
        val localBefore = local.exportJson()
        val historyBefore = history.snapshot()
        val blocker = blockReplacement(File(isolated.filesDir, "local_store.json"))
        val result = manager.importArchive(input(original))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("preserved"))
        assertEquals(original, settings.exportPrefs())
        assertEquals(localBefore, local.exportJson())
        assertEquals(historyBefore, history.snapshot())
        assertEquals(historyBefore, PlayHistoryStore(isolated).snapshot())
        assertEquals("keep", blocker.readText())
        assertNoTemporaryFiles(isolated.filesDir)
    }

    @Test fun historyWriteFailureRollsBackAlreadyWrittenLocalLibrary() = fixture { manager, local, history, isolated, original ->
        val localBefore = local.exportJson()
        val historyBefore = history.snapshot()
        val blocker = blockReplacement(File(isolated.filesDir, "play_history.json"))
        assertTrue(manager.importArchive(input(original)).isFailure)
        assertEquals(original, settings.exportPrefs())
        assertEquals(localBefore, local.exportJson())
        assertEquals(localBefore, LocalStore(isolated).exportJson())
        assertEquals(historyBefore, history.snapshot())
        assertEquals("keep", blocker.readText())
        assertNoTemporaryFiles(isolated.filesDir)
    }

    @Test fun rejectedPreferencesRollBackBothDurableFiles() = fixture { manager, local, history, isolated, original ->
        val localBefore = local.exportJson()
        val historyBefore = history.snapshot()
        val invalid = original.copy(floats = original.floats + ("dsp_balance" to 2f))
        assertTrue(manager.importArchive(input(invalid)).isFailure)
        assertEquals(original, settings.exportPrefs())
        assertEquals(localBefore, local.exportJson())
        assertEquals(localBefore, LocalStore(isolated).exportJson())
        assertEquals(historyBefore, history.snapshot())
        assertEquals(historyBefore, PlayHistoryStore(isolated).snapshot())
        assertNoTemporaryFiles(isolated.filesDir)
    }

    @Test fun awaitedStoreRestoresAreReloadableImmediately() = fixture { _, local, history, isolated, _ ->
        local.restoreBackupJson(replacementLocal)
        history.restoreBackup(listOf(event("new")))
        assertEquals(local.exportJson(), LocalStore(isolated).exportJson())
        assertEquals(history.snapshot(), PlayHistoryStore(isolated).snapshot())
        assertNoTemporaryFiles(isolated.filesDir)
    }

    private fun input(prefs: PrefsBackup) = ByteArrayInputStream(Gson().toJson(AuroraBackup(
        prefs = prefs.copy(strings = prefs.strings + ("backup_durability_marker" to "new")),
        localStore = replacementLocal, playHistory = listOf(event("new")),
    )).toByteArray(Charsets.UTF_8))

    private fun blockReplacement(file: File): File {
        check(file.delete())
        check(file.mkdir())
        return File(file, "must-remain.txt").apply { writeText("keep") }
    }

    private fun assertNoTemporaryFiles(directory: File) {
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
