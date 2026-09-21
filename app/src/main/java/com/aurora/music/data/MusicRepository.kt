package com.aurora.music.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import java.io.File

data class HomeData(
    val newReleases: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(),
    val mostPlayed: List<Album> = emptyList(),
    val random: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val starred: List<Song> = emptyList(),
    val sections: List<HomeFeedSection> = emptyList(),
    val continuation: String? = null,
)

data class HomeFeedSection(val id: String, val title: String, val subtitle: String = "", val items: List<HomeFeedItem>)

sealed interface HomeFeedItem {
    data class Track(val song: Song) : HomeFeedItem
    data class Record(val album: Album) : HomeFeedItem
    data class Collection(val playlist: Playlist) : HomeFeedItem
    data class Performer(val artist: Artist) : HomeFeedItem

    val key: String get() = when (this) {
        is Track -> "song:${song.id}"
        is Record -> "album:${album.id}"
        is Collection -> "playlist:${playlist.id}"
        is Performer -> "artist:${artist.id}"
    }
}

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

data class DetailData(val info: DetailInfo, val tracks: List<Song>, val albums: List<Album> = emptyList())

data class FolderNode(val id: String, val name: String)

data class FolderContent(
    val id: String,
    val title: String,
    val folders: List<FolderNode> = emptyList(),
    val songs: List<Song> = emptyList(),
)

data class DownloadRow(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val accent: Color,
)

