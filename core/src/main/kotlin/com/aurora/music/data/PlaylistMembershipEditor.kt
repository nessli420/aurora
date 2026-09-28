package com.aurora.music.data

import com.aurora.music.model.Playlist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pins the track and its provider for the lifetime of the playlist picker. */
class PlaylistMembershipEditor internal constructor(
    private val backend: MediaBackend,
    private val songId: String,
    private val isActive: () -> Boolean = { true },
    private val onChanged: (String) -> Unit = {},
) {
    private val writes = Mutex()
    private var allowedIds: Set<String> = emptySet()
    private val changedIds = linkedSetOf<String>()

    fun publishChanges() {
        changedIds.forEach(onChanged)
        changedIds.clear()
    }

    suspend fun playlists(): List<Playlist> = backend.playlistsForSong(songId).also {
        allowedIds = it.map { playlist -> playlist.id }.toSet()
    }

    suspend fun contains(playlistId: String): Boolean {
        check(playlistId in allowedIds)
        return backend.collectionTracks("playlist", playlistId).any { it.id == songId }
    }

    suspend fun setIncluded(playlistId: String, included: Boolean): Boolean = writes.withLock {
        if (!isActive() || playlistId !in allowedIds) return@withLock false
        val alreadyIncluded = contains(playlistId)
        if (alreadyIncluded == included) return@withLock true
        if (!isActive()) return@withLock false
        val changed = if (included) backend.addToPlaylist(playlistId, listOf(songId))
            else backend.removeFromPlaylist(playlistId, listOf(songId))
        if (changed) changedIds += playlistId
        changed
    }
}
