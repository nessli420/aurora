package com.aurora.music.data

import com.aurora.music.model.Song
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID

data class CueTrack(
    val number: Int,
    val file: String,
    val title: String = "",
    val performer: String = "",
    val startMs: Long = 0,
    val gainDb: Float? = null,
)

data class CueSheet(
    val title: String = "",
    val performer: String = "",
    val genre: String = "",
    val year: Int = 0,
    val disc: Int = 0,
    val albumGainDb: Float? = null,
    val tracks: List<CueTrack> = emptyList(),
) {
    val files: List<String> get() = tracks.map { it.file }.distinct()
}

// one track of a cue sheet, played as a time range of the album file
data class CueClip(val source: String, val startMs: Long, val endMs: Long?) {
    val url: String get() = source + "#t=" + seconds(startMs) + (endMs?.let { "," + seconds(it) } ?: "")

    companion object {
        private val FRAGMENT = Regex("#t=(\\d+(?:\\.\\d+)?)(?:,(\\d+(?:\\.\\d+)?))?$")

        fun of(url: String): CueClip? {
            if ('#' !in url) return null
            val match = FRAGMENT.find(url) ?: return null
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::millis)
            return CueClip(url.substring(0, match.range.first), millis(match.groupValues[1]), end)
        }

        fun source(url: String): String = of(url)?.source ?: url

        private fun millis(seconds: String) = Math.round(seconds.toDouble() * 1000)
        private fun seconds(ms: Long) = String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000)
    }
}

data class CuePart(val whole: Song, val song: Song, val position: Int, val year: Int)

object CueSheets {
    fun parse(bytes: ByteArray): CueSheet = parse(decode(bytes))

    fun parse(text: String): CueSheet {
        var sheet = CueSheet()
        val tracks = ArrayList<CueTrack>()
        var file = ""
        var track: CueTrack? = null
        for (raw in text.lineSequence()) {
            val words = words(raw)
            if (words.isEmpty()) continue
            val args = words.drop(1)
            val value = args.firstOrNull().orEmpty()
            val open = track
            when (words[0].uppercase(Locale.ROOT)) {
                "FILE" -> { open?.let(tracks::add); track = null; file = value }
                "TRACK" -> {
                    open?.let(tracks::add)
                    val audio = (args.getOrNull(1)?.uppercase(Locale.ROOT) ?: "AUDIO") == "AUDIO"
                    track = if (audio && file.isNotEmpty()) CueTrack(value.toIntOrNull() ?: (tracks.size + 1), file, startMs = -1) else null
                }
                "TITLE" -> if (open != null) track = open.copy(title = value) else sheet = sheet.copy(title = value)
                "PERFORMER" -> if (open != null) track = open.copy(performer = value) else sheet = sheet.copy(performer = value)
                "INDEX" -> if (open != null && args.getOrNull(0)?.toIntOrNull() == 1) time(args.getOrNull(1))?.let { track = open.copy(startMs = it) }
                "REM" -> {
                    val rest = args.drop(1).joinToString(" ")
                    when (value.uppercase(Locale.ROOT)) {
                        "GENRE" -> if (open == null) sheet = sheet.copy(genre = rest)
                        "DATE" -> if (open == null) sheet = sheet.copy(year = YEAR.find(rest)?.value?.toInt() ?: 0)
                        "DISCNUMBER" -> if (open == null) sheet = sheet.copy(disc = rest.takeWhile(Char::isDigit).toIntOrNull() ?: 0)
                        "REPLAYGAIN_ALBUM_GAIN" -> sheet = sheet.copy(albumGainDb = decibels(rest))
                        "REPLAYGAIN_TRACK_GAIN" -> if (open != null) track = open.copy(gainDb = decibels(rest))
                    }
                }
            }
        }
        track?.let(tracks::add)
        return sheet.copy(tracks = tracks.filter { it.startMs >= 0 })
    }

    // utf-8 when it decodes cleanly, otherwise the windows codepage most rippers wrote
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    fun dirKey(dir: String): String = dir.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    fun isCue(name: String) = name.endsWith(".cue", ignoreCase = true)

