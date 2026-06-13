package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import java.io.File

/** Aggregated content for the Home screen. */
data class HomeData(
    val newReleases: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(),
    val mostPlayed: List<Album> = emptyList(),
    val random: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val starred: List<Song> = emptyList(),
)

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** A resolved detail page: header info + its track list (+ albums for an artist). */
data class DetailData(val info: DetailInfo, val tracks: List<Song>, val albums: List<Album> = emptyList())

/** A browsable folder in the file-tree view. */
data class FolderNode(val id: String, val name: String)

/** One level of the folder/file tree: this folder's subfolders + the tracks directly inside it. */
data class FolderContent(
    val id: String,
    val title: String,
    val folders: List<FolderNode> = emptyList(),
    val songs: List<Song> = emptyList(),
)

/** A downloaded album/playlist row for the Library "Downloaded" view. */
data class DownloadRow(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val accent: Color,
)

/**
 * Server-agnostic content facade. Online requests delegate to the active [MediaBackend]
 * (Subsonic/Navidrome or Jellyfin); offline requests are served from downloaded files. Downloads
 * from different servers all live in one index, so offline mode shows them together.
 */
class MusicRepository(
    private val backendProvider: () -> MediaBackend?,
    private val downloadManager: DownloadManager,
    private val offlineProvider: () -> Boolean = { false },
    private val currentServerIdProvider: () -> String = { "" },
    private val smartPlaylistsProvider: () -> List<SmartPlaylist> = { emptyList() },
    private val smartEngine: SmartPlaylistEngine? = null,
) {
    private val backend: MediaBackend? get() = backendProvider()
    private val offline: Boolean get() = offlineProvider()

    // --- Offline (downloads-backed) -----------------------------------------

    /**
     * Downloads to show. Offline: every server's downloads (no server is reachable, so all local
     * music is fair game). Online: only the active server's, so a Navidrome library doesn't show
     * Jellyfin downloads and vice-versa.
     */
    private fun visibleDownloads(): List<DownloadedSong> {
        val all = downloadManager.downloads.value.values
        val scoped = if (offline) all else all.filter { (it.serverId ?: "") == currentServerIdProvider() }
        return scoped.toList()
    }

    private fun visibleCollections(): List<DownloadedCollection> {
        val all = downloadManager.collections.value
        return if (offline) all else all.filter { (it.serverId ?: "") == currentServerIdProvider() }
    }

    fun downloadedSongs(): List<Song> = visibleDownloads()
        .sortedBy { it.title }.map { it.toSong() }

    private fun fileUri(path: String): String = if (path.isBlank()) "" else Uri.fromFile(File(path)).toString()

    /** Downloaded albums/playlists (recorded collections + albums inferred from loose songs). */
    fun downloadedLibrary(): List<DownloadRow> {
        val collections = visibleCollections()
        val colRows = collections.map { DownloadRow(it.id, it.kind, it.title, it.subtitle, fileUri(it.coverPath), accentFor(it.id)) }
        val recordedTracks = collections.flatMap { it.trackIds }.toSet()
        val colIds = collections.map { it.id }.toSet()
        val inferred = visibleDownloads()
            .filter { it.id !in recordedTracks && it.albumId.isNotBlank() && it.albumId !in colIds }
            .groupBy { it.albumId }
            .map { (aid, songs) ->
                val f = songs.first()
                DownloadRow(aid, "album", f.album.ifBlank { "Album" }, f.artist, fileUri(f.coverPath), accentFor(aid))
            }
        return (colRows + inferred).sortedBy { it.title }
    }

    private fun downloadedAlbums(): List<Album> = visibleDownloads()
        .filter { it.albumId.isNotBlank() }
        .groupBy { it.albumId }
        .map { (albumId, songs) ->
            val first = songs.first()
            Album(id = albumId, title = first.album.ifBlank { "Album" }, artist = first.artist, artworkUrl = first.toSong().artworkUrl, year = 0, songCount = songs.size)
        }
        .sortedBy { it.title }

    // --- Queries (delegate online, downloads offline) ------------------------

    suspend fun home(): HomeData {
        if (offline) {
            val albums = downloadedAlbums()
            return HomeData(newReleases = albums, recentlyPlayed = albums, starred = downloadedSongs())
        }
        return backend?.home() ?: HomeData()
    }

    suspend fun allAlbums(): List<Album> =
        if (offline) downloadedAlbums() else backend?.allAlbums().orEmpty()

    suspend fun allArtists(): List<Artist> =
        if (offline) emptyList() else backend?.allArtists().orEmpty()

    suspend fun allPlaylists(): List<Playlist> =
        if (offline) emptyList() else backend?.allPlaylists().orEmpty()

    suspend fun allSongs(): List<Song> =
        if (offline) downloadedSongs() else backend?.allSongs().orEmpty()

    /** The full library (as far as the backend exposes it) — smart playlists / scans run over this. */
    suspend fun librarySongs(limit: Int = 2000): List<Song> =
        if (offline) downloadedSongs() else backend?.librarySongs(limit).orEmpty()

    suspend fun starredSongs(): List<Song> = backend?.starredSongs().orEmpty()

    suspend fun starredCount(): Int = if (offline) starredSongs().size else (backend?.starredCount() ?: 0)

    /** Ids of everything starred/favorited on the server — songs, albums and artists. */
    suspend fun starredIds(): Set<String> = backend?.starredIds() ?: emptySet()

    /** Subset of [ids] that are liked songs (batched, lazy). Empty offline. */
    suspend fun likedSongIds(ids: List<String>): Set<String> =
        if (offline) emptySet() else backend?.likedSongIds(ids).orEmpty()

    /** The signed-in user's avatar URL (for the profile/home/sidebar), or "". */
    suspend fun profileImageUrl(): String = if (offline) "" else backend?.profileImageUrl().orEmpty()

    /** Resolve a single playable song by id (local if downloaded, else from the server). */
    suspend fun songFor(id: String): Song? {
        downloadManager.get(id)?.let { return it.toSong() }
        return backend?.songFor(id)
    }

    suspend fun search(query: String): SearchResults {
        if (offline) {
            val q = query.trim()
            val songs = downloadedSongs().filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
            val albums = downloadedAlbums().filter { it.title.contains(q, true) || it.artist.contains(q, true) }
            return SearchResults(songs = songs, albums = albums, artists = emptyList())
        }
        return backend?.search(query) ?: SearchResults()
    }

    suspend fun scrobble(id: String) {
        if (offline) return
        backend?.scrobble(id)
    }

    /** A "radio" continuation: similar songs to the seed, falling back to random. */
    suspend fun radio(seedId: String): List<Song> {
        if (offline) return emptyList()
        return backend?.radio(seedId).orEmpty()
    }

    suspend fun createPlaylist(name: String): Boolean = backend?.createPlaylist(name) ?: false

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean =
        backend?.addToPlaylist(playlistId, trackIds) ?: false

    // M3U import/export

    /** Tracks of a collection rendered as extended-M3U text, or null when it can't be resolved. */
    suspend fun exportPlaylist(kind: String, id: String): String? =
        detail(kind, id)?.tracks?.takeIf { it.isNotEmpty() }?.let { M3u.write(it) }

    /**
     * Import parsed M3U entries as a new playlist on the active backend: each entry is matched
     * against the library by search + fuzzy (title, artist, duration±5s) scoring. Returns
     * (matched, total), or null when the playlist couldn't be created.
     */
    suspend fun importPlaylist(name: String, entries: List<M3u.Entry>): Pair<Int, Int>? {
        if (offline || entries.isEmpty()) return null
        val matched = entries.mapNotNull { matchEntry(it) }.distinctBy { it.id }
        val playlistId = backend?.createPlaylistWithId(name) ?: return null
        if (matched.isNotEmpty()) backend?.addToPlaylist(playlistId, matched.map { it.id })
        return matched.size to entries.size
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private suspend fun matchEntry(e: M3u.Entry): Song? {
        val query = listOf(e.artist, e.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val candidates = search(query).songs.ifEmpty { search(e.title).songs }
        val titleN = norm(e.title)
        val artistN = norm(e.artist)
        return candidates.map { s ->
            var score = 0
            val st = norm(s.title)
            if (st == titleN) score += 3 else if (st.contains(titleN) || titleN.contains(st)) score += 1
            if (artistN.isNotBlank() && norm(s.artist).contains(artistN)) score += 2
            if (e.durationSec > 0 && kotlin.math.abs(s.durationSec - e.durationSec) <= 5) score += 2
            s to score
        }.filter { it.second >= 3 }.maxByOrNull { it.second }?.first
    }

    suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean =
        backend?.updatePlaylist(id, name, comment) ?: false

    suspend fun deletePlaylist(id: String): Boolean = backend?.deletePlaylist(id) ?: false

    /**
     * Star/favorite on the server. [kind] picks the right server call so albums and artists star
     * correctly. Playlists aren't server-starrable — those are handled locally by the caller.
     */
    suspend fun setStarred(id: String, starred: Boolean, kind: String = "song"): Boolean =
        backend?.setStarred(id, starred, kind) ?: false

    /** Resolve any tappable item (album/artist/playlist/liked/smart) to a detail page. */
    suspend fun detail(kind: String, id: String): DetailData? {
        // Smart playlists evaluate over whatever library is reachable (downloads when offline).
        if (kind == "smart") {
            val sp = smartPlaylistsProvider().firstOrNull { it.id == id } ?: return null
            val tracks = smartEngine?.evaluate(sp, librarySongs()).orEmpty()
            return DetailData(
                DetailInfo(sp.name ?: "Smart playlist", "Smart playlist • ${tracks.size} songs", tracks.firstOrNull()?.artworkUrl ?: "", accentFor(id), false, tracks.size, "Smart playlist"),
                tracks,
            )
        }
        if (offline) {
            val dls = downloadedSongs()
            return when (kind) {
                "album" -> dls.filter { it.albumId == id }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    val f = tracks.first()
                    DetailData(DetailInfo(f.album.ifBlank { "Album" }, "${f.artist} • Downloaded", f.artworkUrl, accentFor(id), false, tracks.size, "Album"), tracks)
                }
                "artist" -> dls.filter { it.artistId == id }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    DetailData(DetailInfo(tracks.first().artist, "${tracks.size} downloaded tracks", tracks.first().artworkUrl, accentFor(id), true, tracks.size, "Artist"), tracks)
                }
                "playlist" -> downloadManager.collections.value.firstOrNull { it.id == id }?.let { col ->
                    val byId = downloadManager.downloads.value
                    val tracks = col.trackIds.mapNotNull { byId[it]?.toSong() }
                    DetailData(DetailInfo(col.title, col.subtitle, fileUri(col.coverPath), accentFor(id), false, tracks.size, "Playlist"), tracks)
                }
                else -> null
            }
        }
        return backend?.detail(kind, id)
    }

    /** Next page of a collection's tracks (lazy loading). Empty offline. */
    suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
        if (offline) emptyList() else backend?.detailPage(kind, id, offset).orEmpty()

    // Folder browsing

    /** Whether the active backend supports folder/file-tree browsing (and we can reach it). */
    val supportsFolders: Boolean get() = !offline && backend?.supportsFolders == true

    /** Whether the active backend can write track metadata server-side (Jellyfin). */
    val supportsServerTagEdit: Boolean get() = !offline && backend?.supportsServerTagEdit == true

    /** The track's current server-side metadata (to seed the editor), or null. */
    suspend fun readMetadata(songId: String): AudioTags? =
        if (offline) null else backend?.readMetadata(songId)

    /** Write a track's metadata on the server (Jellyfin). */
    suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean =
        if (offline) false else backend?.updateMetadata(songId, tags) ?: false

    /** One level of the folder tree ("" = root). Null offline or when unsupported. */
    suspend fun browseFolder(folderId: String): FolderContent? =
        if (offline) null else backend?.browseFolder(folderId)
}
