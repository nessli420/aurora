package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// a track as another app describes it; original is the service's own song when the list came from a catalogue Aurora plays
data class ImportTrack(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val original: Song? = null,
    val originalSource: ImportSource = ImportSource.OTHER,
    // the file an exported list points at
    val location: String = "",
)

data class ImportedPlaylist(val name: String, val tracks: List<ImportTrack>)

enum class ImportSource {
    LIBRARY, YOUTUBE_MUSIC, SPOTIFY, SOUNDCLOUD, OTHER;

    companion object {
        fun of(type: ServerType) = when (type) {
            ServerType.YOUTUBE_MUSIC -> YOUTUBE_MUSIC
            ServerType.SPOTIFY -> SPOTIFY
            ServerType.SOUNDCLOUD -> SOUNDCLOUD
            else -> LIBRARY
        }
    }
}

// one place a song can be found, searched in order: your own sources first, then the catalogues
class ImportStage(val source: ImportSource, val search: suspend (String) -> List<Song>)

data class ImportMatch(val track: ImportTrack, val song: Song?, val source: ImportSource?, val stage: Int)

data class ImportResult(
    val playlistId: String,
    val name: String,
    val total: Int,
    val found: Map<ImportSource, Int>,
    val missing: List<ImportTrack>,
    // a single server can only hold its own songs, so the catalogue matches were left out
    val ownSourceOnly: Boolean = false,
    // a spotify link only shares its first hundred songs
    val limited: Boolean = false,
) {
    val matched: Int get() = found.values.sum()
}

sealed interface ImportProgress {
    data object Reading : ImportProgress
    data class Matching(val done: Int, val total: Int) : ImportProgress
    data object Saving : ImportProgress
}

enum class ImportFailure { UNSUPPORTED, UNREADABLE, EMPTY, OFFLINE, NOT_SAVED }

class ImportException(val failure: ImportFailure, message: String? = null, cause: Throwable? = null) : Exception(message ?: failure.name, cause)

object PlaylistMatcher {
    private const val PARALLEL = 4
    private const val SEARCH_TIMEOUT_MS = 12_000L

