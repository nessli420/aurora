package com.aurora.music.data

import com.aurora.music.util.TrackMatch
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.Locale
import java.util.zip.ZipInputStream

class ExportRead(val plays: List<ExportedPlay>, val skipped: Map<ImportSkip, Int>, val files: List<String>)

// reads every export row one at a time, so a history of hundreds of thousands of rows never sits in memory as text
object HistoryExports {
    private const val MAX_DEPTH = 3
    private const val PROGRESS_EVERY_NS = 150_000_000L

    suspend fun read(sources: List<ExportSource>, progress: (HistoryScanProgress) -> Unit = {}): ExportRead {
        val c = Collector(sources.sumOf { it.size ?: 0L }, progress)
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            source.open().use { raw -> readAny(source.name, Counting(raw, c), c, 0) }
        }
        c.report(force = true)
        return c.finish()
    }

    fun readAll(files: List<Pair<String, () -> InputStream>>): ExportRead {
        val c = Collector(0L) {}
        files.forEach { (name, open) -> open().use { readAny(name, it, c, 0) } }
        return c.finish()
    }

    private fun readAny(name: String, input: InputStream, c: Collector, depth: Int) {
        val stream = BufferedInputStream(input, 64 * 1024)
        stream.mark(8)
        val head = ByteArray(4).let { it.copyOf(stream.read(it).coerceAtLeast(0)) }
        stream.reset()
        val lower = name.lowercase(Locale.ROOT)
        val base = lower.substringAfterLast('/')
        when {
            base.startsWith("._") || "__macosx/" in lower -> Unit
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> if (depth < MAX_DEPTH) readZip(stream, c, depth + 1)
            lower.endsWith(".json") -> readJson(name, stream, c)
            lower.endsWith(".csv") -> readCsv(name, stream, c)
            lower.endsWith(".html") || lower.endsWith(".htm") -> readHtml(name, stream, c)
        }
    }

    private fun readZip(input: InputStream, c: Collector, depth: Int) {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) readAny(entry.name, object : FilterInputStream(zip) { override fun close() = Unit }, c, depth)
            zip.closeEntry()
        }
    }

    // spotify's, apple's and google's json files are arrays of small objects; any other file is left alone
    private fun readJson(name: String, input: InputStream, c: Collector) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        if (runCatching { reader.peek() }.getOrNull() != JsonToken.BEGIN_ARRAY) return
        reader.beginArray()
        val video = "streaming_history_video" in name.lowercase(Locale.ROOT)
        var kind: JsonKind? = null
        while (reader.hasNext()) {
            val o = JsonParser.parseReader(reader) as? JsonObject ?: return
            val k = kind ?: JsonKind.of(o)?.also { kind = it; if (it != JsonKind.APPLE_LIBRARY) c.files += name.substringAfterLast('/') } ?: return
            if (k != JsonKind.APPLE_LIBRARY) c.row()
            when {
                video && k == JsonKind.SPOTIFY_EXTENDED -> c.skip(ImportSkip.VIDEO)
                k == JsonKind.SPOTIFY_EXTENDED -> spotifyExtended(o, c)
                k == JsonKind.SPOTIFY_ACCOUNT -> spotifyAccount(o, name, c)
                k == JsonKind.YOUTUBE -> youtube(o, c, worded = true)
                k == JsonKind.APPLE_LIBRARY -> c.apple.library(o.text("Title"), o.text("Artist"), o.text("Album"))
            }
        }
    }

    private enum class JsonKind {
        SPOTIFY_EXTENDED, SPOTIFY_ACCOUNT, YOUTUBE, APPLE_LIBRARY;

        companion object {
            fun of(o: JsonObject): JsonKind? = when {
                o.has("ms_played") && o.has("ts") -> SPOTIFY_EXTENDED
                o.has("msPlayed") && o.has("endTime") -> SPOTIFY_ACCOUNT
                o.has("header") && o.has("time") && o.has("title") -> YOUTUBE
                o.has("Title") && o.has("Artist") && o.has("Track Identifier") -> APPLE_LIBRARY
                else -> null
            }
        }
    }

    private fun spotifyExtended(o: JsonObject, c: Collector) {
        val ms = o.long("ms_played")
        val track = o.text("master_metadata_track_name")
        val artist = o.text("master_metadata_album_artist_name")
        when {
            o.text("spotify_episode_uri") != null || o.text("episode_name") != null -> return c.skip(ImportSkip.PODCAST)
            o.text("audiobook_uri") != null || o.text("audiobook_chapter_uri") != null || o.text("audiobook_title") != null -> return c.skip(ImportSkip.AUDIOBOOK)
            track == null || artist == null -> return c.skip(ImportSkip.NO_TRACK)
            // a private session is one spotify was asked not to remember, the same rule aurora follows for its own
            o.bool("incognito_mode") == true -> return c.skip(ImportSkip.PRIVATE)
            ms == null || ms <= 0 -> return c.skip(ImportSkip.NOT_PLAYED)
        }
        // ts is the moment the stream ended, in utc
        val at = o.text("ts")?.let(::instant) ?: return c.skip(ImportSkip.NO_DATE)
        val ending = when (o.text("reason_end")) {
            "trackdone" -> PlayEnding.COMPLETED
            "fwdbtn", "backbtn", "clickrow" -> PlayEnding.SKIPPED
            "logout", "trackerror", "unexpected-exit", "unexpected-exit-while-paused" -> PlayEnding.STOPPED
            else -> if (o.bool("skipped") == true) PlayEnding.SKIPPED else null
        }
        c.play(ExportedPlay(HistoryService.SPOTIFY, at, true, c.pool(track!!), c.pool(artist!!),
            c.pool(o.text("master_metadata_album_album_name").orEmpty()), ms, null,
            o.text("spotify_track_uri")?.removePrefix("spotify:track:")?.takeIf { it.length == 22 }, o.bool("shuffle"), ending))
    }

    private val SPOTIFY_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun spotifyAccount(o: JsonObject, name: String, c: Collector) {
        if ("podcast" in name.lowercase(Locale.ROOT) || o.has("podcastName") || o.has("episodeName")) return c.skip(ImportSkip.PODCAST)
        val track = o.text("trackName")
        val artist = o.text("artistName")
        val ms = o.long("msPlayed")
        when {
            track == null || artist == null -> return c.skip(ImportSkip.NO_TRACK)
            ms == null || ms <= 0 -> return c.skip(ImportSkip.NOT_PLAYED)
        }
        // account data writes the end of a stream in utc, to the minute
        val at = o.text("endTime")?.let { runCatching { LocalDateTime.parse(it.trim(), SPOTIFY_MINUTE).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
            ?: return c.skip(ImportSkip.NO_DATE)
        c.play(ExportedPlay(HistoryService.SPOTIFY, at, true, c.pool(track!!), c.pool(artist!!), "", ms))
    }

    // the json title carries google's wording around the song title, the html link holds the bare title
    private fun youtube(o: JsonObject, c: Collector, worded: Boolean) {
        val details = o.get("details")?.takeIf { it.isJsonArray }?.asJsonArray
        if (details?.any { (it as? JsonObject)?.text("name")?.contains("Google Ads") == true } == true) return c.skip(ImportSkip.AD)
        val url = o.text("titleUrl")
        if (url != null && "watch?v=" !in url) return c.skip(ImportSkip.NOT_A_PLAY)
        if (o.text("header") != "YouTube Music") return c.skip(ImportSkip.VIDEO)
        val channel = o.get("subtitles")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let { (it as? JsonObject)?.text("name") }
        if (url == null || channel == null) return c.skip(ImportSkip.REMOVED)
        val title = o.text("title") ?: return c.skip(ImportSkip.NO_TRACK)
        // google writes the time a video was started, in utc
        val at = o.text("time")?.let(::instant) ?: return c.skip(ImportSkip.NO_DATE)
        val play = ExportedPlay(HistoryService.YOUTUBE_MUSIC, at, false, c.pool(title), c.pool(YouTubeTitles.artist(channel)), "", null)
        if (worded) c.worded += play else c.play(play)
    }

    private fun readCsv(name: String, input: InputStream, c: Collector) {
        val rows = Csv(InputStreamReader(input, Charsets.UTF_8))
        val header = rows.next()?.map { it.trim().removePrefix("﻿").trim() } ?: return
        val col = header.withIndex().associate { (i, h) -> h to i }
        fun List<String>.at(column: String) = col[column]?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
        val activity = AppleActivity.of(col)
        val daily = "Track Description" in col && "Date Played" in col && "Hours" in col
        if (activity == null && !daily) return
        if (activity != null) c.files += name.substringAfterLast('/')
        while (true) {
            val row = rows.next() ?: break
            if (row.size <= 1 && row.firstOrNull().isNullOrBlank()) continue
            if (activity != null) { c.row(); activity.read(row, c) }
            else c.apple.daily(row.at("Date Played"), row.at("Hours"), row.at("Track Description"))
        }
    }

    // apple's play activity: one row per playback event, times in utc
    private class AppleActivity(private val col: Map<String, Int>) {
        private fun List<String>.at(name: String) = col[name]?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        fun read(row: List<String>, c: Collector) {
            val type = row.at("Event Type")
            if (type != null && type != "PLAY_END") return c.skip(ImportSkip.NOT_A_PLAY)
            if (row.at("Item Type") in NOT_PLAYS) return c.skip(ImportSkip.NOT_A_PLAY)
            if (row.at("Media Type")?.uppercase(Locale.ROOT).let { it != null && it != "AUDIO" }) return c.skip(ImportSkip.VIDEO)
            val title = row.at("Song Name") ?: row.at("Content Name") ?: return c.skip(ImportSkip.NO_TRACK)
            val ms = row.at("Play Duration Milliseconds")?.toLongOrNull()
            if (ms == null || ms <= 0) return c.skip(ImportSkip.NOT_PLAYED)
            val at = row.at("Event Start Timestamp")?.let(::instant) ?: return c.skip(ImportSkip.NO_DATE)
            val reason = row.at("End Reason Type")
            val ending = when (reason) {
                "NATURAL_END_OF_TRACK" -> PlayEnding.COMPLETED
                "TRACK_SKIPPED_FORWARDS", "TRACK_SKIPPED_BACKWARDS", "MANUALLY_SELECTED_PLAYBACK_OF_A_DIFF_ITEM" -> PlayEnding.SKIPPED
                "EXITED_APPLICATION", "PLAYBACK_STOPPED_DUE_TO_SESSION_TIMEOUT", "FAILED_TO_LOAD" -> PlayEnding.STOPPED
                else -> null
            }
            val shuffle = when (row.at("Shuffle Play")) { "SHUFFLE_ON" -> true; "SHUFFLE_OFF" -> false; else -> null }
            val album = row.at("Album Name") ?: row.at("Container Album Name")
            c.play(ExportedPlay(HistoryService.APPLE_MUSIC, at, false, c.pool(title), c.pool(row.at("Artist Name").orEmpty()), c.pool(album.orEmpty()),
                ms, row.at("Media Duration In Milliseconds")?.toLongOrNull()?.takeIf { it > 0 }, null, shuffle, ending, reason in CONTINUES))
        }

        companion object {
            val NOT_PLAYS = setOf("AGGREGATE_NON_CATALOG_PLAY_TIME", "INVALID")
            val CONTINUES = setOf("PLAYBACK_MANUALLY_PAUSED", "PLAYBACK_SUSPENDED", "SCRUB_BEGIN", "SCRUB_END")

            fun of(col: Map<String, Int>): AppleActivity? =
                if ("Event Start Timestamp" in col && "Play Duration Milliseconds" in col && ("Song Name" in col || "Content Name" in col)) AppleActivity(col) else null
        }
    }

    // newer play activity files name no artist; the same export's daily list and library do, and are used only where they name exactly one
    private class AppleArtists {
        private val byHour = HashMap<String, MutableSet<String>>()
        private val byAlbum = HashMap<String, MutableSet<String>>()

        fun daily(date: String?, hours: String?, description: String?) {
            if (date == null || hours == null || description == null) return
            hours.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { hour ->
                byHour.getOrPut("$date|$hour") { HashSet(2) } += description
            }
        }

        fun library(title: String?, artist: String?, album: String?) {
            if (title == null || artist == null) return
            byAlbum.getOrPut(TrackMatch.norm(title) + "|" + TrackMatch.norm(album.orEmpty())) { HashSet(1) } += artist
        }

        fun artist(play: ExportedPlay): String? {
            val time = Instant.ofEpochMilli(play.at).atOffset(ZoneOffset.UTC)
            val hours = listOf(time, Instant.ofEpochMilli(play.at + (play.listenedMs ?: 0)).atOffset(ZoneOffset.UTC))
                .map { "%04d%02d%02d|%d".format(Locale.ROOT, it.year, it.monthValue, it.dayOfMonth, it.hour) }.distinct()
            val suffix = " - " + play.title
            val fromDay = hours.flatMap { byHour[it].orEmpty() }.filter { it.endsWith(suffix) && it.length > suffix.length }
                .map { it.removeSuffix(suffix).trim() }.toSet()
            if (fromDay.size == 1) return fromDay.single()
            if (fromDay.size > 1) return null
            return byAlbum[TrackMatch.norm(play.title) + "|" + TrackMatch.norm(play.album)]?.singleOrNull()
        }
    }

    private fun readHtml(name: String, input: InputStream, c: Collector) {
        var accepted = false
        YouTubeHtml.cells(InputStreamReader(input, Charsets.UTF_8)) { cell ->
            val entry = YouTubeHtml.entry(cell) ?: return@cells
            if (!accepted) { accepted = true; c.files += name.substringAfterLast('/') }
            c.row()
            youtube(entry, c, worded = false)
        }
    }

    internal fun instant(text: String): Long? = runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrNull()

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private class Collector(val total: Long, val progress: (HistoryScanProgress) -> Unit) {
        val plays = ArrayList<ExportedPlay>()
        val skipped = EnumMap<ImportSkip, Int>(ImportSkip::class.java)
        val files = ArrayList<String>()
        val apple = AppleArtists()
        val worded = ArrayList<ExportedPlay>()
        var bytes = 0L
        private var rows = 0
        private var last = 0L
        private val strings = HashMap<String, String>()

        fun pool(s: String): String = strings.getOrPut(s) { s }
        fun row() { rows++; report() }
        fun play(p: ExportedPlay) { plays += p }
        fun skip(reason: ImportSkip) { skipped.merge(reason, 1, Int::plus) }

        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - last < PROGRESS_EVERY_NS) return
            last = now
            progress(HistoryScanProgress(bytes, total, rows))
        }

        fun finish(): ExportRead {
            val (apples, others) = plays.partition { it.service == HistoryService.APPLE_MUSIC }
            val strip = YouTubeTitles.stripper(worded.map { it.title })
            val out = ArrayList<ExportedPlay>(plays.size + worded.size)
            out += others
            worded.forEach { p -> strip(p.title).let { if (it.isEmpty()) skip(ImportSkip.NO_TRACK) else out += p.copy(title = pool(it)) } }
            out += joined(apples).map { if (it.artist.isNotEmpty()) it else it.copy(artist = apple.artist(it)?.let(::pool).orEmpty()) }
            return ExportRead(out, skipped, files.distinct())
        }

        // rows of one song that apple split at a pause or scrub become the one play they were
        private fun joined(rows: List<ExportedPlay>): List<ExportedPlay> {
            val out = ArrayList<ExportedPlay>(rows.size)
            for (row in rows.sortedBy { it.at }) {
                val prev = out.lastOrNull()
                if (prev != null && prev.continues && prev.title == row.title && prev.album == row.album &&
                    row.at - (prev.at + (prev.listenedMs ?: 0)) <= JOIN_GAP_MS) {
                    out[out.lastIndex] = prev.copy(listenedMs = (prev.listenedMs ?: 0) + (row.listenedMs ?: 0), ending = row.ending,
                        continues = row.continues, shuffle = prev.shuffle ?: row.shuffle, artist = prev.artist.ifEmpty { row.artist })
                } else out += row
            }
            return out
        }
    }

    private const val JOIN_GAP_MS = 6 * 3_600_000L

    private class Counting(input: InputStream, private val c: Collector) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) c.bytes++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) c.bytes += it }
    }

    // rfc 4180: quoted fields may hold commas, doubled quotes and line breaks
    private class Csv(reader: Reader) {
        private val input = reader.buffered(64 * 1024)
        private var done = false

        fun next(): List<String>? {
            if (done) return null
            val fields = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var any = false
            while (true) {
                val ch = input.read()
                if (ch < 0) {
                    done = true
                    if (!any) return null
                    fields += field.toString()
                    return fields
                }
                any = true
                val c = ch.toChar()
                if (quoted) {
                    if (c == '"') {
                        input.mark(1)
                        if (input.read() == '"'.code) field.append('"') else { input.reset(); quoted = false }
                    } else field.append(c)
                } else when (c) {
                    '"' -> quoted = true
                    ',' -> { fields += field.toString(); field.setLength(0) }
                    '\r' -> Unit
                    '\n' -> { fields += field.toString(); return fields }
                    else -> field.append(c)
                }
            }
        }
    }
}

