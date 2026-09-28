package com.aurora.music.desktop.platform

import com.aurora.music.data.LocalBackend
import com.aurora.music.data.MergedBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SubsonicBackend
import com.aurora.music.data.accountKey
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.audio.decode.TestAssets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopContainerTest {
    @get:Rule val temp = TemporaryFolder()

    private val paths by lazy { DesktopPaths(File(temp.root, "Roaming"), File(temp.root, "Local")) }
    private val subsonic = Session("http://127.0.0.1:9", "alice", "salt", "token")

    private fun container(block: suspend DesktopContainer.() -> Unit) {
        val container = DesktopContainer(paths)
        try {
            runBlocking { container.block() }
        } finally {
            container.close()
            runBlocking { container.scope.coroutineContext.job.join() }
        }
    }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T = withTimeout(10_000) { first(predicate) }

    private suspend fun DesktopContainer.awaitBackend(predicate: (Any?) -> Boolean) = withTimeout(10_000) {
        while (!predicate(backend)) delay(10)
    }

    @Test fun startsSignedOutWithTheLayoutInPlace() = container {
        assertEquals(false, sessionReady.await { it != null })
        assertNull(backend)
        assertFalse(offline.value)
        assertFalse(noNetwork.value)
        listOf(paths.roaming, paths.cache, paths.downloads, paths.logs, paths.library, paths.staging).forEach { assertTrue(it.path, it.isDirectory) }
        val file = File(temp.root, "Música ü/track #1.flac")
        assertEquals(desktopFileUri(file.path), downloadManager.fileUri(file.path))
        assertTrue(downloadManager.fileUri(file.path).startsWith("file:///"))
        assertEquals(clientInfo, desktopClientInfo)
    }

    @Test fun localSessionSurvivesARestart() {
        val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)
        container {
            sessionReady.await { it != null }
            applySession(local)
            assertTrue(backend is LocalBackend)
            assertTrue(isLocal)
            assertEquals(true, sessionReady.value)
            assertEquals(local.accountKey(), currentAccountKey())
            accountEpoch.await { it == 1 }
        }
        assertTrue(paths.settingsFile.isFile)
        container {
            assertEquals(true, sessionReady.await { it != null })
            assertTrue(backend is LocalBackend)
            assertEquals(0, accountEpoch.value)
            assertEquals(listOf(local), settingsStore.savedSessions.first())
        }
    }

    @Test fun switchingAccountsRebuildsTheBackendAndBumpsTheEpoch() = container {
        sessionReady.await { it != null }
        applySession(subsonic)
        assertTrue(backend is SubsonicBackend)
        val epoch = accountEpoch.await { it == 1 }
        switchSession(authenticator.local())
        assertTrue(backend is LocalBackend)
        accountEpoch.await { it == epoch + 1 }
        assertEquals(2, settingsStore.savedSessions.first().size)
        forgetSavedSession(authenticator.local())
        assertEquals(false, sessionReady.value)
        assertNull(backend)
        assertEquals(listOf(subsonic), settingsStore.savedSessions.first())
    }

    @Test fun unsupportedAccountsHaveNoBackend() = container {
        val spotify = Session("https://api.spotify.com", "me", "refresh", "access", ServerType.SPOTIFY)
        sessionReady.await { it != null }
        applySession(spotify)
        assertNull(backend)
        assertEquals(false, sessionReady.value)
        assertEquals(spotify, unsupportedAccount.value)
        signOut()
        assertNull(unsupportedAccount.value)
        assertNull(settingsStore.session.first())
    }

    @Test fun unifiedLibraryAndOfflineFollowSettings() = container {
        sessionReady.await { it != null }
        applySession(subsonic)
        settingsStore.setOfflineMode(true)
        offline.await { it }
        settingsStore.setUnifiedLibrary(true)
        awaitBackend { it is MergedBackend }
        libraryReload.await { it > 0 }
        switchSession(authenticator.local())
        offline.await { !it }
    }

    @Test fun musicFoldersFeedTheFolderLibrary() = container {
        val music = File(temp.root, "Music").apply { mkdirs() }
        TestAssets.file("gapless.mp3").copyTo(File(music, "gapless.mp3"))
        sessionReady.await { it != null }
        applySession(authenticator.local())
        val reload = libraryReload.value
        desktopSettings.addMusicFolder(music.path)
        folderLibrary.revision.await { it > 0 }
        assertEquals(listOf(desktopFileUri(File(music, "gapless.mp3").path)), folderLibrary.songs.map { it.streamUrl })
        libraryReload.await { it > reload }
        assertEquals(1, repository.allLibrarySongs(cap = 10).size)
    }
}
