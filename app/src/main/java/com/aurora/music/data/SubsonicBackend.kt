package com.aurora.music.data

import com.aurora.music.data.remote.AlbumDto
import com.aurora.music.data.remote.ArtistDto
import com.aurora.music.data.remote.PlaylistDto
import com.aurora.music.data.remote.SongDto
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.LyricLine
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

class SubsonicBackend(
    private val client: SubsonicClient,
    private val maxBitrateProvider: () -> Int,
    private val localize: (Song) -> Song,
) : MediaBackend {

    override val session: Session get() = client.session

    private val c: SubsonicClient get() = client

    private fun SongDto.toModel(): Song {
        val bitrate = maxBitrateProvider()
        val base = Song(
            id = id,
            title = title,
            artist = artist ?: "Unknown artist",
            album = album ?: "",
            artworkUrl = c.coverArtUrl(coverArt ?: id),
            durationSec = duration,
            liked = starred != null,
            explicit = explicitStatus.equals("explicit", true),
            accent = accentFor(id),
            streamUrl = streamUrl(id, bitrate, bitrate == 0),
            albumId = albumId ?: parent ?: "",
            artistId = artistId ?: "",
            suffix = suffix ?: "",
            bitrateKbps = bitRate,
            sampleRateHz = samplingRate,
            bitDepth = bitDepth,
            replayGainTrack = replayGain?.trackGain?.toFloat() ?: 0f,
            replayGainAlbum = replayGain?.albumGain?.toFloat() ?: 0f,
            path = path ?: "",
        )
        return localize(base)
    }

    private fun AlbumDto.toModel(): Album = Album(
        id = id,
        title = name,
        artist = displayArtist ?: artist ?: "Unknown artist",
        artworkUrl = c.coverArtUrl(coverArt ?: id),
        year = year,
        songCount = songCount,
    )

    private fun ArtistDto.toModel(): Artist = Artist(
        id = id,
        name = name,
        imageUrl = c.coverArtUrl(coverArt ?: id),
        monthlyListeners = albumCount.toLong(),
    )

    private fun PlaylistDto.toModel(): Playlist = Playlist(
        id = id,
        title = name,
        subtitle = comment?.takeIf { it.isNotBlank() } ?: "$songCount songs",
        coverUrl = c.coverArtUrl(coverArt ?: id),
        songCount = songCount,
        accent = accentFor(id),
    )

    override suspend fun ping(): Boolean =
        runCatching { c.api.ping().response.isOk }.getOrDefault(false)

    private suspend fun albumList(type: String, size: Int = 20): List<Album> = runCatching {
        c.api.getAlbumList2(type, size).response.albumList2?.album?.map { it.toModel() }.orEmpty()
    }.getOrDefault(emptyList())

    override suspend fun home(): HomeData {
        val newReleases = albumList("newest", 12)
        val recent = albumList("recent", 12)
        val frequent = albumList("frequent", 12)
        val random = albumList("random", 12)
        val playlists = runCatching {
            c.api.getPlaylists().response.playlists?.playlist?.map { it.toModel() }.orEmpty()
        }.getOrDefault(emptyList())
        val artists = runCatching {
            c.api.getArtists().response.artists?.index?.flatMap { it.artist }?.map { it.toModel() }?.take(20).orEmpty()
        }.getOrDefault(emptyList())
        val starred = runCatching {
            c.api.getStarred2().response.starred2?.song?.map { it.toModel() }.orEmpty()
        }.getOrDefault(emptyList())
        return HomeData(newReleases, recent, frequent, random, playlists, artists, starred)
    }

    override suspend fun allAlbums(): List<Album> = albumList("alphabeticalByName", 500)

    override suspend fun allArtists(): List<Artist> = runCatching {
        c.api.getArtists().response.artists?.index?.flatMap { it.artist }?.map { it.toModel() }.orEmpty()
    }.getOrDefault(emptyList())

    override suspend fun allPlaylists(): List<Playlist> = runCatching {
        c.api.getPlaylists().response.playlists?.playlist?.map { it.toModel() }.orEmpty()
    }.getOrDefault(emptyList())

    override suspend fun allSongs(): List<Song> = runCatching {
        c.api.getRandomSongs(200).response.randomSongs?.song?.map { it.toModel() }.orEmpty()
    }.getOrDefault(emptyList())

    // navidrome returns the whole library for an empty search3 query
    override suspend fun librarySongs(limit: Int): List<Song> = runCatching {
        c.api.search3("", artistCount = 0, albumCount = 0, songCount = limit)
            .response.searchResult3?.song?.map { it.toModel() }.orEmpty()
    }.getOrDefault(emptyList()).ifEmpty { allSongs() }

    override suspend fun starredSongs(): List<Song> = runCatching {
        c.api.getStarred2().response.starred2?.song?.map { it.toModel() }.orEmpty()
    }.getOrDefault(emptyList())

    override suspend fun starredCount(): Int = runCatching { starredSongs().size }.getOrDefault(0)

    override suspend fun starredIds(): Set<String> = runCatching {
        val s = c.api.getStarred2().response.starred2 ?: return emptySet()
        (s.song.map { it.id } + s.album.map { it.id } + s.artist.map { it.id }).toSet()
    }.getOrDefault(emptySet())

    override suspend fun songFor(id: String): Song? =
        runCatching { c.api.getSong(id).response.song?.toModel() }.getOrNull()

    override suspend fun search(query: String): SearchResults = runCatching {
        val r = c.api.search3(query).response.searchResult3
        SearchResults(
            songs = r?.song?.map { it.toModel() }.orEmpty(),
            albums = r?.album?.map { it.toModel() }.orEmpty(),
            artists = r?.artist?.map { it.toModel() }.orEmpty(),
        )
    }.getOrDefault(SearchResults())

    override suspend fun scrobble(id: String) {
        runCatching { c.api.scrobble(id) }
    }

    override suspend fun radio(seedId: String): List<Song> {
        val similar = runCatching {
            c.api.getSimilarSongs2(seedId).response.similarSongs2?.song?.map { it.toModel() }.orEmpty()
        }.getOrDefault(emptyList())
        return similar.ifEmpty {
            runCatching { c.api.getRandomSongs(30).response.randomSongs?.song?.map { it.toModel() }.orEmpty() }.getOrDefault(emptyList())
        }
    }

    override suspend fun createPlaylist(name: String): Boolean =
        runCatching { c.api.createPlaylist(name).response.isOk }.getOrDefault(false)

    override suspend fun createPlaylistWithId(name: String): String? = runCatching {
        // older servers dont return the created playlist fall back to a lookup
        c.api.createPlaylist(name).response.playlist?.id
            ?: c.api.getPlaylists().response.playlists?.playlist?.lastOrNull { it.name == name }?.id
    }.getOrNull()

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean =
        runCatching { c.api.updatePlaylist(playlistId, songIdToAdd = trackIds).response.isOk }.getOrDefault(false)

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean =
        runCatching { c.api.updatePlaylist(id, name, comment).response.isOk }.getOrDefault(false)

    override suspend fun deletePlaylist(id: String): Boolean =
        runCatching { c.api.deletePlaylist(id).response.isOk }.getOrDefault(false)

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = runCatching {
        val resp = when (kind) {
            "album" -> if (starred) c.api.star(albumId = id) else c.api.unstar(albumId = id)
            "artist" -> if (starred) c.api.star(artistId = id) else c.api.unstar(artistId = id)
            else -> if (starred) c.api.star(id = id) else c.api.unstar(id = id)
        }
        resp.response.isOk
    }.getOrDefault(false)

    override suspend fun detail(kind: String, id: String): DetailData? = runCatching {
        when (kind) {
            "album" -> {
                val a = c.api.getAlbum(id).response.album ?: return null
                DetailData(
                    DetailInfo(a.name, "${a.displayArtist ?: a.artist ?: ""} • ${a.year}", c.coverArtUrl(a.coverArt ?: a.id), accentFor(a.id), false, a.songCount, "Album"),
                    a.song.map { it.toModel() },
                )
            }
            "artist" -> {
                val ar = c.api.getArtist(id).response.artist ?: return null
                val albumModels = ar.album.map { it.toModel() }
                val tracks = ar.album.take(6).flatMap { alb ->
                    runCatching { c.api.getAlbum(alb.id).response.album?.song.orEmpty() }.getOrDefault(emptyList())
                }.map { it.toModel() }
                DetailData(
                    DetailInfo(ar.name, "${ar.albumCount} albums · ${ar.album.sumOf { it.songCount }} tracks", c.coverArtUrl(ar.coverArt ?: ar.id), accentFor(ar.id), true, tracks.size, "Artist"),
                    tracks,
                    albumModels,
                )
            }
            "playlist" -> {
                val p = c.api.getPlaylist(id).response.playlist ?: return null
                DetailData(
                    DetailInfo(p.name, p.comment?.takeIf { it.isNotBlank() } ?: "${p.songCount} songs", c.coverArtUrl(p.coverArt ?: p.id), accentFor(p.id), false, p.songCount, "Playlist"),
                    p.entry.map { it.toModel() },
                )
            }
            "liked" -> {
                val songs = c.api.getStarred2().response.starred2?.song?.map { it.toModel() }.orEmpty()
                DetailData(
                    DetailInfo("Liked Songs", "${songs.size} songs you love", songs.firstOrNull()?.artworkUrl ?: "", accentFor("liked"), false, songs.size, "Liked"),
                    songs,
                )
            }
            else -> null
        }
    }.onFailure { android.util.Log.e("AuroraDetail", "subsonic detail($kind,$id) failed", it) }.getOrNull()

    override val supportsFolders: Boolean get() = true

    override suspend fun browseFolder(folderId: String): FolderContent? = runCatching {
        when {
            folderId.isBlank() -> {
                val mfs = c.api.getMusicFolders().response.musicFolders?.musicFolder.orEmpty()
                when {
                    mfs.size <= 1 -> indexLevel(mfs.firstOrNull()?.id, "Folders")
                    else -> FolderContent("", "Folders", mfs.map { FolderNode("mf:${it.id}", it.name ?: "Folder") })
                }
            }
            folderId.startsWith("mf:") -> indexLevel(folderId.removePrefix("mf:"), "Folders")
            else -> {
                val d = c.api.getMusicDirectory(folderId).response.directory ?: return@runCatching null
                FolderContent(
                    id = folderId,
                    title = d.name,
                    folders = d.child.filter { it.isDir }.map { FolderNode(it.id, it.title) },
                    songs = d.child.filter { !it.isDir }.map { it.toModel() },
                )
            }
        }
    }.onFailure { android.util.Log.e("AuroraFolders", "subsonic browseFolder($folderId) failed", it) }.getOrNull()

    private suspend fun indexLevel(musicFolderId: String?, title: String): FolderContent {
        val idx = c.api.getIndexes(musicFolderId).response.indexes
        return FolderContent(
            id = musicFolderId?.let { "mf:$it" } ?: "",
            title = title,
            folders = idx?.index.orEmpty().flatMap { it.artist }.map { FolderNode(it.id, it.name) },
            songs = idx?.child.orEmpty().filter { !it.isDir }.map { it.toModel() },
        )
    }

    override suspend fun serverLyrics(song: Song): Lyrics? {
        val list = runCatching {
            c.api.getLyricsBySongId(song.id).response.lyricsList?.structuredLyrics
        }.getOrNull().orEmpty().filter { it.line.isNotEmpty() }
        val best = list.firstOrNull { it.synced } ?: list.firstOrNull() ?: return null
        val lines = best.line.map { LyricLine(((it.start ?: 0L) / 1000L).toInt(), it.value) }
        return Lyrics(lines, best.synced, "Server")
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String =
        c.streamUrl(songId, maxBitrate, if (lossless) "raw" else null)

    override fun coverArtUrl(id: String, size: Int): String = c.coverArtUrl(id, size)
}