internal object YouTubeTitles {
    // takeout writes the action around the title in the account's language; english, spanish, italian and french are known
    private val PREFIXES = listOf("Watched ", "Has visto ", "Hai guardato ", "Vous avez regardé ")
    private val SUFFIXES = listOf(" angesehen")
    private const val TOPIC = " - Topic"
    private const val DETECT_FROM = 20

    // a wording no list knows is found as the part every title in the file shares
    fun stripper(titles: List<String>): (String) -> String {
        val distinct = titles.distinct()
        val known = PREFIXES.firstOrNull { p -> distinct.count { it.startsWith(p) } * 2 > distinct.size }
        if (known != null) return { it.removePrefix(known).trim() }
        val knownSuffix = SUFFIXES.firstOrNull { p -> distinct.count { it.endsWith(p) } * 2 > distinct.size }
        if (knownSuffix != null) return { it.removeSuffix(knownSuffix).trim() }
        if (distinct.size < DETECT_FROM) return { it.trim() }
        val prefix = distinct.reduce { a, b -> a.commonPrefixWith(b) }
        val suffix = distinct.reduce { a, b -> a.commonSuffixWith(b) }
        return { t -> t.removePrefix(prefix).removeSuffix(suffix).trim().ifEmpty { t.trim() } }
    }

