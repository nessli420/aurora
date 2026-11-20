package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumMap
import kotlin.math.abs

const val IMPORTED_SONG_PREFIX = "imported:"

// a play from an export that matched nothing in the library carries this id
fun isImportedSongId(id: String?): Boolean = id?.startsWith(IMPORTED_SONG_PREFIX) == true

enum class HistoryService(val key: String, val label: String, val requestUrl: String) {
    SPOTIFY("spotify", "Spotify", "https://www.spotify.com/account/privacy/"),
    APPLE_MUSIC("apple-music", "Apple Music", "https://privacy.apple.com/"),
    YOUTUBE_MUSIC("youtube-music", "YouTube Music", "https://takeout.google.com/");

    companion object {
        fun of(origin: String?): HistoryService? = origin?.substringBefore('@')?.let { k -> entries.firstOrNull { it.key == k } }
    }
}

// one row as the export states it; null where the file says nothing
data class ExportedPlay(
    val service: HistoryService,
    val at: Long,
    // spotify stamps the moment a stream ended, apple and youtube the moment it started
    val atEnd: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val listenedMs: Long?,
    val trackMs: Long? = null,
    val spotifyTrack: String? = null,
    val shuffle: Boolean? = null,
    val ending: PlayEnding? = null,
    // apple ends a row when you pause or scrub and starts a new one when the song goes on
    val continues: Boolean = false,
)

enum class ImportSkip {
    PODCAST, AUDIOBOOK, VIDEO, AD, REMOVED, NO_TRACK, NO_DATE, NOT_PLAYED, NOT_A_PLAY, PRIVATE,
    DUPLICATE, ALREADY_IMPORTED, IN_AURORA_HISTORY, HISTORY_FULL,
}

class ExportSource(val name: String, val size: Long? = null, val open: () -> InputStream)

data class HistoryScanProgress(val bytes: Long, val total: Long, val rows: Int) {
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

data class HistoryImportPreview(
    val plays: List<PlayEvent>,
    val services: Set<HistoryService>,
    val files: List<String>,
    val from: Long?,
    val to: Long?,
    val linked: Int,
    val unknownTime: Int,
    val skipped: Map<ImportSkip, Int>,
    val unknownArtist: Int = 0,
)

data class HistoryImportRecord(val origin: String, val service: HistoryService, val importedAt: Long, val plays: Int, val from: Long, val to: Long)

// links a play to a library song only when exactly one recording fits
class HistoryLinker(songs: Collection<Song>) {
    private val bySpotify = HashMap<String, Song>()
    private val byKey = HashMap<String, MutableList<Song>>()

    init {
        for (s in songs) {
            if (s.id.isBlank() || isImportedSongId(s.id)) continue
            val raw = stripMergeNamespace(s.id)
            if (SPOTIFY_ID.matches(raw)) bySpotify.putIfAbsent(raw, s)
            if (s.title.isBlank() || s.artist.isBlank()) continue
            val list = byKey.getOrPut(TrackMatch.key(s.artist, s.title)) { ArrayList(1) }
            if (list.none { it.id == s.id }) list += s
        }
    }

    fun link(play: ExportedPlay): Song? {
        play.spotifyTrack?.let(bySpotify::get)?.let { return it }
        if (play.title.isBlank() || play.artist.isBlank()) return null
        var pool: List<Song> = byKey[TrackMatch.key(play.artist, play.title)] ?: return null
        play.trackMs?.let { ms ->
            pool = pool.filter { it.durationSec > 0 && abs(it.durationSec - ms / 1000) <= TrackMatch.DURATION_TOLERANCE_SEC }
        }
        if (play.album.isNotBlank() && pool.size > 1) {
            val album = TrackMatch.norm(play.album)
            pool.filter { TrackMatch.norm(it.album) == album }.takeIf { it.isNotEmpty() }?.let { pool = it }
        }
        val first = pool.firstOrNull() ?: return null
        if (pool.size == 1) return first
        // the same recording on two sources is still one song; two albums are not
        val album = TrackMatch.norm(first.album)
        val confirmed = play.trackMs != null || play.album.isNotBlank()
        return first.takeIf { confirmed && album.isNotEmpty() && pool.all { TrackMatch.norm(it.album) == album } }
    }

    companion object {
        private val SPOTIFY_ID = Regex("[0-9A-Za-z]{22}")
    }
}

object HistoryImports {
    // spotify's account data rounds to the minute, its extended history keeps the second
    private const val SPOTIFY_SAME_ROW_MS = 60_000L
    private const val SAME_ROW_MS = 1_000L
    private const val OVERLAP_MS = 30_000L
    private const val LONGEST_PLAY_MS = 4 * 3_600_000L
    private const val FUTURE_SLACK_MS = 24 * 3_600_000L

