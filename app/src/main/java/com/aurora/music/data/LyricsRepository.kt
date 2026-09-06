package com.aurora.music.data

import com.aurora.music.model.LyricLine
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)

class LyricsRepository(
    private val backendProvider: () -> MediaBackend?,
    private val lrclibEnabledProvider: () -> Boolean,
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun lyricsFor(song: Song): Lyrics? {
        val server = runCatching { backendProvider()?.serverLyrics(song) }.getOrNull()
        // synced always wins regardless of source
        if (server != null && server.synced) return server
        val lrc = if (lrclibEnabledProvider()) fetchLrcLib(song) else null
        if (lrc != null && lrc.synced) return lrc
        return server ?: lrc
    }

    private suspend fun fetchLrcLib(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val cTitle = cleanTitle(song.title)
        val cArtist = primaryArtist(song.artist)

        // exact get first (artist+title+album+duration) cheapest and most precise
        runCatching { lrcGet(song.artist, song.title, song.album, song.durationSec) }.getOrNull()
            ?.let { syncedFrom(it) }?.let { return@withContext it }
        // mainstream tags often carry feat/remaster/version noise that the strict get rejects retry cleaned
        if (cTitle != song.title || cArtist != song.artist) {
            runCatching { lrcGet(cArtist, cTitle, song.album, song.durationSec) }.getOrNull()
                ?.let { syncedFrom(it) }?.let { return@withContext it }
        }

        // gather search candidates from a few angles then score+pick the closest match
        val cands = LinkedHashSet<LrcLibDto>()
        runCatching { lrcSearch(song.title, song.artist) }.getOrNull()?.let { cands += it }
        if (cTitle != song.title || cArtist != song.artist)
            runCatching { lrcSearch(cTitle, cArtist) }.getOrNull()?.let { cands += it }
        runCatching { lrcSearchQ("$cArtist $cTitle") }.getOrNull()?.let { cands += it }

        val plausible = cands.filter { plausible(it, song, cTitle) }
        // synced wins outright pick the best-scoring synced candidate then fall back to plain
        val best = plausible.filter { !it.syncedLyrics.isNullOrBlank() }.maxByOrNull { score(it, song) }
            ?: plausible.maxByOrNull { score(it, song) }
        best?.let { lyricsFrom(it) }
    }

    // strip version/feat noise so the strict lrclib lookup matches mainstream releases
    private fun cleanTitle(t: String): String {
        var s = PAREN_NOISE.replace(t, " ")
        s = DASH_NOISE.replace(s, " ")
        s = TRAIL_FEAT.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim().ifBlank { t }
    }

    // primary (first credited) artist lrclib indexes by the lead artist not the feature list
    private fun primaryArtist(a: String): String =
        a.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim()?.ifBlank { a } ?: a

    // a candidate is usable only if its title clearly matches guards against wrong-song lyrics
    private fun plausible(dto: LrcLibDto, song: Song, cTitle: String): Boolean {
        val dt = TrackMatch.norm(dto.trackName ?: return false)
        val titleOk = dt == TrackMatch.norm(song.title) || dt == TrackMatch.norm(cTitle)
        if (!titleOk) return false
        val da = TrackMatch.norm(dto.artistName ?: "")
        val sa = TrackMatch.norm(song.artist)
        val pa = TrackMatch.norm(primaryArtist(song.artist))
        // blank artist on either side passes otherwise need some overlap
        return da.isBlank() || sa.isBlank() || da == sa || da == pa ||
            da.contains(pa) || pa.contains(da) || sa.contains(da)
    }

    private fun score(dto: LrcLibDto, song: Song): Int {
        var s = 0
        if (!dto.syncedLyrics.isNullOrBlank()) s += 1000
        val dur = dto.duration?.toInt()
        if (dur != null && song.durationSec > 0) {
            val d = abs(dur - song.durationSec)
            s += if (d <= TrackMatch.DURATION_TOLERANCE_SEC) 100 else -d
        }
        if (TrackMatch.norm(dto.trackName ?: "") == TrackMatch.norm(song.title)) s += 10
        return s
    }

    private fun syncedFrom(dto: LrcLibDto): Lyrics? =
        dto.syncedLyrics?.takeIf { it.isNotBlank() }?.let { Lyrics(parseLrc(it), true, "LRCLIB") }

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
        private val DASH_NOISE = Regex("""\s[-–]\s[^-–]*\b($VERSIONS)\b.*$""", RegexOption.IGNORE_CASE)
        // trailing un-bracketed feature credit  e.g. "Song feat. X"
        private val TRAIL_FEAT = Regex("""[(\[]?\s*\b(feat|ft|featuring)\b\.?.*$""", RegexOption.IGNORE_CASE)
        // lead artist is everything before the first collaborator separator
        private val ARTIST_SPLIT = Regex("""\s*(,|&|;|/|\bfeat\b\.?|\bft\b\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)

        private val TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        fun parseLrc(lrc: String): List<LyricLine> {
            val out = mutableListOf<Pair<Int, String>>()
            lrc.lineSequence().forEach { raw ->
                val tags = TAG.findAll(raw).toList()
                if (tags.isEmpty()) return@forEach
                val text = raw.substring(tags.last().range.last + 1).trim()
                tags.forEach { m ->
                    val min = m.groupValues[1].toIntOrNull() ?: 0
                    val sec = m.groupValues[2].toIntOrNull() ?: 0
                    out.add((min * 60 + sec) to text)
                }
            }
            return out.sortedBy { it.first }.map { LyricLine(it.first, it.second) }
        }
    }
}
