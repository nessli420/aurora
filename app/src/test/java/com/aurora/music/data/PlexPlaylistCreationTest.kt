package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlexPlaylistCreationTest {
    private open class Source(host: String) : MediaBackend {
        override val session = Session(host, "Listener", "", "token", ServerType.PLEX, "machine")
        val songs = listOf(Song("101", "Track $host", "Artist", "Album", "", 180))
        val created = mutableListOf<String>()
        val added = mutableListOf<List<String>>()
        var allowAdd = true
        var preferRadio = false
        val radioSeeds = mutableListOf<String>()

        override suspend fun createPlaylistWithId(name: String): String {
            created += name
            return "900"
        }
        override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
            assertEquals("900", playlistId)
            added += trackIds.toList()
            return allowAdd
        }
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = listOf(Playlist("900", "Playlist", "", "", 0))
        override suspend fun allSongs() = songs
        override suspend fun starredSongs() = emptyList<Song>()
        override suspend fun starredCount() = 0
        override suspend fun starredIds() = emptySet<String>()
        override suspend fun songFor(id: String) = songs.find { it.id == id }
        override suspend fun search(query: String) = SearchResults(songs = songs)
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override fun prefersServerRadio(seedId: String): Boolean {
            radioSeeds += seedId
            return preferRadio
        }
        override suspend fun createPlaylist(name: String) = true
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = true
        override suspend fun deletePlaylist(id: String) = true
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = true
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private class AtomicSource(host: String) : Source(host) {
        val atomicCalls = mutableListOf<Pair<String, List<String>>>()
        var allowCreate = true
        var failure: Exception? = null
        override suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
            failure?.let { throw it }
            atomicCalls += name to trackIds.toList()
            return if (allowCreate) "900" else null
        }
    }

    @Test fun defaultCreationRejectsBlankNamesAndDoesNotAddToEmptyPlaylists() = runBlocking {
        val source = Source("https://first.invalid")
        assertNull(source.createPlaylistWithId("  ", listOf("101")))
        assertTrue(source.created.isEmpty())
        assertEquals("900", source.createPlaylistWithId("  Empty  ", emptyList()))
        assertEquals(listOf("Empty"), source.created)
        assertTrue(source.added.isEmpty())
    }

    @Test fun defaultCreationPreservesOccurrencesAndReportsFailedItemWrites() = runBlocking {
        val source = Source("https://first.invalid")
        val tracks = listOf("101", "102", "101")
        assertEquals("900", source.createPlaylistWithId("Queue", tracks))
        assertEquals(tracks, source.added.single())
        source.allowAdd = false
        assertNull(source.createPlaylistWithId("Failed", tracks))
    }

    @Test fun mergedCreationRejectsEveryForeignTrackBeforeAnyPlaylistIsCreated() = runBlocking {
        val primary = AtomicSource("https://first.invalid")
        val secondary = AtomicSource("https://second.invalid")
        val merged = MergedBackend(listOf(primary, secondary), primary.session)
        val tracks = merged.allSongs().associateBy { it.title }
        val first = tracks.getValue(primary.songs.single().title).id
        val second = tracks.getValue(secondary.songs.single().title).id
        assertNull(merged.createPlaylistWithId("Mixed", listOf(first, second)))
        assertNull(merged.createPlaylistWithId("Foreign", listOf(second)))
        assertNull(merged.createPlaylistWithId("Unscoped", listOf("101")))
        assertNull(merged.createPlaylistWithId("Missing", listOf("unknown${MERGE_NAMESPACE_SEP}101")))
        assertTrue(primary.atomicCalls.isEmpty())
        assertTrue(secondary.atomicCalls.isEmpty())
        assertTrue(primary.created.isEmpty())
        assertTrue(secondary.created.isEmpty())
    }

    @Test fun mergedCreationUsesTheActiveSourceAtomicOperationAndPreservesOrder() = runBlocking {
        val first = AtomicSource("https://first.invalid")
        val primary = AtomicSource("https://second.invalid")
        val merged = MergedBackend(listOf(first, primary), primary.session)
        val track = merged.allSongs().single { it.title == primary.songs.single().title }.id
        val playlist = merged.createPlaylistWithId("  Repeated  ", listOf(track, track))
        assertEquals(track.substringBefore(MERGE_NAMESPACE_SEP) + MERGE_NAMESPACE_SEP + "900", playlist)
        assertEquals(listOf("Repeated" to listOf("101", "101")), primary.atomicCalls)
        assertTrue(first.atomicCalls.isEmpty())
        assertTrue(primary.created.isEmpty())
        assertTrue(primary.added.isEmpty())
        primary.allowCreate = false
        assertNull(merged.createPlaylistWithId("Rejected", listOf(track)))
        assertTrue(primary.created.isEmpty())
    }

    @Test fun reportingBackendForwardsAtomicCreationWithoutSplittingIt() = runBlocking {
        val source = AtomicSource("https://first.invalid")
        val errors = mutableListOf<String>()
        val reporting = ReportingMediaBackend(source, errors::add)
        val tracks = listOf("101", "102", "101")
        assertEquals("900", reporting.createPlaylistWithId("Queue", tracks))
        assertEquals(listOf("Queue" to tracks), source.atomicCalls)
        assertTrue(source.created.isEmpty())
        assertTrue(source.added.isEmpty())
        source.failure = IllegalStateException("Unavailable")
        assertNull(reporting.createPlaylistWithId("Failed", tracks))
        assertEquals(1, errors.size)
        source.failure = CancellationException("Cancelled")
        try {
            reporting.createPlaylistWithId("Cancelled", tracks)
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, errors.size)
        }
    }

    @Test fun mergedRadioPreferenceFollowsTheSeedProviderAcrossSourceReordering() = runBlocking {
        val local = Source("https://first.invalid")
        val server = Source("https://second.invalid").apply { preferRadio = true }
        val merged = MergedBackend(listOf(local, server), local.session)
        val tracks = merged.allSongs().associateBy { it.title }
        val localId = tracks.getValue(local.songs.single().title).id
        val serverId = tracks.getValue(server.songs.single().title).id
        assertFalse(merged.prefersServerRadio(localId))
        assertTrue(merged.prefersServerRadio(serverId))
        assertEquals(listOf("101"), server.radioSeeds)
        assertTrue(MergedBackend(listOf(server, local), local.session).prefersServerRadio(serverId))
        assertFalse(MergedBackend(listOf(local), local.session).prefersServerRadio(serverId))
        assertFalse(merged.prefersServerRadio("missing${MERGE_NAMESPACE_SEP}101"))
        assertTrue(ReportingMediaBackend(server) { fail(it) }.prefersServerRadio("101"))
    }

    @Test fun plexCreatesPopulatedPlaylistsInOneRequestWithRepeatedOccurrences() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"MediaContainer":{"Metadata":[{"ratingKey":"900","type":"playlist","playlistType":"audio"}]}}"""))
            val source = PlexBackend(PlexClient(Session(server.url("/").toString(), "Fixture", "", "token", ServerType.PLEX, "machine")), { 0 }, { it })
            assertTrue(source.prefersServerRadio("101"))
            assertEquals("900", source.createPlaylistWithId("Repeated", listOf("101", "102", "101")))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/playlists", request.requestUrl!!.encodedPath)
            assertEquals("server://machine/com.plexapp.plugins.library/library/metadata/101,102,101", request.requestUrl!!.queryParameter("uri"))
            assertEquals(1, server.requestCount)
        }
    }
}
