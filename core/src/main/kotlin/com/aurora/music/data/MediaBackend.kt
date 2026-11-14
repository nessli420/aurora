package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

// a song as one source has it, labelled for menus; playing marks the source its audio comes from
data class SourceCopy(val label: String, val song: Song, val playing: Boolean = false)

// returns app domain models so callers are server-agnostic backend owns dto mapping auth and urls
interface MediaBackend {
    val session: Session
    val supportsOfflineBrowsing: Boolean get() = false

    // a guest source browses and plays, but likes, comments, playlists and its personal library need an account
    val signedIn: Boolean get() = true
    fun signedIn(id: String): Boolean = signedIn
    // a signed-out catalogue still likes songs, on this device until its account is added
    fun likesLocally(id: String): Boolean = false
    val homeFeeds: List<HomeFeedChoice> get() = emptyList()
    val searchSources: List<SearchSourceChoice> get() = emptyList()
    suspend fun search(query: String, source: String): SearchResults = search(query)
    suspend fun home(feed: String): HomeData = home()
    suspend fun homePage(feed: String, continuation: String): HomeData = homePage(continuation)
    suspend fun playbackCandidates(song: Song): List<Song> = emptyList()
    suspend fun matchingSongs(song: Song): List<Song> = search(recordingTitle(song.title)).songs
    // the song on every source that has it; one source has only its own copy
    suspend fun sourceCopies(song: Song): List<SourceCopy> = listOf(SourceCopy(session.typeLabel, song, playing = true))
    // songs on the source this one came from, to trade a wrong match for the right one
    suspend fun searchOwnSource(song: Song, query: String): List<Song> = search(query).songs

    fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? = song.playbackSource ?: when {
        song.isRadio() -> PlaybackSourceIdentity(source = com.aurora.music.data.rules.RuleSource.RADIO)
        song.isPodcast() -> PlaybackSourceIdentity(source = com.aurora.music.data.rules.RuleSource.PODCAST)
        session.type != ServerType.LOCAL && (song.streamUrl.startsWith("file:") || song.streamUrl.startsWith("content:")) ->
            PlaybackSourceIdentity(source = com.aurora.music.data.rules.RuleSource.LOCAL_FILE)
        else -> PlaybackSourceIdentity.fromSession(session, song.albumId)
    }

    fun playbackCollectionIdentity(kind: String, id: String, name: String? = null): PlaybackCollectionIdentity? {
        if (kind != "playlist") return null
        val provider = PlaybackSourceIdentity.fromSession(session, "").providerId ?: return null
        return PlaybackCollectionIdentity(PlaybackSourceIdentity.scoped(provider, "playlist", id), name)
    }

    suspend fun ping(): Boolean

    suspend fun home(): HomeData
    suspend fun homePage(continuation: String): HomeData = HomeData()
    suspend fun allAlbums(): List<Album>
    suspend fun allArtists(): List<Artist>
    suspend fun allPlaylists(): List<Playlist>
    suspend fun allSongs(): List<Song>

    suspend fun librarySongs(limit: Int = 2000): List<Song> = allSongs()

    // paged library songs in server sort order; backends without paging only serve page zero
    suspend fun songsPage(offset: Int, count: Int): List<Song> =
        if (offset == 0) librarySongs(count) else emptyList()

    suspend fun starredSongs(): List<Song>
    suspend fun starredCount(): Int
    suspend fun starredIds(): Set<String>
    suspend fun songFor(id: String): Song?
    suspend fun search(query: String): SearchResults
    suspend fun scrobble(id: String)
    suspend fun reportPlayback(report: PlaybackReport) {
        if (report.event == PlaybackReportEvent.SCROBBLE) scrobble(report.song.id)
    }

    fun playbackReportTarget(song: Song): PlaybackReportTarget? {
        val provider = PlaybackSourceIdentity.fromSession(session, "").providerId
        val identity = song.playbackSource ?: return null
        if (identity.providerId != provider) return null
        val id = identity.songId ?: song.id
        if (MERGE_NAMESPACE_SEP in id && id.substringBefore(MERGE_NAMESPACE_SEP) != "s${provider?.removePrefix("provider:")}") return null
        val original = song.copy(id = id.substringAfter(MERGE_NAMESPACE_SEP))
        return PlaybackReportTarget(original) { report -> reportPlayback(report.copy(song = original)) }
    }
    suspend fun radio(seedId: String): List<Song>
    fun prefersServerRadio(seedId: String): Boolean = false

    // streaming discovery: new releases, charts, moods and a song's related picks; only catalogues that offer it answer
    val supportsExplore: Boolean get() = false
    suspend fun explore(): ExploreData = ExploreData()
    suspend fun exploreCategory(params: String): HomeData = HomeData()
    suspend fun related(song: Song): HomeData = HomeData()
    suspend fun createPlaylist(name: String): Boolean
    suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean
    suspend fun deletePlaylist(id: String): Boolean

    suspend fun createPlaylistWithId(name: String): String? = null

    suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
        if (name.isBlank()) return null
        val id = createPlaylistWithId(name.trim()) ?: return null
        return id.takeIf { trackIds.isEmpty() || addToPlaylist(id, trackIds) }
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean = false
    suspend fun addSongsToPlaylist(playlistId: String, songs: List<Song>): Boolean = addToPlaylist(playlistId, songs.map { it.id })

