package com.aurora.music.data

import com.aurora.music.data.remote.GeniusAnnotationDto
import com.aurora.music.data.remote.GeniusClient
import com.aurora.music.data.remote.GeniusHit
import com.aurora.music.util.TrackMatch
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GeniusNote(val body: String = "", val authors: List<String> = emptyList(), val verified: Boolean = false)
data class GeniusReferent(val fragment: String = "", val url: String = "", val notes: List<GeniusNote> = emptyList())
data class GeniusSong(
    val found: Boolean = false,
    val url: String = "",
    val description: String = "",
    val describedBy: List<String> = emptyList(),
    val referents: List<GeniusReferent> = emptyList(),
)

// referents placed on lyric lines; a referent may show on several places when its lines repeat
class LyricMeanings(val song: GeniusSong, val spans: List<Pair<IntRange, GeniusReferent>>) {
    fun at(line: Int): List<GeniusReferent> = spans.filter { line in it.first }.map { it.second }.distinct()
    fun markedAt(line: Int): Boolean = spans.any { it.first.last == line }

    companion object {
        fun place(song: GeniusSong, lines: List<String>): LyricMeanings =
            LyricMeanings(song, song.referents.flatMap { ref -> LyricFragments.locate(lines, ref.fragment).map { it to ref } })
    }
}

