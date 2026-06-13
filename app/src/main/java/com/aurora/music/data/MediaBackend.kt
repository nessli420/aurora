package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song

/**
 * A music server the app can talk to (Navidrome/Subsonic or Jellyfin). Every method returns the
 * app's own domain models, so the rest of the app is identical regardless of which server backs it.
 * Implementations own their own DTO mapping, auth and URL building.
 *
 * Downloaded-track substitution and offline sourcing live in [MusicRepository]; a backend only
 * speaks to the live server. Song mapping runs each track through [localize] so a downloaded copy
 * transparently plays from local files even while online.
 */
interface MediaBackend {
    val session: Session

    suspend fun ping(): Boolean

    suspend fun home(): HomeData
    suspend fun allAlbums(): List<Album>
    suspend fun allArtists(): List<Artist>
    suspend fun allPlaylists(): List<Playlist>
    suspend fun allSongs(): List<Song>

    /** As much of the full song library as practical (smart playlists / dedup scans evaluate over
     *  this). Backends whose [allSongs] is already complete keep the default. */
    suspend fun librarySongs(limit: Int = 2000): List<Song> = allSongs()
    suspend fun starredSongs(): List<Song>
    /** Cheap count of liked songs (so the library doesn't load the whole list just for a number). */
    suspend fun starredCount(): Int
    suspend fun starredIds(): Set<String>
    suspend fun songFor(id: String): Song?
    suspend fun search(query: String): SearchResults
    suspend fun scrobble(id: String)
    suspend fun radio(seedId: String): List<Song>
    suspend fun createPlaylist(name: String): Boolean
    suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean
    suspend fun deletePlaylist(id: String): Boolean

    /** Create a playlist and return its id (used by M3U import so tracks can be added). */
    suspend fun createPlaylistWithId(name: String): String? = null

    /** Append tracks to a playlist. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean = false
    suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean
    suspend fun detail(kind: String, id: String): DetailData?

    /** Next page of a collection's tracks for lazy/infinite-scroll loading (offset = already-loaded
     *  count). Backends that return everything in [detail] leave this empty (the default). */
    suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> = emptyList()

    /** Which of [ids] are liked/saved songs — a cheap, batched check so hearts show on tracks
     *  beyond the page [starredIds] returns. Default empty (backends whose [starredIds] is already
     *  complete don't need it). */
    suspend fun likedSongIds(ids: List<String>): Set<String> = emptySet()

    /** The signed-in user's avatar URL (Spotify), or "" if none/not applicable. */
    suspend fun profileImageUrl(): String = ""

    /** True when this backend can browse the library as a folder/file tree. */
    val supportsFolders: Boolean get() = false

    /** Contents of one folder level ([folderId] "" = root), or null when unsupported/unreachable. */
    suspend fun browseFolder(folderId: String): FolderContent? = null

    /** True when this backend can write a track's metadata server-side (e.g. Jellyfin's item API).
     *  Subsonic/Navidrome has no tag-write endpoint; Spotify is a read-only catalog. */
    val supportsServerTagEdit: Boolean get() = false

    /** The track's current server-side metadata (so the editor preserves fields it doesn't show),
     *  or null when unsupported. */
    suspend fun readMetadata(songId: String): AudioTags? = null

    /** Update a track's metadata on the server. Returns false if unsupported or the write failed. */
    suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean = false

    /** Server-provided (synced or plain) lyrics, or null if the server has none. */
    suspend fun serverLyrics(song: Song): Lyrics?

    /** A playable audio URL (auth included). [lossless] = serve the original, untouched file. */
    fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String

    /** A cover-art URL (auth included), or "" when there's no art. */
    fun coverArtUrl(id: String, size: Int = 600): String
}