    // a merged library over several self-hosted ones lets the new playlist pick its home
    val playlistTargets: List<PlaylistTarget> get() = emptyList()
    suspend fun createPlaylistIn(target: String, name: String): String? = createPlaylistWithId(name)

    // local playlists also keep songs from other merged sources, whose ids stay namespaced
    val acceptsForeignTracks: Boolean get() = false

    // where an imported track is looked for, in order
    fun importStages(): List<ImportStage> = listOf(ImportStage(ImportSource.of(session.type)) { search(it).songs })
    // the file an exported list names, when this source holds it; such tracks skip the search
    suspend fun fileCopy(track: ImportTrack): Song? = null
    // a playlist that may hold songs from any source; null where only the source's own songs fit
    suspend fun createMixedPlaylist(name: String, songs: List<Song>): String? = null
    suspend fun playlistEntries(id: String): List<Song>? = null
    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean = false
    suspend fun playlistsForSong(songId: String): List<Playlist> = allPlaylists()
    suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean
    suspend fun detail(kind: String, id: String): DetailData?

    suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> = emptyList()

    /** Complete, ordered collection. Playlist occurrences are intentionally preserved. */
    suspend fun collectionTracks(kind: String, id: String): List<Song> {
        val data = detail(kind, id) ?: error("Could not load this collection. Reconnect and retry.")
        if (kind == "artist") {
            val tracks = data.albums.flatMap { collectionTracks("album", it.id) }
            return (tracks + data.tracks).distinctBy { it.id }
        }
        val tracks = data.tracks.toMutableList()
        while (tracks.size < data.info.songCount) {
            val page = detailPage(kind, id, tracks.size)
            check(page.isNotEmpty()) { "The server returned an incomplete tracklist. Please retry." }
            tracks.addAll(page)
        }
        return tracks
    }

    suspend fun likedSongIds(ids: List<String>): Set<String> = emptySet()

    suspend fun profileImageUrl(): String = ""

    val supportsFolders: Boolean get() = false

    // folderId "" = root
    suspend fun browseFolder(folderId: String): FolderContent? = null

    val supportsServerTagEdit: Boolean get() = false

    suspend fun readMetadata(songId: String): AudioTags? = null

    suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean = false

    suspend fun serverLyrics(song: Song): Lyrics?

    // a short looping video the service pairs with the track (spotify canvas), shown instead of a music video
    fun supportsCanvas(song: Song): Boolean = false
    suspend fun canvasUrl(song: Song): String? = null

    // lyrics a merged catalogue holds for a song that comes from somewhere else
    suspend fun catalogueLyrics(song: Song): Lyrics? = null

    // the kind of account a song comes from; a merged library answers for the source behind it
    fun sourceType(song: Song): ServerType = session.type

    // timed comments along the track; null when there is no track on the service to comment on
    fun supportsComments(song: Song): Boolean = false
    fun canPostComments(song: Song): Boolean = signedIn && supportsComments(song)
    suspend fun comments(song: Song): List<com.aurora.music.model.TrackComment>? = null
    suspend fun postComment(song: Song, body: String, timestampMs: Long): com.aurora.music.model.TrackComment? = null

    fun supportsReposts(id: String): Boolean = false
    suspend fun isReposted(id: String): Boolean = false
    suspend fun setReposted(id: String, reposted: Boolean): Boolean = false

    // a composite backend can show one library at a time; others are a single library
    val libraryViews: List<LibraryViewChoice> get() = emptyList()
    fun libraryView(view: String): MediaBackend = this

    // lossless serves the original untouched file
    fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String

    suspend fun downloadUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        streamUrl(songId, maxBitrate, lossless).takeIf { it.isNotBlank() }?.let { return it }
        songFor(songId)
        return streamUrl(songId, maxBitrate, lossless)
    }

    fun coverArtUrl(id: String, size: Int = 600): String
}

// one home shelf; a failure leaves it empty and its siblings running
internal fun <T> CoroutineScope.shelf(load: suspend () -> List<T>): Deferred<List<T>> =
    async { runCatching { load() }.getOrDefault(emptyList()) }

private const val ALBUM_PAGE = 500
private const val ALBUM_CAP = 10_000
// under the ai dj's 15 s and the merged fan-out's 20 s, either of which would lose every album
private const val ALBUM_BUDGET_MS = 12_000L

// the whole album list a page at a time; a server that ignores offsets adds nothing new, which ends the walk
internal suspend fun pagedAlbums(budgetMs: Long = ALBUM_BUDGET_MS, page: suspend (offset: Int, size: Int) -> List<Album>): List<Album> {
    val out = LinkedHashMap<String, Album>()
    val started = System.currentTimeMillis()
    var offset = 0
    while (out.size < ALBUM_CAP) {
        val left = budgetMs - (System.currentTimeMillis() - started)
        val albums = if (offset == 0) page(0, ALBUM_PAGE) else withTimeoutOrNull(left) { page(offset, ALBUM_PAGE) } ?: break
        val before = out.size
        for (album in albums) out.putIfAbsent(album.id, album)
        if (albums.size < ALBUM_PAGE || out.size == before) break
        offset += ALBUM_PAGE
    }
    return out.values.toList()
}

data class HomeFeedChoice(val id: String, val label: String)
data class SearchSourceChoice(val id: String, val label: String)
data class LibraryViewChoice(val id: String, val label: String)

data class PlaylistTarget(val id: String, val label: String, val type: ServerType)
