package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song

// returns app domain models so callers are server-agnostic backend owns dto mapping auth and urls
interface MediaBackend {
    val session: Session

    fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? = song.playbackSource ?: when {
        song.isRadio() -> PlaybackSourceIdentity(source = com.aurora.music.data.rules.RuleSource.RADIO)
        song.isPodcast() -> PlaybackSourceIdentity(source = com.aurora.music.data.rules.RuleSource.PODCAST)
        session.type != ServerType.LOCAL && (song.streamUrl.startsWith("file:") || song.streamUrl.startsWith("content:")) ->
            PlaybackSourceIdentity(source = com.aurora.music.data.rules.RuleSource.LOCAL_FILE)
        else -> PlaybackSourceIdentity.fromSession(session, song.albumId)
    }

    fun playbackCollectionIdentity(kind: String, id: String, name: String? = null): PlaybackCollectionIdentity? {
        if (kind != "playlist") return null
        val provider = PlaybackSourceIdentity.fromSession(session, "").providerId ?: return null
        return PlaybackCollectionIdentity(PlaybackSourceIdentity.scoped(provider, "playlist", id), name)
    }

    suspend fun ping(): Boolean

    suspend fun home(): HomeData
    suspend fun allAlbums(): List<Album>
    suspend fun allArtists(): List<Artist>
    suspend fun allPlaylists(): List<Playlist>
    suspend fun allSongs(): List<Song>

    suspend fun librarySongs(limit: Int = 2000): List<Song> = allSongs()

    // paged library songs in server sort order; backends without paging only serve page zero
    suspend fun songsPage(offset: Int, count: Int): List<Song> =
        if (offset == 0) librarySongs(count) else emptyList()

    suspend fun starredSongs(): List<Song>
    suspend fun starredCount(): Int
    suspend fun starredIds(): Set<String>
    suspend fun songFor(id: String): Song?
    suspend fun search(query: String): SearchResults
    suspend fun scrobble(id: String)
    suspend fun radio(seedId: String): List<Song>
    suspend fun createPlaylist(name: String): Boolean
    suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean
    suspend fun deletePlaylist(id: String): Boolean

    suspend fun createPlaylistWithId(name: String): String? = null

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean = false
    suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean
    suspend fun detail(kind: String, id: String): DetailData?

    suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> = emptyList()

    /** Complete, ordered collection. Playlist occurrences are intentionally preserved. */
    suspend fun collectionTracks(kind: String, id: String): List<Song> {
        val data = detail(kind, id) ?: error("Could not load this collection. Reconnect and retry.")
        if (kind == "artist") {
            val tracks = data.albums.flatMap { collectionTracks("album", it.id) }
            return (tracks + data.tracks).distinctBy { it.id }
        }
        val tracks = data.tracks.toMutableList()
        while (tracks.size < data.info.songCount) {
            val page = detailPage(kind, id, tracks.size)
            check(page.isNotEmpty()) { "The server returned an incomplete tracklist. Please retry." }
            tracks.addAll(page)
        }
        return tracks
    }

    suspend fun likedSongIds(ids: List<String>): Set<String> = emptySet()

    suspend fun profileImageUrl(): String = ""

    val supportsFolders: Boolean get() = false

    // folderId "" = root
    suspend fun browseFolder(folderId: String): FolderContent? = null

    val supportsServerTagEdit: Boolean get() = false

    suspend fun readMetadata(songId: String): AudioTags? = null

    suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean = false

    suspend fun serverLyrics(song: Song): Lyrics?

    // lossless serves the original untouched file
    fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String

    fun coverArtUrl(id: String, size: Int = 600): String
}
