package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexPage
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

class PlexBackend(
    private val client: PlexClient,
    private val maxBitrateProvider: () -> Int,
    private val localize: (Song) -> Song,
) : MediaBackend {
    override val session: Session get() = client.session
    private val streams = ConcurrentHashMap<String, String>()
    private val artwork = ConcurrentHashMap<String, String>()

    private fun JsonObject.text(key: String): String = runCatching { get(key)?.asString.orEmpty() }.getOrDefault("")
    private fun JsonObject.int(key: String): Int = runCatching { get(key)?.asInt ?: 0 }.getOrDefault(0)
    private fun JsonObject.long(key: String): Long = runCatching { get(key)?.asLong ?: 0L }.getOrDefault(0L)
    private fun JsonObject.obj(key: String): JsonObject? = runCatching { getAsJsonObject(key) }.getOrNull()
    private fun JsonObject.first(key: String): JsonObject? =
        runCatching { getAsJsonArray(key)?.firstOrNull()?.asJsonObject }.getOrNull()

    private fun JsonObject.song(): Song {
        val id = text("ratingKey")
        val media = first("Media")
        val part = media?.first("Part")
        val path = part?.text("key").orEmpty()
        if (path.isNotBlank()) streams[id] = path
        val thumb = text("thumb").ifBlank { text("parentThumb") }.ifBlank { text("grandparentThumb") }
        if (thumb.isNotBlank()) artwork[id] = thumb
        return localize(Song(
            id = id,
            title = text("title").ifBlank { "Unknown" },
            artist = text("grandparentTitle").ifBlank { text("originalTitle") }.ifBlank { "Unknown artist" },
            album = text("parentTitle"),
            artworkUrl = client.url(thumb),
            durationSec = long("duration").div(1000).toInt(),
            liked = int("userRating") >= 8,
            accent = accentFor(id),
            streamUrl = client.url(path),
            albumId = text("parentRatingKey"),
            artistId = text("grandparentRatingKey"),
            suffix = media?.text("container").orEmpty(),
            bitrateKbps = media?.int("bitrate") ?: 0,
            sampleRateHz = media?.int("samplingRate") ?: 0,
            bitDepth = media?.int("bitDepth") ?: 0,
            path = part?.text("file").orEmpty(),
            playCount = int("viewCount"),
            dateAddedSec = long("addedAt"),
        ))
    }

    private fun JsonObject.album(): Album {
        val id = text("ratingKey")
        val thumb = text("thumb")
        if (thumb.isNotBlank()) artwork[id] = thumb
        return Album(id, text("title").ifBlank { "Album" },
            text("parentTitle").ifBlank { text("originalTitle") }, client.url(thumb),
            int("year"), int("leafCount"), long("duration").div(1000).toInt(),
            playCount = int("viewCount"))
    }

    private fun JsonObject.artist(): Artist {
        val id = text("ratingKey")
        val thumb = text("thumb")
        if (thumb.isNotBlank()) artwork[id] = thumb
        return Artist(id, text("title").ifBlank { "Artist" }, client.url(thumb), 0L)
    }

    private fun JsonObject.playlist(): Playlist {
        val id = text("ratingKey")
        val thumb = text("thumb")
        if (thumb.isNotBlank()) artwork[id] = thumb
        val count = int("leafCount")
        return Playlist(id, text("title").ifBlank { "Playlist" }, "$count songs", client.url(thumb), count,
            accentFor(id))
    }

    private suspend fun allChildren(id: String, playlist: Boolean = false): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        while (true) {
            val page = if (playlist) client.playlistItems(id, result.size) else client.children(id, result.size)
            result += page.items
            if (result.size >= page.total || page.items.isEmpty()) break
        }
        return result
    }

    override suspend fun ping(): Boolean = runCatching { client.musicSections(); true }.getOrDefault(false)

    override suspend fun home(): HomeData {
        val albums = allAlbums()
        val artists = allArtists()
        val playlists = allPlaylists()
        val liked = starredSongs()
        return HomeData(albums.takeLast(12).reversed(), emptyList(), emptyList(), albums.shuffled().take(12),
            playlists.take(12), artists.take(20), liked.take(30))
    }

    override suspend fun allAlbums(): List<Album> = client.library(9, size = 500).items.map { it.album() }
    override suspend fun allArtists(): List<Artist> = client.library(8, size = 500).items.map { it.artist() }
    override suspend fun allPlaylists(): List<Playlist> = client.playlists().items.map { it.playlist() }
    override suspend fun allSongs(): List<Song> = librarySongs(200)
    override suspend fun librarySongs(limit: Int): List<Song> = client.library(10, size = limit).items.map { it.song() }
    override suspend fun songsPage(offset: Int, count: Int): List<Song> =
        client.library(10, start = offset, size = count).items.map { it.song() }

    override suspend fun starredSongs(): List<Song> = librarySongs(2000).filter { it.liked }
    override suspend fun starredCount(): Int = starredSongs().size
    override suspend fun starredIds(): Set<String> = starredSongs().map { it.id }.toSet()
    override suspend fun songFor(id: String): Song? = client.metadata(id)?.takeIf { it.text("type") == "track" }?.song()

    override suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val items = client.search(query.trim()).items
        return SearchResults(
            songs = items.filter { it.text("type") == "track" }.map { it.song() },
            albums = items.filter { it.text("type") == "album" }.map { it.album() },
            artists = items.filter { it.text("type") == "artist" }.map { it.artist() },
            playlists = items.filter { it.text("type") == "playlist" }.map { it.playlist() },
        )
    }

    override suspend fun scrobble(id: String) { runCatching { client.scrobble(id) } }
    override suspend fun radio(seedId: String): List<Song> = librarySongs(200).shuffled().take(30)
    override suspend fun createPlaylist(name: String): Boolean = false
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean = false
    override suspend fun deletePlaylist(id: String): Boolean = false
    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = client.rate(id, starred)

    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val tracks = starredSongs()
            return DetailData(DetailInfo("Liked Songs", "Plex", tracks.firstOrNull()?.artworkUrl.orEmpty(),
                accentFor(id), false, tracks.size, "Liked"), tracks)
        }
        val item = client.metadata(id) ?: return null
        return when (kind) {
            "album" -> {
                val tracks = allChildren(id).filter { it.text("type") == "track" }.map { it.song() }
                DetailData(DetailInfo(item.text("title"), item.text("parentTitle"),
                    client.url(item.text("thumb")), accentFor(id), false, tracks.size, "Album"), tracks)
            }
            "artist" -> {
                val albums = allChildren(id).filter { it.text("type") == "album" }.map { it.album() }
                DetailData(DetailInfo(item.text("title"), "${albums.size} albums", client.url(item.text("thumb")),
                    accentFor(id), true, albums.sumOf { it.songCount }, "Artist"), emptyList(), albums)
            }
            "playlist" -> {
                val tracks = allChildren(id, playlist = true).filter { it.text("type") == "track" }.map { it.song() }
                DetailData(DetailInfo(item.text("title"), "${tracks.size} songs", client.url(item.text("thumb")),
                    accentFor(id), false, tracks.size, "Playlist"), tracks)
            }
            else -> null
        }
    }

    override suspend fun collectionTracks(kind: String, id: String): List<Song> = when (kind) {
        "artist" -> allChildren(id).filter { it.text("type") == "album" }
            .flatMap { album -> allChildren(album.text("ratingKey")).filter { it.text("type") == "track" }.map { it.song() } }
        "album" -> allChildren(id).filter { it.text("type") == "track" }.map { it.song() }
        "playlist" -> allChildren(id, playlist = true).filter { it.text("type") == "track" }.map { it.song() }
        else -> super.collectionTracks(kind, id)
    }

    override suspend fun serverLyrics(song: Song): Lyrics? = null
    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String = client.url(streams[songId])
    override suspend fun downloadUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        if (streams[songId] == null) songFor(songId)
        return streamUrl(songId, maxBitrate, lossless)
    }
    override fun coverArtUrl(id: String, size: Int): String =
        client.url(artwork[id] ?: "/library/metadata/$id/thumb")
}