// server-agnostic facade online delegates to backend offline serves downloaded files
class MusicRepository(
    private val backendProvider: () -> MediaBackend?,
    private val downloadManager: DownloadManager,
    private val offlineProvider: () -> Boolean = { false },
    private val currentServerIdProvider: () -> String = { "" },
    private val smartPlaylistsProvider: () -> List<SmartPlaylist> = { emptyList() },
    private val smartEngine: SmartPlaylistEngine? = null,
    private val cachedSongsProvider: () -> List<Song> = { emptyList() },
) {
    private val backend: MediaBackend? get() = backendProvider()
    private val offline: Boolean get() = offlineProvider() && backend?.supportsOfflineBrowsing != true
    private data class CollectionKey(val source: MediaBackend, val kind: String, val id: String)
    private val collectionIdentities = LinkedHashMap<CollectionKey, PlaybackCollectionIdentity>()
    private var identityDownloads: Map<String, DownloadedSong>? = null
    private var downloadsByUri: Map<String, DownloadedSong> = emptyMap()

    @Synchronized private fun downloadedCopy(song: Song): DownloadedSong? {
        val latest = downloadManager.downloads.value
        if (identityDownloads !== latest) {
            downloadsByUri = latest.values.groupBy { fileUri(it.audioPath) }.mapNotNull { (uri, copies) ->
                copies.singleOrNull()?.let { uri to it }
            }.toMap()
            identityDownloads = latest
        }
        return downloadsByUri[song.streamUrl]
    }

    fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? {
        downloadedCopy(song)?.let { return (it.playbackSource ?: PlaybackSourceIdentity()).copy(source = com.aurora.music.data.rules.RuleSource.DOWNLOAD) }
        song.playbackSource?.let { return it }
        val source = when {
            song.isRadio() -> com.aurora.music.data.rules.RuleSource.RADIO
            song.isPodcast() -> com.aurora.music.data.rules.RuleSource.PODCAST
            song.streamUrl.startsWith("file:") || song.streamUrl.startsWith("content:") -> com.aurora.music.data.rules.RuleSource.LOCAL_FILE
            song.streamUrl.startsWith("http:") || song.streamUrl.startsWith("https:") || song.streamUrl.startsWith("aurora-yt:") -> com.aurora.music.data.rules.RuleSource.STREAM
            else -> return null
        }
        return PlaybackSourceIdentity(source = source)
    }

    private fun tag(song: Song, source: MediaBackend): Song {
        val identity = if (downloadedCopy(song) != null || song.playbackSource != null) playbackSourceIdentity(song)
            else source.playbackSourceIdentity(song)
        return song.copy(playbackSource = identity)
    }

    private suspend fun sourceSongs(block: suspend (MediaBackend) -> List<Song>): List<Song> {
        val source = backend ?: return emptyList()
        return block(source).map { tag(it, source) }
    }

    @Synchronized private fun rememberCollection(source: MediaBackend, kind: String, id: String, identity: PlaybackCollectionIdentity?) {
        if (identity == null) return
        collectionIdentities[CollectionKey(source, kind, id)] = identity
        while (collectionIdentities.size > 128) collectionIdentities.remove(collectionIdentities.keys.first())
    }

    @Synchronized fun playbackCollectionIdentity(kind: String, id: String): PlaybackCollectionIdentity? {
        if (kind == "smart") {
            val smart = smartPlaylistsProvider().firstOrNull { it.id == id } ?: return null
            return PlaybackCollectionIdentity(PlaybackSourceIdentity.scoped("aurora-smart", "playlist", id), smart.name)
        }
        if (kind != "playlist") return null
        if (offline) return downloadManager.collections.value.filter { it.kind == kind && it.id == id }.singleOrNull()?.let {
            it.playbackCollection ?: PlaybackCollectionIdentity(name = it.title)
        }
        return backend?.let { collectionIdentities[CollectionKey(it, kind, id)] }
    }

    // offline shows every servers downloads online scopes to the active server
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

    private fun offlineSongs(): List<Song> {
        val downloads = downloadedSongs()
        val identities = downloads.map { it.playbackSource?.providerId to it.id }.toSet()
        val cached = cachedSongsProvider().filter { (it.playbackSource?.providerId to it.id) !in identities &&
            downloads.none { download -> download.id == "cached:${it.streamUrl.substringAfter("://")}" } }
            .map { it.copy(id = "cached:${it.streamUrl.substringAfter("://")}") }
        return (downloads + cached)
            .sortedBy { it.title }
    }

    private fun offlineAlbums(): List<Album> = offlineSongs().filter { it.albumId.isNotBlank() }
        .groupBy { it.playbackSource?.providerId to it.albumId }.map { (_, songs) ->
            val first = songs.first()
            Album(first.playbackSource?.albumId ?: first.albumId, first.album, first.artist, first.artworkUrl, 0, songs.size,
                durationSec = songs.sumOf { it.durationSec })
        }.sortedBy { it.title }

    private fun fileUri(path: String): String = if (path.isBlank()) "" else Uri.fromFile(File(path)).toString()

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
            Album(id = albumId, title = first.album.ifBlank { "Album" }, artist = first.artist, artworkUrl = first.toSong().artworkUrl, year = 0, songCount = songs.size, durationSec = songs.sumOf { it.durationSec })
        }
        .sortedBy { it.title }

    val homeFeeds: List<HomeFeedChoice> get() = if (offline) emptyList() else backend?.homeFeeds.orEmpty()

    suspend fun playbackCandidates(song: Song): List<Song> = if (offline) emptyList() else backend?.playbackCandidates(song).orEmpty()

    suspend fun home(feed: String = "library"): HomeData {
        if (offline) {
            val albums = offlineAlbums()
            return HomeData(newReleases = albums, recentlyPlayed = albums, starred = offlineSongs())
        }
        val source = backend ?: return HomeData()
        return tagHome(source.home(feed), source)
    }

    suspend fun homePage(continuation: String, feed: String = "library"): HomeData {
        if (offline) return HomeData()
        val source = backend ?: return HomeData()
        return tagHome(source.homePage(feed, continuation), source)
    }

    private fun tagHome(data: HomeData, source: MediaBackend) = data.copy(
        starred = data.starred.map { tag(it, source) },
        sections = data.sections.map { section -> section.copy(items = section.items.map {
            if (it is HomeFeedItem.Track) HomeFeedItem.Track(tag(it.song, source)) else it
        }) },
    )

    suspend fun allAlbums(): List<Album> =
        if (offline) offlineAlbums() else backend?.allAlbums().orEmpty()

    suspend fun allArtists(): List<Artist> =
        if (offline) emptyList() else backend?.allArtists().orEmpty()

    suspend fun allPlaylists(): List<Playlist> =
        if (offline) emptyList() else backend?.allPlaylists().orEmpty()

    suspend fun allSongs(): List<Song> =
        if (offline) offlineSongs() else sourceSongs { it.allSongs() }

    suspend fun librarySongs(limit: Int = 2000): List<Song> =
        if (offline) offlineSongs() else sourceSongs { it.librarySongs(limit) }

    suspend fun songsPage(offset: Int, count: Int = 100): List<Song> =
        if (offline) offlineSongs().drop(offset).take(count) else sourceSongs { it.songsPage(offset, count) }

    // walks the whole library via the paging path (guaranteed to traverse every source, unlike a
    // one-shot request which some servers cap). deduped, capped so a pathological library can't run away.
    suspend fun allLibrarySongs(cap: Int = 10000, pageSize: Int = 200): List<Song> {
        if (offline) return offlineSongs()
        val b = backend ?: return emptyList()
        val out = LinkedHashMap<String, Song>()
        var offset = 0
        while (out.size < cap) {
            val chunk = runCatching { b.songsPage(offset, pageSize) }.getOrDefault(emptyList())
            if (chunk.isEmpty()) break
            val before = out.size
            for (s in chunk) if (s.id.isNotEmpty()) out.putIfAbsent(s.id, tag(s, b))
            if (out.size == before) break // malformed servers that ignore paging must not loop forever
            offset += pageSize
        }
        return out.values.toList()
    }

    suspend fun starredSongs(): List<Song> = sourceSongs { it.starredSongs() }

    suspend fun starredCount(): Int = if (offline) starredSongs().size else (backend?.starredCount() ?: 0)

    suspend fun starredIds(): Set<String> = backend?.starredIds() ?: emptySet()

    suspend fun likedSongIds(ids: List<String>): Set<String> =
        if (offline) emptySet() else backend?.likedSongIds(ids).orEmpty()

    suspend fun profileImageUrl(): String = if (offline) "" else backend?.profileImageUrl().orEmpty()

    suspend fun songFor(id: String): Song? {
        downloadManager.get(id)?.let { return it.toSong() }
        if (offline) return offlineSongs().singleOrNull { it.id == id }
        val source = backend ?: return null
        return source.songFor(id)?.let { tag(it, source) }
    }

    val searchSources: List<SearchSourceChoice> get() = if (offline) emptyList() else backend?.searchSources.orEmpty()

    suspend fun enrichSearchDurations(results: SearchResults): SearchResults = kotlinx.coroutines.coroutineScope {
        val source = backend ?: return@coroutineScope results
        if (offline) return@coroutineScope results
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val durations = results.songs.filter { it.durationSec <= 0 }.distinctBy { it.id }.take(20).map { song ->
            async {
                gate.acquire()
                try {
                    val duration = kotlinx.coroutines.withTimeoutOrNull(4_000) { source.songFor(song.id)?.durationSec } ?: 0
                    song.id to duration
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { song.id to 0 }
                finally { gate.release() }
            }
        }.awaitAll().toMap()
        results.copy(songs = results.songs.map { song ->
            durations[song.id]?.takeIf { it > 0 }?.let { song.copy(durationSec = it) } ?: song
        })
    }

    suspend fun search(query: String): SearchResults = search(query, "discovery")

    suspend fun search(query: String, sourceId: String): SearchResults {
        if (offline) {
            val q = query.trim()
            val songs = offlineSongs().filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
            val albums = downloadedAlbums().filter { it.title.contains(q, true) || it.artist.contains(q, true) }
            return SearchResults(songs = songs, albums = albums, artists = emptyList())
        }
        val source = backend ?: return SearchResults()
        return source.search(query, sourceId).let { it.copy(songs = it.songs.map { song -> tag(song, source) }) }
    }

    suspend fun scrobble(id: String) {
        if (offline) return
        backend?.scrobble(backendSongId(id) ?: return)
    }

    private fun backendSongId(id: String): String? {
        if (!id.startsWith("cached:")) return id
        val cached = cachedSongsProvider().singleOrNull { "cached:${it.streamUrl.substringAfter("://")}" == id } ?: return null
        val provider = cached.playbackSource?.providerId ?: return null
        if (backend?.playbackSourceIdentity(cached)?.providerId != provider) return null
        return cached.id
    }

    suspend fun radio(seedId: String): List<Song> {
        if (offline) return emptyList()
        return sourceSongs { it.radio(seedId) }
    }

    suspend fun createPlaylist(name: String): Boolean = backend?.createPlaylist(name) ?: false

    private val playlistChangeEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val playlistChanges: kotlinx.coroutines.flow.Flow<String> = playlistChangeEvents

    fun playlistEditor(song: Song): PlaylistMembershipEditor? {
        if (offline) return null
        val source = backend ?: return null
        val id = backendSongId(song.id) ?: return null
        val expectedProvider = source.playbackSourceIdentity(song.copy(id = id, playbackSource = null, streamUrl = ""))?.providerId
        if (song.playbackSource?.providerId != null && song.playbackSource.providerId != expectedProvider) return null
        return PlaylistMembershipEditor(source, id, isActive = { !offline && backend === source },
            onChanged = { playlistChangeEvents.tryEmit(it) })
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean =
        backend?.addToPlaylist(playlistId, trackIds) ?: false

    suspend fun createPlaylistFromSongs(name: String, trackIds: List<String>): Boolean {
        val b = backend ?: return false
        val id = b.createPlaylistWithId(name) ?: return false
        return if (trackIds.isNotEmpty()) b.addToPlaylist(id, trackIds) else true
    }

    suspend fun exportPlaylist(kind: String, id: String): String? =
        detail(kind, id)?.tracks?.takeIf { it.isNotEmpty() }?.let { M3u.write(it) }

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

    // playlists arent server-starrable handled locally by the caller
    suspend fun setStarred(id: String, starred: Boolean, kind: String = "song"): Boolean {
        if (offline) return false
        return backend?.setStarred(backendSongId(id) ?: return false, starred, kind) ?: false
    }

    suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "smart") {
            val sp = smartPlaylistsProvider().firstOrNull { it.id == id } ?: return null
            val tracks = smartEngine?.evaluate(sp, librarySongs()).orEmpty()
            return DetailData(
                DetailInfo(sp.name ?: "Smart playlist", "Smart playlist • ${tracks.size} songs", tracks.firstOrNull()?.artworkUrl ?: "", accentFor(id), false, tracks.size, "Smart playlist"),
                tracks,
            )
        }
        if (offline) {
            val dls = offlineSongs()
            return when (kind) {
                "album" -> dls.filter { it.albumId == id || it.playbackSource?.albumId == id }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    val f = tracks.first()
                    val label = com.aurora.music.model.releaseTypeLabel(com.aurora.music.model.inferReleaseType(tracks.size, tracks.sumOf { it.durationSec }))
                    DetailData(DetailInfo(f.album.ifBlank { "Album" }, "${f.artist} • Downloaded", f.artworkUrl, accentFor(id), false, tracks.size, label), tracks)
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
        val source = backend ?: return null
        val result = source.detail(kind, id) ?: return null
        rememberCollection(source, kind, id, source.playbackCollectionIdentity(kind, id, result.info.title))
        return result.copy(tracks = result.tracks.map { tag(it, source) })
    }

    suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
        if (offline) emptyList() else sourceSongs { it.detailPage(kind, id, offset) }

    suspend fun collectionTracks(kind: String, id: String): List<Song> =
        if (offline || kind == "smart") detail(kind, id)?.tracks.orEmpty()
        else sourceSongs { it.collectionTracks(kind, id) }

    val supportsFolders: Boolean get() = !offline && backend?.supportsFolders == true

    val supportsServerTagEdit: Boolean get() = !offline && backend?.supportsServerTagEdit == true

    suspend fun readMetadata(songId: String): AudioTags? =
        if (offline) null else backend?.readMetadata(songId)

    suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean =
        if (offline) false else backend?.updateMetadata(songId, tags) ?: false

    suspend fun browseFolder(folderId: String): FolderContent? {
        if (offline) return null
        val source = backend ?: return null
        return source.browseFolder(folderId)?.let { it.copy(songs = it.songs.map { song -> tag(song, source) }) }
    }
}
