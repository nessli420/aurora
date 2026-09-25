package com.aurora.music.playback

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.DownloadState
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlexBackend
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@UnstableApi
class PlexPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun plexOriginalUrlAuthenticatesDecodesAndSeeksOnDevice() = runBlocking {
        val token = "fixture-token+/=&?#"
        val audio = wav(seconds = 8)
        val audioRequests = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    if (url.encodedPath == "/library/parts/101/fixture.wav") {
                        audioRequests += request
                        if (url.queryParameter("X-Plex-Token") != token) return MockResponse().setResponseCode(401)
                        val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        if (start !in audio.indices) return MockResponse().setResponseCode(416)
                        return MockResponse().setHeader("Content-Type", "audio/wav")
                            .setHeader("Accept-Ranges", "bytes")
                            .setResponseCode(if (start > 0) 206 else 200)
                            .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${audio.lastIndex}/${audio.size}") }
                            .setBody(Buffer().write(audio, start, audio.size - start))
                    }
                    if (request.getHeader("X-Plex-Token") != token) return MockResponse().setResponseCode(401)
                    val body = when (url.encodedPath) {
                        "/", "/identity" -> """{"MediaContainer":{"friendlyName":"Playback fixture","machineIdentifier":"plex-playback-fixture"}}"""
                        "/library/sections" -> """{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}"""
                        "/library/metadata/101" -> """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"101","type":"track","title":"Plex playback fixture","duration":8000,"parentRatingKey":"20","parentTitle":"Fixture album","grandparentRatingKey":"10","grandparentTitle":"Fixture artist","Media":[{"container":"wav","audioCodec":"pcm","Part":[{"key":"/library/parts/101/fixture.wav","Stream":[{"streamType":2,"codec":"pcm","samplingRate":44100,"bitDepth":16,"channels":2}]}]}]}]}}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            server.start()
            val session = PlexClient.authenticate(server.url("/").toString(), token)
            val backend = PlexBackend(PlexClient(session), { 0 }, { it })
            val song = requireNotNull(backend.songFor("101"))
            assertEquals("Plex playback fixture", song.title)
            assertEquals(8, song.durationSec)
            val player = main { ExoPlayer.Builder(instrumentation.targetContext).build().apply { volume = 0f } }
            try {
                main {
                    player.setMediaItem(MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl).build())
                    player.prepare()
                    player.play()
                }
                await("Plex original decodes and advances", player) {
                    player.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition >= 250
                }
                main {
                    assertEquals(8000L, player.duration)
                    assertEquals(44100, player.audioFormat?.sampleRate)
                    assertEquals(2, player.audioFormat?.channelCount)
                    assertNull(player.playerError)
                    player.pause()
                }
                val paused = main { player.currentPosition }
                SystemClock.sleep(180)
                assertTrue(kotlin.math.abs(main { player.currentPosition } - paused) < 80)
                main { player.seekTo(4000); player.play() }
                await("Plex original resumes after seek", player) { player.isPlaying && player.currentPosition in 4300..7000 }
                assertTrue(audioRequests.isNotEmpty())
                assertTrue(audioRequests.all { it.requestUrl!!.queryParameter("X-Plex-Token") == token })
                main { player.seekTo(7600) }
                await("Plex original reaches end", player) { player.playbackState == Player.STATE_ENDED }
            } finally {
                main { player.release() }
            }
        }
    }

    @Test fun freshBackendDownloadsOriginalAndKeepsMergedAccountCopiesSeparate() = runBlocking {
        val token = "download-fixture-token"
        val audio = wav(seconds = 1)
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "plex-download-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        try {
            MockWebServer().use { server ->
                val requests = CopyOnWriteArrayList<RecordedRequest>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests += request
                        return when (request.requestUrl!!.encodedPath) {
                            "/library/metadata/101" -> {
                                if (request.getHeader("X-Plex-Token") != token) MockResponse().setResponseCode(401)
                                else MockResponse().setHeader("Content-Type", "application/json")
                                    .setBody("""{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"101","type":"track","title":"Download fixture","duration":1000,"Media":[{"container":"wav","Part":[{"key":"/library/parts/101/fixture.wav"}]}]}]}}""")
                            }
                            "/library/parts/101/fixture.wav" -> {
                                if (request.requestUrl!!.queryParameter("X-Plex-Token") != token) MockResponse().setResponseCode(401)
                                else MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio))
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()
                val session = Session(server.url("/").toString().trimEnd('/'), "Download fixture", "", token, ServerType.PLEX, "fixture")
                val browsing = PlexBackend(PlexClient(session), { 0 }, { it })
                val selected = requireNotNull(browsing.songFor("101")).copy(artworkUrl = "")
                val fresh = PlexBackend(PlexClient(session), { 0 }, { it })
                assertTrue(fresh.streamUrl("101", 0, true).isBlank())
                val manager = DownloadManager(isolated,
                    currentServerIdProvider = { session.server },
                    downloadUrlResolverProvider = { { _, bitrate, lossless -> fresh.downloadUrl("101", bitrate, lossless) } })
                val firstIdentity = PlaybackSourceIdentity.fromSession(session, "")
                val otherIdentity = PlaybackSourceIdentity.fromSession(session.copy(token = "another-account-token"), "")
                fun mergedId(identity: PlaybackSourceIdentity) = "s${identity.providerId!!.removePrefix("provider:")}\u0001101"
                val first = selected.copy(id = mergedId(firstIdentity), playbackSource = firstIdentity, streamUrl = "http://127.0.0.1:1/stale")
                val second = selected.copy(id = mergedId(otherIdentity), playbackSource = otherIdentity, streamUrl = "http://127.0.0.1:1/stale")
                fun downloadAndAwait(song: Song) {
                    manager.downloadSong(song)
                    val end = SystemClock.elapsedRealtime() + 15000
                    while (manager.states.value[song.id] != DownloadState.Done && SystemClock.elapsedRealtime() < end) {
                        assertNotEquals("Fixture download failed", DownloadState.Failed, manager.states.value[song.id])
                        SystemClock.sleep(50)
                    }
                    assertEquals(DownloadState.Done, manager.states.value[song.id])
                    assertArrayEquals(audio, File(requireNotNull(manager.get(song.id)).audioPath).readBytes())
                }
                listOf(first, second).forEach(::downloadAndAwait)
                assertEquals(2, requests.count { it.requestUrl!!.encodedPath == "/library/metadata/101" })
                assertEquals(first.id, manager.getByOriginalId("101", firstIdentity.providerId)?.id)
                assertEquals(second.id, manager.getByOriginalId("101", otherIdentity.providerId)?.id)
                assertNull(manager.getByOriginalId("101", "missing-provider"))
                val reloaded = DownloadManager(isolated)
                assertEquals(first.id, reloaded.getByOriginalId("101", firstIdentity.providerId)?.id)
                assertEquals(second.id, reloaded.getByOriginalId("101", otherIdentity.providerId)?.id)

                downloadAndAwait(first.copy(id = "101"))
                val sameAccount = MusicRepository({ fresh }, manager)
                assertTrue(requireNotNull(sameAccount.songFor("101")).streamUrl.startsWith("file:"))
                val otherSession = session.copy(token = "another-account-token")
                val otherStream = server.url("/source-b/101.wav").toString()
                var lookups = 0
                val otherBackend = object : MediaBackend by fresh {
                    override val session = otherSession
                    override fun playbackSourceIdentity(song: Song) = otherIdentity
                    override suspend fun songFor(id: String): Song {
                        assertEquals("101", id)
                        lookups++
                        return selected.copy(title = "Second account recording", streamUrl = otherStream, playbackSource = otherIdentity)
                    }
                }
                val otherAccount = MusicRepository({ otherBackend }, manager)
                val resolved = requireNotNull(otherAccount.songFor("101"))
                assertEquals(1, lookups)
                assertEquals("Second account recording", resolved.title)
                assertEquals(otherStream, resolved.streamUrl)
                assertEquals(otherIdentity.providerId, resolved.playbackSource?.providerId)

                val originalDownload = requireNotNull(manager.get("101"))
                val originalFile = File(originalDownload.audioPath)
                assertTrue(sameAccount.isDownloaded("101"))
                assertTrue("101" in sameAccount.downloadedIds())
                assertFalse(otherAccount.isDownloaded("101"))
                assertFalse("101" in otherAccount.downloadedIds())
                assertFalse(otherAccount.removeDownload("101"))
                assertTrue(originalFile.isFile)
                val foreignTrack = selected.copy(playbackSource = otherIdentity, artworkUrl = "")
                assertFalse(manager.downloadSong(foreignTrack))
                assertEquals(DownloadState.Failed, manager.states.value["101"])
                assertEquals(originalDownload, manager.get("101"))
                assertArrayEquals(audio, originalFile.readBytes())

                assertTrue(manager.downloadCollection("20", "album", "Original album", "", "", listOf(selected)))
                val collectionDeadline = SystemClock.elapsedRealtime() + 5000
                while (manager.collections.value.none { it.id == "20" } && SystemClock.elapsedRealtime() < collectionDeadline) SystemClock.sleep(25)
                val originalCollection = manager.collections.value.single { it.id == "20" }
                assertFalse(manager.downloadCollection("20", "album", "Foreign album", "", "", listOf(foreignTrack)))
                assertFalse(manager.downloadCollection("30", "album", "Conflicting tracks", "", "", listOf(foreignTrack)))
                assertEquals(originalCollection, manager.collections.value.single { it.id == "20" })
                assertFalse(manager.collections.value.any { it.id == "30" })
                assertFalse(otherAccount.removeDownloadedCollection("20", "album"))
                assertArrayEquals(audio, originalFile.readBytes())

                val guardedContext = object : ContextWrapper(isolated) {
                    override fun getFilesDir() = File(directory, "guarded").apply { mkdirs() }
                    override fun getApplicationContext(): Context = this
                }
                val guarded = DownloadManager(guardedContext,
                    currentServerIdProvider = { session.server },
                    playbackSourceProvider = { otherIdentity },
                    copyCached = { _, file, progress -> file.writeBytes(audio); progress(1f) },
                    copyExtension = { _, file, progress -> file.writeBytes(audio); progress(1f) })
                assertFalse(guarded.downloadSong(selected.copy(id = "202")))
                assertEquals(DownloadState.Failed, guarded.states.value["202"])
                for ((id, uri) in listOf("203" to "aurora-cache://fixture", "204" to "aurora-extension://fixture")) {
                    assertTrue(guarded.downloadSong(selected.copy(id = id, streamUrl = uri)))
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (guarded.states.value[id] != DownloadState.Done && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
                    assertEquals(DownloadState.Done, guarded.states.value[id])
                    assertEquals(firstIdentity.providerId, guarded.get(id)?.playbackSource?.providerId)
                }

                val offline = MusicRepository({ otherBackend }, manager, offlineProvider = { true })
                assertTrue("101" in offline.downloadedIds())
                assertTrue(offline.removeDownload("101"))
                assertFalse(originalFile.exists())
                assertTrue(offline.removeDownloadedCollection("20", "album"))
                assertNotNull(manager.get(first.id))
                assertNotNull(manager.get(second.id))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun await(description: String, player: ExoPlayer, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            main { player.playerError?.let { throw AssertionError(description, it) } }
            if (main(condition)) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $description; ${main { "state=${player.playbackState}, position=${player.currentPosition}" }}")
    }

    private fun wav(seconds: Int): ByteArray {
        val rate = 44100
        val samples = seconds * rate
        val dataSize = samples * 4
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
            repeat(samples) { index ->
                val value = (sin(2 * PI * 440 * index / rate) * 2000).toInt().toShort()
                putShort(value); putShort(value)
            }
        }.array()
    }
}