// app-level genius lookup that works for every source; only reviewed annotations are kept
class GeniusMeanings(private val client: GeniusClient, private val store: GeniusStore) {
    // null on a miss or a network failure, which is retried next time
    suspend fun lookup(artist: String, title: String): GeniusSong? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val key = TrackMatch.key(artist, title)
        store.get(key)?.let { return@withContext it.takeIf { s -> s.found } }
        val song = try { fetch(artist, title) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withContext null }
        store.put(key, song)
        song.takeIf { it.found }
    }

    fun bytes(): Long = store.bytes()

    fun clear() = store.clear()

    private fun fetch(artist: String, title: String): GeniusSong {
        val hit = pickHit(artist, title, client.search("${mainArtist(artist)} ${coreTitle(title)}"))
            ?: artistHit(artist, title) ?: return GeniusSong()
        val id = hit.id ?: return GeniusSong()
        val song = client.song(id)
        val about = song?.descriptionAnnotation?.annotations.orEmpty().firstOrNull()
        val description = song?.description?.plain.orEmpty().trim().takeIf { it.length > 1 && (about == null || about.reviewed()) }.orEmpty()
        val referents = if ((hit.annotationCount ?: 0) == 0) emptyList() else client.referents(id).mapNotNull { r ->
            val fragment = r.fragment.orEmpty().trim()
            val notes = r.annotations.orEmpty().filter { it.reviewed() }.mapNotNull { it.toNote() }
            if (r.isDescription == true || notes.isEmpty() || fragment.isBlank() || SECTION.matches(fragment)) null
            else GeniusReferent(fragment, r.url.orEmpty(), notes)
        }
        return GeniusSong(true, hit.url?.takeIf(String::isNotBlank) ?: song?.url.orEmpty(), description, about?.authorNames().orEmpty(), referents)
    }

    // popular songs can sit below a page of translations, so look inside the artist's own songs too
    private fun artistHit(artist: String, title: String): GeniusHit? {
        val wanted = keys(artist, split = true)
        val id = client.artists(mainArtist(artist)).firstOrNull { a -> keys(a.name.orEmpty(), split = false).any { it in wanted } }?.id ?: return null
        return pickHit(artist, title, client.artistSongs(id, coreTitle(title)))
    }

    private fun GeniusAnnotationDto.reviewed() = state == "accepted" || verified == true
    private fun GeniusAnnotationDto.authorNames() = authors.orEmpty().mapNotNull { it.user?.login?.takeIf(String::isNotBlank) ?: it.user?.name?.takeIf(String::isNotBlank) }
    private fun GeniusAnnotationDto.toNote() = body?.plain?.trim()?.takeIf { it.isNotBlank() }?.let { GeniusNote(it, authorNames(), verified == true) }

    companion object {
        private val SECTION = Regex("""^\[[^\]]*]$""")
        private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
        private val DASH_SUFFIX = Regex("""\s[-–—]\s.*$""")
        private val FEAT = Regex("""\s\b(feat|ft|featuring)\b\.?.*$""", RegexOption.IGNORE_CASE)
        private val ARTIST_SPLIT = Regex("""\s*(?:,|&|;|/|\+|\s[x×]\s|\bfeat\.?|\bft\.?|\bfeaturing\b|\band\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
        private val INSIDE = Regex("""[(\[]([^)\]]*)[)\]]""")

        internal fun coreTitle(title: String): String =
            FEAT.replace(DASH_SUFFIX.replace(BRACKETS.replace(title, " "), ""), "").trim().ifBlank { title.trim() }

        private fun mainArtist(artist: String) = FEAT.replace(artist, "").trim().ifBlank { artist }

        // the plain name, its credited parts and any bracketed alias, so "BIBI (비비)" meets "비비"
        private fun keys(value: String, split: Boolean): Set<String> {
            val names = listOf(value, BRACKETS.replace(value, " ")) + INSIDE.findAll(value).map { it.groupValues[1] } +
                (if (split) value.split(ARTIST_SPLIT) else emptyList())
            return names.map { TrackMatch.norm(it).removePrefix("the ") }.filter { it.isNotBlank() }.toSet()
        }

        // first search hit whose title and artist both match; anything else is skipped rather than shown wrong
        internal fun pickHit(artist: String, title: String, hits: List<GeniusHit>): GeniusHit? {
            val wantTitle = TrackMatch.norm(coreTitle(title))
            val wantArtists = keys(artist, split = true)
            return hits.firstOrNull { hit ->
                hit.instrumental != true &&
                    (TrackMatch.norm(coreTitle(hit.title.orEmpty())) == wantTitle || wantTitle in keys(hit.title.orEmpty(), split = false)) &&
                    (keys(hit.primaryArtist?.name.orEmpty(), split = false) + keys(hit.artistNames.orEmpty(), split = true)).any { it in wantArtists }
            }
        }
    }
}

const val GENIUS_CACHE = "genius"

// one small file per song so a write never rewrites the rest; oldest files go past the cap
class GeniusStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis, private val limit: Int = 400) {
    private val gson = Gson()

    private class Entry(val version: Int? = 0, val key: String? = "", val at: Long? = 0, val song: GeniusSong? = null)

    private fun file(key: String) = File(dir, Integer.toHexString(key.hashCode()) + ".json")

    fun get(key: String): GeniusSong? = synchronized(this) {
        val e = runCatching { gson.fromJson(file(key).readText(), Entry::class.java) }.getOrNull() ?: return null
        val song = e.song ?: return null
        val ttl = if (song.found) FRESH_MS else MISS_MS
        if (e.version != VERSION || e.key != key || clock() - (e.at ?: 0) > ttl) null else song
    }

    fun put(key: String, song: GeniusSong) = synchronized(this) {
        runCatching {
            dir.mkdirs()
            persistBackupFileAtomically(file(key), gson.toJson(Entry(VERSION, key, clock(), song)).toByteArray(Charsets.UTF_8))
            val files = dir.listFiles().orEmpty()
            if (files.size > limit) files.sortedBy { it.lastModified() }.take(files.size - limit).forEach { it.delete() }
        }
        Unit
    }

    fun bytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    fun clear() = synchronized(this) { dir.listFiles().orEmpty().forEach { it.delete() } }

    private companion object {
        const val VERSION = 1
        const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
        const val MISS_MS = 3L * 24 * 60 * 60 * 1000
    }
}

// finds a genius fragment in lyric lines: it may cover part of a line or run over several
object LyricFragments {
    private val ASIDES = Regex("""\([^)]*\)""")
    private val APOSTROPHES = Regex("""['’‘`´]""")

    private fun words(text: String): List<String> =
        TrackMatch.norm(APOSTROPHES.replace(ASIDES.replace(text, " "), "")).split(' ').filter { it.isNotEmpty() }

    // every exact place, or else the one close place (backing vocals, spelling); short fragments must fill whole lines
    fun locate(lines: List<String>, fragment: String): List<IntRange> {
        val target = words(fragment)
        if (target.isEmpty()) return emptyList()
        val all = ArrayList<String>()
        val owner = ArrayList<Int>()
        lines.forEachIndexed { i, line -> words(line).forEach { all += it; owner += i } }
        val n = target.size
        fun whole(s: Int, e: Int) = (s == 0 || owner[s - 1] != owner[s]) && (e == all.lastIndex || owner[e + 1] != owner[e])
        val exact = ArrayList<IntRange>()
        var s = 0
        while (s + n <= all.size) {
            if ((0 until n).all { all[s + it] == target[it] } && (n >= 3 || whole(s, s + n - 1))) {
                exact += owner[s]..owner[s + n - 1]
                s += n
            } else s++
        }
        if (exact.isNotEmpty() || n < 4) return exact
        val anchors = target.take(3).toSet()
        var best = 0
        var bestRange: IntRange? = null
        val prev = IntArray(n + 1)
        val cur = IntArray(n + 1)
        for (start in all.indices) {
            if (all[start] !in anchors) continue
            val end = minOf(all.size, start + n + maxOf(2, n / 4))
            prev.fill(0)
            var top = 0
            var last = start
            for (j in start until end) {
                for (i in 1..n) cur[i] = if (all[j] == target[i - 1]) prev[i - 1] + 1 else maxOf(prev[i], cur[i - 1])
                if (cur[n] > top) { top = cur[n]; last = j }
                cur.copyInto(prev)
            }
            if (top > best) { best = top; bestRange = owner[start]..owner[last] }
        }
        return if (best * 5 >= n * 4) listOfNotNull(bestRange) else emptyList()
    }
}
