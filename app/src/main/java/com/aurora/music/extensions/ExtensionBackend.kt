package com.aurora.music.extensions

import com.aurora.music.data.*
import com.aurora.music.model.*
import com.aurora.music.util.accentFor

class ExtensionBackend(
    private val manager: ExtensionManager,
    override val session: Session,
    private val localize: (Song) -> Song = { it },
) : MediaBackend {
    private val component get() = session.userId
    override val supportsOfflineBrowsing get() = true
    override suspend fun ping(): Boolean { manager.refresh(); return manager.entries.value.any { it.component == component && it.enabled } }
    override suspend fun allSongs(): List<Song> = manager.loadTracks(component).map { localize(manager.song(component, it)) }
    override suspend fun songsPage(offset: Int, count: Int): List<Song> = allSongs().drop(offset.coerceAtLeast(0)).take(count.coerceIn(0, 2000))
    override suspend fun librarySongs(limit: Int): List<Song> = allSongs().take(limit)
    override suspend fun allAlbums(): List<Album> = allSongs().groupBy { it.albumId }.map { (id, songs) ->
        Album(id, songs.first().album, songs.first().artist, "", 0, songs.size, songs.sumOf { it.durationSec }) }
    override suspend fun allArtists(): List<Artist> = allSongs().distinctBy { it.artistId }.map { Artist(it.artistId, it.artist, "", 0) }
    override suspend fun allPlaylists(): List<Playlist> = emptyList()
    override suspend fun home() = HomeData(newReleases = allAlbums(), artists = allArtists())
    override suspend fun starredSongs(): List<Song> = emptyList()
    override suspend fun starredCount() = 0
    override suspend fun starredIds(): Set<String> = emptySet()
    override suspend fun songFor(id: String): Song? = allSongs().find { it.id == id }
    override suspend fun search(query: String) = SearchResults(
        songs = allSongs().filter { "${it.title} ${it.artist} ${it.album}".contains(query, true) },
        albums = allAlbums().filter { "${it.title} ${it.artist}".contains(query, true) },
        artists = allArtists().filter { it.name.contains(query, true) })
    override suspend fun scrobble(id: String) = Unit
    override suspend fun radio(seedId: String): List<Song> = emptyList()
    override suspend fun createPlaylist(name: String) = false
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
    override suspend fun deletePlaylist(id: String) = false
    override suspend fun setStarred(id: String, starred: Boolean, kind: String) = false
    override suspend fun detail(kind: String, id: String): DetailData? {
        val songs = allSongs().filter { if (kind == "album") it.albumId == id else if (kind == "artist") it.artistId == id else false }
        val first = songs.firstOrNull() ?: return null
        val title = if (kind == "artist") first.artist else first.album
        return DetailData(DetailInfo(title, first.artist, "", accentFor(title), kind == "artist", songs.size, kind), songs)
    }
    override suspend fun serverLyrics(song: Song): Lyrics? = null
    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String = android.net.Uri.Builder()
        .scheme("aurora-extension").authority(ExtensionManager.key(component))
        .appendPath(songId.removePrefix("ext-${ExtensionManager.key(component)}-")).build().toString()
    override fun coverArtUrl(id: String, size: Int) = ""
}
