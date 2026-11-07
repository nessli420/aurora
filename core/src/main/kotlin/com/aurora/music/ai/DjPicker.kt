package com.aurora.music.ai

import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.model.Song
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

data class DjCandidate(
    val song: Song,
    val plays: Int = 0,
    val liked: Boolean = false,
    val year: Int = 0,
    val energy: Float? = null,
    val bpm: Int = 0,
    val vector: FloatArray? = null,
    val hits: Int = 0,
)

object DjPicker {
    private val genreEnergy = listOf(
        "drum and bass" to 0.9f, "hardcore" to 0.95f, "metal" to 0.9f, "dubstep" to 0.9f, "punk" to 0.85f, "techno" to 0.85f,
        "edm" to 0.85f, "trance" to 0.8f, "grunge" to 0.8f, "ska" to 0.8f, "dance" to 0.8f, "drill" to 0.8f, "disco" to 0.75f,
        "house" to 0.75f, "trap" to 0.75f, "k pop" to 0.75f, "reggaeton" to 0.75f, "emo" to 0.75f, "garage" to 0.75f, "rock" to 0.75f,
        "funk" to 0.7f, "rap" to 0.7f, "afrobeat" to 0.7f, "dream pop" to 0.4f, "trip hop" to 0.4f, "hip hop" to 0.65f, "pop" to 0.65f,
        "electronic" to 0.65f, "latin" to 0.65f, "alternative" to 0.65f, "synth" to 0.6f, "indie" to 0.55f, "shoegaze" to 0.55f,
        "gospel" to 0.55f, "country" to 0.5f, "reggae" to 0.5f, "r and b" to 0.5f, "soul" to 0.45f, "blues" to 0.45f, "jazz" to 0.4f,
        "soundtrack" to 0.4f, "opera" to 0.4f, "downtempo" to 0.35f, "folk" to 0.35f, "singer songwriter" to 0.35f, "bossa" to 0.35f,
        "acoustic" to 0.3f, "lo fi" to 0.3f, "chill" to 0.3f, "classical" to 0.25f, "piano" to 0.25f, "new age" to 0.2f, "ambient" to 0.15f,
    )
    // catalogue searches return dj mixes and episodes, often without a duration to catch them by
    private val longForm = listOf("party mix", "hip hop mix", "hiphop mix", "dj mix", "megamix", "mega mix", "mix vol", "mixtape vol", "full album",
        "full vinyl", "dj set", "playlist", "compilation", "nonstop", "non stop", "1 hour", "2 hours", "3 hours", "hour mix", "episode", "podcast",
        "the ultimate", "best of", "greatest hits", "throwback mix", "old school mix", "classics mix").map { " $it " }

    private fun longForm(song: Song, title: String, artist: String): Boolean {
        val spaced = " $title "
        return artist == "episode" || '|' in song.title || song.title.count { it == ',' } >= 2 ||
            longForm.any { spaced.contains(it) }
    }

    private val instrumental = listOf("ambient", "classical", "piano", "soundtrack", "score", "instrumental", "post rock", "lo fi",
        "new age", "orchestral", "drone", "chillhop", "beats")

    private fun genreEnergyOf(genre: String): Float? = genreEnergy.firstOrNull { genre.contains(it.first) }?.second

    fun energyOf(c: DjCandidate): Float? = c.energy ?: genreEnergyOf(DjKeywords.norm(c.song.genre))

    // norm is a regex, and a pick looks at each of these many times
    private class Named(val c: DjCandidate) {
        val artist = DjKeywords.norm(c.song.artist)
        val title = DjKeywords.norm(c.song.title)
        val genre = DjKeywords.norm(c.song.genre)
        val energy = c.energy ?: genreEnergyOf(genre)
    }

    private class Wants(intent: DjIntent) {
        val genres = intent.genres.map(DjKeywords::norm)
        val moods = DjKeywords.parse(intent.moods.joinToString(" ")).intent.genres.map(DjKeywords::norm)
        val artists = intent.artists.map(DjKeywords::norm)
        val avoid = intent.avoid.map(DjKeywords::norm)
    }

    private fun matches(field: String, w: String): Boolean {
        if (field.isEmpty() || w.isEmpty()) return false
        return " $field ".contains(" $w ") || field.replace(" ", "").contains(w.replace(" ", "")) && w.length >= 4
    }

