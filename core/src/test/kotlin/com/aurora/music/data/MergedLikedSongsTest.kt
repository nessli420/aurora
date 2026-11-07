package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MergedLikedSongsTest {
    private class Source(override val session: Session, private val liked: List<Song>, private val extraIds: Set<String>, private val down: Boolean = false) : MediaBackend {
        override suspend fun ping() = true
        override suspend fun home() = HomeData(starred = liked)
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = liked
        override suspend fun starredSongs() = liked
        override suspend fun starredCount() = liked.size
        override suspend fun starredIds() = liked.map { it.id }.toSet() + extraIds
        override suspend fun likedSongIds(ids: List<String>): Set<String> = if (down) throw java.io.IOException("down") else ids.filter { id -> liked.any { it.id == id } }.toSet()
        override suspend fun songFor(id: String) = liked.firstOrNull { it.id == id }
        override suspend fun search(query: String) = SearchResults()
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

    private fun song(id: String, title: String, artist: String, seconds: Int) = Song(id, title, artist, "Album", "", seconds)

    private fun source(name: String, liked: List<Song>, extra: Set<String> = emptySet(), down: Boolean = false) =
        Source(Session("https://$name.invalid", name, "salt", "token", ServerType.SUBSONIC, "user-$name"), liked, extra, down)

    @Test fun aFailedSourceKeepsTheOthersAnswersAndLeavesOnlyItsSongsUnanswered() = runBlocking {
        val up = source("up", listOf(song("1", "One", "Tester", 200)))
        val down = source("down", emptyList(), down = true)
        val merged = MergedBackend(listOf(up, down), up.session)
        val upIds = listOf("1", "2").map { "${mergeNamespace(up.session)}$MERGE_NAMESPACE_SEP$it" }
        val downId = "${mergeNamespace(down.session)}${MERGE_NAMESPACE_SEP}x"
        val partial = try { merged.likedSongIds(upIds + downId); null } catch (e: PartialLikes) { e }
        assertEquals(setOf(upIds[0]), partial?.liked)
        assertEquals(setOf(downId), partial?.unanswered)
        assertEquals(setOf(upIds[0]), merged.likedSongIds(upIds))
    }

    @Test fun likedCountMatchesTheMergedSongListNotEveryStarredId() = runBlocking {
        val first = source("first", listOf(song("1", "Café Lights", "Tester", 200), song("2", "夏の雨", "テスト", 240), song("3", "冬の星", "テスト", 241)),
            extra = setOf("album-9", "artist-4"))
        val second = source("second", listOf(song("a", "Cafe Lights", "Tester", 201), song("b", "Another Song", "Tester", 180)), extra = setOf("album-7"))
        val merged = MergedBackend(listOf(first, second), first.session)
        val songs = merged.starredSongs()
        assertEquals(4, songs.size)
        assertEquals(songs.size, merged.starredCount())
    }

    @Test fun matchingKeepsOtherScriptsAndFoldsLatinAccents() {
        assertEquals(TrackMatch.key("Beyoncé", "Café"), TrackMatch.key("Beyonce", "Cafe"))
        assertNotEquals(TrackMatch.key("テスト", "夏の雨"), TrackMatch.key("テスト", "冬の星"))
        assertNotEquals(TrackMatch.norm("かき"), TrackMatch.norm("がき"))
        assertEquals(TrackMatch.norm("Song"), TrackMatch.norm("Song (feat. Someone)"))
        assertNotEquals(TrackMatch.identity("!!!"), TrackMatch.identity("…"))
    }
}