    // youtube files a song under its artist's auto-generated "<artist> - Topic" channel
    fun artist(channel: String): String = channel.trim().removeSuffix(TOPIC).trim()
}

internal object YouTubeHtml {
    private val HEADER = Regex("""<p class="mdl-typography--title">(.*?)<br""", RegexOption.DOT_MATCHES_ALL)
    private val CONTENT = Regex("""<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val LINK = Regex("""<a href="([^"]*)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")
    private const val CELL = "<div class=\"outer-cell"

    fun cells(reader: Reader, onCell: (String) -> Unit) {
        val buffer = CharArray(64 * 1024)
        val pending = StringBuilder()
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            pending.append(buffer, 0, n)
            var start = pending.indexOf(CELL)
            if (start < 0) { if (pending.length > CELL.length) pending.delete(0, pending.length - CELL.length); continue }
            while (true) {
                val next = pending.indexOf(CELL, start + CELL.length)
                if (next < 0) break
                onCell(pending.substring(start, next))
                start = next
            }
            pending.delete(0, start)
        }
        if (pending.startsWith(CELL)) onCell(pending.toString())
    }

    // the html holds what the json holds, with the time written out in words
    fun entry(cell: String): JsonObject? {
        val header = HEADER.find(cell)?.groupValues?.get(1)?.let(::text) ?: return null
        val content = CONTENT.find(cell)?.groupValues?.get(1) ?: return null
        val links = LINK.findAll(content).toList()
        val o = JsonObject()
        o.addProperty("header", header)
        links.firstOrNull()?.let { o.addProperty("titleUrl", text(it.groupValues[1])); o.addProperty("title", text(it.groupValues[2])) }
            ?: o.addProperty("title", text(content.substringBefore("<br")))
        links.getOrNull(1)?.let { link -> o.add("subtitles", JsonArray().apply { add(JsonObject().apply { addProperty("name", text(link.groupValues[2])) }) }) }
        val time = content.split("<br>").map(::text).lastOrNull { it.isNotBlank() }?.let(HtmlDates::parse)
        o.addProperty("time", time?.let { Instant.ofEpochMilli(it).toString() } ?: "")
        if ("Google Ads" in cell) o.add("details", JsonArray().apply { add(JsonObject().apply { addProperty("name", "From Google Ads") }) })
        return o
    }

    private fun text(html: String): String = html.replace(TAG, "").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(' ', ' ').replace(' ', ' ').trim()
}

