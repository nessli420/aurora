package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

// mediabackend over on-device files only server-only ops are no-ops
class LocalBackend(
    private val library: LocalLibrary,
    private val store: LocalStore,
    override val session: Session,
) : MediaBackend {

    private fun LocalPlaylist.toPlaylist(): Playlist {
        val tracks = trackIds.orEmpty().mapNotNull { library.song(it) }
        return Playlist(
            id = id,
            title = title ?: "",
            subtitle = (subtitle ?: "").ifBlank { "${tracks.size} song${if (tracks.size == 1) "" else "s"}" },
            coverUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "",
            songCount = tracks.size,
            accent = accentFor(id),
        )
    }

    override suspend fun ping(): Boolean = true

    override suspend fun home(): HomeData {
        library.ensureLoaded()
        val albums = library.albums
        return HomeData(
            newReleases = albums.take(20),                                   // locallibrary already sorts by date added
            mostPlayed = albums.sortedByDescending { it.songCount }.take(20),
            playlists = store.playlists().map { it.toPlaylist() },
            artists = library.artists.take(40),
            starred = starredSongs().take(40),
        )
    }

    override suspend fun allAlbums(): List<Album> { library.ensureLoaded(); return library.albums }
    override suspend fun allArtists(): List<Artist> { library.ensureLoaded(); return library.artists }
    override suspend fun allPlaylists(): List<Playlist> = store.playlists().map { it.toPlaylist() }
    override suspend fun allSongs(): List<Song> { library.ensureLoaded(); return library.songs }

    override suspend fun starredSongs(): List<Song> {
        library.ensureLoaded()
        val liked = store.likedIds()
        return library.songs.filter { it.id in liked }
    }

    override suspend fun starredCount(): Int = starredSongs().size

    override suspend fun starredIds(): Set<String> = store.likedIds()

    override suspend fun songFor(id: String): Song? { library.ensureLoaded(); return library.song(id) }

    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        val liked = store.likedIds()
        return ids.filterTo(HashSet()) { it in liked }
    }

    override suspend fun search(query: String): SearchResults {
        library.ensureLoaded()
        val q = query.trim()
        if (q.isBlank()) return SearchResults()
        return SearchResults(
            songs = library.songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.take(60),
            albums = library.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.take(30),
            artists = library.artists.filter { it.name.contains(q, true) }.take(30),
            playlists = store.playlists().map { it.toPlaylist() }.filter { it.title.contains(q, true) }.take(20),
        )
    }

    override suspend fun scrobble(id: String) { /* last.fm handled separately */ }

    override suspend fun radio(seedId: String): List<Song> {
        library.ensureLoaded()
        val seed = library.song(seedId)
        val sameArtist = seed?.let { s -> library.songsByArtistId(s.artistId).filter { it.id != seedId } }.orEmpty()
        val rest = library.songs.filter { it.id != seedId && it !in sameArtist }.shuffled()
        return (sameArtist.shuffled() + rest).take(40)
    }

    override suspend fun detail(kind: String, id: String): DetailData? {
        library.ensureLoaded()
        return when (kind) {
            "album" -> {
                val tracks = library.songsByAlbumId(id)
                val album = library.albums.firstOrNull { it.id == id } ?: return null
                DetailData(
                    info = DetailInfo(album.title, album.artist, album.artworkUrl, accentFor(id), false, tracks.size, "Album"),
                    tracks = tracks,
                )
            }
            "artist" -> {
                val artist = library.artists.firstOrNull { it.id == id } ?: return null
                val tracks = library.songsByArtistId(id)
                val albums = library.albumsByArtistId(id)
                DetailData(
                    info = DetailInfo(artist.name, "${tracks.size} song${if (tracks.size == 1) "" else "s"}", artist.imageUrl, accentFor(id), true, tracks.size, "Artist"),
                    tracks = tracks,
                    albums = albums,
                )
            }
            "playlist" -> {
                val pl = store.playlist(id) ?: return null
                val tracks = pl.trackIds.orEmpty().mapNotNull { library.song(it) }
                DetailData(
                    info = DetailInfo(pl.title ?: "", pl.subtitle ?: "", tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "", accentFor(id), false, tracks.size, "Playlist"),
                    tracks = tracks,
                )
            }
            "liked" -> {
                val tracks = starredSongs()
                DetailData(
                    info = DetailInfo("Liked Songs", "${tracks.size} song${if (tracks.size == 1) "" else "s"}", tracks.firstOrNull()?.artworkUrl ?: "", accentFor("liked"), false, tracks.size, "Liked"),
                    tracks = tracks,
                )
            }
            else -> null
        }
    }

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = store.setLiked(id, starred)

    override suspend fun createPlaylist(name: String): Boolean { store.createPlaylist(name); return true }

    override suspend fun createPlaylistWithId(name: String): String? = store.createPlaylist(name)

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean {
        store.updatePlaylist(id, name, comment); return true
    }

    override suspend fun deletePlaylist(id: String): Boolean { store.deletePlaylist(id); return true }

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean { store.addTracks(playlistId, trackIds); return true }
    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean { store.removeTracks(playlistId, trackIds); return true }

    // folder tree rooted at the deepest common directory

    override val supportsFolders: Boolean get() = true

    override suspend fun browseFolder(folderId: String): FolderContent? {
        library.ensureLoaded()
        val base = folderId.ifBlank { library.folderRoot }
        if (base.isBlank()) return null
        val (subdirs, tracks) = library.browse(base)
        return FolderContent(
            id = base,
            title = if (folderId.isBlank()) "Folders" else base.substringAfterLast('/'),
            folders = subdirs.map { FolderNode("$base/$it", it) },
            songs = tracks,
        )
    }

    override suspend fun serverLyrics(song: Song): Lyrics? = null

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String =
        library.song(songId)?.streamUrl ?: ""

    override fun coverArtUrl(id: String, size: Int): String =
        library.albums.firstOrNull { it.id == id }?.artworkUrl
            ?: library.song(id)?.artworkUrl ?: ""
}
