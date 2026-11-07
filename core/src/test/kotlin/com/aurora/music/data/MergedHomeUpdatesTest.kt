package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

class MergedHomeUpdatesTest {
    private class Source(
        name: String,
        type: ServerType = ServerType.SUBSONIC,
        private val liked: List<Song> = emptyList(),
        private val onSearch: suspend () -> SearchResults = { SearchResults() },
        private val onHome: suspend () -> HomeData = { HomeData() },
    ) : MediaBackend {
        override val session = Session("https://$name.invalid", name, "salt", "token", type, "user-$name")
        var homes = 0
        var searches = 0
        var albums = 0
        var matches = 0
        override suspend fun ping() = true
        override suspend fun home(): HomeData { homes++; return onHome() }
        override suspend fun allAlbums(): List<Album> { albums++; return emptyList() }
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = emptyList<Song>()
        override suspend fun starredSongs() = liked
        override suspend fun starredCount() = liked.size
        override suspend fun starredIds() = liked.map { it.id }.toSet()
        override suspend fun songFor(id: String): Song? = null
        override suspend fun search(query: String): SearchResults { searches++; return onSearch() }
        override suspend fun matchingSongs(song: Song): List<Song> { matches++; return emptyList() }
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

    private val dir: File = Files.createTempDirectory("aurora-home").toFile()

    @After fun tearDown() { dir.deleteRecursively() }

    private fun album(id: String, title: String) = Album(id, title, "Artist $id", "", 2020, 1)
    private fun song(id: String) = Song(id, "Song $id", "Artist $id", "Album", "", 180)
    private fun repository(backend: MediaBackend) =
        MusicRepository({ backend }, DownloadManager(dir, { "file://$it" }, { null }), mapping = EmptyCoroutineContext)

    @Test fun aFastSourceShowsFirstAndTheLastUpdateIsWhatHomeReturns() = runBlocking {
        val slowAnswer = CompletableDeferred<Unit>()
        val fast = Source("fast", onHome = { HomeData(newReleases = listOf(album("a", "Fast"))) })
        val slow = Source("slow", onHome = { slowAnswer.await(); HomeData(newReleases = listOf(album("b", "Slow")), starred = listOf(song("1"))) })
        val merged = MergedBackend(listOf(fast, slow), fast.session)
        val updates = mutableListOf<HomeData>()
        merged.homeUpdates("library").collect { updates += it; slowAnswer.complete(Unit) }
        assertEquals(listOf("Fast"), updates.first().newReleases.map { it.title })
        assertEquals(merged.home(), updates.last())
        assertEquals(listOf("Fast", "Slow"), updates.last().newReleases.map { it.title })
    }

    @Test fun updatesStillEndWithAnEmptyHomeWhenEverySourceIsSkipped() = runBlocking {
        val catalogue = Source("catalogue", ServerType.YOUTUBE_MUSIC)
        val merged = MergedBackend(listOf(catalogue), catalogue.session)
        val updates = mutableListOf<HomeData>()
        merged.homeUpdates("library").collect { updates += it }
        assertEquals(listOf(HomeData()), updates)
    }

    @Test fun aSlowEmptyAnswerSkipsTheSourceInEveryView() = runBlocking {
        val catalogue = Source("catalogue", ServerType.YOUTUBE_MUSIC)
        val noHome = Source("nohome", onHome = { delay(8_100); HomeData() })
        lateinit var noSearch: Source
        noSearch = Source("nosearch", onSearch = { if (noSearch.searches == 1) delay(8_100); SearchResults() })
        val alive = Source("alive", onHome = { HomeData(newReleases = listOf(album("a", "Alive"))) })
        val merged = MergedBackend(listOf(alive, noHome, noSearch, catalogue), catalogue.session)
        coroutineScope {
            launch { merged.home() }
            launch { merged.search("query", "library") }
        }
        assertEquals(listOf("Alive"), merged.home().newReleases.map { it.title })
        merged.search("query", "library")
        assertEquals(1, noHome.homes)
        // finding nothing slowly is an answer, so search keeps asking and nothing else skips it
        assertEquals(2, noSearch.searches)

        merged.libraryView("library").allAlbums()
        assertEquals(1, alive.albums)
        assertEquals(0, noHome.albums)
        assertEquals(1, noSearch.albums)

        merged.playbackCandidates(song("1"))
        assertEquals(1, alive.matches)
        assertEquals(0, noHome.matches)
    }

    @Test fun aPartialHomeFillsOnlyWhatTheScreenLacks() {
        val shown = HomeData(newReleases = listOf(album("old", "Old")))
        val partial = HomeData(newReleases = listOf(album("new", "New")), recentlyPlayed = listOf(album("r", "Recent")))
        val filled = shown.filledFrom(partial)
        assertEquals(shown.newReleases, filled.newReleases)
        assertEquals(partial.recentlyPlayed, filled.recentlyPlayed)
    }

    @Test fun playingHomeFavouritesAsksForTheWholeListWithTheSameIds() = runBlocking {
        val all = listOf(song("1"), song("2"), song("3"))
        val source = Source("server", liked = all, onHome = { HomeData(starred = all.take(1)) })
        val single = repository(source)
        assertEquals(1, single.home().starred.size)
        assertEquals(all.map { it.id }, single.homeStarred().map { it.id })

        val merged = repository(MergedBackend(listOf(source), source.session))
        val capped = merged.homeUpdates().toList().last().starred.single()
        assertTrue(merged.homeStarred().any { it.id == capped.id })
    }

    @Test fun aFavouritesTapStartsTheWholeListAtTheSameRecording() {
        val shown = listOf(song("1"), song("2"))
        val all = listOf(song("0"), song("1"), song("9").copy(title = "Song 2", artist = "Artist 2"))
        assertEquals(all to 1, starredQueue(all, shown, shown[0]))
        // a merged list can keep another copy of the same recording
        assertEquals(all to 2, starredQueue(all, shown, shown[1]))
        assertEquals(shown to 1, starredQueue(emptyList(), shown, shown[1]))
    }

    @Test fun readsLeaveTheCallersThreadByDefault() = runBlocking {
        val caller = Thread.currentThread()
        var reader: Thread? = null
        val source = Source("server", onHome = { reader = Thread.currentThread(); HomeData() })
        MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null })).home()
        assertNotSame(caller, reader)
    }
}
