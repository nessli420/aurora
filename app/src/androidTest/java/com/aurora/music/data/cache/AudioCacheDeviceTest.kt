package com.aurora.music.data.cache

import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import com.aurora.music.playback.PresetContextPublisher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioCacheDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun directory() = File(context.cacheDir, "cache-test-${UUID.randomUUID()}").apply { mkdirs() }

    private fun item(uri: String = "https://audio.invalid/track.wav", provider: String = "provider:" + "a".repeat(64)): MediaItem {
        val song = Song("same-id", "Cache fixture", "Artist", "Album", "", 2, streamUrl = uri, albumId = "album",
            playbackSource = PlaybackSourceIdentity(provider, "Fixture", RuleSource.STREAM, "album:" + "b".repeat(64)))
        return MediaItem.Builder().setMediaId(song.id).setUri(uri).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).setExtras(PresetContextPublisher.extras(song)).build()).build()
    }
    private fun read(factory: DataSource.Factory, item: MediaItem, limit: Int = Int.MAX_VALUE): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(item.localConfiguration!!.uri).setKey(item.localConfiguration!!.customCacheKey).build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() < limit) {
                val count = source.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count == -1) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally { source.close() }
    }
    private suspend fun ready(cache: AudioCache) = withTimeout(5000) {
        assertFalse(cache.status.first { it.ready || it.failed }.failed)
    }

    @Test fun completeAudioReplaysWithoutUpstreamAndSurvivesRecreation() = runBlocking {
        val root = directory()
        var cache = AudioCache(context, null, directory = root)
        val audio = ByteArray(32 * 1024) { (it % 127).toByte() }
        val requests = AtomicInteger()
        val upstream = DataSource.Factory {
            val data = ByteArrayDataSource(audio)
            object : DataSource by data {
                override fun open(dataSpec: DataSpec): Long { requests.incrementAndGet(); return data.open(dataSpec) }
            }
        }
        try {
            ready(cache)
            val prepared = cache.prepare(item())
            assertArrayEquals(audio, read(cache.factory(upstream), prepared))
            cache.refresh()
            assertEquals(1, cache.status.value.tracks)
            assertEquals(audio.size.toLong(), cache.status.value.bytes)
            assertArrayEquals(audio, read(cache.factory(upstream), prepared))
            assertEquals("Second playback must reuse cached bytes", 1, requests.get())
            cache.release()
            cache = AudioCache(context, null, directory = root)
            ready(cache)
            val offline = cache.prepare(item(cache.songs.value.single().streamUrl))
            assertArrayEquals(audio, read(cache.factory(DataSource.Factory { error("Offline playback requested the network") }), offline))
        } finally { cache.release(); root.deleteRecursively() }
    }

    @Test fun partialDisabledAndClearedDataAreNotAdvertisedAsOffline() = runBlocking {
        val root = directory()
        val cache = AudioCache(context, null, directory = root)
        val audio = ByteArray(16 * 1024) { 42 }
        val upstream = DataSource.Factory { ByteArrayDataSource(audio) }
        try {
            ready(cache)
            val prepared = cache.prepare(item())
            read(cache.factory(upstream), prepared, 1024)
            cache.refresh()
            assertTrue(cache.status.value.bytes > 0)
            assertEquals(0, cache.status.value.tracks)
            cache.clear()
            cache.configure(AudioCachePrefs(enabled = false))
            read(cache.factory(upstream), prepared)
            cache.refresh()
            assertEquals(0L, cache.status.value.bytes)
            cache.configure(AudioCachePrefs(enabled = true))
            read(cache.factory(upstream), prepared)
            cache.refresh()
            assertEquals(1, cache.songs.value.size)
            cache.clear()
            assertEquals(0L, cache.status.value.bytes)
            assertTrue(cache.songs.value.isEmpty())
        } finally { cache.release(); root.deleteRecursively() }
    }

    @Test fun clearDuringAnOpenStreamDoesNotInterruptReadingOrRecommitOldData() = runBlocking {
        val root = directory()
        val cache = AudioCache(context, null, directory = root)
        val audio = ByteArray(8192) { 63 }
        val source = cache.factory(DataSource.Factory { ByteArrayDataSource(audio) }).createDataSource()
        try {
            ready(cache)
            val prepared = cache.prepare(item())
            source.open(DataSpec.Builder().setUri(prepared.localConfiguration!!.uri).setKey(prepared.localConfiguration!!.customCacheKey).build())
            val buffer = ByteArray(1024)
            assertEquals(1024, source.read(buffer, 0, buffer.size))
            cache.clear()
            var remaining = 0
            while (true) { val read = source.read(buffer, 0, buffer.size); if (read < 0) break; remaining += read }
            assertEquals(audio.size - 1024, remaining)
            source.close()
            cache.refresh()
            assertEquals(0L, cache.status.value.bytes)
            assertTrue(cache.songs.value.isEmpty())
        } finally { source.close(); cache.release(); root.deleteRecursively() }
    }

    @Test fun quotaEvictsLeastRecentlyUsedAndShrinksImmediately() {
        val root = directory()
        val evictor = ResizableCacheEvictor(8192) {}
        val cache = SimpleCache(root, evictor, StandaloneDatabaseProvider(context))
        try {
            fun fill(key: String) {
                val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(
                    DataSource.Factory { ByteArrayDataSource(ByteArray(4096)) })
                read(factory, MediaItem.Builder().setUri("https://fixture/$key").setCustomCacheKey(key).build())
            }
            fill("a"); Thread.sleep(2); fill("b")
            Thread.sleep(2)
            cache.startReadWriteNonBlocking("a", 0, 1)
            Thread.sleep(2); fill("c")
            assertTrue(cache.isCached("a", 0, 4096))
            assertFalse(cache.isCached("b", 0, 4096))
            assertTrue(cache.cacheSpace <= 8192)
            evictor.resize(cache, 4096)
            assertTrue(cache.cacheSpace <= 4096)
        } finally { cache.release(); root.deleteRecursively() }
    }

    @Test fun offlineCatalogueUsesUniqueIdsAndClearPreservesDownloads() = runBlocking {
        val root = directory()
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(root, "files").apply { mkdirs() } }
        val downloadDir = File(isolated.filesDir, "downloads").apply { mkdirs() }
        val saved = File(downloadDir, "manual.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cache = AudioCache(context, null, directory = File(root, "streams"))
        val downloads = DownloadManager(isolated, { _, _, _ -> error("Saving cached audio must not request a URL") }, copyCached = cache::copyTo)
        try {
            ready(cache)
            val factory = cache.factory(DataSource.Factory { ByteArrayDataSource(ByteArray(4096)) })
            read(factory, cache.prepare(item(provider = "provider:" + "a".repeat(64))))
            read(factory, cache.prepare(item(provider = "provider:" + "c".repeat(64))))
            cache.refresh()
            val repository = MusicRepository({ null }, downloads, offlineProvider = { true }, cachedSongsProvider = { cache.songs.value })
            val songs = repository.allSongs()
            assertEquals(2, songs.size)
            assertEquals(2, songs.map { it.id }.distinct().size)
            assertTrue(songs.all { repository.songFor(it.id) != null })
            assertEquals(2, repository.search("fixture").songs.size)
            downloads.downloadSong(songs.first())
            withTimeout(5000) { downloads.states.first { it[songs.first().id] == com.aurora.music.data.DownloadState.Done } }
            assertEquals("Saving a cache entry must not duplicate the offline row", 2, repository.allSongs().size)
            val promoted = File(downloads.get(songs.first().id)!!.audioPath)
            cache.clear()
            assertEquals(1, repository.allSongs().size)
            assertEquals(4096L, promoted.length())
            assertArrayEquals(byteArrayOf(1, 2, 3), saved.readBytes())
        } finally { cache.release(); root.deleteRecursively() }
    }

    @Test fun exoPlayerDecodesCachedAudioAfterTheHttpServerStops() = runBlocking {
        val root = directory()
        val cache = AudioCache(context, null, directory = root)
        val server = MockWebServer()
        val data = wav()
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(data)))
        server.start()
        try {
            ready(cache)
            val factory = cache.factory(DefaultDataSource.Factory(context))
            val original = cache.prepare(item(server.url("/audio.wav").toString()))
            play(factory, original)
            cache.refresh()
            assertEquals(1, cache.status.value.tracks)
            assertEquals(1, server.requestCount)
            server.shutdown()
            play(factory, cache.prepare(item(cache.songs.value.single().streamUrl)))
        } finally { runCatching { server.shutdown() }; cache.release(); root.deleteRecursively() }
    }

    private fun play(factory: DataSource.Factory, item: MediaItem) {
        val ended = CountDownLatch(1)
        var player: ExoPlayer? = null
        var failure: PlaybackException? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().also {
                it.volume = 0f
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                    override fun onPlayerError(error: PlaybackException) { failure = error; ended.countDown() }
                })
                it.setMediaItem(item); it.prepare(); it.play()
            }
        }
        try { assertTrue("Player did not finish", ended.await(10, TimeUnit.SECONDS)); assertNull(failure) }
        finally { instrumentation.runOnMainSync { player?.release() } }
    }

    private fun wav(): ByteArray {
        val samples = 8000 * 2
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort((kotlin.math.sin(it * 2.0 * Math.PI * 440 / 8000) * 1000).toInt().toShort()) }
        return buffer.array()
    }
}
