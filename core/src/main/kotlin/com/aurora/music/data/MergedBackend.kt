package com.aurora.music.data

import com.aurora.music.data.merged.FeatureBorrower
import com.aurora.music.data.merged.MergeMatcher
import com.aurora.music.data.merged.SourceRouter
import com.aurora.music.data.merged.SourceRouter.Companion.SEP
import com.aurora.music.data.merged.SourceRouter.Companion.SOURCE_TIMEOUT_MS
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

// composite backend merging several live backends ids namespaced per source so calls route back
class MergedBackend(
    val sources: List<MediaBackend>,
    override val session: Session,
    private val priority: () -> List<String> = { DEFAULT_SOURCE_PRIORITY },
    // the downloads that could be this song, so a play doesn't walk all of them
    private val downloads: (Song) -> List<Song> = { emptyList() },
    private val catalogueMatch: () -> CatalogueMatchPrefs = { CatalogueMatchPrefs() },
    // a library view over some of the sources; it offers no further views or catalogue feeds
    private val nested: Boolean = false,
    // a source found down in one view stays skipped in the others; keyed by namespace because view indices differ
    private val downUntil: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
) : MediaBackend {

    private val router = SourceRouter(sources, session, nested, downUntil)
    private val matcher = MergeMatcher(router, priority, downloads, catalogueMatch)
    private val features = FeatureBorrower(router)

    private fun Song.wrap(i: Int) = router.wrap(this, i)
    private fun Album.wrap(i: Int) = router.wrap(this, i)
    private fun Artist.wrap(i: Int) = router.wrap(this, i)
    private fun Playlist.wrap(i: Int) = router.wrap(this, i)
    private fun HomeData.wrapHome(i: Int) = router.wrapHome(this, i)

    override val homeFeeds: List<HomeFeedChoice> get() = router.choices(::HomeFeedChoice, "library", "Library", "discovery")
    override val searchSources: List<SearchSourceChoice> get() = router.choices(::SearchSourceChoice, "library", "Local & servers", "discovery")
    override val libraryViews: List<LibraryViewChoice> get() = router.choices(::LibraryViewChoice, "library", "Local & servers", "youtube")

    private val views = HashMap<String, MediaBackend>()

    // views reuse the same namespaced ids, so anything they hand out still routes through this backend
    override fun libraryView(view: String): MediaBackend {
        if (nested) return this
        return synchronized(views) {
            views.getOrPut(view) {
                val picked = when (view) {
                    "library" -> sources.filterNot(router::isCatalogue)
                    else -> listOfNotNull(sources.getOrNull(router.catalogueFor(view)))
                }
                if (picked.isEmpty()) this else MergedBackend(picked, session, priority, downloads, catalogueMatch, nested = true, downUntil = downUntil)
            }
        }
    }

    override suspend fun home(feed: String): HomeData {
        val index = router.catalogueFor(feed)
        return if (index >= 0) sources[index].home().wrapHome(index) else home()
    }

    override suspend fun homePage(feed: String, continuation: String): HomeData {
        val index = router.catalogueFor(feed)
        return if (index >= 0) sources[index].homePage(continuation).wrapHome(index) else HomeData()
    }

    override suspend fun playbackCandidates(song: Song): List<Song> = matcher.playbackCandidates(song)
    override suspend fun sourceCopies(song: Song): List<SourceCopy> = matcher.copies(song)
    override suspend fun searchOwnSource(song: Song, query: String): List<Song> {
        val (i, _) = router.unwrap(song.id) ?: return search(query).songs
        return sources[i].search(query).songs.map { it.wrap(i) }
    }

    override fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? {
        song.playbackSource?.let { return it }
        val (index, id) = router.unwrap(song.id) ?: return null
        val album = router.unwrap(song.albumId)?.takeIf { it.first == index }?.second.orEmpty()
        return sources[index].playbackSourceIdentity(song.copy(id = id, albumId = album))
    }

    override fun playbackCollectionIdentity(kind: String, id: String, name: String?): PlaybackCollectionIdentity? {
        val (index, original) = router.unwrap(id) ?: return null
        return sources[index].playbackCollectionIdentity(kind, original, name)
    }

    override suspend fun ping(): Boolean = router.fanOut { listOf(it.ping()) }.any { it.firstOrNull() == true }

    private suspend fun sourceHome(idx: Int): HomeData? = if (router.isCatalogue(sources[idx])) null
        else router.reach(idx) { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { sources[idx].home() } }

    override suspend fun home(): HomeData = matcher.mergeHomes(coroutineScope { sources.indices.map { async { sourceHome(it) } }.awaitAll() })

    // what home(feed) returns, built up as each source answers; the last emission is the whole of it
    fun homeUpdates(feed: String): Flow<HomeData> = flow {
        if (router.catalogueFor(feed) >= 0) {
            emit(home(feed))
            return@flow
        }
        coroutineScope {
            val landed = Channel<Pair<Int, HomeData?>>(sources.size)
            sources.indices.forEach { idx -> launch { landed.send(idx to sourceHome(idx)) } }
            val homes = arrayOfNulls<HomeData>(sources.size)
            repeat(sources.size) { count ->
                val (idx, data) = landed.receive()
                homes[idx] = data
                if (data != null && count < sources.lastIndex) emit(matcher.mergeHomes(homes.asList()))
            }
            emit(matcher.mergeHomes(homes.asList()))
        }
    }

    override suspend fun allAlbums(): List<Album> = matcher.dedupAlbums(wrapAll(router.fanOut { it.allAlbums() }) { a, i -> a.wrap(i) })
    override suspend fun allArtists(): List<Artist> = matcher.dedupArtists(wrapAll(router.fanOut { it.allArtists() }) { a, i -> a.wrap(i) })
    override suspend fun allPlaylists(): List<Playlist> = wrapAll(router.fanOut { it.allPlaylists() }) { p, i -> p.wrap(i) }
    override suspend fun allSongs(): List<Song> = matcher.dedupSongs(wrapAll(router.fanOut { it.allSongs() }) { s, i -> s.wrap(i) })
    override suspend fun librarySongs(limit: Int): List<Song> = matcher.dedupSongs(wrapAll(router.fanOut { it.librarySongs(limit) }) { s, i -> s.wrap(i) })
    // each source pages independently so a page is only locally deduped; cross-page dupes are rare and harmless
    override suspend fun songsPage(offset: Int, count: Int): List<Song> =
        matcher.dedupSongs(wrapAll(router.fanOut { it.songsPage(offset, count) }) { s, i -> s.wrap(i) }).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    override suspend fun starredSongs(): List<Song> =
        matcher.dedupSongs(wrapAll(router.fanOut(router::likedTimeout) { it.starredSongs() }) { s, i -> s.wrap(i) })
    // the count matches the deduped liked list; a single-source view asks its source directly
    override suspend fun starredCount(): Int = sources.singleOrNull()?.starredCount() ?: starredSongs().size
    override suspend fun starredIds(): Set<String> =
        sources.indices.zip(router.fanOut(router::likedTimeout) { it.starredIds().toList() }).flatMap { (i, ids) -> ids.map { router.wrapId(i, it) } }.toSet()

    override suspend fun search(query: String): SearchResults = search(query, "discovery")

    override suspend fun search(query: String, source: String): SearchResults {
        val index = router.searchIndex(source)
        if (index >= 0) {
            val result = sources[index].search(query)
            return result.copy(songs = result.songs.map { it.wrap(index) },
                albums = result.albums.map { it.wrap(index) }, artists = result.artists.map { it.wrap(index) },
                playlists = result.playlists.map { it.wrap(index) })
        }
        val results = coroutineScope {
            sources.mapIndexed { idx, src -> async { idx to if (router.isCatalogue(src)) null
                else router.reach(idx) { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { src.search(query) } } } }.awaitAll()
        }
        val songs = ArrayList<Song>(); val albums = ArrayList<Album>(); val artists = ArrayList<Artist>(); val playlists = ArrayList<Playlist>()
        for ((idx, r) in results) {
            if (r == null) continue
            songs += r.songs.map { it.wrap(idx) }
            albums += r.albums.map { it.wrap(idx) }
            artists += r.artists.map { it.wrap(idx) }
            playlists += r.playlists.map { it.wrap(idx) }
        }
        return SearchResults(matcher.dedupSongs(songs), matcher.dedupAlbums(albums), matcher.dedupArtists(artists), playlists)
    }

    // a failed source reaches the caller with the others' answers, so only its songs are asked about again
    override suspend fun likedSongIds(ids: List<String>): Set<String> = supervisorScope {
        val groups = ids.mapNotNull { id -> router.unwrap(id)?.let { (idx, original) -> idx to (id to original) } }.groupBy({ it.first }, { it.second })
        val answers = groups.map { (idx, pairs) -> async { sources[idx].likedSongIds(pairs.map { it.second }).map { router.wrapId(idx, it) } } }
        val liked = HashSet<String>(); val unanswered = HashSet<String>()
        groups.values.zip(answers).forEach { (pairs, answer) ->
            try { liked += answer.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { pairs.mapTo(unanswered) { it.first } }
        }
        if (unanswered.isNotEmpty()) throw PartialLikes(liked, unanswered)
        liked
    }

    override val supportsFolders: Boolean get() = sources.any { it.supportsFolders }

    override suspend fun browseFolder(folderId: String): FolderContent? {
        if (folderId.isBlank()) {
            val perSource = coroutineScope {
                sources.mapIndexed { i, s ->
                    async { i to if (s.supportsFolders) runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { s.browseFolder("") } }.getOrNull() else null }
                }.awaitAll()
            }
            val folders = ArrayList<FolderNode>(); val songs = ArrayList<Song>()
            for ((i, c) in perSource) {
                if (c == null) continue
                folders += c.folders.map { FolderNode(router.wrapId(i, it.id), it.name) }
                songs += c.songs.map { it.wrap(i) }
            }
            return FolderContent(id = "", title = "Folders", folders = folders, songs = songs)
        }
        val (i, oid) = router.unwrap(folderId) ?: return null
        val c = runCatching { sources.getOrNull(i)?.browseFolder(oid) }.getOrNull() ?: return null
        return c.copy(
            id = router.wrapId(i, c.id),
            folders = c.folders.map { FolderNode(router.wrapId(i, it.id), it.name) },
            songs = c.songs.map { it.wrap(i) },
        )
    }

    override val supportsServerTagEdit: Boolean get() = sources.any { it.supportsServerTagEdit }
    override suspend fun readMetadata(songId: String): AudioTags? = router.route(songId) { src, _, oid -> src.readMetadata(oid) }
    override suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean =
        router.route(songId) { src, _, oid -> src.updateMetadata(oid, tags) } ?: false

    override suspend fun songFor(id: String): Song? = router.route(id) { src, i, oid -> src.songFor(oid)?.wrap(i) }
    override suspend fun radio(seedId: String): List<Song> = router.route(seedId) { src, i, oid -> src.radio(oid).map { it.wrap(i) } } ?: emptyList()

    override val supportsExplore: Boolean get() = router.explorer >= 0

    override suspend fun explore(): ExploreData {
        val index = router.explorer.takeIf { it >= 0 } ?: return ExploreData()
        val data = sources[index].explore()
        return data.copy(sections = HomeData(sections = data.sections).wrapHome(index).sections)
    }

    override suspend fun exploreCategory(params: String): HomeData {
        val index = router.explorer.takeIf { it >= 0 } ?: return HomeData()
        return sources[index].exploreCategory(params).wrapHome(index)
    }

    override suspend fun related(song: Song): HomeData {
        val index = router.explorer.takeIf { it >= 0 } ?: return HomeData()
        val (owner, original) = router.unwrap(song.id) ?: (-1 to song.id)
        return sources[index].related(if (owner == index) song.copy(id = original) else song).wrapHome(index)
    }
    override fun prefersServerRadio(seedId: String): Boolean {
        val (index, original) = router.unwrap(seedId) ?: return if (SEP in seedId) false
            else router.primary?.prefersServerRadio(seedId) ?: false
        return sources[index].prefersServerRadio(original)
    }
    override suspend fun scrobble(id: String) { router.route(id) { src, _, oid -> src.scrobble(oid) } }

    override fun playbackReportTarget(song: Song): PlaybackReportTarget? {
        val provider = song.playbackSource?.providerId ?: return null
        val source = sources.singleOrNull { PlaybackSourceIdentity.fromSession(it.session, "").providerId == provider } ?: return null
        val id = song.playbackSource.songId ?: song.id
        val original = if (SEP in id) {
            val (index, raw) = router.unwrap(id) ?: return null
            if (sources[index] !== source) return null
            raw
        } else id
        return source.playbackReportTarget(song.copy(id = original, playbackSource = song.playbackSource.copy(songId = original)))
    }
    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean =
        router.route(id) { src, _, oid -> src.setStarred(oid, starred, kind) } ?: false

    override val signedIn: Boolean get() = sources.all { it.signedIn }
    override fun signedIn(id: String): Boolean = router.unwrap(id)?.let { (i, oid) -> sources[i].signedIn(oid) } ?: router.primary?.signedIn(id) ?: true
    override fun likesLocally(id: String): Boolean = router.unwrap(id)?.let { (i, oid) -> sources[i].likesLocally(oid) } ?: router.primary?.likesLocally(id) ?: false

    // liked songs follow the library view or the picked sources they were opened from; any other id is every source together
    private fun likedScope(id: String): MediaBackend {
        if (nested) return this
        if (id in VIEW_IDS) return libraryView(id)
        val keys = id.split(',').takeIf { parts -> parts.all { MERGE_NAMESPACE_PATTERN.matches(it) } }?.toSet() ?: return this
        return synchronized(views) {
            views.getOrPut(id) { MergedBackend(sources.filter { mergeNamespace(it.session) in keys }, session, priority, downloads, catalogueMatch, nested = true, downUntil = downUntil) }
        }
    }

    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val songs = likedScope(id).starredSongs()
            val subtitle = if (id == ALL_LIKED_ID) "${songs.size} songs from every source" else "${songs.size} songs you love"
            return DetailData(
                DetailInfo("Liked Songs", subtitle, songs.firstOrNull()?.artworkUrl ?: "", accentArgbFor("liked"), false, songs.size, "Liked"),
                songs,
            )
        }
        return router.route(id) { src, i, oid ->
            src.detail(kind, oid)?.let { d ->
                val mixed = if (kind == "playlist") src.playlistEntries(oid)?.let { router.mixed(it, i) } else null
                d.copy(tracks = mixed ?: d.tracks.map { it.wrap(i) }, albums = d.albums.map { it.wrap(i) },
                    info = if (mixed == null) d.info else d.info.copy(songCount = mixed.size,
                        subtitle = "${mixed.size} song${if (mixed.size == 1) "" else "s"}",
                        artUrl = d.info.artUrl.ifBlank { mixed.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty() }))
            }
        }
    }

    override suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
        router.route(id) { src, i, oid -> src.detailPage(kind, oid, offset).map { it.wrap(i) } } ?: emptyList()

    override suspend fun collectionTracks(kind: String, id: String): List<Song> =
        if (kind == "liked") likedScope(id).starredSongs()
        else router.route(id) { src, i, oid ->
            (if (kind == "playlist") src.playlistEntries(oid)?.let { router.mixed(it, i) } else null) ?: src.collectionTracks(kind, oid).map { it.wrap(i) }
        }
            ?: error("This collection's source is unavailable.")

    override fun sourceType(song: Song): ServerType = router.unwrap(song.id)?.let { sources[it.first].session.type } ?: session.type

    override fun supportsCanvas(song: Song): Boolean = features.supportsCanvas(song)
    override suspend fun canvasUrl(song: Song): String? = features.canvasUrl(song)
    override suspend fun catalogueLyrics(song: Song): Lyrics? = features.catalogueLyrics(song)
    override fun supportsComments(song: Song): Boolean = features.supportsComments(song)
    override fun canPostComments(song: Song): Boolean = features.canPostComments(song)

    override fun supportsReposts(id: String): Boolean = router.unwrap(id)?.let { (i, oid) -> sources[i].supportsReposts(oid) } ?: false
    override suspend fun isReposted(id: String): Boolean = router.route(id) { src, _, oid -> src.isReposted(oid) } ?: false
    // not routed through route(), so a bot check refusal reaches the caller
    override suspend fun setReposted(id: String, reposted: Boolean): Boolean {
        val (i, oid) = router.unwrap(id) ?: return false
        return sources[i].setReposted(oid, reposted)
    }

    override suspend fun comments(song: Song): List<com.aurora.music.model.TrackComment>? = features.comments(song)
    override suspend fun postComment(song: Song, body: String, timestampMs: Long): com.aurora.music.model.TrackComment? =
        features.postComment(song, body, timestampMs)

    override suspend fun serverLyrics(song: Song): Lyrics? {
        val (i, oid) = router.unwrap(song.id) ?: return router.primary?.serverLyrics(song)
        return sources.getOrNull(i)?.serverLyrics(song.copy(id = oid))
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        val (i, oid) = router.unwrap(songId) ?: return if (SEP in songId) "" else router.primary?.streamUrl(songId, maxBitrate, lossless) ?: ""
        return sources.getOrNull(i)?.streamUrl(oid, maxBitrate, lossless) ?: ""
    }
    // each source resolves its own download link, such as soundcloud's hls stream behind its play link
    override suspend fun downloadUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        val (i, oid) = router.unwrap(songId) ?: return if (SEP in songId) "" else router.primary?.downloadUrl(songId, maxBitrate, lossless) ?: ""
        return sources.getOrNull(i)?.downloadUrl(oid, maxBitrate, lossless) ?: ""
    }
    override fun coverArtUrl(id: String, size: Int): String {
        val (i, oid) = router.unwrap(id) ?: return if (SEP in id) "" else router.primary?.coverArtUrl(id, size) ?: ""
        return sources.getOrNull(i)?.coverArtUrl(oid, size) ?: ""
    }

    override suspend fun createPlaylist(name: String): Boolean = router.primary?.createPlaylist(name) ?: false
    override val playlistTargets: List<PlaylistTarget> get() = sources.mapIndexedNotNull { i, source ->
        val type = source.session.type
        if (type !in PLAYLIST_HOMES) null else PlaylistTarget(router.namespaces[i], source.session.typeLabel, type)
    }.takeIf { it.size > 1 }.orEmpty()
    override suspend fun createPlaylistIn(target: String, name: String): String? {
        val index = router.namespaces.indexOf(target).takeIf { it >= 0 && sources[it].session.type in PLAYLIST_HOMES } ?: return null
        return sources[index].createPlaylistWithId(name)?.let { router.wrapId(index, it) }
    }
    override suspend fun createPlaylistWithId(name: String): String? {
        val pIdx = sources.indexOfFirst { it === router.primary }
        val pid = router.primary?.createPlaylistWithId(name) ?: return null
        return if (pIdx >= 0) router.wrapId(pIdx, pid) else pid
    }
    override suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
        if (name.isBlank()) return null
        val source = router.primary ?: return null
        val index = sources.indexOfFirst { it === source }
        if (index < 0) return null
        val ids = trackIds.map { router.unwrap(it) ?: return null }
        if (ids.any { it.first != index || it.second.isBlank() }) return null
        val id = source.createPlaylistWithId(name.trim(), ids.map { it.second }) ?: return null
        return router.wrapId(index, id)
    }
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean =
        router.route(id) { src, _, oid -> src.updatePlaylist(oid, name, comment) } ?: false
    override suspend fun deletePlaylist(id: String): Boolean =
        router.route(id) { src, _, oid -> src.deletePlaylist(oid) } ?: false

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        val (i, oPid) = router.unwrap(playlistId) ?: return false
        val src = sources.getOrNull(i) ?: return false
        val ids = trackIds.map { router.unwrap(it) ?: return false }
        if (ids.any { it.first != i }) return false
        return src.addToPlaylist(oPid, ids.map { it.second })
    }

    // your own sources together first, then each catalogue on its own
    override fun importStages(): List<ImportStage> = buildList {
        add(ImportStage(ImportSource.LIBRARY) { search(it, "library").songs })
        listOf(router.youtubeIndex, router.spotifyIndex, router.soundcloudIndex).filter { it >= 0 }.forEach { i ->
            sources[i].importStages().forEach { stage -> add(ImportStage(stage.source) { q -> stage.search(q).map { it.wrap(i) } }) }
        }
    }

    override suspend fun fileCopy(track: ImportTrack): Song? = sources.indices.firstNotNullOfOrNull { i -> sources[i].fileCopy(track)?.wrap(i) }

    // the on-device library holds mixed playlists; songs from sources outside this merge keep their own namespace
    override suspend fun createMixedPlaylist(name: String, songs: List<Song>): String? {
        val home = sources.indexOfFirst { it.session.type == ServerType.LOCAL && it.acceptsForeignTracks }.takeIf { it >= 0 } ?: return null
        val own = songs.mapNotNull { song ->
            val (i, original) = router.unwrap(song.id) ?: return@mapNotNull song.takeIf { SEP in it.id }
            if (i == home) song.copy(id = original) else song
        }
        return sources[home].createMixedPlaylist(name, own)?.let { router.wrapId(home, it) }
    }

    override suspend fun addSongsToPlaylist(playlistId: String, songs: List<Song>): Boolean {
        val (i, oPid) = router.unwrap(playlistId) ?: return false
        val src = sources.getOrNull(i) ?: return false
        val own = songs.map { song ->
            val (index, original) = router.unwrap(song.id) ?: return false
            if (index == i) song.copy(id = original) else if (src.acceptsForeignTracks) song else return false
        }
        return src.addSongsToPlaylist(oPid, own)
    }

    // a song can go in its own source's playlists and in any local one
    override suspend fun playlistsForSong(songId: String): List<Playlist> {
        val (index, original) = router.unwrap(songId) ?: return emptyList()
        val local = sources.withIndex().filter { it.index != index && it.value.acceptsForeignTracks }
            .flatMap { (i, source) -> source.allPlaylists().map { it.wrap(i) } }
        return sources[index].playlistsForSong(original).map { it.wrap(index) } + local
    }

    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        val (index, original) = router.unwrap(playlistId) ?: return false
        val source = sources.getOrNull(index) ?: return false
        val ids = trackIds.map { id ->
            val (i, oid) = router.unwrap(id) ?: return false
            if (i == index) oid else if (source.acceptsForeignTracks) id else return false
        }
        return source.removeFromPlaylist(original, ids)
    }

    override suspend fun profileImageUrl(): String = router.primary?.profileImageUrl() ?: ""

    private fun <T> wrapAll(perSource: List<List<T>>, wrap: (T, Int) -> T): List<T> =
        perSource.flatMapIndexed { i, list -> list.map { wrap(it, i) } }

    private companion object {
        val VIEW_IDS = setOf("library", "youtube", "spotify", "soundcloud")
        val PLAYLIST_HOMES = setOf(ServerType.LOCAL, ServerType.SUBSONIC, ServerType.JELLYFIN, ServerType.PLEX)
    }
}

