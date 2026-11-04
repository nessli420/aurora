package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val timeSec: Int,
    val text: String,
    val startMs: Long = timeSec * 1000L,
    val words: List<LyricWord> = emptyList(),
) {
    val displayText: String get() = if (words.isEmpty()) text else words.joinToString("") { it.text }
}

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)

class LyricsRepository(
    private val backendProvider: () -> MediaBackend?,
    private val lrclibEnabledProvider: () -> Boolean,
    private val separatorsProvider: suspend () -> ArtistSeparators = { ArtistSeparators() },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val orderProvider: suspend () -> List<String> = { DEFAULT_LYRICS_SOURCES },
    // whether spotify may supply lyrics for songs of this kind of source, its own songs included
    private val spotifyAllowed: suspend (ServerType?) -> Boolean = { true },
) {
    private val gson = Gson()

    // sources are asked in the chosen order; synced lyrics win over an earlier plain result
    suspend fun lyricsFor(song: Song): Lyrics? {
        if (song.id.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX)) return null
        // a file's own lyrics beat every lookup
        attempt { withContext(Dispatchers.IO) { FileLyrics.of(song) } }?.let { return it }
        val backend = backendProvider()
        val type = backend?.sourceType(song)
        val spotify = spotifyAllowed(type)
        var plain: Lyrics? = null
        for (source in orderProvider()) {
            val found = when (source) {
                "server" -> if (type == ServerType.SPOTIFY && !spotify) null else attempt { backend?.serverLyrics(song) }
                "spotify" -> if (spotify) attempt { backend?.catalogueLyrics(song) } else null
                "lrclib" -> if (lrclibEnabledProvider()) fetchLrcLib(song) else null
                else -> null
            } ?: continue
            if (found.synced) return found
            if (plain == null) plain = found
        }
        return plain
    }

    private suspend fun fetchLrcLib(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val cTitle = cleanTitle(song.title)
        val separators = separatorsProvider()
        val artists = artistNames(song.artist, separators)
        val credits = (listOf(song.artist.trim()) + artists).distinctBy(::artistNameKey)
        val titles = listOf(song.title, cTitle).distinct()
        var plain: Lyrics? = null

        // Exhaust the full credit first, then each artist in credited order.
        for (artist in credits) {
            currentCoroutineContext().ensureActive()
            val candidates = linkedSetOf<LrcLibDto>()
            for (title in titles) {
                attempt { lrcGet(artist, title, song.album, song.durationSec) }?.let { dto ->
                    if (plausible(dto, song, cTitle, artists, separators)) {
                        syncedFrom(dto)?.let { return@withContext it }
                        candidates += dto
                    }
                }
            }
            for (title in titles) {
                attempt { lrcSearch(title, artist) }?.let { candidates += it }
            }
            attempt { lrcSearchQ("$artist $cTitle") }?.let { candidates += it }
            val plausible = candidates.filter { plausible(it, song, cTitle, artists, separators) }
                .sortedByDescending { score(it, song) }
            plausible.firstNotNullOfOrNull(::syncedFrom)?.let { return@withContext it }
            if (plain == null) plain = plausible.firstNotNullOfOrNull(::lyricsFrom)
        }
        plain
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? {
        currentCoroutineContext().ensureActive()
        return try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private fun artistNames(credit: String, separators: ArtistSeparators): List<String> {
        if (credit.isBlank()) return emptyList()
        val rules = separators.rules.orEmpty()
        val extras = listOf(
            ArtistSeparator("feat", SeparatorMatch.WORD), ArtistSeparator("ft", SeparatorMatch.WORD),
            ArtistSeparator("featuring", SeparatorMatch.WORD), ArtistSeparator("x", SeparatorMatch.SPACED),
            ArtistSeparator("×", SeparatorMatch.SPACED), ArtistSeparator("+", SeparatorMatch.SPACED),
            ArtistSeparator("|", SeparatorMatch.ANYWHERE),
        ).filter { extra -> rules.none {
            it.text.equals(extra.text, ignoreCase = true) ||
                (it.match == SeparatorMatch.OFF && it.text?.trimEnd('.').equals(extra.text, ignoreCase = true))
        } }
        val names = ArtistSeparators(rules + extras).split(credit)
        return (if (names.size > 1) names.map { it.trim(' ', '(', ')', '[', ']') } else names)
            .filter(String::isNotBlank).distinctBy(::artistNameKey)
    }

    // strip version/feat noise so the strict lrclib lookup matches mainstream releases
    private fun cleanTitle(t: String): String {
        var s = PAREN_NOISE.replace(t, " ")
        s = DASH_NOISE.replace(s, " ")
        s = TRAIL_FEAT.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim().ifBlank { t }
    }

    private fun plausible(dto: LrcLibDto, song: Song, cTitle: String, artists: List<String>, separators: ArtistSeparators): Boolean {
        val title = matchKey(dto.trackName ?: return false)
        if (title.isBlank() || title !in setOf(matchKey(song.title), matchKey(cTitle))) return false
        val artist = dto.artistName.orEmpty()
        if (artist.isBlank() || song.artist.isBlank()) return true
        if (matchKey(artist) == matchKey(song.artist)) return true
        val credited = artists.map(::matchKey).filter(String::isNotBlank).toSet()
        return artistNames(artist, separators).any { matchKey(it) in credited }
    }

    private fun matchKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun score(dto: LrcLibDto, song: Song): Int {
        var s = 0
        if (!dto.syncedLyrics.isNullOrBlank()) s += 1000
        val dur = dto.duration?.toInt()
        if (dur != null && song.durationSec > 0) {
            val d = abs(dur - song.durationSec)
            s += if (d <= TrackMatch.DURATION_TOLERANCE_SEC) 100 else -d
        }
        if (matchKey(dto.trackName ?: "") == matchKey(song.title)) s += 10
        return s
    }

    private fun syncedFrom(dto: LrcLibDto): Lyrics? =
        dto.syncedLyrics?.takeIf { it.isNotBlank() }?.let { parseLrc(it).takeIf { lines -> lines.any { line -> line.text.isNotBlank() } } }
            ?.let { Lyrics(it, true, "LRCLIB") }

    private fun lyricsFrom(dto: LrcLibDto): Lyrics? {
        syncedFrom(dto)?.let { return it }
        return dto.plainLyrics?.takeIf { it.isNotBlank() }
            ?.let { Lyrics(it.lines().map { l -> LyricLine(-1, l) }, false, "LRCLIB") }
    }

    private fun lrcGet(artist: String, title: String, album: String, durationSec: Int): LrcLibDto? {
        val url = buildString {
            append("https://lrclib.net/api/get")
            append("?artist_name=").append(enc(artist))
            append("&track_name=").append(enc(title))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        http.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return runCatching { gson.fromJson(body, LrcLibDto::class.java) }.getOrNull()
        }
    }

    private fun lrcSearch(track: String, artist: String): List<LrcLibDto> {
        val url = "https://lrclib.net/api/search?track_name=${enc(track)}&artist_name=${enc(artist)}"
        return searchUrl(url)
    }

    private fun lrcSearchQ(q: String): List<LrcLibDto> =
        searchUrl("https://lrclib.net/api/search?q=${enc(q)}")

    private fun searchUrl(url: String): List<LrcLibDto> {
        http.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return runCatching { gson.fromJson(body, Array<LrcLibDto>::class.java).toList() }.getOrDefault(emptyList())
        }
    }

    private fun req(url: String): Request =
        Request.Builder().url(url).header("User-Agent", "Aurora Music (Navidrome client)").build()

    private data class LrcLibDto(
        val id: Long? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
    )

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private const val VERSIONS =
            "remaster(ed)?|deluxe|expanded|edition|version|mono|stereo|anniversary|bonus|" +
            "explicit|clean|radio edit|single version|album version|re-?recorded|re-?master|" +
            "remix|instrumental|acoustic|demo|live|mix|take \\d+"
        // parenthetical/bracket version tags  e.g. "(Remastered 2011)" "[Deluxe Edition]"
        private val PAREN_NOISE = Regex("""[(\[][^)\]]*\b($VERSIONS)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        // trailing dash suffix  e.g. "Song - 2011 Remaster"
        private val DASH_NOISE = Regex("""\s[-â€“]\s[^-â€“]*\b($VERSIONS)\b.*$""", RegexOption.IGNORE_CASE)
        // trailing un-bracketed feature credit  e.g. "Song feat. X"
        private val TRAIL_FEAT = Regex("""[(\[]?\s*\b(feat|ft|featuring)\b\.?.*$""", RegexOption.IGNORE_CASE)

        private val TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val LEADING_TAGS = Regex("""^(?:\s*\[\d+:\d{1,2}(?:[.:]\d{1,3})?])+""")
        private val WORD_TAG = Regex("""[<\[](\d+):(\d{1,2})(?:[.:](\d{1,3}))?[>\]]""")

        private fun tagMs(m: MatchResult): Long {
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val frac = m.groupValues[3]
            val ms = (frac.toLongOrNull() ?: 0L) * when (frac.length) { 1 -> 100; 2 -> 10; else -> 1 }
            return min * 60_000 + sec * 1000 + ms
        }

        fun parseLrc(lrc: String): List<LyricLine> {
            val out = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val lead = LEADING_TAGS.find(raw) ?: return@forEach
                val body = raw.substring(lead.range.last + 1)
                val starts = TAG.findAll(lead.value).map(::tagMs).toList()
                val first = timedLine(starts.first(), body)
                starts.forEach { ms ->
                    val shift = ms - first.startMs
                    out += first.copy(timeSec = (ms / 1000).toInt(), startMs = ms,
                        words = first.words.map { it.copy(startMs = it.startMs + shift, endMs = if (it.endMs < 0) -1 else it.endMs + shift) })
                }
            }
            return out.sortedBy { it.startMs }
        }

        // enhanced lrc: <mm:ss.xx> (or inline [mm:ss.xx]) before each word
        fun timedLine(startMs: Long, body: String): LyricLine {
            val tags = WORD_TAG.findAll(body).toList()
            if (tags.isEmpty()) return LyricLine((startMs / 1000).toInt(), body.trim(), startMs)
            val segments = mutableListOf<Pair<Long, String>>()
            var at = startMs
            var from = 0
            for (m in tags) {
                segments += at to body.substring(from, m.range.first)
                at = tagMs(m)
                from = m.range.last + 1
            }
            segments += at to body.substring(from)
            val words = segments.mapIndexedNotNull { i, (time, text) ->
                if (text.isEmpty()) null else LyricWord(time, segments.getOrNull(i + 1)?.first ?: -1, text)
            }
            return trimmedLine(startMs, words)
        }

        fun trimmedLine(startMs: Long, timed: List<LyricWord>): LyricLine {
            val words = timed.toMutableList()
            while (words.firstOrNull()?.text?.isBlank() == true) words.removeAt(0)
            while (words.lastOrNull()?.text?.isBlank() == true) words.removeAt(words.lastIndex)
            if (words.isNotEmpty()) {
                words[0] = words[0].copy(text = words[0].text.trimStart())
                words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
            }
            return LyricLine((startMs / 1000).toInt(), words.joinToString("") { it.text }, startMs, words)
        }
    }
}