    fun origin(service: HistoryService, importedAt: Long) = "${service.key}@$importedAt"

    fun records(history: List<PlayEvent>): List<HistoryImportRecord> {
        val byOrigin = HashMap<String, LongArray>()
        for (e in history) {
            val origin = (e.origin as String?) ?: continue
            val acc = byOrigin.getOrPut(origin) { longArrayOf(0, Long.MAX_VALUE, Long.MIN_VALUE) }
            acc[0]++
            acc[1] = minOf(acc[1], e.timestamp)
            acc[2] = maxOf(acc[2], e.timestamp)
        }
        return byOrigin.mapNotNull { (origin, acc) ->
            val service = HistoryService.of(origin) ?: return@mapNotNull null
            HistoryImportRecord(origin, service, origin.substringAfter('@').toLongOrNull() ?: 0L, acc[0].toInt(), acc[1], acc[2])
        }.sortedByDescending { it.importedAt }
    }

    fun records(history: Flow<List<PlayEvent>>): Flow<List<HistoryImportRecord>> =
        history.map { records(it) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // what the exports would add: duplicates, plays aurora recorded itself and rows that are no music play are left out
    fun plan(rows: List<ExportedPlay>, skipped: Map<ImportSkip, Int>, files: List<String>, history: List<PlayEvent>,
             library: Collection<Song>, importedAt: Long, now: Long = importedAt, room: Int = PlayHistoryStore.MAX - history.size): HistoryImportPreview {
        val skips = EnumMap<ImportSkip, Int>(ImportSkip::class.java).apply { putAll(skipped) }
        fun skip(reason: ImportSkip, n: Int = 1) { if (n > 0) skips.merge(reason, n, Int::plus) }
        val linker = HistoryLinker(library + history.asSequence().filter { it.origin == null }.distinctBy { it.songId }.map { it.asSong() }.toList())
        val index = SpanIndex()
        val keys = HashMap<String, String>()
        for (e in history) {
            val id = (e.songId as String?).orEmpty()
            index.add(keys.getOrPut(id) { TrackMatch.key((e.artist as String?).orEmpty(), (e.title as String?).orEmpty()) }, Span.of(e))
        }
        val kept = ArrayList<PlayEvent>()
        val linkCache = HashMap<String, Song?>()
        // when two files list one play, the row that says more about it is the one kept
        for (row in rows.sortedWith(compareByDescending<ExportedPlay> { it.detail }.thenBy { it.start })) {
            if (row.at <= 0 || row.at > now + FUTURE_SLACK_MS) { skip(ImportSkip.NO_DATE); continue }
            val key = TrackMatch.key(row.artist, row.title)
            val span = Span(row.start, row.end, row.at, row.listenedMs, row.service, true)
            val clash = index.clash(key, span)
            if (clash != null) {
                skip(when { clash.service == null -> ImportSkip.IN_AURORA_HISTORY; clash.batch -> ImportSkip.DUPLICATE; else -> ImportSkip.ALREADY_IMPORTED })
                continue
            }
            index.add(key, span)
            val linked = linkCache.getOrPut("${row.spotifyTrack}|$key|${row.trackMs?.div(1000)}|${row.album}") { linker.link(row) }
            kept += row.toEvent(linked, origin(row.service, importedAt))
        }
        val fit = kept.sortedByDescending { it.timestamp }.take(room.coerceAtLeast(0))
        skip(ImportSkip.HISTORY_FULL, kept.size - fit.size)
        return HistoryImportPreview(fit, fit.mapNotNullTo(LinkedHashSet()) { HistoryService.of(it.origin) }, files,
            fit.minOfOrNull { it.timestamp }, fit.maxOfOrNull { it.timestamp },
            fit.count { !isImportedSongId(it.songId) }, fit.count { it.timeUnknown }, skips, fit.count { it.artist.isBlank() })
    }

    suspend fun scan(sources: List<ExportSource>, history: List<PlayEvent>, library: Collection<Song>, importedAt: Long,
                     progress: (HistoryScanProgress) -> Unit = {}): HistoryImportPreview = withContext(Dispatchers.IO) {
        val read = HistoryExports.read(sources, progress)
        withContext(Dispatchers.Default) { plan(read.plays, read.skipped, read.files, history, library, importedAt) }
    }

    suspend fun commit(preview: HistoryImportPreview, store: PlayHistoryStore, details: DetailedListeningRepository?): Int {
        val added = withContext(Dispatchers.Default) { store.addImported(preview.plays) }
        if (details != null && added.isNotEmpty()) details.importHistory(added)
        return added.size
    }

    suspend fun undo(origin: String, store: PlayHistoryStore, details: DetailedListeningRepository?): Int {
        val removed = withContext(Dispatchers.Default) { store.removeImport(origin) }
        if (details != null && removed.isNotEmpty()) details.removeHistory(removed)
        return removed.size
    }

    private val ExportedPlay.detail: Int get() =
        listOf(album.isNotEmpty(), spotifyTrack != null, ending != null, shuffle != null, trackMs != null).count { it }

    private val ExportedPlay.start: Long get() = if (atEnd) at - (listenedMs ?: 0) else at
    private val ExportedPlay.end: Long get() = if (atEnd) at else at + (listenedMs ?: 0)

    private fun ExportedPlay.toEvent(linked: Song?, origin: String): PlayEvent {
        val sameAlbum = linked != null && album.isNotBlank() && TrackMatch.norm(album) == TrackMatch.norm(linked.album)
        return PlayEvent(linked?.id ?: (IMPORTED_SONG_PREFIX + TrackMatch.key(artist, title)), title, artist, album,
            if (sameAlbum) linked!!.albumId else "", linked?.artistId.orEmpty(), linked?.artworkUrl.orEmpty(),
            ((trackMs ?: 0L) / 1000).toInt(), at, listenedMs, origin, shuffle, ending)
    }

    private fun PlayEvent.asSong() = Song((songId as String?).orEmpty(), (title as String?).orEmpty(), (artist as String?).orEmpty(),
        (album as String?).orEmpty(), (artworkUrl as String?).orEmpty(), durationSec, albumId = (albumId as String?).orEmpty(),
        artistId = (artistId as String?).orEmpty())

    // service null is a play aurora recorded itself
    private class Span(val start: Long, val end: Long, val stated: Long, val listened: Long?, val service: HistoryService?, val batch: Boolean) {
        companion object {
            fun of(e: PlayEvent): Span {
                val service = HistoryService.of(e.origin as String?)
                val ms = e.listeningMillis
                val atEnd = service == HistoryService.SPOTIFY
                return Span(if (atEnd) e.timestamp - ms else e.timestamp, if (atEnd) e.timestamp else e.timestamp + ms,
                    e.timestamp, e.listenedMs, service, false)
            }
        }
    }

    private class SpanIndex {
        private val byKey = HashMap<String, ArrayList<Span>>()

        fun add(key: String, span: Span) {
            val list = byKey.getOrPut(key) { ArrayList(2) }
            var i = list.size
            while (i > 0 && list[i - 1].start > span.start) i--
            list.add(i, span)
        }

        fun clash(key: String, span: Span): Span? {
            val list = byKey[key] ?: return null
            var hi = list.size
            var lo = 0
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (list[mid].start <= span.end + OVERLAP_MS) lo = mid + 1 else hi = mid }
            var i = lo - 1
            while (i >= 0 && list[i].start >= span.start - OVERLAP_MS - LONGEST_PLAY_MS) {
                val other = list[i--]
                if (other.service == null) {
                    if (other.start <= span.end + OVERLAP_MS && span.start <= other.end + OVERLAP_MS) return other
                } else if (other.service == span.service && sameRow(other, span)) return other
            }
            return null
        }

        private fun sameRow(a: Span, b: Span): Boolean {
            val tolerance = if (a.service == HistoryService.SPOTIFY) SPOTIFY_SAME_ROW_MS else SAME_ROW_MS
            if (abs(a.stated - b.stated) > tolerance) return false
            val x = a.listened
            val y = b.listened
            return x == null || y == null || abs(x - y) <= SAME_ROW_MS
        }
    }
}

// what the settings page drives: read exports, add them to the history and take them out again
class HistoryImporter(
    private val store: PlayHistoryStore,
    private val repository: MusicRepository,
    private val details: () -> DetailedListeningRepository,
    private val detailsOn: suspend () -> Boolean,
    private val changed: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val records: Flow<List<HistoryImportRecord>> = HistoryImports.records(store.history)

    suspend fun scan(sources: List<ExportSource>, progress: (HistoryScanProgress) -> Unit = {}): HistoryImportPreview = coroutineScope {
        val library = async { bounded(30_000) { repository.librarySongs(5000) } }
        val liked = bounded(60_000) { repository.starredSongs() }
        HistoryImports.scan(sources, store.snapshot(), liked + library.await(), clock(), progress)
    }

    suspend fun commit(preview: HistoryImportPreview): Int =
        HistoryImports.commit(preview, store, if (detailsOn()) details() else null).also { if (it > 0) changed() }

    suspend fun undo(origin: String): Int = HistoryImports.undo(origin, store, details()).also { if (it > 0) changed() }

    private suspend fun bounded(ms: Long, block: suspend () -> List<Song>): List<Song> = try {
        withTimeoutOrNull(ms) { block() }.orEmpty()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
}