const val MERGE_NAMESPACE_SEP = '\u0001'   // same control char as SourceRouter.SEP

// the prefix a merged library puts on a source's ids; likes are filed per account under the same key,
// and songs from a source outside the merge are wrapped the same way
fun mergeNamespace(session: Session): String = "s" + PlaybackSourceIdentity.fromSession(session, "").providerId!!.removePrefix("provider:")
fun Song.inNamespace(session: Session): Song = if (MERGE_NAMESPACE_SEP in id) this else copy(id = "${mergeNamespace(session)}$MERGE_NAMESPACE_SEP$id")

// liked songs id for every source's likes together, beside the per-view ids
const val ALL_LIKED_ID = "all"

// liked songs id for the picked sources' likes, by merge namespace; all of them is every source
fun likedSourcesId(picked: Collection<String>, all: Collection<String>): String =
    if (picked.containsAll(all)) ALL_LIKED_ID else picked.joinToString(",")

// some sources answered a likes check; the unanswered ids are worth asking again
class PartialLikes(val liked: Set<String>, val unanswered: Set<String>) : Exception("some sources did not answer")

private val MERGE_NAMESPACE_PATTERN = Regex("s[0-9a-f]{64}")

fun stripMergeNamespace(id: String): String {
    val i = id.indexOf(MERGE_NAMESPACE_SEP)
    if (i <= 0) return id
    val prefix = id.substring(0, i)
    return if (prefix.all { it.isDigit() } || prefix.matches(MERGE_NAMESPACE_PATTERN)) id.substring(i + 1) else id
}
