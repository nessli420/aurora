package com.aurora.music.desktop.audio

import com.aurora.music.data.cache.AudioCachePrefs
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.TestAssets
import com.aurora.music.desktop.audio.decode.decodeAll
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val files = mutableMapOf<String, ByteArray>()
    private val http = OkHttpClient()
    private var offline = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bytes = files[request.path!!.substringBefore('?')] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    ?: return MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "audio/mpeg")
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
                return MockResponse().setResponseCode(206).setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setHeader("Content-Type", "audio/mpeg")
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun cache(limitBytes: Long = 1L shl 30) = AudioCache(temp.newFolder(), http, null, offline = { offline }, limitOf = { limitBytes }).also {
        runBlocking { it.configure(AudioCachePrefs(enabled = true)) }
    }

    private fun song(id: String, path: String, duration: Int = 30) =
        Song(id, "Song $id", "Artist", "Album", "", duration, streamUrl = server.url(path).toString())

    private fun upstream(song: Song) = { ResolvedStream(song.streamUrl, mapOf("X-Test" to "1")) }

    private fun fetch(url: String, range: String? = null): ByteArray =
        http.newCall(Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()).execute().use { it.body!!.bytes() }

    private fun awaitCached(cache: AudioCache, count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (cache.songs.value.size < count && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(count, cache.songs.value.size)
    }

    @Test fun streamsThroughTheCacheAndPlaysFromDiskAfterwards() {
        val audio = TestAssets.file("gapless.mp3").readBytes()
        files["/song.mp3"] = audio
        val cache = cache()
        val song = song("a1", "/song.mp3")
        val first = cache.resolve(song, upstream(song))
        assertTrue(first.url.startsWith("http://127.0.0.1:"))
        val streamed = FfmpegDecoder.open(first.url).use { decodeAll(it) }
        val original = FfmpegDecoder.open(TestAssets.file("gapless.mp3").path).use { decodeAll(it) }
        assertArrayEquals(original, streamed, 0.0)
        awaitCached(cache, 1)
        assertEquals("1", server.takeRequest().getHeader("X-Test"))

        val second = cache.resolve(song) { error("should not reach the network") }
        assertTrue(second.url.startsWith("file:"))
        assertArrayEquals(audio, File(java.net.URI(second.url)).readBytes())
        val cached = cache.songs.value.single()
        assertTrue(cached.streamUrl.startsWith("aurora-cache://"))
        assertEquals("Song a1", cached.title)
        assertTrue(cache.resolve(cached) { error("cached songs resolve locally") }.url.startsWith("file:"))
        assertEquals(audio.size.toLong(), cache.status.value.bytes)

        val copy = temp.newFile("promoted.mp3")
        cache.copyTo(cached.streamUrl, copy) {}
        assertArrayEquals(audio, copy.readBytes())
        cache.close()
    }

    // the player still gets audio from the source after the background download gives up
    @Test fun aFailedDownloadFallsBackToTheSource() {
        val audio = TestAssets.file("gapless.mp3").readBytes()
        files["/flaky.mp3"] = audio
        var failures = 1
        val flaky = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (failures-- > 0) MockResponse().setResponseCode(503) else flaky.dispatch(request)
        }
        val cache = cache()
        val song = song("f1", "/flaky.mp3")
        val local = cache.resolve(song, upstream(song))
        Thread.sleep(500)
        val streamed = FfmpegDecoder.open(local.url).use { decodeAll(it) }
        val original = FfmpegDecoder.open(TestAssets.file("gapless.mp3").path).use { decodeAll(it) }
        assertArrayEquals(original, streamed, 0.0)
        assertTrue(cache.songs.value.isEmpty())
        cache.close()
    }

    @Test fun servesRangesWhileDownloading() {
        val bytes = ByteArray(9 * 1024 * 1024) { (it * 31 % 251).toByte() }
        files["/big.bin"] = bytes
        val cache = cache()
        val song = song("big", "/big.bin")
        val url = cache.resolve(song, upstream(song)).url
        assertArrayEquals(bytes.copyOfRange(100, 200), fetch(url, "bytes=100-199"))
        assertArrayEquals(bytes.copyOfRange(bytes.size - 50, bytes.size), fetch(url, "bytes=${bytes.size - 50}-"))
        assertArrayEquals(bytes, fetch(url))
        awaitCached(cache, 1)
        cache.close()
    }

    @Test fun evictsTheLeastRecentlyPlayedOverTheLimit() {
        val cache = cache(limitBytes = 1024 * 1024)
        listOf("one", "two", "three").forEachIndexed { i, id ->
            files["/$id.bin"] = ByteArray(400 * 1024) { (it + i).toByte() }
            val song = song(id, "/$id.bin")
            fetch(cache.resolve(song, upstream(song)).url)
            awaitCached(cache, minOf(i + 1, 2))
            Thread.sleep(20)
        }
        assertEquals(listOf("Song three", "Song two"), cache.songs.value.map { it.title }.sorted())
        assertTrue(cache.status.value.bytes <= 1024 * 1024)
        cache.close()
    }

    @Test fun leavesRadioDisabledAndStreamingFormatsAlone() {
        files["/s.mp3"] = ByteArray(1000)
        val cache = cache()
        val radio = Song("radio:1", "Radio", "", "", "", 0, streamUrl = server.url("/s.mp3").toString())
        assertEquals(radio.streamUrl, cache.resolve(radio, upstream(radio)).url)
        val hls = song("h", "/list.m3u8")
        assertEquals(hls.streamUrl, cache.resolve(hls, upstream(hls)).url)
        val local = Song("l", "Local", "", "", "", 30, streamUrl = "file:///C:/music/a.flac")
        assertEquals(local.streamUrl, cache.resolve(local) { ResolvedStream(local.streamUrl) }.url)
        runBlocking { cache.configure(AudioCachePrefs(enabled = false)) }
        val plain = song("p", "/s.mp3")
        assertEquals(plain.streamUrl, cache.resolve(plain, upstream(plain)).url)
        offline = true
        runBlocking { cache.configure(AudioCachePrefs(enabled = true)) }
        assertEquals(plain.streamUrl, cache.resolve(plain, upstream(plain)).url)
        cache.close()
    }

    // a youtube match is usually corrected while the wrong song is still streaming in: that audio must not finish into
    // the cache and come back on the next play
    @Test fun forgettingASongMidStreamKeepsItsOldAudioOut() {
        files["/wrong.bin"] = ByteArray(2 * 1024 * 1024) { (it % 249).toByte() }
        val slow = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                slow.dispatch(request).throttleBody(64 * 1024, 40, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val cache = cache()
        val song = song("m1", "/wrong.bin")
        val url = cache.resolve(song, upstream(song)).url
        val player = Thread { runCatching { fetch(url) } }.apply { start() }
        Thread.sleep(300)
        runBlocking { cache.forget(song.id).join() }
        player.join(15_000)
        Thread.sleep(300)
        assertTrue(cache.songs.value.isEmpty())
        var upstreamAgain = false
        cache.resolve(song) { upstreamAgain = true; ResolvedStream(song.streamUrl) }
        assertTrue("the corrected song is looked up again", upstreamAgain)
        cache.close()
    }

    @Test fun clearingRemovesEverything() {
        files["/c.bin"] = ByteArray(5000) { it.toByte() }
        val cache = cache()
        val song = song("c", "/c.bin")
        fetch(cache.resolve(song, upstream(song)).url)
        awaitCached(cache, 1)
        runBlocking { cache.clear() }
        assertEquals(0, cache.songs.value.size)
        assertEquals(0L, cache.status.value.bytes)
        assertFalse(cache.resolve(song, upstream(song)).url.startsWith("file:"))
        cache.close()
    }
}
