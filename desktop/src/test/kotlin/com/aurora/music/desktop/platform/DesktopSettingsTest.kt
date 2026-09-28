package com.aurora.music.desktop.platform

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopSettingsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun settings(block: suspend (DesktopSettings) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DesktopSettings(PreferenceDataStoreFactory.create(scope = scope) { File(temp.root, "desktop_settings.preferences_pb") }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun defaultsApplyToAFreshFile() = settings {
        assertEquals(OutputPrefs(null, false, 400), it.output.first())
        assertNull(it.outputDeviceId.first())
        assertFalse(it.exclusiveMode.first())
        assertEquals(1f, it.volume.first())
        assertEquals("", it.languageTag.first())
        assertEquals(emptyList<String>(), it.musicFolders.first())
        assertFalse(it.closeToTray.first())
        assertNull(it.window.first())
    }

    @Test fun valuesRoundTripThroughTheFile() {
        val music = temp.newFolder("Música ü")
        val placement = WindowPlacement(-1200, 40, 1440, 900, maximized = true)
        settings {
            it.setOutputDevice("{0.0.0.00000000}.{6b03e6fd}")
            it.setExclusiveMode(true)
            it.setOutputBufferMs(250)
            it.setVolume(0.35f)
            it.setLanguageTag(" ru ")
            it.setMusicFolders(listOf(music.path))
            it.setCloseToTray(true)
            it.setWindow(placement)
        }
        settings {
            assertEquals(OutputPrefs("{0.0.0.00000000}.{6b03e6fd}", true, 250), it.output.first())
            assertEquals(0.35f, it.volume.first())
            assertEquals("ru", it.languageTag.first())
            assertEquals(listOf(music.path), it.musicFolders.first())
            assertEquals(true, it.closeToTray.first())
            assertEquals(placement, it.window.first())
            it.setOutputDevice(null)
            assertNull(it.outputDeviceId.first())
        }
    }

    @Test fun outputValuesAreClamped() = settings {
        it.setOutputBufferMs(1)
        assertEquals(DesktopSettings.MIN_BUFFER_MS, it.outputBufferMs.first())
        it.setOutputBufferMs(60_000)
        assertEquals(DesktopSettings.MAX_BUFFER_MS, it.outputBufferMs.first())
        it.setVolume(3f)
        assertEquals(1f, it.volume.first())
        it.setVolume(-1f)
        assertEquals(0f, it.volume.first())
    }

    @Test fun musicFoldersAreNormalizedAndDeduplicated() = settings {
        val rock = temp.newFolder("Music", "Rock")
        val jazz = temp.newFolder("Music", "Jazz")
        it.addMusicFolder("  ${rock.path}${File.separator}..${File.separator}Rock  ")
        it.addMusicFolder(rock.path.uppercase())
        it.addMusicFolder(jazz.path)
        it.addMusicFolder(" ")
        assertEquals(listOf(rock.path, jazz.path), it.musicFolders.first())
        it.removeMusicFolder(rock.path.lowercase())
        assertEquals(listOf(jazz.path), it.musicFolders.first())
    }
}
