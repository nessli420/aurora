package com.aurora.music.playback

import com.aurora.music.data.SearchResults
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song

data class MediaSearchRequest(
    val kind: Kind,
    val query: String,
    val artist: String = "",
    val album: String = "",
) {
    enum class Kind { RESUME, TEXT, SONG, ARTIST, ALBUM, GENRE, PLAYLIST }

    suspend fun resolve(
        search: suspend (String) -> SearchResults,
        collectionTracks: suspend (String, String) -> List<Song>,
        playlists: suspend () -> List<Playlist>,
    ): List<Song> {
        if (kind == Kind.RESUME) return emptyList()
        if (kind == Kind.PLAYLIST) {
            val match = playlists().firstOrNull { it.title.matches(query) } ?: return emptyList()
            return collectionTracks("playlist", match.id).playable()
        }
        val results = search(query)
        val tracks = when (kind) {
            Kind.SONG -> results.songs.filter { it.title.matches(query) && it.artist.accepts(artist) && it.album.accepts(album) }.take(1)
            Kind.ARTIST -> results.artists.firstOrNull { it.name.matches(query) }?.let {
                collectionTracks("artist", it.id)
            } ?: results.songs.filter { it.artist.matches(query) }
            Kind.ALBUM -> results.albums.firstOrNull { it.title.matches(query) && it.artist.accepts(artist) }?.let {
                collectionTracks("album", it.id)
            } ?: results.songs.filter { it.album.matches(query) && it.artist.accepts(artist) }
            Kind.GENRE -> results.songs.filter { it.genre.matches(query) }
            Kind.TEXT -> results.songs.ifEmpty {
                results.albums.firstOrNull()?.let { collectionTracks("album", it.id) }
                    ?: results.artists.firstOrNull()?.let { collectionTracks("artist", it.id) }.orEmpty()
            }
            else -> emptyList()
        }
        return tracks.playable()
    }

    companion object {
        fun parse(query: String?, focus: String? = null, title: String? = null, artist: String? = null,
            album: String? = null, genre: String? = null, playlist: String? = null): MediaSearchRequest? {
            if (listOf(query, focus, title, artist, album, genre, playlist).any { it != null && it.length > 1_000 }) return null
            val text = query?.trim()
            val kind = when (focus) {
                "vnd.android.cursor.item/audio" -> Kind.SONG
                "vnd.android.cursor.item/artist" -> Kind.ARTIST
                "vnd.android.cursor.item/album" -> Kind.ALBUM
                "vnd.android.cursor.item/genre" -> Kind.GENRE
                "vnd.android.cursor.item/playlist" -> Kind.PLAYLIST
                else -> Kind.TEXT
            }
            val target = when (kind) {
                Kind.SONG -> title
                Kind.ARTIST -> artist
                Kind.ALBUM -> album
                Kind.GENRE -> genre
                Kind.PLAYLIST -> playlist
                else -> text
            }?.trim().orEmpty()
            if (target.isNotEmpty()) return MediaSearchRequest(kind, target, artist?.trim().orEmpty(), album?.trim().orEmpty())
            if (!text.isNullOrEmpty()) return MediaSearchRequest(Kind.TEXT, text)
            if (text != null && kind == Kind.TEXT && (focus == null || focus == "vnd.android.cursor.item/*")) {
                return MediaSearchRequest(Kind.RESUME, "")
            }
            return null
        }

        private fun String.matches(other: String) = trim().equals(other.trim(), ignoreCase = true)
        private fun String.accepts(filter: String) = filter.isEmpty() || matches(filter)
        private fun List<Song>.playable() = filter { it.id.isNotBlank() && it.streamUrl.isNotBlank() }
    }
}
