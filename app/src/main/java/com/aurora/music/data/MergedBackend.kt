package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.aurora.music.util.accentFor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 7.1b — a composite [MediaBackend] that presents one merged library across several real
 * backends (on-device Local + each included Navidrome/Jellyfin login), all live at once. Each browse
 * call fans out to every source in parallel, the results are concatenated and **deduped by fuzzy
 * identity**, and a duplicate collapses to a single entity sourced from the best copy (highest audio
 * quality for tracks; the first source in priority order for albums/artists). Because it satisfies the
 * [MediaBackend] interface, the whole server-agnostic app browses the union with no other changes.
 *
 * IDs are **namespaced** per source (`"<idx><id>"`) so every id-taking call routes back to the
 * owning source. The per-source [Song.streamUrl] is already a complete, authed URL, so playback needs
 * no rewrite. A slow/unreachable source is skipped (timed-out fan-out), never blocking the rest.
 */
class MergedBackend(
    private val sources: List<MediaBackend>,
    override val session: Session,
) : MediaBackend {

    private val primary: MediaBackend? = sources.firstOrNull { it.session.type != ServerType.LOCAL } ?: sources.firstOrNull()

    // ---- id namespacing -------------------------------------------------------------------------

    private fun wrapId(idx: Int, id: String): String = if (id.isBlank()) "" else "$idx$SEP$id"
    private fun unwrap(wrapped: String): Pair<Int, String>? {
        val i = wrapped.indexOf(SEP)
        if (i <= 0) return null
        val idx = wrapped.substring(0, i).toIntOrNull() ?: return null
        if (idx !in sources.indices) return null
        return idx to wrapped.substring(i + 1)
    }

    private fun Song.wrap(idx: Int) = copy(
        id = wrapId(idx, id),
        albumId = wrapId(idx, albumId),
        artistId = wrapId(idx, artistId),
    )
    private fun Album.wrap(idx: Int) = copy(id = wrapId(idx, id))
    private fun Artist.wrap(idx: Int) = copy(id = wrapId(idx, id))
    private fun Playlist.wrap(idx: Int) = copy(id = wrapId(idx, id))

    /** Run [block] on every source in parallel (timed-out, failures → empty), returning per-source lists. */
    private suspend fun <T> fanOut(block: suspend (MediaBackend) -> List<T>): List<List<T>> = coroutineScope {
        sources.map { src -> async { runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block(src) } ?: emptyList() }.getOrDefault(emptyList()) } }
            .awaitAll()
    }

    // ---- dedup ----------------------------------------------------------------------------------

    private fun dedupAlbums(all: List<Album>): List<Album> =
        all.distinctBy { TrackMatch.norm(it.artist) + "|" + TrackMatch.norm(it.title) }

    private fun dedupArtists(all: List<Artist>): List<Artist> =
        all.distinctBy { TrackMatch.norm(it.name) }.filter { it.name.isNotBlank() }

    /** Collapse same-recording tracks to the highest-quality copy. Clusters by a real ±tolerance
     *  duration window (not fixed bins) so near-identical durations don't split across a boundary. */
    private fun dedupSongs(all: List<Song>): List<Song> =
        all.groupBy { TrackMatch.key(it.artist, it.title) }
            .values
            .flatMap { group ->
                val clusters = ArrayList<MutableList<Song>>()
                for (s in group.sortedBy { it.durationSec }) {
                    val cur = clusters.lastOrNull()
                    if (cur != null && s.durationSec - cur.first().durationSec <= TrackMatch.DURATION_TOLERANCE_SEC) cur.add(s)
                    else clusters.add(mutableListOf(s))
                }
                clusters.map { c -> c.maxByOrNull { qualityScore(it) } ?: c.first() }
            }

    private fun qualityScore(s: Song): Long {
        var score = 0L
        if (s.suffix.lowercase() in LOSSLESS) score += 2_000_000
        score += s.bitDepth.toLong() * 100_000
        score += s.sampleRateHz.toLong() / 100
        score += s.bitrateKbps.toLong()
        return score
    }

    // ---- MediaBackend: browse (fan-out + dedup) -------------------------------------------------

    override suspend fun ping(): Boolean = fanOut { listOf(it.ping()) }.any { it.firstOrNull() == true }

    override suspend fun home(): HomeData {
        val homes = coroutineScope {
            sources.mapIndexed { idx, src -> async { idx to runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { src.home() } }.getOrNull() } }.awaitAll()
        }
        val albums = ArrayList<Album>(); val recent = ArrayList<Album>(); val most = ArrayList<Album>(); val random = ArrayList<Album>()
        val playlists = ArrayList<Playlist>(); val artists = ArrayList<Artist>(); val starred = ArrayList<Song>()
        for ((idx, h) in homes) {
            if (h == null) continue
            albums += h.newReleases.map { it.wrap(idx) }
            recent += h.recentlyPlayed.map { it.wrap(idx) }
            most += h.mostPlayed.map { it.wrap(idx) }
            random += h.random.map { it.wrap(idx) }
            playlists += h.playlists.map { it.wrap(idx) }
            artists += h.artists.map { it.wrap(idx) }
            starred += h.starred.map { it.wrap(idx) }
        }
        return HomeData(
            newReleases = dedupAlbums(albums),
            recentlyPlayed = dedupAlbums(recent),
            mostPlayed = dedupAlbums(most),
            random = dedupAlbums(random),
            playlists = playlists,
            artists = dedupArtists(artists),
            starred = dedupSongs(starred),
        )
    }

    override suspend fun allAlbums(): List<Album> = dedupAlbums(wrapAll(fanOut { it.allAlbums() }) { a, i -> a.wrap(i) })
    override suspend fun allArtists(): List<Artist> = dedupArtists(wrapAll(fanOut { it.allArtists() }) { a, i -> a.wrap(i) })
    override suspend fun allPlaylists(): List<Playlist> = wrapAll(fanOut { it.allPlaylists() }) { p, i -> p.wrap(i) }
    override suspend fun allSongs(): List<Song> = dedupSongs(wrapAll(fanOut { it.allSongs() }) { s, i -> s.wrap(i) })
    override suspend fun librarySongs(limit: Int): List<Song> = dedupSongs(wrapAll(fanOut { it.librarySongs(limit) }) { s, i -> s.wrap(i) })
    override suspend fun starredSongs(): List<Song> = dedupSongs(wrapAll(fanOut { it.starredSongs() }) { s, i -> s.wrap(i) })
    override suspend fun starredCount(): Int = starredIds().size
    override suspend fun starredIds(): Set<String> =
        sources.indices.zip(fanOut { it.starredIds().toList() }).flatMap { (i, ids) -> ids.map { wrapId(i, it) } }.toSet()

    override suspend fun search(query: String): SearchResults {
        val results = coroutineScope {
            sources.mapIndexed { idx, src -> async { idx to runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { src.search(query) } }.getOrNull() } }.awaitAll()
        }
        val songs = ArrayList<Song>(); val albums = ArrayList<Album>(); val artists = ArrayList<Artist>(); val playlists = ArrayList<Playlist>()
        for ((idx, r) in results) {
            if (r == null) continue
            songs += r.songs.map { it.wrap(idx) }
            albums += r.albums.map { it.wrap(idx) }
            artists += r.artists.map { it.wrap(idx) }
            playlists += r.playlists.map { it.wrap(idx) }
        }
        return SearchResults(dedupSongs(songs), dedupAlbums(albums), dedupArtists(artists), playlists)
    }

    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        val bySource = ids.mapNotNull { unwrap(it) }.groupBy({ it.first }, { it.second })
        val liked = HashSet<String>()
        for ((idx, origIds) in bySource) {
            val src = sources.getOrNull(idx) ?: continue
            runCatching { src.likedSongIds(origIds) }.getOrNull()?.forEach { liked += wrapId(idx, it) }
        }
        return liked
    }

    // ---- MediaBackend: folder browse (fan out root, route by namespaced id) ---------------------

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
                folders += c.folders.map { FolderNode(wrapId(i, it.id), it.name) }
                songs += c.songs.map { it.wrap(i) }
            }
            return FolderContent(id = "", title = "Folders", folders = folders, songs = songs)
        }
        val (i, oid) = unwrap(folderId) ?: return null
        val c = runCatching { sources.getOrNull(i)?.browseFolder(oid) }.getOrNull() ?: return null
        return c.copy(
            id = wrapId(i, c.id),
            folders = c.folders.map { FolderNode(wrapId(i, it.id), it.name) },
            songs = c.songs.map { it.wrap(i) },
        )
    }

    // ---- MediaBackend: server tag editing (route by namespaced id) ------------------------------

    override val supportsServerTagEdit: Boolean get() = sources.any { it.supportsServerTagEdit }
    override suspend fun readMetadata(songId: String): AudioTags? = route(songId) { src, _, oid -> src.readMetadata(oid) }
    override suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean =
        route(songId) { src, _, oid -> src.updateMetadata(oid, tags) } ?: false

    // ---- MediaBackend: route by namespaced id ---------------------------------------------------

    override suspend fun songFor(id: String): Song? = route(id) { src, i, oid -> src.songFor(oid)?.wrap(i) }
    override suspend fun radio(seedId: String): List<Song> = route(seedId) { src, i, oid -> src.radio(oid).map { it.wrap(i) } } ?: emptyList()
    override suspend fun scrobble(id: String) { route(id) { src, _, oid -> src.scrobble(oid) } }
    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean =
        route(id) { src, _, oid -> src.setStarred(oid, starred, kind) } ?: false

    override suspend fun detail(kind: String, id: String): DetailData? {
        // "Liked Songs" is universal in unified mode: the union of every source's likes (already
        // merged + deduped by starredSongs), not just the primary source's.
        if (kind == "liked") {
            val songs = starredSongs()
            return DetailData(
                DetailInfo("Liked Songs", "${songs.size} songs you love", songs.firstOrNull()?.artworkUrl ?: "", accentFor("liked"), false, songs.size, "Liked"),
                songs,
            )
        }
        return route(id) { src, i, oid ->
            src.detail(kind, oid)?.let { d -> d.copy(tracks = d.tracks.map { it.wrap(i) }, albums = d.albums.map { it.wrap(i) }) }
        }
    }
    override suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
        route(id) { src, i, oid -> src.detailPage(kind, oid, offset).map { it.wrap(i) } } ?: emptyList()

    override suspend fun serverLyrics(song: Song): Lyrics? {
        val (i, oid) = unwrap(song.id) ?: return primary?.serverLyrics(song)
        return sources.getOrNull(i)?.serverLyrics(song.copy(id = oid))
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        val (i, oid) = unwrap(songId) ?: return primary?.streamUrl(songId, maxBitrate, lossless) ?: ""
        return sources.getOrNull(i)?.streamUrl(oid, maxBitrate, lossless) ?: ""
    }
    override fun coverArtUrl(id: String, size: Int): String {
        val (i, oid) = unwrap(id) ?: return primary?.coverArtUrl(id, size) ?: ""
        return sources.getOrNull(i)?.coverArtUrl(oid, size) ?: ""
    }

    // ---- MediaBackend: writes (route to the owning / primary source) ----------------------------

    override suspend fun createPlaylist(name: String): Boolean = primary?.createPlaylist(name) ?: false
    override suspend fun createPlaylistWithId(name: String): String? {
        val pIdx = sources.indexOfFirst { it === primary }
        val pid = primary?.createPlaylistWithId(name) ?: return null
        return if (pIdx >= 0) wrapId(pIdx, pid) else pid
    }
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean =
        route(id) { src, _, oid -> src.updatePlaylist(oid, name, comment) } ?: false
    override suspend fun deletePlaylist(id: String): Boolean =
        route(id) { src, _, oid -> src.deletePlaylist(oid) } ?: false

    /** Append tracks to a playlist — only the tracks that live on the playlist's own source. */
    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        val (i, oPid) = unwrap(playlistId) ?: return false
        val src = sources.getOrNull(i) ?: return false
        val sameSource = trackIds.mapNotNull { unwrap(it) }.filter { it.first == i }.map { it.second }
        // Don't report success when every track was from a different source (cross-source add dropped).
        if (trackIds.isNotEmpty() && sameSource.isEmpty()) return false
        return src.addToPlaylist(oPid, sameSource)
    }

    override suspend fun profileImageUrl(): String = primary?.profileImageUrl() ?: ""

    // ---- helpers --------------------------------------------------------------------------------

    private fun <T> wrapAll(perSource: List<List<T>>, wrap: (T, Int) -> T): List<T> =
        perSource.flatMapIndexed { i, list -> list.map { wrap(it, i) } }

    private suspend fun <R> route(id: String, block: suspend (MediaBackend, Int, String) -> R): R? {
        val (i, oid) = unwrap(id) ?: return primary?.let { p -> runCatching { block(p, sources.indexOf(p), id) }.getOrNull() }
        val src = sources.getOrNull(i) ?: return null
        return runCatching { block(src, i, oid) }.getOrNull()
    }

    private companion object {
        const val SEP = '\u0001'   // control char that never appears in a real backend id
        const val SOURCE_TIMEOUT_MS = 20_000L
        val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
    }
}

/** Source-namespace separator a [MergedBackend] prepends to ids (the same char as its private SEP). */
const val MERGE_NAMESPACE_SEP = '\u0001'   // same control char as MergedBackend.SEP

/** Strip a [MergedBackend] source-namespace prefix ("<digits>SEP") if present, else return [id]. */
fun stripMergeNamespace(id: String): String {
    val i = id.indexOf(MERGE_NAMESPACE_SEP)
    if (i <= 0) return id
    return if (id.substring(0, i).all { it.isDigit() }) id.substring(i + 1) else id
}
