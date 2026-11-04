package com.aurora.music.ai

import com.aurora.music.data.DetailData
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.HomeData
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.SearchResults
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.data.Lyrics
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class DjEngineTest {
    private fun song(id: String, artist: String, genre: String) = Song(id, "Track $id", artist, "Album $id", "", 210, genre = genre, albumId = "al$id")

    private val library = (1..30).map { song("lib$it", "Artist ${it % 10}", if (it % 3 == 0) "Ambient" else if (it % 3 == 1) "Rock" else "Pop") }
    private val catalogue = (1..10).map { song("cat$it", "Portishead", "Trip Hop") }

    private inner class Source(val songs: List<Song> = library) : MediaBackend {
        override val session = Session("https://dj.invalid", "dj", "salt", "token", ServerType.SUBSONIC, "user")
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = songs
        override suspend fun starredSongs() = songs.take(2)
        override suspend fun starredCount() = 2
        override suspend fun starredIds() = library.take(2).map { it.id }.toSet()
        override suspend fun songFor(id: String) = (library + catalogue).firstOrNull { it.id == id }
        override suspend fun search(query: String) = SearchResults(songs = catalogue.filter { query.contains("portishead", ignoreCase = true) })
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

    private val dir = Files.createTempDirectory("aurora-dj").toFile()
    private val source = Source()
    private val engine = DjEngine(MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null })), PlayHistoryStore(dir), null, { false })

    @Test fun picksOnlyRealTracksFromTheLibraryAndSearch() = runBlocking {
        val read = engine.interpret("calm ambient like portishead, no rock")
        assertFalse(read.usedModel)
        val picked = engine.pick(read.intent, 12)
        val known = (library + catalogue).map { it.id }.toSet()
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.all { it.id in known })
        assertTrue(picked.none { it.genre == "Rock" })
        assertTrue(picked.any { it.artist == "Portishead" })
        assertTrue("Ambient" in engine.vocabulary().genres)
    }

    private val history = PlayHistoryStore(dir)
    private val auto = DjEngine(MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null })), history, null, { false })

    private class FakeQueue : DjMode.Queue {
        val items = mutableListOf<Song>()
        var index = 0
        override val current get() = items.getOrNull(index)
        var looks = 0
        override val upcoming get() = items.drop(index + 1).also { looks++ }
        override fun append(songs: List<Song>) { items += songs }
        override fun replaceUpcoming(songs: List<Song>) { while (items.size > index + 1) items.removeAt(items.size - 1); items += songs }
        override fun skipToNext() { index++ }
        override var playing = true
        override fun pause() { playing = false }
        override fun play() { playing = true }
    }

    private suspend fun DjMode.settle() = withTimeout(10_000) { do delay(20) while (state.value.busy) }

    @Test fun djDecidesFromHistoryWithoutAPrompt() = runBlocking {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        (3 downTo 1).forEach { n -> (1..6).forEach { i -> history.record(library[i], now - n * day + i * 60_000) } }
        val session = DjSession(listOf(DjSegment.REPEAT))
        val set = auto.nextSet(session, null)!!
        assertEquals(DjSegment.REPEAT, set.segment)
        assertEquals((1..6).map { library[it].id }.toSet(), set.songs.map { it.id }.toSet())
        val mix = auto.nextSet(session, null)!!
        assertEquals(DjSegment.MIX, mix.segment)
        assertTrue(mix.songs.none { it.id in set.songs.map { s -> s.id } })
    }

    @Test fun djModeStartsRefillsSwitchesAndSteers() = runBlocking {
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val queue = FakeQueue()
        var started: Boolean? = null
        dj.start(queue, { queue.items += it }, { started = it })
        withTimeout(10_000) { while (started == null) delay(20) }
        assertEquals(true, started)
        assertEquals(DjMode.BATCH, queue.items.size)
        queue.index = queue.items.size - 2
        dj.onQueueChanged(queue)
        dj.settle()
        assertEquals(DjMode.BATCH * 2, queue.items.size)
        assertEquals(queue.items.size, queue.items.map { it.id }.toSet().size)
        dj.steer("switch to ambient", queue)
        withTimeout(10_000) { while (dj.state.value.intent?.genres?.firstOrNull() != "Ambient" || dj.state.value.busy) delay(20) }
        assertEquals("Ambient", queue.upcoming.first().genre)
        val before = queue.index
        dj.switchUp(queue)
        dj.settle()
        assertEquals(before + 1, queue.index)
        queue.items.clear(); queue.index = 0; queue.items += song("other", "Someone", "Jazz")
        dj.onQueueChanged(queue)
        assertFalse(dj.state.value.active)
    }

    @Test fun sessionsDoNotRepeatEachOtherOrOpenTheSameWay() = runBlocking {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        (3 downTo 1).forEach { n -> library.forEachIndexed { i, s -> history.record(s, now - n * day + i * 60_000) } }
        val first = auto.nextSet(auto.newSession(Random(1)), null, random = Random(1))!!
        val second = auto.nextSet(auto.newSession(Random(2)), null, random = Random(2))!!
        assertTrue(first.songs.map { it.id }.intersect(second.songs.map { it.id }.toSet()).isEmpty())
        val openers = (0 until 40).map { seed ->
            val engine = DjEngine(MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null })), history, null, { false })
            engine.nextSet(engine.newSession(Random(seed)), null, random = Random(seed))!!.segment
        }.toSet()
        assertTrue("openers $openers", openers.size >= 2)
    }

    @Test fun theOldQueueReportingDuringStartDoesNotEndTheSession() = runBlocking {
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val queue = FakeQueue().apply { items += song("old", "Someone", "Jazz") }
        val waiting = mutableListOf<Song>()
        var started: Boolean? = null
        dj.start(queue, { waiting += it }, { started = it })
        withTimeout(10_000) { while (started == null) delay(20) }
        dj.onQueueChanged(queue)
        assertTrue(dj.state.value.active)
        queue.items.clear(); queue.items += waiting
        dj.onQueueChanged(queue)
        assertEquals(waiting.first(), dj.known(waiting.first().id))
        queue.items.clear(); queue.items += song("other", "Someone", "Jazz")
        dj.onQueueChanged(queue)
        assertFalse(dj.state.value.active)
    }

    @Test fun aStartThePlayerNeverTookEndsTheSession() = runBlocking {
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), startGraceMs = 50)
        var started: Boolean? = null
        dj.start(FakeQueue(), {}, { started = it })
        withTimeout(10_000) { while (started == null) delay(20) }
        withTimeout(5_000) { while (dj.state.value.active) delay(10) }
    }

    @Test fun anEmptiedQueueEndsTheSession() = runBlocking {
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val queue = FakeQueue()
        started(dj, queue)
        dj.onQueueChanged(queue)
        queue.items.clear()
        dj.onQueueChanged(queue)
        assertFalse(dj.state.value.active)
    }

    @Test fun aFailedRefillTriesAgainWithoutATrackChange() = runBlocking {
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), retryMs = 30)
        val queue = FakeQueue()
        started(dj, queue)
        var tries = 0
        while (!dj.state.value.failed && tries++ < 12) {
            queue.index = queue.items.size - 1
            dj.onQueueChanged(queue)
            dj.settle()
        }
        assertTrue(dj.state.value.failed)
        val before = queue.looks
        withTimeout(5_000) { while (queue.looks <= before) delay(10) }
        dj.stop()
    }

    @Test fun refillWaitsUntilTheLineHasPlayed() = runBlocking {
        val voice = FakeVoice()
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), voice)
        val queue = FakeQueue()
        started(dj, queue)
        queue.items.subList(3, queue.items.size).clear()
        dj.onQueueChanged(queue)
        assertFalse(dj.state.value.busy)
        queue.index = 1
        dj.onQueueChanged(queue)
        dj.settle()
        assertTrue(queue.items.size > 3)
        assertTrue(voice.moments.last() is DjMoment.NextSet)
    }

    private class FakeVoice : DjVoice {
        val moments = mutableListOf<DjMoment>()
        override fun script(moment: DjMoment): DjScript { moments += moment; return DjScript("line ${moments.size}", "AI DJ", "label") }
        override suspend fun synthesize(text: String, id: String) = DjAudio("file:///$id.wav", 3)
        override fun clear(keep: String?) {}
    }

    private class FakeEars(val heard: String) : DjEars {
        var open = false
        override fun start(): Boolean { open = true; return true }
        override suspend fun stop(): String { open = false; return heard }
    }

    @Test fun djSpeaksBeforeEachSetAndTheScreenFollows() = runBlocking {
        val voice = FakeVoice()
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), voice)
        val queue = FakeQueue()
        var started: Boolean? = null
        dj.start(queue, { queue.items += it }, { started = it })
        withTimeout(10_000) { while (started == null) delay(20) }
        assertTrue(queue.items.first().isDjSpeech())
        assertTrue(voice.moments.single() is DjMoment.Welcome)
        assertEquals(DjMode.BATCH + 1, queue.items.size)
        dj.onQueueChanged(queue)
        assertEquals("line 1", dj.state.value.speech?.text)
        queue.index = 1
        dj.onQueueChanged(queue)
        assertEquals(null, dj.state.value.speech)
        dj.switchUp(queue)
        dj.settle()
        assertTrue(queue.current!!.isDjSpeech())
        assertTrue(voice.moments.last() is DjMoment.Switch)
        assertFalse(queue.upcoming.any { it.isDjSpeech() })
    }

    private suspend fun started(dj: DjMode, queue: FakeQueue) {
        var ok: Boolean? = null
        dj.start(queue, { queue.items += it }, { ok = it })
        withTimeout(10_000) { while (ok == null) delay(20) }
    }

    @Test fun holdingPausesMusicAndAVoiceRequestSteers() = runBlocking {
        val voice = FakeVoice()
        val ears = FakeEars("play some portishead")
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), voice, ears)
        val queue = FakeQueue()
        started(dj, queue)
        assertTrue(dj.startListening(queue))
        assertTrue(ears.open && dj.state.value.listening)
        assertFalse(queue.playing)
        dj.stopListening(queue)
        dj.settle()
        assertTrue(queue.playing)
        assertTrue(voice.moments.last() is DjMoment.Steered)
        assertTrue(queue.current!!.isDjSpeech())
        assertTrue(queue.upcoming.first().artist == "Portishead")
    }

    @Test fun switchWordsSwitchAndSilenceJustResumes() = runBlocking {
        val voice = FakeVoice()
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), voice, FakeEars("Switch it up."))
        val queue = FakeQueue()
        started(dj, queue)
        dj.startListening(queue)
        dj.stopListening(queue)
        dj.settle()
        assertTrue(voice.moments.last() is DjMoment.Switch)
        val quiet = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default), FakeVoice(), FakeEars(""))
        val other = FakeQueue()
        started(quiet, other)
        val before = other.items.size
        quiet.startListening(other)
        quiet.stopListening(other)
        quiet.settle()
        assertTrue(quiet.state.value.missed)
        assertTrue(other.playing)
        assertEquals(before, other.items.size)
    }

    @Test fun djWithNothingToPlayStaysOff() = runBlocking {
        val empty = Source(emptyList())
        val none = DjEngine(MusicRepository({ empty }, DownloadManager(dir, { "file://$it" }, { null })), PlayHistoryStore(Files.createTempDirectory("aurora-dj-none").toFile()), null, { false })
        val dj = DjMode(none, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        var started: Boolean? = null
        dj.start(FakeQueue(), {}, { started = it })
        withTimeout(10_000) { while (started == null) delay(20) }
        assertEquals(false, started)
        assertFalse(dj.state.value.active)
    }

    @Test fun transcriptDropsRepeatsNoiseAndFiller() {
        assertEquals("Switch it up", cleanTranscript(" Switch it up. Switch it up."))
        assertEquals("Play some 90s hip hop", cleanTranscript(" [MUSIC] Play some 90s hip hop. "))
        assertEquals("", cleanTranscript(" You"))
        assertEquals("", cleanTranscript(" Thank you."))
        assertEquals("Something for you", cleanTranscript("Something for you"))
        assertEquals("Slow it down a bit", cleanTranscript(" Slow it down a bit. Slow"))
        assertEquals("Play some hip hop", cleanTranscript("Play some hip hop Play some hip hop Play"))
        assertEquals("hip hop and more hip hop", cleanTranscript("hip hop and more hip hop"))
    }
}