    // a file whose sheet lists two or more tracks is replaced by those tracks; sheets that only name split files change nothing
    fun split(songs: List<Song>, sheets: Map<String, List<CueSheet>>): List<CuePart> {
        if (sheets.isEmpty()) return emptyList()
        val byDir = songs.filter { it.path.isNotBlank() }.groupBy { dirKey(parent(it.path)) }
        val parts = ArrayList<CuePart>()
        val used = HashSet<String>()
        for ((dir, list) in sheets) {
            val audio = byDir[dir] ?: continue
            for (sheet in list) for (name in sheet.files) {
                val tracks = sheet.tracks.filter { it.file == name }.sortedBy { it.startMs }
                if (tracks.size < 2) continue
                val whole = match(name, sheet.files.size, audio) ?: continue
                if (!used.add(whole.id)) continue
                parts += tracks(whole, sheet, tracks, dir)
            }
        }
        return parts
    }

    private fun tracks(whole: Song, sheet: CueSheet, tracks: List<CueTrack>, dir: String): List<CuePart> {
        val lengthMs = whole.durationSec * 1000L
        val sameAlbum = sheet.title.isBlank() || sheet.title.equals(whole.album, ignoreCase = true)
        val albumId = if (sameAlbum) whole.albumId
            else "local-cue-album-" + UUID.nameUUIDFromBytes("$dir\u0000${sheet.title.lowercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8))
        return tracks.mapIndexedNotNull { index, track ->
            if (lengthMs > 0 && track.startMs >= lengthMs) return@mapIndexedNotNull null
            val end = tracks.getOrNull(index + 1)?.startMs
            val id = "${whole.id}-cue-${track.number}"
            val song = whole.copy(
                id = id,
                title = track.title.ifBlank { "Track ${track.number}" },
                artist = track.performer.ifBlank { sheet.performer }.ifBlank { whole.artist },
                album = sheet.title.ifBlank { whole.album },
                albumId = albumId,
                durationSec = (((end ?: lengthMs) - track.startMs).coerceAtLeast(0) + 500).div(1000).toInt(),
                streamUrl = CueClip(whole.streamUrl, track.startMs, end).url,
                genre = sheet.genre.ifBlank { whole.genre },
                replayGainTrack = track.gainDb ?: whole.replayGainTrack,
                replayGainAlbum = sheet.albumGainDb ?: whole.replayGainAlbum,
            )
            CuePart(whole, song, sheet.disc * 10_000 + track.number, sheet.year)
        }
    }

    // exact name first, then the same name with another extension (sheets often outlive a re-encode)
    private fun match(name: String, files: Int, audio: List<Song>): Song? {
        val wanted = name.replace('\\', '/').substringAfterLast('/')
        audio.firstOrNull { fileName(it.path).equals(wanted, ignoreCase = true) }?.let { return it }
        val base = wanted.substringBeforeLast('.')
        audio.filter { fileName(it.path).substringBeforeLast('.').equals(base, ignoreCase = true) }.singleOrNull()?.let { return it }
        return if (files == 1) audio.singleOrNull() else null
    }

    private fun parent(path: String) = path.replace('\\', '/').substringBeforeLast('/', "")
    private fun fileName(path: String) = path.replace('\\', '/').substringAfterLast('/')

    private fun time(value: String?): Long? {
        val parts = value?.split(':')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 } ?: return null
        return (parts[0] * 60 + parts[1]) * 1000 + parts[2] * 1000 / 75
    }

    private fun decibels(value: String) = DECIMAL.find(value)?.value?.toFloatOrNull()

    private fun words(line: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            if (line[i] == '"') {
                val end = line.indexOf('"', i + 1).takeIf { it >= 0 } ?: line.length
                out += line.substring(i + 1, end)
                i = end + 1
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                out += line.substring(start, i)
            }
        }
        return out
    }

    private val YEAR = Regex("\\d{4}")
    private val DECIMAL = Regex("[-+]?\\d+(?:\\.\\d+)?")
}
