package com.aurora.music.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor

private const val LOOSE_DOWNLOADS = "songs"

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

// a mood or genre a streaming catalogue curates playlists for; params open its page
data class ExploreCategory(val title: String, val params: String, val colorArgb: Long = 0)
data class ExploreGroup(val title: String, val categories: List<ExploreCategory>)
data class ExploreData(val groups: List<ExploreGroup> = emptyList(), val sections: List<HomeFeedSection> = emptyList())

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
    val accentArgb: Long,
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
    private val coverUrl: (String, String, String, String, Int) -> String = { original, _, _, _, _ -> original },
    // every account a song can be liked on; the app adds signed-in accounts outside the library
    private val likeSourcesProvider: suspend () -> List<MediaBackend> = { backendProvider()?.let { (it as? MergedBackend)?.sources ?: listOf(it) }.orEmpty() },
    private val trackChoices: TrackChoices? = null,
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
            song.streamUrl.startsWith("http:") || song.streamUrl.startsWith("https:") || song.streamUrl.startsWith("aurora-yt:") || song.streamUrl.startsWith("aurora-sc:") -> com.aurora.music.data.rules.RuleSource.STREAM
            else -> return null
        }
        return PlaybackSourceIdentity(source = source)
    }

    private fun artwork(song: Song): Song = song.copy(artworkUrl = coverUrl(song.artworkUrl, song.artist, song.album, song.title, song.durationSec))
    private fun artwork(album: Album): Album = album.copy(artworkUrl = coverUrl(album.artworkUrl, album.artist, album.title, "", 0))

    private fun tag(song: Song, source: MediaBackend): Song {
        val identity = if (downloadedCopy(song) != null || song.playbackSource != null) playbackSourceIdentity(song)
            else source.playbackSourceIdentity(song)
        return artwork(song.copy(playbackSource = identity))
    }

    private suspend fun sourceSongs(view: String? = null, block: suspend (MediaBackend) -> List<Song>): List<Song> {
        val source = viewOf(view) ?: return emptyList()
        return block(source).map { tag(it, source) }
    }

    val libraryViews: List<LibraryViewChoice> get() = if (offline) offlineSources().map { LibraryViewChoice(it.viewId, it.label) }
        else backend?.libraryViews.orEmpty()

    // null is the whole backend; a view narrows a composite one to the library the user picked
    private fun viewOf(view: String?): MediaBackend? = backend?.let { b -> if (view == null) b else b.libraryView(view) }

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
        return downloadManager.downloads.value.values.filter(::ownsDownload)
    }

    private fun visibleCollections(): List<DownloadedCollection> {
        return downloadManager.collections.value.filter(::ownsCollection)
    }

    private fun sourceOwner(id: String, albumId: String = ""): DownloadOwner? {
        val source = backend ?: return null
        val song = Song(id, "", "", "", "", 0, albumId = albumId)
        return DownloadOwner(source.playbackSourceIdentity(song)?.providerId, source.session.server)
    }

    private fun ownsDownload(download: DownloadedSong): Boolean = offline ||
        sourceOwner(download.id, download.albumId)?.matches(
            DownloadOwner(download.playbackSource?.providerId, download.serverId.orEmpty())) == true

    private fun ownsCollection(collection: DownloadedCollection): Boolean {
        if (offline) return true
        if (sourceOwner(collection.id)?.matches(DownloadOwner(collection.sourceProviderId, collection.serverId.orEmpty())) == true) return true
        val savedIdentity = collection.playbackCollection?.id ?: return false
        return savedIdentity == backend?.playbackCollectionIdentity(collection.kind, collection.id)?.id
    }

    fun downloadedIds(): Set<String> = visibleDownloads().mapTo(linkedSetOf()) { it.id }

    // likes learned online reach the active source's downloads; a starred list can be partial, so callers only add from one
    fun markDownloadsLiked(ids: Collection<String>, liked: Boolean) {
        if (offline) return
        downloadManager.markLiked(ids.filter { id -> downloadManager.get(id)?.let(::ownsDownload) == true }, liked)
    }

    fun isDownloaded(id: String): Boolean = downloadManager.get(id)?.let(::ownsDownload) == true

    fun removeDownload(id: String): Boolean {
        val download = downloadManager.get(id)?.takeIf(::ownsDownload) ?: return false
        return downloadManager.removeDownload(download)
    }

    fun removeDownloadedCollection(id: String, kind: String): Boolean {
        val collection = downloadManager.collections.value.firstOrNull { it.id == id && it.kind == kind }
            ?.takeIf(::ownsCollection) ?: return false
        return downloadManager.removeCollection(collection, ::ownsDownload)
    }

    fun downloadedSongs(): List<Song> = visibleDownloads()
        .sortedBy { it.title }.map { artwork(it.toSong(downloadManager.fileUri)) }

    // offline gets one view per source that has downloads; a single source needs no switch
    private fun offlineSources(): List<DownloadSource> =
        visibleDownloads().map(DownloadSource::of).distinct().sorted().takeIf { it.size > 1 }.orEmpty()

    private fun inView(download: DownloadedSong, view: String?): Boolean =
        DownloadSource.forView(view)?.let { it == DownloadSource.of(download) } ?: true

    // offline lists only what was downloaded; fully cached tracks still play when something asks for them by id
    private fun offlineSongs(view: String? = null): List<Song> = visibleDownloads().filter { inView(it, view) }
        .map { artwork(it.toSong(downloadManager.fileUri)) }.sortedBy { it.title }

    private fun cachedSongs(): List<Song> = cachedSongsProvider().map { it.copy(id = "cached:${it.streamUrl.substringAfter("://")}") }

    // downloads from liked songs, or of songs liked anywhere before they were saved
    private fun offlineLiked(view: String? = null): List<Song> = offlineSongs(view).filter { it.liked }

    private fun offlineArtists(view: String?): List<Artist> = offlineSongs(view).filter { it.artistId.isNotBlank() }
        .groupBy { it.artistId }.map { (id, songs) -> Artist(id, songs.first().artist, songs.first().artworkUrl, 0) }.sortedBy { it.name }

    private fun offlinePlaylists(view: String?): List<Playlist> {
        val byId = downloadManager.downloads.value
        return downloadManager.collections.value.filter { col ->
            col.kind == "playlist" && col.trackIds.firstNotNullOfOrNull { byId[it] }?.let { inView(it, view) } ?: (view == null)
        }.map { Playlist(it.id, it.title, it.subtitle, fileUri(it.coverPath), it.trackIds.size, accentArgbFor(it.id)) }
    }

    private fun offlineAlbums(view: String? = null): List<Album> = offlineSongs(view).filter { it.albumId.isNotBlank() }
        .groupBy { it.playbackSource?.providerId to it.albumId }.map { (_, songs) ->
            val first = songs.first()
            Album(first.playbackSource?.albumId ?: first.albumId, first.album, first.artist, first.artworkUrl, 0, songs.size,
                durationSec = songs.sumOf { it.durationSec })
        }.sortedBy { it.title }

    private fun fileUri(path: String): String = if (path.isBlank()) "" else downloadManager.fileUri(path)

    fun downloadedLibrary(): List<DownloadRow> {
        val collections = visibleCollections()
        val colRows = collections.map { DownloadRow(it.id, it.kind, it.title, it.subtitle, fileUri(it.coverPath), accentArgbFor(it.id)) }
        val recordedTracks = collections.flatMap { it.trackIds }.toSet()
        val colIds = collections.map { it.id }.toSet()
        val inferred = visibleDownloads()
            .filter { it.id !in recordedTracks && it.albumId.isNotBlank() && it.albumId !in colIds }
            .groupBy { it.albumId }
            .map { (aid, songs) ->
                val f = songs.first()
                DownloadRow(aid, "album", f.album.ifBlank { "Album" }, f.artist, fileUri(f.coverPath), accentArgbFor(aid))
            }
        val loose = looseDownloads(collections)
        val looseRow = loose.firstOrNull()?.let { listOf(DownloadRow(LOOSE_DOWNLOADS, "downloaded", "Downloaded songs", "${loose.size} tracks", fileUri(it.coverPath), accentArgbFor(LOOSE_DOWNLOADS))) }.orEmpty()
        return looseRow + (colRows + inferred).sortedBy { it.title }
    }

    // single tracks with no album or saved collection would otherwise have no row of their own
    private fun looseDownloads(collections: List<DownloadedCollection> = visibleCollections()): List<DownloadedSong> {
        val recorded = collections.flatMap { it.trackIds }.toSet()
        return visibleDownloads().filter { it.id !in recorded && it.albumId.isBlank() }
    }

    private fun downloadedAlbums(): List<Album> = visibleDownloads()
        .filter { it.albumId.isNotBlank() }
        .groupBy { it.albumId }
        .map { (albumId, songs) ->
            val first = songs.first()
            Album(id = albumId, title = first.album.ifBlank { "Album" }, artist = first.artist, artworkUrl = first.toSong(downloadManager.fileUri).artworkUrl, year = 0, songCount = songs.size, durationSec = songs.sumOf { it.durationSec })
        }
        .sortedBy { it.title }

    val homeFeeds: List<HomeFeedChoice> get() = if (offline) offlineSources().map { HomeFeedChoice(it.viewId, it.label) }
        else backend?.homeFeeds.orEmpty()

    suspend fun playbackCandidates(song: Song): List<Song> = if (offline) emptyList() else backend?.playbackCandidates(song).orEmpty().map { artwork(it) }

    suspend fun home(feed: String = "library"): HomeData {
        if (offline) {
            val albums = offlineAlbums(feed)
            return HomeData(newReleases = albums, recentlyPlayed = albums, starred = offlineSongs(feed))
        }
        val source = backend ?: return HomeData()
        return tagHome(source.home(feed), source)
    }

    val supportsExplore: Boolean get() = !offline && backend?.supportsExplore == true

    suspend fun explore(): ExploreData {
        val source = backend?.takeIf { !offline } ?: return ExploreData()
        val data = source.explore()
        return data.copy(sections = tagHome(HomeData(sections = data.sections), source).sections)
    }

    suspend fun exploreCategory(params: String): HomeData {
        val source = backend?.takeIf { !offline } ?: return HomeData()
        return tagHome(source.exploreCategory(params), source)
    }

    suspend fun related(song: Song): HomeData {
        val source = backend?.takeIf { !offline && song.id.isNotEmpty() } ?: return HomeData()
        return tagHome(source.related(song), source)
    }

    suspend fun homePage(continuation: String, feed: String = "library"): HomeData {
        if (offline) return HomeData()
        val source = backend ?: return HomeData()
        return tagHome(source.homePage(feed, continuation), source)
    }

    private fun tagHome(data: HomeData, source: MediaBackend) = data.copy(
        newReleases = data.newReleases.map { artwork(it) },
        recentlyPlayed = data.recentlyPlayed.map { artwork(it) },
        mostPlayed = data.mostPlayed.map { artwork(it) },
        random = data.random.map { artwork(it) },
        starred = data.starred.map { tag(it, source) },
        sections = data.sections.map { section -> section.copy(items = section.items.map {
            when (it) {
                is HomeFeedItem.Track -> HomeFeedItem.Track(tag(it.song, source))
                is HomeFeedItem.Record -> HomeFeedItem.Record(artwork(it.album))
                else -> it
            }
        }) },
    )

    suspend fun allAlbums(view: String? = null): List<Album> =
        if (offline) offlineAlbums(view) else viewOf(view)?.allAlbums().orEmpty().map { artwork(it) }

    suspend fun allArtists(view: String? = null): List<Artist> =
        if (offline) offlineArtists(view) else viewOf(view)?.allArtists().orEmpty()

    suspend fun allPlaylists(view: String? = null): List<Playlist> =
        if (offline) offlinePlaylists(view) else viewOf(view)?.allPlaylists().orEmpty()

    suspend fun allSongs(): List<Song> =
        if (offline) offlineSongs() else sourceSongs { it.allSongs() }

    suspend fun librarySongs(limit: Int = 2000): List<Song> =
        if (offline) offlineSongs() else sourceSongs { it.librarySongs(limit) }

    suspend fun songsPage(offset: Int, count: Int = 100, view: String? = null): List<Song> =
        if (offline) offlineSongs(view).drop(offset).take(count) else sourceSongs(view) { it.songsPage(offset, count) }

    // walks the whole library via the paging path (guaranteed to traverse every source, unlike a
    // one-shot request which some servers cap). deduped, capped so a pathological library can't run away.
    suspend fun allLibrarySongs(cap: Int = 10000, pageSize: Int = 200, view: String? = null): List<Song> {
        if (offline) return offlineSongs(view)
        val b = viewOf(view) ?: return emptyList()
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

    suspend fun starredCount(view: String? = null): Int = if (offline) offlineLiked(view).size else (viewOf(view)?.starredCount() ?: 0)

    suspend fun starredIds(): Set<String> = backend?.starredIds() ?: emptySet()

    suspend fun likeSources(): List<MediaBackend> = if (offline) emptyList() else likeSourcesProvider()
    val activeSession: Session? get() = backend?.session

    fun supportsCanvas(song: Song): Boolean = !offline && backend?.supportsCanvas(song) == true
    suspend fun canvasUrl(song: Song): String? = if (offline) null else backend?.canvasUrl(song)

    fun supportsComments(song: Song): Boolean = !offline && backend?.supportsComments(song) == true
    fun canPostComments(song: Song): Boolean = !offline && backend?.canPostComments(song) == true

    fun supportsReposts(id: String): Boolean = !offline && backendSongId(id)?.let { backend?.supportsReposts(it) } == true
    suspend fun isReposted(id: String): Boolean = supportsReposts(id) && backend?.isReposted(backendSongId(id) ?: id) == true
    suspend fun setReposted(id: String, reposted: Boolean): Boolean =
        supportsReposts(id) && backend?.setReposted(backendSongId(id) ?: id, reposted) == true

    // guest sources hide likes, playlist edits and their personal library
    fun canLike(id: String): Boolean = id.isNotEmpty() && !id.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX) && backend?.signedIn(backendSongId(id) ?: id) != false
    fun signedIn(view: String? = null): Boolean = viewOf(view)?.signedIn != false
    fun sourceType(song: Song): ServerType? = backend?.sourceType(song)
    suspend fun comments(song: Song): List<com.aurora.music.model.TrackComment>? = if (offline) null else backend?.comments(song)
    suspend fun postComment(song: Song, body: String, timestampMs: Long): com.aurora.music.model.TrackComment? =
        if (offline || body.isBlank()) null else backend?.postComment(song, body.trim(), timestampMs)

    suspend fun likedSongIds(ids: List<String>): Set<String> =
        if (offline) emptySet() else backend?.likedSongIds(ids).orEmpty()

    suspend fun profileImageUrl(): String = if (offline) "" else backend?.profileImageUrl().orEmpty()

    suspend fun songFor(id: String): Song? {
        val source = backend
        downloadManager.get(id)?.let { download ->
            val song = download.toSong(downloadManager.fileUri)
            val storedProvider = download.playbackSource?.providerId
            val requestedProvider = source?.playbackSourceIdentity(song.copy(playbackSource = null, streamUrl = ""))?.providerId
            val sameSource = if (storedProvider != null) storedProvider == requestedProvider
                else !download.serverId.isNullOrBlank() && download.serverId == source?.session?.server
            if (offline || sameSource) return artwork(song)
        }
        if (offline) return (offlineSongs() + cachedSongs()).firstOrNull { it.id == id }
        if (source == null) return null
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
        return source.search(query, sourceId).let { it.copy(songs = it.songs.map { song -> tag(song, source) }, albums = it.albums.map { album -> artwork(album) }) }
    }

    suspend fun scrobble(id: String) {
        if (offline) return
        backend?.scrobble(backendSongId(id) ?: return)
    }

    fun playbackReportTarget(song: Song): PlaybackReportTarget? {
        if (offline || song.isRadio() || song.isPodcast() || song.id.startsWith("aurora-mix:")) return null
        val source = backend ?: return null
        val original = if (song.id.startsWith("cached:")) {
            cachedSongsProvider().singleOrNull { "cached:${it.streamUrl.substringAfter("://")}" == song.id } ?: return null
        } else song
        return source.playbackReportTarget(original)
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
        val id = backendSongId(seedId) ?: return emptyList()
        return sourceSongs { it.radio(id) }
    }

    fun prefersServerRadio(seedId: String): Boolean {
        if (offline) return false
        val id = backendSongId(seedId) ?: return false
        return backend?.prefersServerRadio(id) == true
    }

    // a library view creates in that library's own account, or in the self-hosted one picked from its targets
    suspend fun createPlaylist(name: String, view: String? = null, description: String = "", target: String? = null): Boolean {
        val source = viewOf(view) ?: return false
        val id = (if (target != null) source.createPlaylistIn(target, name) else source.createPlaylistWithId(name)) ?: return false
        if (description.isNotBlank() && !source.updatePlaylist(id, null, description)) {
            com.aurora.music.util.AppLog.w("Playlists", "created $id but its description was not saved")
        }
        playlistChangeEvents.tryEmit(id)
        return true
    }

    fun playlistTargets(view: String? = null): List<PlaylistTarget> = if (offline) emptyList() else viewOf(view)?.playlistTargets.orEmpty()

    private val playlistChangeEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val playlistChanges: kotlinx.coroutines.flow.Flow<String> = playlistChangeEvents

    fun playlistEditor(song: Song): PlaylistMembershipEditor? {
        if (offline) return null
        val source = backend ?: return null
        val id = backendSongId(song.id) ?: return null
        // a guest on its own has no playlists; merged, its songs still go in local ones
        if (!source.signedIn(id) && source.libraryViews.isEmpty()) return null
        val expectedProvider = source.playbackSourceIdentity(song.copy(id = id, playbackSource = null, streamUrl = ""))?.providerId
        if (song.playbackSource?.providerId != null && song.playbackSource.providerId != expectedProvider) return null
        return PlaylistMembershipEditor(source, id, song.copy(id = id), isActive = { !offline && backend === source },
            onChanged = { playlistChangeEvents.tryEmit(it) })
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        val added = backend?.addToPlaylist(playlistId, trackIds) ?: false
        if (added) playlistChangeEvents.tryEmit(playlistId)
        return added
    }

    suspend fun createPlaylistFromSongs(name: String, trackIds: List<String>): Boolean {
        val b = backend ?: return false
        val id = b.createPlaylistWithId(name, trackIds) ?: return false
        playlistChangeEvents.tryEmit(id)
        return true
    }

    suspend fun exportPlaylist(kind: String, id: String): String? =
        detail(kind, id)?.tracks?.takeIf { it.isNotEmpty() }?.let { M3u.write(it) }

    // each track is looked for in your own sources first, then the catalogues; a mixed local playlist keeps every match
    suspend fun importTracks(list: ImportedPlaylist, extra: List<ImportStage>, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        if (offline) throw ImportException(ImportFailure.OFFLINE)
        if (list.tracks.isEmpty()) throw ImportException(ImportFailure.EMPTY)
        val source = backend ?: throw ImportException(ImportFailure.NOT_SAVED)
        val own = source.importStages()
        val stages = own + extra.filter { e -> own.none { it.source == e.source } }
        val matches = PlaylistMatcher.matchAll(list.tracks, stages) { done, total -> onProgress(ImportProgress.Matching(done, total)) }
        if (backend !== source || offline) throw ImportException(ImportFailure.NOT_SAVED)
        onProgress(ImportProgress.Saving)
        val name = list.name.trim()
        var kept = matches.filter { it.song != null }
        var ownOnly = false
        val id = source.createMixedPlaylist(name, kept.map { it.song!! }) ?: run {
            ownOnly = kept.any { it.stage != 0 }
            kept = kept.filter { it.stage == 0 }
            source.createPlaylistWithId(name, kept.map { it.song!!.id })
        } ?: throw ImportException(ImportFailure.NOT_SAVED)
        playlistChangeEvents.tryEmit(id)
        val found = kept.groupingBy { it.source ?: ImportSource.OTHER }.eachCount()
        val missing = matches.filter { m -> kept.none { it === m } }.map { it.track }
        return ImportResult(id, name, list.tracks.size, found, missing, ownOnly)
    }

    suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean {
        val updated = backend?.updatePlaylist(id, name, comment) ?: false
        if (updated) playlistChangeEvents.tryEmit(id)
        return updated
    }

    suspend fun deletePlaylist(id: String): Boolean {
        val deleted = backend?.deletePlaylist(id) ?: false
        if (deleted) playlistChangeEvents.tryEmit(id)
        return deleted
    }

    // playlists arent server-starrable handled locally by the caller
    suspend fun setStarred(id: String, starred: Boolean, kind: String = "song"): Boolean {
        if (offline) return false
        return backend?.setStarred(backendSongId(id) ?: return false, starred, kind) ?: false
    }

    suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "downloaded") {
            val tracks = looseDownloads().map { artwork(it.toSong(downloadManager.fileUri)) }
            return DetailData(DetailInfo("Downloaded songs", "Downloaded", tracks.firstOrNull()?.artworkUrl.orEmpty(), accentArgbFor(id), false, tracks.size, "Downloaded"), tracks)
        }
        if (kind == "smart") {
            val sp = smartPlaylistsProvider().firstOrNull { it.id == id } ?: return null
            val tracks = smartEngine?.evaluate(sp, librarySongs()).orEmpty()
            return DetailData(
                DetailInfo(sp.name ?: "Smart playlist", "Smart playlist • ${tracks.size} songs", tracks.firstOrNull()?.artworkUrl ?: "", accentArgbFor(id), false, tracks.size, "Smart playlist"),
                tracks,
            )
        }
        if (offline) {
            val dls = offlineSongs()
            return when (kind) {
                "liked" -> offlineLiked(id).let { tracks ->
                    DetailData(DetailInfo("Liked Songs", "${tracks.size} downloaded songs you love", tracks.firstOrNull()?.artworkUrl.orEmpty(),
                        accentArgbFor("liked"), false, tracks.size, "Liked"), tracks)
                }
                "album" -> dls.filter { it.albumId == id || it.playbackSource?.albumId == id }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    val f = tracks.first()
                    val label = com.aurora.music.model.releaseTypeLabel(com.aurora.music.model.inferReleaseType(tracks.size, tracks.sumOf { it.durationSec }))
                    DetailData(DetailInfo(f.album.ifBlank { "Album" }, "${f.artist} • Downloaded", f.artworkUrl, accentArgbFor(id), false, tracks.size, label), tracks)
                }
                "artist" -> dls.filter { it.artistId == id }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    DetailData(DetailInfo(tracks.first().artist, "${tracks.size} downloaded tracks", tracks.first().artworkUrl, accentArgbFor(id), true, tracks.size, "Artist"), tracks)
                }
                "playlist" -> downloadManager.collections.value.firstOrNull { it.id == id }?.let { col ->
                    val byId = downloadManager.downloads.value
                    val tracks = col.trackIds.mapNotNull { byId[it]?.toSong(downloadManager.fileUri) }
                    DetailData(DetailInfo(col.title, col.subtitle, fileUri(col.coverPath), accentArgbFor(id), false, tracks.size, "Playlist"), tracks)
                }
                else -> null
            }
        }
        val source = backend ?: return null
        val result = source.detail(kind, id) ?: return null
        rememberCollection(source, kind, id, source.playbackCollectionIdentity(kind, id, result.info.title))
        val tracks = result.tracks.map { tag(it, source) }
        val art = when (kind) {
            "album" -> coverUrl(result.info.artUrl, result.tracks.firstOrNull()?.artist.orEmpty(), result.info.title, "", 0)
            "playlist", "liked" -> result.info.artUrl.ifBlank { tracks.firstOrNull()?.artworkUrl.orEmpty() }
            else -> result.info.artUrl
        }
        return result.copy(info = result.info.copy(artUrl = art), tracks = tracks, albums = result.albums.map { artwork(it) })
    }

    suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
        if (offline) emptyList() else sourceSongs { it.detailPage(kind, id, offset) }

    suspend fun collectionTracks(kind: String, id: String): List<Song> = playable(kind, id,
        if (offline || kind == "smart") detail(kind, id)?.tracks.orEmpty()
        else sourceSongs { it.collectionTracks(kind, id) })

    // songs hidden in a playlist stay listed but are left out when it plays
    fun playable(kind: String, id: String, songs: List<Song>, picked: Song? = null): List<Song> =
        trackChoices?.visible(kind, id, songs, picked) ?: songs

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
