package com.aurora.music.foryou

import com.aurora.music.ai.DjCandidate
import com.aurora.music.ai.DjIntent
import com.aurora.music.ai.DjKeywords
import com.aurora.music.ai.DjPicker
import com.aurora.music.ai.DjSonic
import com.aurora.music.ai.djPrimaryArtist
import com.aurora.music.ai.djTrackKey
import com.aurora.music.data.GenreText
import com.aurora.music.data.PlayEvent
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.data.qualifiesAsPlay
import com.aurora.music.model.Song
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

enum class MixKind { TIME, DAILY, RECOMMENDED, DISCOVER, STATION }

class StationSeed(val artist: String, val songs: List<Song>)

// what the sources answered for one station: the artist's songs on each of them, and songs their radios play next to those
class Station(val artist: String, val own: List<Song>, val related: List<Song>)

enum class DayPart(val startHour: Int) {
    MORNING(5), AFTERNOON(12), EVENING(17), NIGHT(22);

    companion object {
        fun of(hour: Int) = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

// a mix day starts with the morning, so one night's listening stays in one edition
class MixClock(val now: Long, val offsetMs: Long) {
    private val local = now + offsetMs
    val part: DayPart = DayPart.of(hourOf(now))
    val day: Long = Math.floorDiv(local - DayPart.MORNING.startHour * HOUR, DAY)
    val untilNextPart: Long
        get() {
            val today = Math.floorMod(local, DAY)
            val next = DayPart.entries.map { it.startHour * HOUR }.firstOrNull { it > today } ?: (DAY + DayPart.MORNING.startHour * HOUR)
            return next - today
        }

    fun hourOf(time: Long): Int = (Math.floorMod(time + offsetMs, DAY) / HOUR).toInt()

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}

const val MIX_KIND = "mix"
const val MIX_COVER_PREFIX = "aurora-mix-cover:"

fun mixSlotOf(id: String): String = id.substringBefore('@')

private const val MIX_QUEUE_PREFIX = "foryou:"

// what a queued song carries so its queue can move on with the mix
fun mixCollection(id: String) = PlaybackCollectionIdentity(MIX_QUEUE_PREFIX + id)

private fun mixOf(song: Song): String? = song.playbackCollection?.id?.takeIf { it.startsWith(MIX_QUEUE_PREFIX) }?.substring(MIX_QUEUE_PREFIX.length)

// the edition a queue is on: the last mix song from the playing one on says which
fun queueMix(queue: List<Song>, index: Int): String? {
    if (index < 0) return null
    for (i in queue.lastIndex downTo index) mixOf(queue[i])?.let { return it }
    return null
}

// what took a playing edition's place: its slot's next, or the mix for the new time of day; a station's artist moves on, so it has none
fun mixSuccessor(playing: String, tiles: List<GeneratedMix>): GeneratedMix? {
    if (tiles.any { it.id == playing }) return null
    val slot = mixSlotOf(playing)
    return tiles.firstOrNull { it.kind != MixKind.STATION && if (slot.startsWith("time-")) it.kind == MixKind.TIME else it.slot == slot }
}

// behind the playing song once its mix moved on: what the listener queued themself, then the new edition without what was heard
fun mixFollowUp(queue: List<Song>, index: Int, added: Set<String>, next: List<Song>): List<Song> {
    val yours = queue.drop(index + 1).filter { it.id !in added && mixOf(it) == null }
    val taken = (queue.take(index + 1) + yours).mapTo(HashSet()) { it.id }
    val fresh = next.filter { it.id !in taken }
    return if (fresh.isEmpty()) emptyList() else yours + fresh
}

data class GeneratedMix(
    val slot: String,
    val kind: MixKind,
    val day: Long,
    val index: Int = 0,
    val part: DayPart? = null,
    val artists: List<String> = emptyList(),
    val songs: List<Song> = emptyList(),
    // a picture of one of its artists, drawn under the cover's mark
    val art: String = "",
) {
    val id: String get() = "$slot@$day"
    val coverUrl: String get() = MIX_COVER_PREFIX + slot + if (art.isBlank()) "" else "|$art"
}

class MixInput(
    val events: List<PlayEvent>,
    val clock: MixClock,
    val known: List<Song>,
    val likedIds: Set<String> = emptySet(),
    // radio, related and search results from every source; a repeat counts as more evidence
    val candidates: List<Song> = emptyList(),
    val vectors: Map<String, FloatArray> = emptyMap(),
    val skips: Map<String, Int> = emptyMap(),
    val shown: Map<String, Map<String, Long>> = emptyMap(),
    val hidden: (Song) -> Boolean = { false },
    val kinds: Set<MixKind> = MixKind.entries.toSet(),
    val seed: Long = 0,
    val stations: List<Station> = emptyList(),
) {
    internal var taste: MixTaste? = null
}

fun mixTrackKey(artist: String, title: String) = djTrackKey(djPrimaryArtist(artist), title)

internal fun mixTrackKey(song: Song) = mixTrackKey(song.artist, song.title)

internal fun mixArtistKey(name: String) = DjKeywords.norm(djPrimaryArtist(name))

private val CREDIT_SPLIT = Regex("[,;&]| feat[.]? | ft[.]? | x | with ", RegexOption.IGNORE_CASE)

// whether a credit names the artist, alone or among others; a longer name that merely starts the same is someone else
internal fun creditNames(credit: String, artistKey: String): Boolean =
    mixArtistKey(credit) == artistKey || credit.split(CREDIT_SPLIT).any { DjKeywords.norm(it) == artistKey }

// what the history says, keyed by recording so a play on one source counts for its copy on another
internal class MixTaste(events: List<PlayEvent>, val clock: MixClock, extraSkips: Map<String, Int> = emptyMap()) {
    val plays = HashMap<String, Int>()
    val affinity = HashMap<String, Double>()
    val last = HashMap<String, Long>()
    val ids = HashMap<String, String>()
    val skips = HashMap(extraSkips)
    val partPlays = List(DayPart.entries.size) { HashMap<String, Int>() }
    val partTotal = IntArray(DayPart.entries.size)
    val artistLove = HashMap<String, Double>()
    val artistPart = List(DayPart.entries.size) { HashMap<String, Int>() }
    private val artistSessions = HashMap<String, Int>()
    private val together = HashMap<String, HashMap<String, Int>>()

    init {
        val session = LinkedHashSet<String>()
        // a long history repeats its songs, and each key costs a few regex passes
        val named = HashMap<String, Pair<String, String>>()
        var previous = Long.MIN_VALUE
        fun close() {
            val artists = session.take(SESSION_ARTISTS)
            artists.forEach { a ->
                artistSessions.merge(a, 1, Int::plus)
                artists.forEach { b -> if (a != b) together.getOrPut(a) { HashMap() }.merge(b, 1, Int::plus) }
            }
            session.clear()
        }
        for (e in events.sortedBy { it.timestamp }) {
            // gson leaves nulls in old rows
            val id = (e.songId as String?).orEmpty()
            val title = (e.title as String?).orEmpty()
            val credit = (e.artist as String?).orEmpty()
            if (id.isEmpty() || title.isBlank() || id.startsWith("radio:") || id.startsWith("podcast:")) continue
            val (key, artist) = named.getOrPut(id) { mixTrackKey(credit, title) to mixArtistKey(credit) }
            if (!e.qualifiesAsPlay) { skips.merge(key, 1, Int::plus); continue }
            if (previous != Long.MIN_VALUE && e.timestamp - previous > SESSION_GAP) close()
            previous = e.timestamp
            val weight = 0.5.pow(((clock.now - e.timestamp).coerceAtLeast(0) / MixClock.DAY.toDouble()) / HALF_LIFE_DAYS)
            val part = DayPart.of(clock.hourOf(e.timestamp)).ordinal
            plays.merge(key, 1, Int::plus)
            affinity.merge(key, weight, Double::plus)
            last[key] = e.timestamp
            ids[key] = id
            partPlays[part].merge(key, 1, Int::plus)
            partTotal[part]++
            if (artist.isNotEmpty()) {
                artistLove.merge(artist, weight, Double::plus)
                artistPart[part].merge(artist, 1, Int::plus)
                session += artist
            }
        }
        close()
    }

    val listened: Boolean get() = artistSessions.isNotEmpty()

    fun heardTogether(a: String) = a in artistSessions

    fun coListen(a: String, b: String): Double {
        val n = together[a]?.get(b) ?: return 0.0
        return n / sqrt(((artistSessions[a] ?: 0) + 2.0) * ((artistSessions[b] ?: 0) + 2.0))
    }

    fun top(n: Int): List<String> = affinity.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(n).map { it.key }

    private companion object {
        const val SESSION_GAP = 30 * 60_000L
        const val SESSION_ARTISTS = 12
        const val HALF_LIFE_DAYS = 90.0
    }
}

object MixBuilder {
    const val TARGET = 30
    const val MIN = 12
    const val MIN_DISCOVER = 10
    const val MAX_DAILY = 6
    const val MAX_STATIONS = 5
    // the pseudo slot station artists rest under, next to the track keys of real slots
    const val STATION_ARTISTS = "station-artists"
    private const val MAX_MEMBERS = 10
    private const val MIN_MEMBERS = 3
    private const val SEEDS = 60
    private const val NEAR = 0.15
    private const val FIT_FLOOR = 0.35
    private const val RARE_AGE = 30 * MixClock.DAY
    private val FLOW = DjIntent(energyStart = 0, energyEnd = 10)
    private val NOT_GENRES = setOf("unknown", "other", "misc", "none", "and", "music")
    private val NO_ARTIST = setOf("", "unknown artist", "unknown", "various artists", "various")

    private fun rest(kind: MixKind) = when (kind) {
        MixKind.DAILY, MixKind.TIME -> 1
        MixKind.RECOMMENDED -> 3
        MixKind.DISCOVER -> 7
        MixKind.STATION -> 2
    }

    fun slot(kind: MixKind, index: Int = 0, part: DayPart? = null) = when (kind) {
        MixKind.DAILY -> "daily-$index"
        MixKind.TIME -> "time-${part?.name?.lowercase()}"
        MixKind.RECOMMENDED -> "recommended"
        MixKind.DISCOVER -> "discover"
        MixKind.STATION -> "artist-$index"
    }

    fun build(input: MixInput): List<GeneratedMix> = Build(input).run(input.stations)

    // two steps, for a caller that asks the sources about the station artists in between
    fun prepare(input: MixInput) = Prepared(Build(input))

    class Prepared internal constructor(private val build: Build) {
        val stationArtists: List<StationSeed> get() = build.stationSeeds()
        fun build(stations: List<Station>): List<GeneratedMix> = build.run(stations)
    }

    internal class Track(val song: Song, val key: String, val artist: String, val genres: Set<String>, val signal: DjSonic.Signal?, val liked: Boolean)

    internal class Artist(val key: String, val name: String) {
        val tracks = ArrayList<Track>()
        var love = 0.0
        var genres: Set<String> = emptySet()
        var centroid: FloatArray? = null
    }

    internal class Build(private val input: MixInput) {
        private val clock = input.clock
        private val taste = input.taste ?: MixTaste(input.events, clock, input.skips)
        private val signals = DjSonic.signals(input.vectors)
        private val tracks: List<Track>
        private val byKey: Map<String, Track>
        private val artists = LinkedHashMap<String, Artist>()
        private val topLove: Double
        private val topArtists: List<Artist>
        private val lovedVectors: List<FloatArray>
        private val genreLove = HashMap<String, Double>()
        private val topGenre: Double

        init {
            val seen = HashSet<String>()
            tracks = input.known.mapNotNull { track(it)?.takeIf { t -> seen.add(t.key) } }
            byKey = tracks.associateBy { it.key }
            for (t in tracks) {
                if (t.artist.isEmpty()) continue
                artists.getOrPut(t.artist) { Artist(t.artist, djPrimaryArtist(t.song.artist)) }.tracks += t
            }
            for (a in artists.values) {
                a.love = (taste.artistLove[a.key] ?: 0.0) + LIKE_ARTIST * a.tracks.count { it.liked }
                a.genres = a.tracks.flatMapTo(HashSet()) { it.genres }
                a.centroid = centroid(a.tracks)
            }
            topArtists = artists.values.filter { it.love > 0 }.sortedWith(compareByDescending<Artist> { it.love }.thenBy { it.key }).take(SEEDS)
            topLove = topArtists.firstOrNull()?.love ?: 0.0
            lovedVectors = tracks.filter { it.signal != null && love(it) > 0 }
                .sortedWith(compareByDescending<Track> { love(it) }.thenBy { it.song.id }).take(24).map { it.signal!!.vector }
            for (t in tracks) love(t).takeIf { it > 0 }?.let { w -> t.genres.forEach { g -> genreLove.merge(g, w, Double::plus) } }
            topGenre = genreLove.values.maxOrNull() ?: 0.0
        }

        // catalogue searches return dj sets and episodes, and the dj's own filter already knows them
        private fun track(song: Song): Track? {
            if (song.id.isEmpty() || input.hidden(song) || DjPicker.score(FLOW, DjCandidate(song)) == null) return null
            // by word, so "hip-hop & rap", "hip hop/rap" and "hiphop" are one neighbourhood
            val genres = GenreText.split(song.genre).flatMap { DjKeywords.norm(it).replace("hiphop", "hip hop").split(' ') }.filterTo(HashSet()) { it.length >= 2 && it !in NOT_GENRES }
            return Track(song, mixTrackKey(song), mixArtistKey(song.artist), genres, signals[song.id], song.liked || song.id in input.likedIds)
        }

        private fun centroid(of: List<Track>): FloatArray? {
            val vectors = of.mapNotNull { it.signal?.vector }
            if (vectors.isEmpty()) return null
            return FloatArray(vectors.first().size) { d -> (vectors.sumOf { it[d].toDouble() } / vectors.size).toFloat() }
        }

        private fun cosine(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0.0
        }

        private fun plays(t: Track) = taste.plays[t.key] ?: 0

        private fun love(t: Track) = ((taste.affinity[t.key] ?: 0.0) + (if (t.liked) LIKE_TRACK else 0.0) - SKIP * (taste.skips[t.key] ?: 0)).coerceAtLeast(0.0)

        private fun sim(a: Artist, b: Artist): Double {
            var total = 0.0
            var weight = 0.0
            if (taste.heardTogether(a.key) && taste.heardTogether(b.key)) { total += 0.5 * taste.coListen(a.key, b.key); weight += 0.5 }
            if (a.genres.isNotEmpty() && b.genres.isNotEmpty()) {
                total += 0.25 * a.genres.count { it in b.genres } / (a.genres.size + b.genres.size - a.genres.count { it in b.genres }).toDouble()
                weight += 0.25
            }
            val ca = a.centroid
            val cb = b.centroid
            if (ca != null && cb != null) { total += 0.25 * cosine(ca, cb).coerceAtLeast(0.0); weight += 0.25 }
            return if (weight == 0.0) 0.0 else total / weight
        }

        private fun fit(t: Track): Double {
            var s = 0.0
            val artist = artists[t.artist]
            if (topLove > 0) {
                val own = (artist?.love ?: taste.artistLove[t.artist] ?: 0.0) / topLove
                if (own > 0) s += 2.0 * sqrt(own.coerceAtMost(1.0))
                else if (artist != null) s += topArtists.take(12).maxOfOrNull { sim(artist, it) * sqrt(it.love / topLove) } ?: 0.0
            }
            if (topGenre > 0 && t.genres.isNotEmpty()) s += t.genres.maxOf { genreLove[it] ?: 0.0 } / topGenre
            t.signal?.let { v -> lovedVectors.maxOfOrNull { cosine(v.vector, it) }?.let { s += 1.5 * it.coerceAtLeast(0.0) } }
            return s
        }

        private fun seedFor(slot: String) = (input.seed * 31 + clock.day) * 1_000_003L + slot.hashCode()

        private fun choose(kind: MixKind, slot: String, weighted: List<Pair<Track, Double>>, cap: Int, taken: Set<String> = emptySet(), count: Int = TARGET): List<Track> {
            val random = Random(seedFor(slot))
            val shown = input.shown[slot].orEmpty()
            val pool = weighted.filter { it.second > 0 && it.first.key !in taken }
            // a narrow taste may lean on its few artists rather than leave the mix short
            val limit = max(cap, ceil(count / pool.distinctBy { it.first.artist }.size.coerceAtLeast(1).toDouble()).toInt() + 1)
            val ranked = pool
                .sortedWith(compareBy<Pair<Track, Double>> { it.first.song.id }.thenBy { it.first.key })
                .map { (t, w) -> Triple(t, random.nextDouble().pow(1.0 / w), shown[t.key]?.let { clock.day - it in 1..rest(kind) } == true) }
                .sortedByDescending { it.second }
            val out = ArrayList<Track>(count)
            val keys = HashSet<String>()
            val perArtist = HashMap<String, Int>()
            for (resting in listOf(false, true)) for ((t, _, rests) in ranked) {
                if (out.size >= count) break
                if (rests != resting || t.key in keys || (perArtist[t.artist] ?: 0) >= limit) continue
                keys += t.key
                perArtist.merge(t.artist, 1, Int::plus)
                out += t
            }
            return out
        }

        private fun mix(kind: MixKind, slot: String, picked: List<Track>, index: Int = 0, part: DayPart? = null): GeneratedMix {
            val candidates = picked.map { DjCandidate(it.song, plays(it), it.liked, 0, it.signal?.energy, it.signal?.bpm ?: 0, it.signal?.vector) }
            val ordered = DjPicker.pick(FLOW, candidates, picked.size, random = Random(seedFor(slot) + 1), flow = true)
            val lead = picked.groupingBy { it.artist }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { artists[it.key]?.love ?: 0.0 }.thenBy { it.key })
                .take(3).mapNotNull { e -> picked.first { it.artist == e.key }.song.artist.let(::djPrimaryArtist).takeIf { it.isNotBlank() } }
            return GeneratedMix(slot, kind, clock.day, index, part, lead, ordered)
        }

        private fun clusters(): List<List<Artist>> {
            if (topArtists.isEmpty()) return emptyList()
            val free = LinkedHashSet(topArtists)
            val out = ArrayList<List<Artist>>()
            for (seed in topArtists) {
                if (out.size >= MAX_DAILY || seed.love < topLove * 0.04) break
                if (seed !in free) continue
                val near = free.filter { it !== seed }.map { it to sim(seed, it) }.filter { it.second >= NEAR }
                    .sortedWith(compareByDescending<Pair<Artist, Double>> { it.second }.thenBy { it.first.key })
                val members = arrayListOf(seed)
                var songs = seed.tracks.size
                for ((artist, _) in near) {
                    // a seed with a shelf of its own still gets company before the size cap applies
                    if (members.size >= MAX_MEMBERS || (songs >= TARGET * 4 && members.size >= MIN_MEMBERS)) break
                    members += artist
                    songs += artist.tracks.size
                }
                // one or two artists are no neighbourhood; they stay free for a later seed
                if (songs < MIN || members.size < MIN_MEMBERS) continue
                free.removeAll(members.toSet())
                out += members
            }
            return out
        }

        private fun daily(taken: MutableSet<String>): List<GeneratedMix> {
            val out = ArrayList<GeneratedMix>()
            for (members in clusters()) {
                val index = out.size + 1
                val slot = slot(MixKind.DAILY, index)
                val inside = members.flatMap { it.tracks }
                val keys = inside.mapTo(HashSet()) { it.key }
                val weighted = inside.map { it to 1.0 + 2.0 * love(it) }.toMutableList()
                centroid(inside)?.let { centre ->
                    weighted += tracks.asSequence().filter { it.signal != null && it.key !in keys }
                        .map { it to cosine(it.signal!!.vector, centre) }.filter { it.second >= 0.55 }
                        .sortedWith(compareByDescending<Pair<Track, Double>> { it.second }.thenBy { it.first.song.id }).take(TARGET / 5).map { it.first to 0.5 * it.second }
                }
                val picked = choose(MixKind.DAILY, slot, weighted, 3, taken)
                if (picked.size < MIN) continue
                taken += picked.map { it.key }
                out += mix(MixKind.DAILY, slot, picked, index)
            }
            if (out.isNotEmpty()) return out
            val slot = slot(MixKind.DAILY, 1)
            val picked = choose(MixKind.DAILY, slot, tracks.map { it to love(it) }, 3, taken)
            if (picked.size < MIN) return emptyList()
            taken += picked.map { it.key }
            return listOf(mix(MixKind.DAILY, slot, picked, 1))
        }

        private fun time(part: DayPart): GeneratedMix? {
            val heard = taste.partPlays[part.ordinal]
            if (taste.partTotal[part.ordinal] < 15 || heard.size < 8) return null
            val core = heard.mapNotNull { (key, n) -> byKey[key]?.let { it to n * (0.5 + n / (taste.plays[key] ?: n).toDouble()) } }
            val keys = core.mapTo(HashSet()) { it.first.key }
            val centre = centroid(core.map { it.first }).takeIf { core.count { it.first.signal != null } >= 5 }
            val partArtists = taste.artistPart[part.ordinal]
            val around = tracks.filter { it.key !in keys }.mapNotNull { t ->
                var w = (partArtists[t.artist] ?: 0).takeIf { it >= 2 }?.let { 0.25 * minOf(it, 6) / 6.0 } ?: 0.0
                if (centre != null) t.signal?.let { cosine(it.vector, centre) }?.takeIf { it >= 0.5 }?.let { w += 0.5 * it }
                if (w > 0) t to w else null
            }
            val slot = slot(MixKind.TIME, part = part)
            val picked = choose(MixKind.TIME, slot, core + around, 3)
            return if (picked.size < MIN) null else mix(MixKind.TIME, slot, picked, part = part)
        }

        private fun recommended(taken: MutableSet<String>): GeneratedMix? {
            val weighted = tracks.filter { plays(it) <= 1 && clock.now - (taste.last[it.key] ?: 0L) >= RARE_AGE }
                .map { it to fit(it) + if (it.liked) 0.6 else 0.0 }.filter { it.second >= FIT_FLOOR }
            val picked = choose(MixKind.RECOMMENDED, slot(MixKind.RECOMMENDED), weighted.map { it.first to it.second * it.second }, 2, taken)
            if (picked.size < MIN) return null
            taken += picked.map { it.key }
            return mix(MixKind.RECOMMENDED, slot(MixKind.RECOMMENDED), picked)
        }

        private fun discover(taken: Set<String>): GeneratedMix? {
            val evidence = HashMap<String, Int>()
            val found = LinkedHashMap<String, Track>()
            for (song in input.candidates) {
                val t = track(song) ?: continue
                if (t.liked || t.artist in NO_ARTIST || t.key in taste.plays || t.key in byKey) continue
                evidence.merge(t.key, 1, Int::plus)
                found.putIfAbsent(t.key, t)
            }
            var weighted = found.values.map { t ->
                t to 0.6 * minOf(evidence[t.key] ?: 1, 3) + fit(t) + if ((taste.artistLove[t.artist] ?: 0.0) == 0.0) 0.3 else 0.0
            }
            if (weighted.size < MIN_DISCOVER) weighted = weighted + tracks.filter { !it.liked && it.key !in taste.plays }
                .map { it to fit(it) }.filter { it.second >= FIT_FLOOR }
            val picked = choose(MixKind.DISCOVER, slot(MixKind.DISCOVER), weighted.map { it.first to it.second * it.second }, 2, taken)
            return if (picked.size < MIN_DISCOVER) null else mix(MixKind.DISCOVER, slot(MixKind.DISCOVER), picked)
        }

        fun stationSeeds(): List<StationSeed> {
            if (MixKind.STATION !in input.kinds) return emptyList()
            val random = Random(seedFor(STATION_ARTISTS))
            val shown = input.shown[STATION_ARTISTS].orEmpty()
            return topArtists.take(STATION_POOL).filter { it.key !in NO_ARTIST }
                .map { Triple(it, random.nextDouble().pow(1.0 / it.love), shown[it.key]?.let { day -> clock.day - day in 1..rest(MixKind.STATION) } == true) }
                .sortedWith(compareBy<Triple<Artist, Double, Boolean>> { it.third }.thenByDescending { it.second }).take(MAX_STATIONS)
                .map { (artist, _, _) -> StationSeed(artist.name, artist.tracks.sortedWith(compareByDescending<Track> { love(it) }.thenBy { it.song.id }).map { it.song }) }
        }

        // the artist on every source that has them, one copy per recording, then what the sources play next to them
        private fun station(index: Int, material: Station): GeneratedMix? {
            val artist = mixArtistKey(material.artist)
            val seen = HashSet<String>()
            val own = ((artists[artist]?.tracks.orEmpty().map { it.song }) + material.own).mapNotNull(::track)
                .filter { creditNames(it.song.artist, artist) }.filter { seen.add(it.key) }
            if (own.size < STATION_OWN_MIN) return null
            val evidence = HashMap<String, Int>()
            val around = LinkedHashMap<String, Track>()
            for (song in material.related) {
                val t = track(song) ?: continue
                if (t.key in seen || t.artist == artist || t.artist in NO_ARTIST) continue
                evidence.merge(t.key, 1, Int::plus)
                around.putIfAbsent(t.key, t)
            }
            val slot = slot(MixKind.STATION, index)
            // the sources list an artist's best known songs first
            val picked = choose(MixKind.STATION, slot, own.mapIndexed { i, t -> t to 1.0 + love(t) + 1.0 / (1 + i / 5) }, TARGET, count = if (around.isEmpty()) TARGET else STATION_OWN)
            val keys = picked.mapTo(HashSet()) { it.key }
            val rest = choose(MixKind.STATION, slot, around.values.map { it to 0.5 + 0.5 * minOf(evidence[it.key] ?: 1, 3) + 0.5 * minOf(fit(it), 2.0) }, 2, keys, TARGET - picked.size)
            if (picked.size + rest.size < MIN) return null
            val mix = mix(MixKind.STATION, slot, picked + rest, index)
            val opener = mix.songs.firstOrNull { s -> picked.any { it.song.id == s.id } }
            val names = listOf(material.artist) + mix.artists.filter { mixArtistKey(it) != artist }.take(2)
            return mix.copy(artists = names, songs = listOfNotNull(opener) + mix.songs.filter { it !== opener })
        }

        fun run(stations: List<Station> = emptyList()): List<GeneratedMix> {
            val out = ArrayList<GeneratedMix>()
            val taken = HashSet<String>()
            if (MixKind.TIME in input.kinds) DayPart.entries.forEach { part -> time(part)?.let(out::add) }
            if (MixKind.DAILY in input.kinds) out += daily(taken)
            if (MixKind.RECOMMENDED in input.kinds) recommended(taken)?.let(out::add)
            if (MixKind.DISCOVER in input.kinds) discover(taken)?.let(out::add)
            if (MixKind.STATION in input.kinds) stations.forEach { material -> station(out.count { it.kind == MixKind.STATION } + 1, material)?.let(out::add) }
            return out
        }

        private companion object {
            const val LIKE_TRACK = 1.0
            const val LIKE_ARTIST = 0.6
            const val SKIP = 0.5
            const val STATION_POOL = 15
            const val STATION_OWN = 12
            const val STATION_OWN_MIN = 3
        }
    }
}
