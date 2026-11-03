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

class DjEngineTest {
    private fun song(id: String, artist: String, genre: String) = Song(id, "Track $id", artist, "Album $id", "", 210, genre = genre, albumId = "al$id")

    private val library = (1..30).map { song("lib$it", "Artist ${it % 10}", if (it % 3 == 0) "Ambient" else if (it % 3 == 1) "Rock" else "Pop") }
    private val catalogue = (1..10).map { song("cat$it", "Portishead", "Trip Hop") }

    private inner class Source : MediaBackend {
        override val session = Session("https://dj.invalid", "dj", "salt", "token", ServerType.SUBSONIC, "user")
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = library
        override suspend fun starredSongs() = library.take(2)
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

    @Test fun djModeRefillsAndSteers() = runBlocking {
        val dj = DjMode(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val queue = object : DjMode.Queue {
            val items = mutableListOf<Song>()
            var index = 0
            override val current get() = items.getOrNull(index)
            override val upcoming get() = items.drop(index + 1)
            override fun append(songs: List<Song>) { items += songs }
            override fun replaceUpcoming(songs: List<Song>) { while (items.size > index + 1) items.removeAt(items.size - 1); items += songs }
        }
        val start = engine.pick(DjIntent(genres = listOf("pop")), 3)
        queue.items += start
        dj.start("pop", DjIntent(genres = listOf("pop")), false, start)
        dj.onQueueChanged(queue)
        withTimeout(10_000) { while (queue.items.size == 3 || dj.state.value.busy) delay(20) }
        assertEquals(3 + DjMode.BATCH, queue.items.size)
        assertEquals(queue.items.size, queue.items.map { it.id }.toSet().size)
        dj.steer("switch to ambient", queue)
        withTimeout(10_000) { while (dj.state.value.intent?.genres?.firstOrNull() != "Ambient" || dj.state.value.busy) delay(20) }
        assertTrue(queue.upcoming.take(4).all { it.genre == "Ambient" })
        queue.items.clear(); queue.items += song("other", "Someone", "Jazz")
        dj.onQueueChanged(queue)
        assertFalse(dj.state.value.active)
    }
}
