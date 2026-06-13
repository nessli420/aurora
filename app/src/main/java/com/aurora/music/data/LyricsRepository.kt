package com.aurora.music.data

import com.aurora.music.model.LyricLine
import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Resolved lyrics: timed [lines] (timeSec = -1 when unsynced) + where they came from. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)

/**
 * Lyrics provider. Tries the music server first (OpenSubsonic getLyricsBySongId — i.e. .lrc
 * files sitting next to the tracks), then falls back to the public LRCLIB database.
 */
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
        // Resolve a server result first (may be synced or plain).
        val server = runCatching { backendProvider()?.serverLyrics(song) }.getOrNull()
        // Real time-synced lyrics always win, regardless of source.
        if (server != null && server.synced) return server
        val lrc = if (lrclibEnabledProvider()) fetchLrcLib(song) else null
        if (lrc != null && lrc.synced) return lrc
        // No synced anywhere — fall back to plain (server's first, then LRCLIB's).
        return server ?: lrc
    }

    /**
     * Fetch the best LRCLIB result in a single pass, prioritising real synced lyrics: try the
     * exact /get match, then /search (which surfaces alternate uploads that may be synced even
     * when the exact record is plain-only), and only then fall back to plain text.
     */
    private suspend fun fetchLrcLib(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val exact = runCatching { lrcGet(song) }.getOrNull()
        exact?.syncedLyrics?.takeIf { it.isNotBlank() }?.let { return@withContext Lyrics(parseLrc(it), true, "LRCLIB") }

        val results = runCatching { lrcSearch(song) }.getOrNull().orEmpty()
        results.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
            ?.let { return@withContext Lyrics(parseLrc(it.syncedLyrics!!), true, "LRCLIB") }

        // No synced anywhere — plain fallback.
        exact?.plainLyrics?.takeIf { it.isNotBlank() }
            ?.let { return@withContext Lyrics(it.lines().map { l -> LyricLine(-1, l) }, false, "LRCLIB") }
        results.firstOrNull { !it.plainLyrics.isNullOrBlank() }
            ?.let { return@withContext Lyrics(it.plainLyrics!!.lines().map { l -> LyricLine(-1, l) }, false, "LRCLIB") }
        null
    }

    private fun lrcGet(song: Song): LrcLibDto? {
        val url = buildString {
            append("https://lrclib.net/api/get")
            append("?artist_name=").append(enc(song.artist))
            append("&track_name=").append(enc(song.title))
            if (song.album.isNotBlank()) append("&album_name=").append(enc(song.album))
            if (song.durationSec > 0) append("&duration=").append(song.durationSec)
        }
        http.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return gson.fromJson(body, LrcLibDto::class.java)
        }
    }

    private fun lrcSearch(song: Song): List<LrcLibDto> {
        val url = buildString {
            append("https://lrclib.net/api/search")
            append("?artist_name=").append(enc(song.artist))
            append("&track_name=").append(enc(song.title))
        }
        http.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return runCatching { gson.fromJson(body, Array<LrcLibDto>::class.java).toList() }.getOrDefault(emptyList())
        }
    }

    private fun req(url: String): Request =
        Request.Builder().url(url).header("User-Agent", "Aurora Music (Navidrome client)").build()

    private data class LrcLibDto(val syncedLyrics: String? = null, val plainLyrics: String? = null)

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private val TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        /** Parse an LRC body into timed lines (one per timestamp tag). */
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
