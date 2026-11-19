package com.aurora.music.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class StationPodcastLikesTest {
    private class Source(type: ServerType) : MediaBackend {
        override val session = Session("https://${type.name.lowercase()}.invalid", "me", "salt", "token", type, "user-$type")
        override val signedIn = true
        val starCalls = mutableListOf<String>()
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = emptyList<Song>()
        override suspend fun starredSongs() = emptyList<Song>()
        override suspend fun starredCount() = 0
        override suspend fun starredIds() = emptySet<String>()
        override suspend fun songFor(id: String): Song? = null
        override suspend fun search(query: String) = SearchResults()
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean { starCalls += id; return false }
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private val dir: File = Files.createTempDirectory("aurora-station-likes").toFile()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @After fun tearDown() {
        scope.cancel(); io.cancel(); executor.shutdownNow(); dir.deleteRecursively()
    }

    private fun settings(name: String) = SettingsStore(PreferenceDataStoreFactory.create(scope = io) { File(dir, "$name.preferences_pb") }, dir, dir)

    private val station = RadioStation(uuid = "st-1", name = "Jazz FM", streamUrl = "https://jazz.invalid/live", tags = "jazz")
    private val episode = PodcastEpisode(id = "ep-1", title = "Pilot", audioUrl = "https://show.invalid/1.mp3", podcastTitle = "The Show")
        .toSong(feedUrl = "https://show.invalid/feed.xml")

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }

    // the storage doesn't depend on the sources: with local, a merge holding it, or a streaming account alone
    @Test fun heartsKeepStationsAndShowsWhateverTheSources() = runBlocking {
        val cases = listOf(
            "local" to listOf(Source(ServerType.LOCAL)),
            "merge with local" to listOf(Source(ServerType.LOCAL), Source(ServerType.SPOTIFY)),
            "spotify only" to listOf(Source(ServerType.SPOTIFY)),
            "merge without local" to listOf(Source(ServerType.SOUNDCLOUD), Source(ServerType.YOUTUBE_MUSIC)),
        )
        for ((name, sources) in cases) {
            val store = settings(name.replace(' ', '-'))
            val backend: MediaBackend = sources.singleOrNull() ?: MergedBackend(sources, sources.first().session)
            val repository = MusicRepository({ backend }, DownloadManager(File(dir, name).apply { mkdirs() }, { "file://$it" }, { null }))
            val likes = Likes(scope, repository, appItems = store) { _, _ -> }
            val radio = station.toSong()
            assertTrue(name, repository.canLikeSong(radio.id) && repository.canLikeSong(episode.id))

            likes.check(listOf(radio, episode))
            likes.toggle(radio)
            likes.toggle(episode)
            until { radio.id in likes.likedIds.value && episode.id in likes.likedIds.value }
            assertEquals(name, listOf(station), store.radioFavorites.first())
            assertEquals(name, listOf("https://show.invalid/feed.xml"), store.podcastSubs.first().map { it.feedUrl })
            assertEquals(name, "The Show", store.podcastSubs.first().single().title)
            assertTrue(name, sources.all { it.starCalls.isEmpty() })

            likes.toggle(radio)
            likes.toggle(episode)
            until { radio.id !in likes.likedIds.value && episode.id !in likes.likedIds.value }
            assertTrue(name, store.radioFavorites.first().isEmpty() && store.podcastSubs.first().isEmpty())
        }
    }

    // the local library's old station likes join the favourites once, and only after the station is found
    @Test fun oldLocalStationLikesMoveWithoutDuplicates() = runBlocking {
        val store = settings("move")
        store.saveRadioStation(station)
        val local = LocalStore(dir)
        listOf("radio:st-1", "radio:st-2", "radio:st-gone", "radio:custom:9", "podcast:ep-1", "song-1").forEach { local.setLiked(it, true) }
        val asked = mutableListOf<List<String>>()
        val lookup: suspend (List<String>) -> List<RadioStation>? = { uuids ->
            asked += uuids
            listOf(RadioStation(uuid = "st-2", name = "Rock FM", streamUrl = "https://rock.invalid"), station, RadioStation(uuid = "st-2", name = "Rock FM"))
        }

        moveStationLikes(local, store, lookup)
        moveStationLikes(local, store, lookup)

        assertEquals(listOf("st-1", "st-2"), store.radioFavorites.first().map { it.uuid })
        assertEquals("Jazz FM", store.radioFavorites.first().first().name)
        assertEquals(setOf("radio:st-gone", "radio:custom:9", "podcast:ep-1", "song-1"), local.likedIds())
        assertEquals(listOf(listOf("st-2", "st-gone"), listOf("st-gone")), asked)

        moveStationLikes(LocalStore(File(dir, "offline").apply { mkdirs() }).apply { setLiked("radio:st-3", true) }, store) { null }
        assertEquals(2, store.radioFavorites.first().size)
    }

    @Test fun backupCarriesStationsAndShows() = runBlocking {
        val from = settings("from")
        from.saveRadioStation(station)
        from.savePodcast(Podcast(feedUrl = "https://show.invalid/feed.xml", title = "The Show"))
        val to = settings("to")
        to.importPrefs(from.exportPrefs())
        assertEquals(listOf(station), to.radioFavorites.first())
        assertEquals("The Show", to.podcastSubs.first().single().title)
    }
}