    private class Rated(val c: Named, val score: Double, val relevant: Boolean)

    // null means the track breaks a hard rule of the request
    fun score(intent: DjIntent, c: DjCandidate): Double? = rate(intent, Named(c), Wants(intent))?.score

    private fun rate(intent: DjIntent, named: Named, wants: Wants): Rated? {
        val c = named.c
        val genre = named.genre
        val artist = named.artist
        val title = named.title
        if (wants.avoid.any { matches(genre, it) || matches(artist, it) }) return null
        if (c.song.durationSec in 1..59 || c.song.durationSec > 1200 || longForm(c.song, title, artist)) return null
        var s = 0.0
        val genreHit = wants.genres.any { matches(genre, it) }
        val moodHit = !genreHit && wants.moods.any { matches(genre, it) }
        val artistHit = wants.artists.any { it == artist || matches(artist, it) }
        val eraHit = c.year > 0 && intent.decades.any { c.year in it until it + 10 }
        if (genreHit) s += 3.0 else if (moodHit) s += 1.5
        if (artistHit) s += 4.0
        s += minOf(c.hits, 4) * 1.5
        if (intent.decades.isNotEmpty()) s += when {
            c.year <= 0 -> -2.0
            intent.decades.any { c.year in it until it + 10 } -> 2.5
            else -> -4.0
        }
        val bare = instrumental.any { genre.contains(it) } || title.contains("instrumental")
        when (intent.vocals) {
            DjVocals.NONE -> s += if (bare) 3.0 else -1.5
            DjVocals.LESS -> if (bare) s += 1.5
            DjVocals.ANY -> {}
        }
        val plays = minOf(c.plays, 30)
        when (intent.familiarity) {
            DjFamiliarity.FAVOURITES -> s += (if (c.liked) 3.0 else 0.0) + ln(1.0 + plays)
            DjFamiliarity.DISCOVER -> s += if (plays == 0 && !c.liked) 2.5 else -ln(1.0 + plays) * 0.6 - (if (c.liked) 1.0 else 0.0)
            DjFamiliarity.MIX -> s += (if (c.liked) 1.0 else 0.0) + if (plays > 0) 0.5 else 0.0
        }
        if (c.bpm > 0) s += when (intent.tempo) {
            DjTempo.SLOW -> if (c.bpm < 100) 1.5 else if (c.bpm > 125) -1.5 else 0.0
            DjTempo.FAST -> if (c.bpm >= 118) 1.5 else if (c.bpm < 95) -1.5 else 0.0
            DjTempo.MID -> if (c.bpm in 95..125) 1.0 else 0.0
            DjTempo.ANY -> 0.0
        }
        named.energy?.let { e ->
            val lo = minOf(intent.energyStart, intent.energyEnd) / 10f - 0.15f
            val hi = maxOf(intent.energyStart, intent.energyEnd) / 10f + 0.15f
            if (e < lo) s -= (lo - e) * 6 else if (e > hi) s -= (e - hi) * 6
        }
        val wrongEra = intent.decades.isNotEmpty() && c.year > 0 && !eraHit
        return Rated(named, s, !wrongEra && (genreHit || artistHit || c.hits > 0 || (wants.genres.isEmpty() && (eraHit || moodHit))))
    }

