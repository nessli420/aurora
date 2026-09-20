package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException

/** Screens can finish loading after a provider failure while still displaying its error. */
class ReportingMediaBackend(private val source: MediaBackend, private val onError: (String) -> Unit) : MediaBackend by source {
    private suspend fun <T> attempt(fallback: T, block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            onError(if (e is MediaAccountExpiredException) "${source.session.typeLabel} needs you to reconnect in Settings → Accounts."
                else "${source.session.typeLabel} could not complete this request. Please try again.")
            fallback
        }
    override suspend fun ping() = attempt(false) { source.ping() }
    override suspend fun home() = source.home()
    override suspend fun allAlbums() = attempt(emptyList()) { source.allAlbums() }
    override suspend fun allArtists() = attempt(emptyList()) { source.allArtists() }
    override suspend fun allPlaylists() = attempt(emptyList()) { source.allPlaylists() }
    override suspend fun allSongs() = attempt(emptyList()) { source.allSongs() }
    override suspend fun librarySongs(limit: Int) = attempt(emptyList()) { source.librarySongs(limit) }
    override suspend fun songsPage(offset: Int, count: Int) = attempt(emptyList()) { source.songsPage(offset, count) }
    override suspend fun starredSongs() = attempt(emptyList()) { source.starredSongs() }
    override suspend fun starredCount() = attempt(0) { source.starredCount() }
    override suspend fun starredIds() = attempt(emptySet()) { source.starredIds() }
    override suspend fun likedSongIds(ids: List<String>) = attempt(emptySet()) { source.likedSongIds(ids) }
    override suspend fun songFor(id: String) = attempt<Song?>(null) { source.songFor(id) }
    override suspend fun search(query: String) = attempt(SearchResults()) { source.search(query) }
    override suspend fun radio(seedId: String) = attempt(emptyList()) { source.radio(seedId) }
    override suspend fun detail(kind: String, id: String) = attempt<DetailData?>(null) { source.detail(kind, id) }
    override suspend fun detailPage(kind: String, id: String, offset: Int) = attempt(emptyList()) { source.detailPage(kind, id, offset) }
    override suspend fun setStarred(id: String, starred: Boolean, kind: String) = attempt(false) { source.setStarred(id, starred, kind) }
    override suspend fun createPlaylist(name: String) = attempt(false) { source.createPlaylist(name) }
    override suspend fun createPlaylistWithId(name: String) = attempt<String?>(null) { source.createPlaylistWithId(name) }
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = attempt(false) { source.updatePlaylist(id, name, comment) }
    override suspend fun deletePlaylist(id: String) = attempt(false) { source.deletePlaylist(id) }
    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) = attempt(false) { source.addToPlaylist(playlistId, trackIds) }
    // Complete collection requests deliberately propagate failure rather than playing a partial list.
}

class MediaAccountExpiredException : java.io.IOException("Reconnect your music account in Settings → Accounts.")
