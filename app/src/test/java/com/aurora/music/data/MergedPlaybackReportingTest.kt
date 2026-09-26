package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.data.rules.RuleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MergedPlaybackReportingTest {
    private class Source(override val session: Session, title: String = session.username) : MediaBackend {
        val song = Song(if (session.type == ServerType.YOUTUBE_MUSIC) "video-id" else "101", title, "Artist", "Album", "", 180,
            streamUrl = if (session.type == ServerType.YOUTUBE_MUSIC) "aurora-yt://video/video-id" else "${session.server}/stream/101")
        val reports = mutableListOf<PlaybackReport>()
        override suspend fun reportPlayback(report: PlaybackReport) { reports += report }
        override suspend fun ping() = true
        override suspend fun home() = HomeData(starred = listOf(song))
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = listOf(song)
        override suspend fun starredSongs() = listOf(song)
        override suspend fun starredCount() = 1
        override suspend fun starredIds() = setOf(song.id)
        override suspend fun songFor(id: String) = song.takeIf { it.id == id }
        override suspend fun search(query: String) = SearchResults(songs = listOf(song))
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = false
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = song.streamUrl
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private fun source(name: String, type: ServerType = ServerType.SUBSONIC, title: String = name) = Source(
        Session("https://server.invalid", name, "salt", "token-$name", type, "user-$name"), title,
    )

    private fun report(song: Song, event: PlaybackReportEvent = PlaybackReportEvent.START) = PlaybackReport(
        song, "occurrence", event, PlaybackReportState.PLAYING, 5000, 180_000, 5000, 1_750_000_000_000,
    )

    @Test fun identicalRawIdsReportOnlyToTheirOwningAccounts() = runBlocking {
        val first = source("first")
        val second = source("second")
        val merged = MergedBackend(listOf(first, second), first.session)
        val songs = merged.allSongs()
        assertEquals(2, songs.size)
        songs.forEach { song ->
            val target = requireNotNull(merged.playbackReportTarget(song))
            target.send(report(song))
        }
        assertEquals("101", first.reports.single().song.id)
        assertEquals("first", first.reports.single().song.title)
        assertEquals("101", second.reports.single().song.id)
        assertEquals("second", second.reports.single().song.title)
        assertNotEquals(songs[0].id, songs[1].id)
    }

    @Test fun sourceReorderingPreservesAccountRoutingAndRemovedAccountsAreRejected() = runBlocking {
        val first = source("first")
        val second = source("second", ServerType.JELLYFIN)
        val songs = MergedBackend(listOf(first, second), first.session).allSongs()
        val song = songs.single { it.title == "second" }
        val reordered = MergedBackend(listOf(second, first), first.session)
        requireNotNull(reordered.playbackReportTarget(song)).send(report(song))
        assertTrue(first.reports.isEmpty())
        assertEquals("101", second.reports.single().song.id)
        assertNull(MergedBackend(listOf(first), first.session).playbackReportTarget(song))
    }

    @Test fun targetPinsOriginalAccountAndSongAcrossLaterBackendChanges() = runBlocking {
        val first = source("first")
        val second = source("second")
        val merged = MergedBackend(listOf(first, second), first.session)
        val songs = merged.allSongs()
        val original = songs.single { it.title == "first" }
        val target = requireNotNull(merged.playbackReportTarget(original))
        val other = songs.single { it.title == "second" }
        MergedBackend(listOf(second), second.session)
        target.send(report(other, PlaybackReportEvent.STOP))
        assertEquals("101", first.reports.single().song.id)
        assertEquals("first", first.reports.single().song.title)
        assertTrue(second.reports.isEmpty())
    }

    @Test fun unownedRawIdsMissingProvidersAndMismatchedNamespacesFailClosed() = runBlocking {
        val first = source("first")
        val second = source("second")
        val merged = MergedBackend(listOf(first, second), first.session)
        val original = merged.allSongs().single { it.title == "first" }
        assertNull(merged.playbackReportTarget(first.song))
        assertNull(merged.playbackReportTarget(original.copy(playbackSource = null)))
        assertNull(merged.playbackReportTarget(original.copy(id = "unknown\u0001101")))
        assertNull(merged.playbackReportTarget(original.copy(
            playbackSource = PlaybackSourceIdentity.fromSession(second.session, ""))))
        assertTrue(first.reports.isEmpty())
        assertTrue(second.reports.isEmpty())
    }

    @Test fun preferredServerRecordingReportsItsActualIdWithoutChangingCatalogueIdentity() = runBlocking {
        val server = source("server", title = "The song")
        val youtube = source("youtube", ServerType.YOUTUBE_MUSIC, "The song")
        val merged = MergedBackend(listOf(server, youtube), youtube.session)
        val catalogue = merged.search("The song").songs.single()
        val preferred = merged.playbackCandidates(catalogue).single()
        assertEquals(catalogue.id, preferred.id)
        assertEquals(server.song.streamUrl, preferred.streamUrl)
        val target = requireNotNull(merged.playbackReportTarget(preferred))
        target.send(report(preferred))
        assertTrue(youtube.reports.isEmpty())
        assertEquals("101", server.reports.single().song.id)
        assertEquals(server.song.streamUrl, server.reports.single().song.streamUrl)
    }

    @Test fun directYoutubeStreamReportsToTheGoogleAccountEvenWhenMerged() = runBlocking {
        val server = source("server")
        val youtube = source("youtube", ServerType.YOUTUBE_MUSIC)
        val merged = MergedBackend(listOf(server, youtube), server.session)
        val catalogue = merged.search("youtube").songs.single()
        requireNotNull(merged.playbackReportTarget(catalogue)).send(report(catalogue))
        assertTrue(server.reports.isEmpty())
        assertEquals("video-id", youtube.reports.single().song.id)
        assertEquals(youtube.song.streamUrl, youtube.reports.single().song.streamUrl)
    }

    @Test fun downloadedRawIdsWithProviderIdentityRouteAfterEnablingMergedMode() = runBlocking {
        val first = source("first")
        val second = source("second")
        val merged = MergedBackend(listOf(first, second), first.session)
        val downloaded = second.song.copy(streamUrl = "file:///downloaded.flac",
            playbackSource = PlaybackSourceIdentity.fromSession(second.session, "", RuleSource.DOWNLOAD))
        requireNotNull(merged.playbackReportTarget(downloaded)).send(report(downloaded))
        assertTrue(first.reports.isEmpty())
        assertEquals("101", second.reports.single().song.id)
        assertEquals(downloaded.streamUrl, second.reports.single().song.streamUrl)
        assertNull(MergedBackend(listOf(first), first.session).playbackReportTarget(downloaded))
    }

    @Test fun preferredActualIdCannotTargetASeparateProviderNamespace() = runBlocking {
        val first = source("first")
        val second = source("second")
        val merged = MergedBackend(listOf(first, second), first.session)
        val songs = merged.allSongs()
        val firstSong = songs.single { it.title == "first" }
        val secondSong = songs.single { it.title == "second" }
        val mismatched = firstSong.copy(playbackSource = firstSong.playbackSource!!.copy(songId = secondSong.id))
        assertNull(merged.playbackReportTarget(mismatched))
        assertNull(first.playbackReportTarget(mismatched))
    }

    @Test fun disablingMergedModeStillReportsExistingQueueIdsToTheMatchingSource() = runBlocking {
        val first = source("first")
        val second = source("second")
        val song = MergedBackend(listOf(first, second), first.session).allSongs().single { it.title == "first" }
        requireNotNull(first.playbackReportTarget(song)).send(report(song))
        assertEquals("101", first.reports.single().song.id)
        assertNull(second.playbackReportTarget(song))
    }
}
