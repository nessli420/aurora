package com.aurora.music.data

import com.aurora.music.data.remote.BaseItemDto
import com.aurora.music.data.remote.CreatePlaylistRequest
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.LyricLine
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

class JellyfinBackend(
    private val client: JellyfinClient,
    private val maxBitrateProvider: () -> Int,
    private val localize: (Song) -> Song,
) : MediaBackend {

    override val session: Session get() = client.session
    private val uid: String get() = client.userId

    private companion object {
        const val TICKS_PER_SEC = 10_000_000L
    }

    private fun BaseItemDto.imageId(): String? = when {
        ImageTags?.containsKey("Primary") == true -> Id
        !AlbumId.isNullOrBlank() -> AlbumId
        else -> Id
    }

    private fun BaseItemDto.toSong(): Song {
        val bitrate = maxBitrateProvider()
        val source = MediaSources?.firstOrNull()
        val audioStream = source?.MediaStreams?.firstOrNull { it.Type == "Audio" }
        val base = Song(
            id = Id,
            title = Name ?: "Unknown",
            artist = Artists?.firstOrNull() ?: AlbumArtist ?: "Unknown artist",
            album = Album ?: "",
            artworkUrl = client.coverArtUrl(imageId()),
            durationSec = ((RunTimeTicks ?: 0L) / TICKS_PER_SEC).toInt(),
            liked = UserData?.IsFavorite == true,
            explicit = false,
            accent = accentFor(Id),
            streamUrl = client.streamUrl(Id, bitrate, bitrate == 0),
            albumId = AlbumId ?: "",
            artistId = ArtistItems?.firstOrNull()?.Id ?: AlbumArtists?.firstOrNull()?.Id ?: "",
            suffix = source?.Container ?: "",
            bitrateKbps = (source?.Bitrate ?: 0) / 1000,
            sampleRateHz = audioStream?.SampleRate ?: 0,
            bitDepth = audioStream?.BitDepth ?: 0,
            path = Path ?: "",
        )
        return localize(base)
    }

    private fun BaseItemDto.toAlbum(): Album = Album(
        id = Id,
        title = Name ?: "Album",
        artist = AlbumArtist ?: Artists?.firstOrNull() ?: "Unknown artist",
        artworkUrl = client.coverArtUrl(Id),
        year = ProductionYear ?: 0,
        songCount = ChildCount ?: 0,
    )

    private fun BaseItemDto.toArtist(): Artist = Artist(
        id = Id,
        name = Name ?: "Artist",
        imageUrl = client.coverArtUrl(Id),
        monthlyListeners = 0L,
    )

    private fun BaseItemDto.toPlaylist(): Playlist = Playlist(
        id = Id,
        title = Name ?: "Playlist",
        subtitle = "${ChildCount ?: 0} songs",
        coverUrl = client.coverArtUrl(Id),
        songCount = ChildCount ?: 0,
        accent = accentFor(Id),
    )

    private suspend fun items(params: Map<String, String>): List<BaseItemDto> =
        runCatching { client.api.items(uid, params).Items }.getOrDefault(emptyList())

    private fun albumParams(sortBy: String, order: String, limit: Int, extra: Map<String, String> = emptyMap()) =
        mapOf("IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "SortBy" to sortBy, "SortOrder" to order, "Limit" to "$limit") + extra

    override suspend fun ping(): Boolean =
        runCatching { client.api.publicInfo().isSuccessful }.getOrDefault(false)

    override suspend fun home(): HomeData {
        val newReleases = items(albumParams("DateCreated", "Descending", 12)).map { it.toAlbum() }
        val recent = items(albumParams("DatePlayed", "Descending", 12, mapOf("Filters" to "IsPlayed"))).map { it.toAlbum() }
        val frequent = items(albumParams("PlayCount", "Descending", 12, mapOf("Filters" to "IsPlayed"))).map { it.toAlbum() }
        val random = items(albumParams("Random", "Ascending", 12)).map { it.toAlbum() }
        val playlists = items(mapOf("IncludeItemTypes" to "Playlist", "Recursive" to "true", "SortBy" to "SortName")).map { it.toPlaylist() }
        val artists = runCatching {
            client.api.artists(mapOf("UserId" to uid, "Recursive" to "true", "SortBy" to "SortName", "Limit" to "20")).Items
        }.getOrDefault(emptyList()).map { it.toArtist() }
        val starred = items(mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "Filters" to "IsFavorite", "Limit" to "50", "Fields" to "MediaSources")).map { it.toSong() }
        return HomeData(newReleases, recent, frequent, random, playlists, artists, starred)
    }

    override suspend fun allAlbums(): List<Album> =
        items(mapOf("IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "SortBy" to "SortName", "Limit" to "500")).map { it.toAlbum() }

    override suspend fun allArtists(): List<Artist> = runCatching {
        client.api.artists(mapOf("UserId" to uid, "Recursive" to "true", "SortBy" to "SortName")).Items
    }.getOrDefault(emptyList()).map { it.toArtist() }

    override suspend fun allPlaylists(): List<Playlist> =
        items(mapOf("IncludeItemTypes" to "Playlist", "Recursive" to "true", "SortBy" to "SortName")).map { it.toPlaylist() }

    override suspend fun allSongs(): List<Song> =
        items(mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "SortBy" to "Random", "Limit" to "200")).map { it.toSong() }

    override suspend fun librarySongs(limit: Int): List<Song> =
        items(mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "SortBy" to "SortName", "Limit" to "$limit", "Fields" to "MediaSources,Path")).map { it.toSong() }

    override suspend fun starredSongs(): List<Song> =
        items(mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "Filters" to "IsFavorite", "Fields" to "MediaSources")).map { it.toSong() }

    override suspend fun starredCount(): Int = runCatching { starredSongs().size }.getOrDefault(0)

    override suspend fun starredIds(): Set<String> =
        items(mapOf("IncludeItemTypes" to "Audio,MusicAlbum,MusicArtist", "Recursive" to "true", "Filters" to "IsFavorite")).map { it.Id }.toSet()

    override suspend fun songFor(id: String): Song? =
        runCatching { client.api.item(uid, id).toSong() }.getOrNull()

    override suspend fun search(query: String): SearchResults {
        val all = items(mapOf("SearchTerm" to query, "Recursive" to "true", "IncludeItemTypes" to "Audio,MusicAlbum,MusicArtist", "Limit" to "60"))
        return SearchResults(
            songs = all.filter { it.Type == "Audio" }.map { it.toSong() },
            albums = all.filter { it.Type == "MusicAlbum" }.map { it.toAlbum() },
            artists = all.filter { it.Type == "MusicArtist" }.map { it.toArtist() },
        )
    }

    override suspend fun scrobble(id: String) {
        runCatching { client.api.markPlayed(uid, id) }
    }

    override suspend fun radio(seedId: String): List<Song> {
        val similar = runCatching {
            client.api.similar(seedId, mapOf("userId" to uid, "limit" to "30")).Items
        }.getOrDefault(emptyList()).filter { it.Type == "Audio" }.map { it.toSong() }
        return similar.ifEmpty { allSongs().shuffled().take(30) }
    }

    override suspend fun createPlaylist(name: String): Boolean =
        runCatching { client.api.createPlaylist(CreatePlaylistRequest(name, uid)).Id != null }.getOrDefault(false)

    override suspend fun createPlaylistWithId(name: String): String? =
        runCatching { client.api.createPlaylist(CreatePlaylistRequest(name, uid)).Id }.getOrNull()

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean =
        runCatching { client.api.addToPlaylist(playlistId, trackIds.joinToString(","), uid).isSuccessful }.getOrDefault(false)

    // jellyfin has no playlist rename/comment endpoint so edit is a no-op
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean = false

    override suspend fun deletePlaylist(id: String): Boolean =
        runCatching { client.api.deleteItem(id).isSuccessful }.getOrDefault(false)

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = runCatching {
        val resp = if (starred) client.api.favorite(uid, id) else client.api.unfavorite(uid, id)
        resp.isSuccessful
    }.getOrDefault(false)

    override suspend fun detail(kind: String, id: String): DetailData? = runCatching {
        when (kind) {
            "album" -> {
                val a = client.api.item(uid, id)
                val tracks = items(mapOf("ParentId" to id, "IncludeItemTypes" to "Audio", "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "Fields" to "MediaSources")).map { it.toSong() }
                DetailData(
                    DetailInfo(a.Name ?: "Album", "${a.AlbumArtist ?: a.Artists?.firstOrNull() ?: ""} • ${a.ProductionYear ?: ""}", client.coverArtUrl(a.Id), accentFor(a.Id), false, tracks.size, "Album"),
                    tracks,
                )
            }
            "artist" -> {
                val ar = client.api.item(uid, id)
                val albums = items(mapOf("IncludeItemTypes" to "MusicAlbum", "ArtistIds" to id, "Recursive" to "true", "SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending")).map { it.toAlbum() }
                val tracks = items(mapOf("IncludeItemTypes" to "Audio", "ArtistIds" to id, "Recursive" to "true", "Limit" to "60", "Fields" to "MediaSources")).map { it.toSong() }
                DetailData(
                    DetailInfo(ar.Name ?: "Artist", "${albums.size} albums · ${tracks.size} tracks", client.coverArtUrl(ar.Id), accentFor(ar.Id), true, tracks.size, "Artist"),
                    tracks,
                    albums,
                )
            }
            "playlist" -> {
                val p = client.api.item(uid, id)
                val tracks = runCatching {
                    client.api.playlistItems(id, mapOf("userId" to uid, "Fields" to "MediaSources")).Items
                }.getOrDefault(emptyList()).map { it.toSong() }
                DetailData(
                    DetailInfo(p.Name ?: "Playlist", "${tracks.size} songs", client.coverArtUrl(p.Id), accentFor(p.Id), false, tracks.size, "Playlist"),
                    tracks,
                )
            }
            "liked" -> {
                val songs = starredSongs()
                DetailData(
                    DetailInfo("Liked Songs", "${songs.size} songs you love", songs.firstOrNull()?.artworkUrl ?: "", accentFor("liked"), false, songs.size, "Liked"),
                    songs,
                )
            }
            else -> null
        }
    }.onFailure { android.util.Log.e("AuroraDetail", "jellyfin detail($kind,$id) failed", it) }.getOrNull()

    override val supportsFolders: Boolean get() = true

    override suspend fun browseFolder(folderId: String): FolderContent? = runCatching {
        if (folderId.isBlank()) {
            // no parentid means root children are the user's library views
            val views = items(mapOf("SortBy" to "SortName"))
            val music = views.filter { it.CollectionType == "music" }
            val roots = music.ifEmpty { views.filter { it.IsFolder == true } }
            FolderContent("", "Folders", roots.map { FolderNode(it.Id, it.Name ?: "Folder") })
        } else {
            val children = items(mapOf("ParentId" to folderId, "SortBy" to "IsFolder,SortName", "Fields" to "MediaSources,Path"))
            val name = runCatching { client.api.item(uid, folderId).Name }.getOrNull() ?: "Folder"
            FolderContent(
                id = folderId,
                title = name,
                folders = children.filter { it.IsFolder == true && it.Type != "Audio" }.map { FolderNode(it.Id, it.Name ?: "Folder") },
                songs = children.filter { it.Type == "Audio" }.map { it.toSong() },
            )
        }
    }.onFailure { android.util.Log.e("AuroraFolders", "jellyfin browseFolder($folderId) failed", it) }.getOrNull()

    override val supportsServerTagEdit: Boolean get() = true

    override suspend fun readMetadata(songId: String): AudioTags? = runCatching {
        val o = client.api.itemRaw(uid, songId)
        fun str(key: String) = o.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        fun arr(key: String) = o.getAsJsonArray(key)?.mapNotNull { e -> e.takeIf { !it.isJsonNull }?.asString } ?: emptyList()
        fun int(key: String) = o.get(key)?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it > 0 }?.toString() ?: ""
        AudioTags(
            title = str("Name"),
            artist = arr("Artists").joinToString("; "),
            album = str("Album"),
            albumArtist = str("AlbumArtist"),
            genre = arr("Genres").joinToString("; "),
            year = int("ProductionYear"),
            trackNumber = int("IndexNumber"),
        )
    }.getOrNull()

    override suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean = runCatching {
        // post the full item back unabridged so provider ids and image tags aren't clobbered
        val obj = client.api.itemRaw(uid, songId)
        fun strArray(values: List<String>) = com.google.gson.JsonArray().apply { values.forEach { add(it) } }
        fun split(s: String) = s.split(';', ',').map { it.trim() }.filter { it.isNotBlank() }

        obj.addProperty("Name", tags.title)
        obj.addProperty("Album", tags.album)
        obj.addProperty("AlbumArtist", tags.albumArtist)
        obj.add("Artists", strArray(split(tags.artist)))
        obj.add("Genres", strArray(split(tags.genre)))
        tags.year.toIntOrNull()?.let { obj.addProperty("ProductionYear", it) }
        tags.trackNumber.toIntOrNull()?.let { obj.addProperty("IndexNumber", it) }

        client.api.updateItem(songId, obj).isSuccessful
    }.onFailure { android.util.Log.e("AuroraTagEdit", "jellyfin updateMetadata($songId) failed", it) }.getOrDefault(false)

    override suspend fun serverLyrics(song: Song): Lyrics? {
        val lines = runCatching { client.api.lyrics(song.id).Lyrics }.getOrNull()?.filter { !it.Text.isNullOrEmpty() }.orEmpty()
        if (lines.isEmpty()) return null
        val synced = lines.any { (it.Start ?: 0L) > 0L }
        val mapped = lines.map { LyricLine(((it.Start ?: 0L) / TICKS_PER_SEC).toInt(), it.Text ?: "") }
        return Lyrics(mapped, synced, "Jellyfin")
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String =
        client.streamUrl(songId, maxBitrate, lossless)

    override fun coverArtUrl(id: String, size: Int): String = client.coverArtUrl(id, size)
}
