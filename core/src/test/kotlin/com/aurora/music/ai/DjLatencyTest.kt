package com.aurora.music.ai

import com.aurora.music.data.DetailData
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.HomeData
import com.aurora.music.data.Lyrics
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.SearchResults
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DjLatencyTest {
    private val day = 86_400_000L

    private fun song(id: String) = Song(id, "Track $id", "Artist $id", "Album $id", "", 210, genre = "Rock", albumId = "al$id")

    private val library = (1..8).map { song("lib$it") }
    private val dir = Files.createTempDirectory("aurora-dj-latency").toFile()
    private val history = PlayHistoryStore(dir)

    private inner class Source(
        val songs: List<Song> = library,
        val lookup: suspend (String) -> Song? = { null },
        val albums: suspend () -> List<Album> = { emptyList() },
    ) : MediaBackend {
        val reads = AtomicInteger()
        override val session = Session("https://dj.invalid", "dj", "salt", "token", ServerType.SUBSONIC, "user")
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = albums()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs(): List<Song> { reads.incrementAndGet(); return songs }
        override suspend fun starredSongs() = emptyList<Song>()
        override suspend fun starredCount() = 0
        override suspend fun starredIds() = emptySet<String>()
        override suspend fun songFor(id: String) = lookup(id)
        override suspend fun search(query: String) = SearchResults(songs = emptyList())
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = false
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private class FakeQueue : DjMode.Queue {
        val items = mutableListOf<Song>()
        var index = 0
        @Volatile var looks = 0
        override val current get() = items.getOrNull(index)
        override val upcoming get() = items.drop(index + 1).also { looks++ }
        override val playing = true
        override fun append(songs: List<Song>) { items += songs }
        override fun replaceUpcoming(songs: List<Song>) {}
        override fun skipToNext() {}
        override fun pause() {}
        override fun play() {}
    }

    private fun newEngine(source: MediaBackend, releaseMs: Long = 60_000) =
        DjEngine(MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null })), history, null, { false }, releaseMs = releaseMs)

    // on repeat as far as history goes, whatever the library knows
    // oldest first, the store drops an older play of the same song as a re-fire
    private fun played(songs: List<Song>) {
        val now = System.currentTimeMillis()
        songs.forEach { s -> (2 downTo 1).forEach { n -> history.record(s, now - n * day) } }
    }

    @Test fun aStalledSegmentGivesWayToTheMixWithinTheBudget() = runBlocking {
        played((1..12).map { song("gone$it") })
        val stalled = Source(lookup = { delay(60_000); null })
        val began = System.currentTimeMillis()
        val set = newEngine(stalled).nextSet(DjSession(listOf(DjSegment.REPEAT)), null, budgetMs = 100)!!
        assertEquals(DjSegment.MIX, set.segment)
        assertTrue(System.currentTimeMillis() - began < 4_000)
    }

    @Test fun withNothingKnownTheBudgetDoesNotCutASegmentShort() = runBlocking {
        val gone = (1..8).map { song("gone$it") }
        played(gone)
        val slow = Source(songs = emptyList(), lookup = { id -> delay(300); gone.firstOrNull { it.id == id } })
        val set = newEngine(slow).nextSet(DjSession(listOf(DjSegment.REPEAT)), null, budgetMs = 50)!!
        assertEquals(DjSegment.REPEAT, set.segment)
    }

    @Test fun hydrateKeepsLookupsToAFewAtATime() = runBlocking {
        val gone = (1..24).map { song("gone$it") }
        played(gone)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val source = Source(lookup = { id ->
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(20)
            running.decrementAndGet()
            gone.firstOrNull { it.id == id }
        })
        assertEquals(DjSegment.REPEAT, newEngine(source).nextSet(DjSession(listOf(DjSegment.REPEAT)), null)!!.segment)
        assertTrue("peak ${peak.get()}", peak.get() in 2..6)
    }

    @Test fun statsReuseTheSetThatJustRan() = runBlocking {
        val track = library.first()
        val now = System.currentTimeMillis()
        (3 downTo 1).forEach { history.record(track, now - it * day) }
        val engine = newEngine(Source())
        engine.nextSet(DjSession(listOf(DjSegment.MIX)), null)
        (4 downTo 1).forEach { history.record(track, now - it * 60_000L) }
        assertEquals(7, DjTaste(history.snapshot(), System.currentTimeMillis()).stats!!.topTrackPlays)
        assertEquals(3, engine.stats()!!.topTrackPlays)
    }

    @Test fun failedRefillsBackOff() = runBlocking {
        val dj = DjMode(newEngine(Source()), CoroutineScope(SupervisorJob() + Dispatchers.Default), retryMs = 30)
        val queue = FakeQueue()
        var started: Boolean? = null
        dj.start(queue, { queue.items += it }, { started = it })
        withTimeout(10_000) { while (started == null) delay(20) }
        queue.index = queue.items.size - 1
        dj.onQueueChanged(queue)
        withTimeout(10_000) { while (!dj.state.value.failed) delay(10) }
        val before = queue.looks
        delay(1_000)
        val passes = queue.looks - before
        dj.stop()
        assertTrue("passes $passes", passes in 1..9)
    }

    @Test fun decadePicksShareOneAlbumFetch() = runBlocking {
        val fetches = AtomicInteger()
        val engine = newEngine(Source(albums = { fetches.incrementAndGet(); delay(200); listOf(Album("allib1", "Album", "Artist", "", 1994, 1)) }))
        val intent = DjIntent(decades = listOf(1990))
        listOf(async { engine.pick(intent, 4) }, async { engine.pick(intent, 4) }).awaitAll()
        engine.pick(intent, 4)
        assertEquals(1, fetches.get())
    }

    @Test fun listsGoAfterTheWaitUnlessTheDjHoldsThem() = runBlocking {
        val source = Source()
        val engine = newEngine(source, releaseMs = 200)
        engine.vocabulary()
        engine.scheduleRelease()
        delay(500)
        engine.vocabulary()
        assertEquals(2, source.reads.get())
        engine.djRunning(true)
        engine.scheduleRelease()
        delay(500)
        engine.vocabulary()
        assertEquals(2, source.reads.get())
        engine.djRunning(false)
        engine.vocabulary()
        delay(500)
        engine.vocabulary()
        assertEquals(2, source.reads.get())
    }

    @Test fun aCancelledStopStillClosesTheMicAndAQuickPressWaitsForIt() = runBlocking {
        val calls = Collections.synchronizedList(ArrayList<String>())
        val entered = CountDownLatch(1)
        val mic = object : DjMic {
            override fun start(): Boolean { calls += "start"; return true }
            override fun stop(): FloatArray { entered.countDown(); Thread.sleep(100); calls += "stop"; return FloatArray(0) }
        }
        val ears = WhisperEars(mic, LocalWhisper({ null }, 1)) { "en" }
        val job = launch(Dispatchers.Default) { cancel(); ears.stop() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(ears.start())
        job.join()
        assertEquals(listOf("stop", "start"), calls.toList())
    }
}