    fun pick(
        intent: DjIntent,
        candidates: List<DjCandidate>,
        count: Int = intent.count,
        exclude: Set<String> = emptySet(),
        previous: DjCandidate? = null,
        random: Random = Random.Default,
        flow: Boolean = false,
    ): List<Song> {
        if (count <= 0) return emptyList()
        val wants = Wants(intent)
        val seen = HashSet<String>()
        val rated = candidates.asSequence()
            .filter { it.song.id.isNotEmpty() && it.song.id !in exclude && !it.song.isRadio() && !it.song.isPodcast() && seen.add(it.song.id) }
            .map { Named(it) }
            .filter { seen.add("${it.artist}|${it.title}") }
            .mapNotNull { n -> rate(intent, n, wants)?.let { Rated(n, it.score + random.nextDouble() * (if (flow) 2.0 else 0.6), it.relevant) } }
            .sortedByDescending { it.score }
            .toList()
        // untagged catalogue tracks can't prove they fit, so tracks that match the request go first and the rest only fill gaps
        val asked = wants.genres.isNotEmpty() || wants.moods.isNotEmpty() || wants.artists.isNotEmpty() || intent.decades.isNotEmpty() || intent.search.isNotEmpty()
        val (fits, rest) = if (asked) rated.partition { it.relevant } else rated to emptyList()
        val scored = (fits.take(maxOf(count * 3, 30)) + rest.take((count - fits.size).coerceAtLeast(0)))
            .map { it.c to it.score + if (it.relevant || !asked) 10.0 else 0.0 }.toMutableList()
        val out = ArrayList<Named>(count)
        val before = previous?.let { Named(it) }
        var last = before
        while (out.size < count && scored.isNotEmpty()) {
            val slot = out.size
            // flow follows the last track's energy instead of an arc the request asked for
            val target = if (flow) last?.energy else if (count == 1) intent.energyEnd / 10f
                else (intent.energyStart + (intent.energyEnd - intent.energyStart) * slot / (count - 1f)) / 10f
            val recent = (listOfNotNull(before) + out).takeLast(3).map { it.artist }
            var best = 0
            var bestValue = Double.NEGATIVE_INFINITY
            for ((i, pair) in scored.withIndex()) {
                val (n, base) = pair
                var v = base
                val energy = n.energy
                v += if (energy != null && target != null) 2.0 * (1 - abs(energy - target)) else 1.0
                last?.let { v += transition(it.c, n.c) }
                if (n.artist in recent) v -= 3.0
                if (last != null && n.c.song.albumId.isNotEmpty() && n.c.song.albumId == last.c.song.albumId) v -= 1.0
                if (v > bestValue) { bestValue = v; best = i }
            }
            val chosen = scored.removeAt(best).first
            out += chosen
            last = chosen
        }
        return out.map { it.c.song }
    }

    private fun transition(a: DjCandidate, b: DjCandidate): Double {
        var v = 0.0
        val va = a.vector
        val vb = b.vector
        if (va != null && vb != null && va.size == vb.size) {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in va.indices) { dot += va[i] * vb[i]; na += va[i] * va[i]; nb += vb[i] * vb[i] }
            if (na > 0 && nb > 0) v += 0.6 * dot / (sqrt(na) * sqrt(nb))
        }
        if (a.bpm > 0 && b.bpm > 0) v += 0.6 * (1 - (abs(a.bpm - b.bpm) / 30.0).coerceAtMost(1.0))
        return v
    }
}

object DjSonic {
    private const val DIMS = 51
    private const val CENTROID = 26
    private const val FLUX = 29
    private const val RMS = 36
    private const val TEMPO = 50

    data class Signal(val energy: Float, val bpm: Int, val vector: FloatArray)

    // indices follow the sonic feature layout (mfcc 26, spectral 10, rms 2, chroma 12, tempo 1)
    fun signals(vectors: Map<String, FloatArray>): Map<String, Signal> {
        val usable = vectors.filterValues { it.size == DIMS && it.all { x -> x.isFinite() } }
        if (usable.size < 3) return usable.mapValues { (_, v) -> Signal(0.5f, bpm(v), v) }
        val mean = DoubleArray(DIMS)
        val std = DoubleArray(DIMS)
        for (v in usable.values) for (d in 0 until DIMS) mean[d] += v[d].toDouble()
        for (d in 0 until DIMS) mean[d] /= usable.size
        for (v in usable.values) for (d in 0 until DIMS) { val x = v[d] - mean[d]; std[d] += x * x }
        for (d in 0 until DIMS) std[d] = sqrt(std[d] / usable.size).coerceAtLeast(1e-6)
        return usable.mapValues { (_, v) ->
            fun z(d: Int) = (v[d] - mean[d]) / std[d]
            val raw = 0.4 * z(RMS) + 0.25 * z(FLUX) + 0.2 * z(CENTROID) + 0.15 * z(TEMPO)
            val energy = (1.0 / (1.0 + kotlin.math.exp(-1.6 * raw))).toFloat()
            Signal(energy, bpm(v), FloatArray(DIMS) { z(it).toFloat() })
        }
    }

    private fun bpm(v: FloatArray) = (60f + v[TEMPO] * 120f).toInt().coerceIn(40, 220)
}