    suspend fun matchAll(tracks: List<ImportTrack>, stages: List<ImportStage>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<ImportMatch> {
        val out = arrayOfNulls<ImportMatch>(tracks.size)
        val done = AtomicInteger()
        val gate = Semaphore(PARALLEL)
        onProgress(0, tracks.size)
        coroutineScope {
            tracks.forEachIndexed { i, track ->
                launch {
                    gate.withPermit { out[i] = match(track, stages) }
                    onProgress(done.incrementAndGet(), tracks.size)
                }
            }
        }
        return out.map { it!! }
    }

    suspend fun match(track: ImportTrack, stages: List<ImportStage>): ImportMatch {
        val query = query(track)
        if (query.isNotBlank()) stages.forEachIndexed { i, stage ->
            val candidates = try { withTimeoutOrNull(SEARCH_TIMEOUT_MS) { stage.search(query) }.orEmpty() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            best(track, candidates)?.let { return ImportMatch(track, it, stage.source, i) }
        }
        return track.original?.let { ImportMatch(track, it, track.originalSource, stages.size) } ?: ImportMatch(track, null, null, -1)
    }

    fun best(track: ImportTrack, candidates: List<Song>): Song? =
        candidates.asSequence().filter { it.streamUrl.isNotBlank() }
            .map { it to score(track, it) }.filter { it.second >= threshold(track) }
            .maxByOrNull { it.second }?.first

    // with neither artist nor length there is only the title to go on
    private fun threshold(track: ImportTrack) = if (track.artist.isBlank() && track.durationSec <= 0) 3 else 5

    fun score(track: ImportTrack, song: Song): Int {
        val a = core(track.title)
        val b = core(song.title)
        if (a.isEmpty() || b.isEmpty()) return -10
        val title = when {
            a == b -> 3
            a.length >= 4 && b.length >= 4 && (b.contains(a) || a.contains(b)) -> 1
            else -> return -10
        }
        val artist = when {
            track.artist.isBlank() -> 0
            credits(track.artist).intersect(credits(song.artist)).isNotEmpty() -> 3
            // catalogue uploads often put the artist in the title instead
            credits(track.artist).any { it.length >= 3 && TrackMatch.norm(song.title).contains(it) } -> 2
            else -> -2
        }
        val length = if (track.durationSec <= 0 || song.durationSec <= 0) 0 else abs(track.durationSec - song.durationSec).let {
            when {
                it <= 3 -> 2
                it <= 8 -> 1
                it > 25 -> -4
                else -> 0
            }
        }
        return title + artist + length
    }

    fun query(track: ImportTrack): String {
        val artist = track.artist.split(CREDIT_SPLIT).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return listOf(artist, recordingTitle(stripTags(track.title))).filter { it.isNotBlank() }.joinToString(" ")
    }

    // remasters and edition notes are the same recording; live, acoustic and remixes are not and stay in the title
    private val TAGS = Regex("(?i)\\s*(?:[-–]\\s*|[(\\[]\\s*)(?:\\d{4}\\s+)?(?:remaster(?:ed)?|digital(?:ly)? remaster(?:ed)?|single version|album version|radio edit|mono|stereo|explicit|clean|bonus track|deluxe(?: edition)?|original mix)(?:\\s+\\d{4})?(?:\\s+version)?\\s*[)\\]]?")
    private fun stripTags(title: String) = title.replace(TAGS, "").trim()
    private fun core(title: String) = TrackMatch.norm(recordingTitle(stripTags(title)))

    private val CREDIT_SPLIT = Regex("\\s*(?:,|;|&|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)
    private fun credits(value: String) = value.split(CREDIT_SPLIT).map { TrackMatch.norm(it).removePrefix("the ") }.filter { it.isNotBlank() }.toSet()
}

// exported lists: m3u, xspf, csv or tsv from Exportify, TuneMyMusic, Soundiiz or Apple Music, or one "Artist - Title" per line
object PlaylistFiles {
    fun read(fileName: String, bytes: ByteArray): ImportedPlaylist {
        val text = CueSheets.decode(bytes).removePrefix("﻿")
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim()
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val trimmed = text.trimStart()
        val (name, tracks) = when {
            ext in setOf("m3u", "m3u8") || trimmed.startsWith("#EXTM3U") ->
                base to M3u.parse(text).map { ImportTrack(it.title, it.artist, durationSec = it.durationSec, location = it.location) }
            ext == "xspf" || trimmed.startsWith("<?xml") && trimmed.contains("<trackList", ignoreCase = true) -> xspf(text, base)
            else -> table(text, base)
        }
        return ImportedPlaylist(name.ifBlank { base }, tracks.filter { it.title.isNotBlank() })
    }

    private fun xspf(text: String, base: String): Pair<String, List<ImportTrack>> {
        fun tag(block: String, name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val tracks = Regex("<track>(.*?)</track>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
            val block = m.groupValues[1]
            ImportTrack(tag(block, "title"), tag(block, "creator"), tag(block, "album"), ((tag(block, "duration").toLongOrNull() ?: 0) / 1000).toInt())
        }.toList()
        val head = text.substringBefore("<trackList")
        return (Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(head)?.groupValues?.get(1)?.let(::unescape)?.trim() ?: base) to tracks
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private val TITLE = listOf("track name", "title", "song name", "track title", "name", "track", "song")
    private val ARTIST = listOf("artist name(s)", "artist names", "artist name", "artist", "artists", "creator", "album artist")
    private val ALBUM = listOf("album name", "album", "album title")
    private val LENGTH = listOf("duration (ms)", "duration_ms", "track duration (ms)", "duration", "time", "length")
    private val NAME = listOf("playlist name", "playlist")

    private fun table(text: String, base: String): Pair<String, List<ImportTrack>> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return base to emptyList()
        val head = lines.first()
        val sep = listOf('\t', ',', ';').maxByOrNull { c -> head.count { it == c } }?.takeIf { c -> head.count { it == c } > 0 }
        val header = sep?.let { split(head, it).map { c -> c.trim().lowercase(Locale.ROOT) } }
        fun col(names: List<String>) = header?.let { h -> names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } } } ?: -1
        val title = col(TITLE)
        if (sep == null || title < 0) return base to lines.map(::line)
        val artist = col(ARTIST)
        val album = col(ALBUM)
        val length = col(LENGTH)
        val millis = length >= 0 && header!![length].contains("ms")
        val list = col(NAME)
        val rows = lines.drop(1).map { split(it, sep) }
        val tracks = rows.map { r ->
            fun at(i: Int) = r.getOrNull(i)?.trim().orEmpty()
            ImportTrack(at(title), at(artist), at(album), seconds(at(length), millis))
        }
        val name = if (list >= 0) rows.firstNotNullOfOrNull { it.getOrNull(list)?.trim()?.takeIf(String::isNotBlank) } else null
        return (name ?: base) to tracks
    }

    // "Artist - Title", or just a title
    private fun line(raw: String): ImportTrack {
        val s = raw.trim().removePrefix("﻿").replace(Regex("^\\d+[.)]\\s+"), "")
        val dash = Regex("\\s+[-–—]\\s+").find(s) ?: return ImportTrack(s)
        return ImportTrack(s.substring(dash.range.last + 1).trim(), s.substring(0, dash.range.first).trim())
    }

    private fun seconds(value: String, millis: Boolean): Int {
        if (value.isBlank()) return 0
        if (':' in value) return value.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, n -> acc * 60 + n }
        val n = value.replace(',', '.').toDoubleOrNull() ?: return 0
        return if (millis || n > 20_000) (n / 1000).toInt() else n.toInt()
    }

    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }
}
