package com.aurora.music.desktop.platform

import com.aurora.music.data.DownloadState
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalProfile
import com.aurora.music.data.MergedBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SubsonicBackend
import com.aurora.music.data.accountKey
import com.aurora.music.data.artwork.ArtworkRequest
import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.audio.decode.TestAssets
import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

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

    @Test fun localSignInReplacesOlderLocalSpellings() = container {
        sessionReady.await { it != null }
        val legacy = Session("On this device", "Local library", "", "local", ServerType.LOCAL)
        settingsStore.addSavedSession(legacy)
        settingsStore.addSavedSession(subsonic)
        applySession(authenticator.local())
        assertEquals(listOf(subsonic, AccountAuthenticator.LOCAL_SESSION), settingsStore.savedSessions.first())
    }

    @Test fun credentialsAndTheQueueStayOutOfPlainRoamingFiles() {
        val secret = Session("http://127.0.0.1:9", "alice", "pepper-salt-9", "secret-token-42")
        File(paths.roaming.apply { mkdirs() }, "queue_state.json")
            .writeText("""{"subsonic|alice":{"tracks":[{"id":"s1","streamUrl":"http://127.0.0.1:9/rest/stream.view?t=secret"}]}}""")
        container {
            sessionReady.await { it != null }
            applySession(secret)
            assertEquals("s1", queueStore.get("subsonic|alice")?.tracks?.single()?.id)
        }
        val settings = paths.settingsFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("secret-token-42" in settings)
        assertFalse("pepper-salt-9" in settings)
        assertFalse(File(paths.roaming, "queue_state.json").exists())
        assertTrue(File(paths.local, "queue_state.json").isFile)
        container { assertEquals(secret, settingsStore.session.first()) }
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

    @Test fun downloadsSaveTheServerCoverBehindArtworkUris() {
        val cover = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, 0x3366ff) }, "png", it)
        }.toByteArray()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/rest/getCoverArt.view" -> MockResponse().setBody(Buffer().write(cover))
                    "/rest/stream.view" -> MockResponse().setBody("audio")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val request = ArtworkRequest("Lunar Tide", "Nocturne", "", server.url("/rest/getCoverArt.view?id=al-1").toString(), 0)
            val artwork = "content://${ArtworkUrls.AUTHORITY}/v1/" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(request).toByteArray())
            val song = Song(id = "s1", title = "Midnight Bloom", artist = "Lunar Tide", album = "Nocturne", artworkUrl = artwork,
                durationSec = 214, streamUrl = server.url("/rest/stream.view?id=s1").toString())
            container {
                assertEquals(request, ArtworkUrls.decode(artwork))
                assertTrue(downloadManager.downloadCollection("al-1", "album", "Nocturne", "Lunar Tide", artwork, listOf(song)))
                downloadManager.states.await { it["s1"] == DownloadState.Done }
                val collection = downloadManager.collections.await { it.isNotEmpty() }.single()
                assertArrayEquals(cover, File(downloadManager.get("s1")!!.coverPath).readBytes())
                assertArrayEquals(cover, File(collection.coverPath).readBytes())
            }
        }
    }

    @Test fun backupRestoresProfileAndHistory() = container {
        settingsStore.setLocalProfile(LocalProfile("Maren"))
        playHistory.record(Song("s1", "Midnight Bloom", "Lunar Tide", "Nocturne", "", 214), 1_000L)
        val backup = backupManager.export(1L)
        settingsStore.setLocalProfile(LocalProfile())
        playHistory.clear()
        assertTrue(backupManager.import(backup))
        assertEquals("Maren", settingsStore.localProfile.first().name)
        assertEquals(listOf("s1"), playHistory.snapshot().map { it.songId })
    }
}