internal object HtmlDates {
    private val ZONED = listOf("MMM d, yyyy, h:mm:ss a", "MMM d, yyyy, HH:mm:ss", "d MMM yyyy, HH:mm:ss")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    // the html names a zone by its abbreviation; each is read as the one fixed offset it stands for, an unknown one is not guessed
    // bst, ist and cst each name zones on different continents, so they are among the unknown
    private val OFFSETS = mapOf("UTC" to 0, "GMT" to 0, "WET" to 0, "WEST" to 60, "CET" to 60, "CEST" to 120,
        "EET" to 120, "EEST" to 180, "MSK" to 180, "EST" to -300, "EDT" to -240, "CDT" to -300, "MST" to -420, "MDT" to -360,
        "PST" to -480, "PDT" to -420, "AKST" to -540, "AKDT" to -480, "HST" to -600, "JST" to 540, "KST" to 540, "AEST" to 600, "AEDT" to 660)

    fun parse(text: String): Long? {
        val clean = text.replace(' ', ' ').replace(' ', ' ').trim()
        val last = clean.substringAfterLast(' ')
        val zone = when {
            last.matches(Regex("(GMT|UTC)[+-]\\d{1,2}(:\\d{2})?")) -> runCatching { ZoneOffset.of(last.drop(3)) }.getOrNull() ?: return null
            last in OFFSETS -> ZoneOffset.ofTotalSeconds(OFFSETS.getValue(last) * 60)
            last.any { it.isDigit() } || last == "AM" || last == "PM" -> null
            else -> return null
        }
        // older takeouts print dates before 2018 with no zone, in utc
        val local = if (zone == null) clean else clean.substringBeforeLast(' ')
        for (f in ZONED) runCatching { return LocalDateTime.parse(local, f).toInstant(zone ?: ZoneOffset.UTC).toEpochMilli() }
        return null
    }
}
