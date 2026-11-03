package com.aurora.music.ai

import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

class DjEngine(
    private val repository: MusicRepository,
    private val history: PlayHistoryStore,
    private val llm: LocalLlm?,
    private val modelReady: () -> Boolean,
    private val vectors: suspend () -> Map<String, FloatArray> = { emptyMap() },
    private val similar: suspend (Song, Int) -> List<Song> = { _, _ -> emptyList() },
) {
    data class Interpretation(val intent: DjIntent, val usedModel: Boolean, val timings: LlmTimings? = null)

    private companion object { val FLOW = DjIntent(energyStart = 0, energyEnd = 10) }

    private class Library(val key: String, val at: Long, val songs: List<Song>, val vocabulary: DjVocabulary)

    private val lock = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var library: Library? = null
    @Volatile private var liked: Pair<String, List<Song>>? = null
    private var likedJob: Job? = null
    @Volatile private var years: Pair<String, Map<String, Int>>? = null

    val usesModel: Boolean get() = llm != null && modelReady() && llm.available

    private fun key() = repository.activeSession?.let { "${it.type}|${it.server}|${it.username}" }.orEmpty()

    // some sources block instead of suspending, so the wait is bounded rather than the work itself
    private suspend inline fun <T> quietly(ms: Long, default: T, crossinline block: suspend () -> T): T {
        val work = background.async { block() }
        return try {
            withTimeoutOrNull(ms) { work.await() } ?: default
        } catch (e: CancellationException) { throw e } catch (_: Exception) { default } finally { if (!work.isCompleted) work.cancel() }
    }

    private suspend fun library(): Library = lock.withLock {
        val key = key()
        library?.takeIf { it.key == key && System.currentTimeMillis() - it.at < 15 * 60_000 && it.songs.isNotEmpty() }?.let { return it }
        likedNow()
        val songs = quietly(30_000, emptyList()) { repository.librarySongs(5000) }
        val plays = history.snapshot().groupingBy { it.artist }.eachCount()
        val genres = songs.asSequence().map { it.genre.trim() }
            .filter { it.length in 2..30 && DjKeywords.norm(it) !in setOf("unknown", "other", "misc", "none") }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(30).map { it.key }
        val artists = songs.groupingBy { it.artist.trim() }.eachCount().filterKeys { it.length in 2..40 }
            .entries.sortedByDescending { it.value + (plays[it.key] ?: 0) * 3 }.take(30).map { it.key }
        Library(key, System.currentTimeMillis(), songs, DjVocabulary(genres, artists)).also { library = it }
    }

    // liked songs can take far longer than a request on big merged accounts, so they never block one
    @Synchronized private fun likedNow(): List<Song> {
        val key = key()
        liked?.takeIf { it.first == key }?.let { return it.second }
        if (likedJob?.isActive != true) likedJob = background.launch {
            val songs = quietly(120_000, emptyList()) { repository.starredSongs() }
            if (songs.isNotEmpty()) liked = key to songs
        }
        return emptyList()
    }

    suspend fun vocabulary(): DjVocabulary = library().vocabulary

    suspend fun interpret(text: String, current: DjIntent? = null): Interpretation = withContext(Dispatchers.Default) {
        val vocabulary = library().vocabulary
        val keywords = DjKeywords.parse(text, vocabulary, current)
        val model = llm?.takeIf { usesModel } ?: return@withContext Interpretation(keywords.intent, false)
        val prompt = if (current == null) DjPrompt.request(text) else DjPrompt.steer(current, text)
        val raw = quietly(60_000, null) { model.complete(DjPrompt.prefix(vocabulary), prompt, DjPrompt.GRAMMAR, DjPrompt.MAX_TOKENS) }
        val parsed = raw?.let(DjIntent::parse)?.let(DjPrompt::scrub) ?: return@withContext Interpretation(keywords.intent, false, model.lastTimings)
        Interpretation(DjKeywords.merge(parsed, keywords, text, current != null), true, model.lastTimings)
    }

    suspend fun pick(intent: DjIntent, count: Int = intent.count, exclude: Set<String> = emptySet(), after: Song? = null): List<Song> = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val library = async { library() }
        fun era(d: Int) = if (d < 2000) "${d % 100}s" else "${d}s"
        val mood = intent.moods.firstOrNull()?.let { "$it " }.orEmpty()
        val eras = intent.decades.take(2).flatMap { d -> intent.genres.take(2).map { "${era(d)} $mood$it" } }
        val queries = (intent.artists.take(3) + eras + intent.search + intent.genres).ifEmpty { listOf(intent.moods.take(2).joinToString(" ")) }
            .map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }.take(6)
        val searches = queries.map { q -> async { quietly(8_000, emptyList()) { repository.search(q).songs.take(25) } } }
        val seeded = after?.let { seed ->
            async { quietly(8_000, emptyList()) { repository.radio(seed.id) } + quietly(8_000, emptyList()) { similar(seed, 30) } }
        }
        val years = if (intent.decades.isEmpty()) emptyMap() else async { years() }.await()
        val signals = DjSonic.signals(quietly(10_000, emptyMap()) { vectors() })
        val plays = history.snapshot().groupingBy { it.songId }.eachCount()
        val cached = library.await()
        val likedSongs = likedNow()
        val likedIds = likedSongs.mapTo(HashSet()) { it.id }
        val found = searches.awaitAll()
        val ready = System.currentTimeMillis()
        val hits = HashMap<String, Int>()
        val eraOf = HashMap<String, Int>()
        // a hit for an era search stands in for the year untagged catalogue tracks don't carry
        found.forEachIndexed { i, songs ->
            val query = queries[i].lowercase()
            val weight = if (intent.genres.any { it.equals(query, ignoreCase = true) }) 1 else 2
            val decade = intent.decades.firstOrNull { era(it) in query || it.toString() in query }
            songs.forEach { s -> hits[s.id] = (hits[s.id] ?: 0) + weight; if (decade != null) eraOf[s.id] = decade }
        }
        val pool = LinkedHashMap<String, Song>()
        for (s in found.flatten() + likedSongs + cached.songs + (seeded?.await().orEmpty())) if (s.id.isNotEmpty()) pool.putIfAbsent(s.id, s)
        val candidates = pool.values.map { s ->
            val signal = signals[s.id]
            DjCandidate(s, plays[s.id] ?: 0, s.liked || s.id in likedIds, years[s.albumId] ?: eraOf[s.id] ?: 0, signal?.energy, signal?.bpm ?: 0, signal?.vector, hits[s.id] ?: 0)
        }
        val previous = after?.let { a -> candidates.firstOrNull { it.song.id == a.id } ?: DjCandidate(a) }
        DjPicker.pick(intent, candidates, count, exclude + listOfNotNull(after?.id), previous).also {
            AppLog.d("AiDj", "pool ${candidates.size} (library ${cached.songs.size}, liked ${likedSongs.size}, searches ${queries.size}) in ${ready - start}ms, pick ${System.currentTimeMillis() - ready}ms")
        }
    }

    // the dj's own next set, taken from listening history and likes with no prompt
    suspend fun nextSet(session: DjSession, after: Song?, count: Int = DjMode.BATCH, random: Random = Random.Default): DjSet? = withContext(Dispatchers.Default) {
        val cached = library()
        val taste = DjTaste(history.snapshot(), System.currentTimeMillis())
        val liked = likedNow()
        val likedIds = liked.mapTo(HashSet()) { it.id }
        val known = LinkedHashMap<String, Song>()
        for (s in liked + cached.songs) if (s.id.isNotEmpty()) known.putIfAbsent(s.id, s)
        val signals = DjSonic.signals(quietly(10_000, emptyMap()) { vectors() })
        val skip = session.placed + taste.justHeard + listOfNotNull(after?.id)
        fun candidate(s: Song) = signals[s.id].let { DjCandidate(s, taste.plays[s.id] ?: 0, s.liked || s.id in likedIds, 0, it?.energy, it?.bpm ?: 0, it?.vector) }
        fun build(segment: DjSegment, artist: String?, songs: List<Song>): DjSet? {
            val fresh = songs.filterNot { it.id in skip || session.heard(it) }
            val picked = DjPicker.pick(FLOW, fresh.map(::candidate), count, previous = after?.let(::candidate), random = random, flow = true)
            return if (picked.size >= minOf(3, count)) DjSet(segment, artist, picked).also { session.mark(picked) } else null
        }
        for (step in session.plan.indices) {
            val segment = session.plan[session.next++ % session.plan.size]
            val want = count * 2
            val set = when (segment) {
                DjSegment.REPEAT -> build(segment, null, hydrate(DjTaste.sample(taste.repeat.filterKeys { it !in skip }, want, random), known))
                DjSegment.HOUR -> build(segment, null, hydrate(DjTaste.sample(taste.hour.filterKeys { it !in skip }, want, random), known))
                DjSegment.PAST -> build(segment, null, hydrate(DjTaste.sample(taste.past.filterKeys { it !in skip }, want, random), known))
                DjSegment.LIKED -> build(segment, null, known.values.filter { (it.liked || it.id in likedIds) && it.id !in taste.repeat }.shuffled(random).take(want))
                DjSegment.ARTIST -> taste.artists.take(12).firstOrNull { it !in session.artists }?.let { artist ->
                    session.artists += artist
                    val name = DjKeywords.norm(artist)
                    val found = quietly(8_000, emptyList()) { repository.search(artist).songs.take(25) }
                    build(segment, artist, (known.values + found).filter { " ${DjKeywords.norm(it.artist)} ".contains(" $name ") }.shuffled(random).take(want))
                }
                DjSegment.NEW -> {
                    val seeds = hydrate(DjTaste.sample(taste.repeat.ifEmpty { taste.plays }, 2, random), known)
                    val related = seeds.flatMap { seed -> quietly(8_000, emptyList()) { repository.radio(seed.id) } + quietly(8_000, emptyList()) { similar(seed, 20) } }
                    build(segment, null, related.filter { it.id !in taste.plays && !it.liked && it.id !in likedIds }.shuffled(random).take(want))
                }
                DjSegment.MIX -> null
            }
            if (set != null) return@withContext set
        }
        build(DjSegment.MIX, null, known.values.shuffled(random).take(count * 4))
    }

    private suspend fun hydrate(ids: List<String>, known: Map<String, Song>): List<Song> = coroutineScope {
        ids.map { id -> async { known[id] ?: quietly(5_000, null) { repository.songFor(id) } } }.awaitAll().filterNotNull()
    }

    private suspend fun years(): Map<String, Int> {
        val key = key()
        this.years?.takeIf { it.first == key }?.let { return it.second }
        val albums = quietly(15_000, emptyList()) { repository.allAlbums() }
        return albums.filter { it.year > 0 }.associate { it.id to it.year }.also { if (albums.isNotEmpty()) this.years = key to it }
    }
}
