package com.aurora.music.ai

import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

class DjEngine(
    private val repository: MusicRepository,
    private val history: PlayHistoryStore,
    private val llm: LocalLlm?,
    private val modelReady: () -> Boolean,
    private val vectors: suspend () -> Map<String, FloatArray> = { emptyMap() },
    private val similar: suspend (Song, Int, List<Song>) -> List<Song> = { _, _, _ -> emptyList() },
    val memory: DjMemory = DjMemory(null),
    private val releaseMs: Long = 15 * 60_000L,
) {
    data class Interpretation(val intent: DjIntent, val usedModel: Boolean, val timings: LlmTimings? = null)

    private companion object {
        val FLOW = DjIntent(energyStart = 0, energyEnd = 10)
        const val MIN_SET = 4
        const val LIBRARY_TTL_MS = 6 * 3_600_000L
        const val LIBRARY_RETRY_MS = 15 * 60_000L
        val NOT_GENRES = setOf("unknown", "other", "misc", "none")
    }

    private class Library(val key: String, val at: Long, val songs: List<Song>, val vocabulary: DjVocabulary)

    private val lock = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var library: Library? = null
    @Volatile private var liked: Pair<String, List<Song>>? = null
    private var likedJob: Job? = null
    @Volatile private var years: Pair<String, Map<String, Int>>? = null
    private var yearsLoad: Pair<String, Deferred<Map<String, Int>>>? = null
    @Volatile private var lastStats: Pair<Long, DjStats?>? = null
    @Volatile private var release: Job? = null
    private var held = false

    val usesModel: Boolean get() = llm != null && modelReady() && llm.available

    private fun key() = repository.activeSession?.let { "${it.type}|${it.server}|${it.username}|${repository.isOffline}" }.orEmpty()

    fun forgetLibrary() { library = null }

    // some sources block instead of suspending, so the wait is bounded rather than the work itself
    private suspend inline fun <T> quietly(ms: Long, default: T, crossinline block: suspend () -> T): T {
        val work = background.async { block() }
        return try {
            withTimeoutOrNull(ms) { work.await() } ?: default
        } catch (e: CancellationException) { throw e } catch (_: Exception) { default } finally { if (!work.isCompleted) work.cancel() }
    }

    private suspend fun library(): Library = lock.withLock {
        release?.cancel()
        val key = key()
        library?.takeIf { it.key == key && System.currentTimeMillis() - it.at < LIBRARY_TTL_MS && it.songs.isNotEmpty() }?.let { return it }
        likedNow()
        val fetched = quietly<List<Song>?>(30_000, null) { repository.librarySongs(5000) }
        // a dead link keeps the last list and retries later, not on every call with this lock held
        if (fetched.isNullOrEmpty()) library?.takeIf { it.key == key && it.songs.isNotEmpty() }?.let { old ->
            return Library(key, System.currentTimeMillis() - LIBRARY_TTL_MS + LIBRARY_RETRY_MS, old.songs, old.vocabulary).also { library = it }
        }
        val songs = fetched.orEmpty()
        val plays = history.snapshot().groupingBy { it.artist }.eachCount()
        val genres = songs.asSequence().flatMap { com.aurora.music.data.GenreText.split(it.genre) }
            .filter { it.length in 2..30 && DjKeywords.norm(it) !in NOT_GENRES }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(30).map { it.key }
        val artists = songs.groupingBy { it.artist.trim() }.eachCount().filterKeys { it.length in 2..40 }
            .entries.sortedByDescending { it.value + (plays[it.key] ?: 0) * 3 }.take(30).map { it.key }
        Library(key, System.currentTimeMillis(), songs, DjVocabulary(genres, artists)).also { library = it }
    }

    // liked songs can take far longer than a request on big merged accounts, so they never block one
    @Synchronized private fun likedNow(): List<Song> {
        release?.cancel()
        val key = key()
        liked?.takeIf { it.first == key }?.let { return it.second }
        if (likedJob?.isActive != true) likedJob = background.launch {
            val songs = quietly(120_000, emptyList()) { repository.starredSongs() }
            if (songs.isNotEmpty()) liked = key to songs
        }
        return emptyList()
    }

    // refills can be further apart than the wait, so a running dj holds the lists
    @Synchronized fun scheduleRelease() {
        release?.cancel()
        if (!held) release = background.launch {
            delay(releaseMs)
            synchronized(this@DjEngine) {
                if (isActive) { likedJob?.cancel(); library = null; liked = null; years = null; yearsLoad = null }
            }
        }
    }

    @Synchronized fun djRunning(running: Boolean) {
        held = running
        scheduleRelease()
    }

    suspend fun vocabulary(): DjVocabulary = withContext(Dispatchers.Default) { library().vocabulary }

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
            async {
                val radio = quietly(8_000, emptyList()) { repository.radio(seed.id) }
                val pool = likedNow() + library.await().songs
                radio + quietly(8_000, emptyList()) { similar(seed, 30, pool) }
            }
        }
        val albumYears = if (intent.decades.isEmpty()) null else async { years() }
        val signals = DjSonic.signals(quietly(10_000, emptyMap()) { vectors() })
        val years = albumYears?.await().orEmpty()
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

    fun newSession(random: Random = Random.Default) = DjSession(memory.plan(random))

    // the dj's own next set, taken from listening history and likes with no prompt
    suspend fun nextSet(session: DjSession, after: Song?, count: Int = DjMode.BATCH, random: Random = Random.Default, budgetMs: Long? = null): DjSet? = withContext(Dispatchers.Default) {
        val loadedVectors = async { quietly(10_000, emptyMap<String, FloatArray>()) { vectors() } }
        val cached = library()
        val taste = DjTaste(history.snapshot(), System.currentTimeMillis())
        lastStats = System.currentTimeMillis() to taste.stats
        val liked = likedNow()
        val likesLoading = liked.isEmpty() && likedJob?.isActive == true
        val likedIds = liked.mapTo(HashSet()) { it.id }
        val known = LinkedHashMap<String, Song>()
        for (s in liked + cached.songs) if (s.id.isNotEmpty()) known.putIfAbsent(s.id, s)
        val signals = DjSonic.signals(loadedVectors.await())
        val skip = session.placed + taste.justHeard + listOfNotNull(after?.id)
        val want = count * 3
        val heard = taste.keys.values.toHashSet()
        fun candidate(s: Song) = signals[s.id].let { DjCandidate(s, taste.plays[s.id] ?: 0, s.liked || s.id in likedIds, 0, it?.energy, it?.bpm ?: 0, it?.vector) }
        fun open(weights: Map<String, Int>) = weights.filterKeys { it !in skip && !memory.resting(taste.keys[it].orEmpty()) }
        fun artistsToTry(top: Int) = taste.artists.take(top).withIndex()
            .filter { (_, name) -> name !in session.artists && !memory.artistResting(name) }.associate { (i, name) -> name to top - i }
        fun unheard(s: Song) = s.id !in taste.plays && djTrackKey(s.artist, s.title) !in heard
        fun build(segment: DjSegment, artist: String?, songs: List<Song>, rest: Boolean = true): DjSet? {
            val fresh = songs.filterNot { it.id in skip || session.heard(it) }
            val usable = if (rest) memory.freshFirst(fresh) else fresh
            val picked = DjPicker.pick(FLOW, usable.take(want).map(::candidate), count, previous = after?.let(::candidate), random = random, flow = true)
            if (picked.size < minOf(MIN_SET, count)) return null
            session.mark(picked)
            memory.placed(picked)
            if (session.sets++ == 0) memory.opened(segment)
            return DjSet(segment, artist, picked)
        }
        suspend fun related(seeds: List<Song>, each: Int) = coroutineScope {
            seeds.map { seed ->
                async { quietly(8_000, emptyList()) { repository.radio(seed.id) } + quietly(8_000, emptyList()) { similar(seed, each, known.values.toList()) } }
            }.awaitAll().flatten()
        }
        suspend fun segments(): DjSet? {
            for (step in session.plan.indices) {
                val segment = session.plan[session.next++ % session.plan.size]
                val set = when (segment) {
                    DjSegment.REPEAT -> build(segment, null, hydrate(DjTaste.sample(open(taste.repeat), want, random), known))
                    DjSegment.HOUR -> build(segment, null, hydrate(DjTaste.sample(open(taste.hour), want, random), known))
                    DjSegment.PAST -> build(segment, null, hydrate(DjTaste.sample(open(taste.past), want, random), known))
                    // likes still loading would leave a tiny pool, this set can wait for its next turn
                    DjSegment.LIKED -> if (likesLoading) null else build(segment, null, known.values.filter { (it.liked || it.id in likedIds) && it.id !in taste.repeat && it.id !in taste.lately }.shuffled(random))
                    DjSegment.ARTIST -> DjTaste.sample(artistsToTry(30), 1, random).firstOrNull()?.let { artist ->
                        session.artists += artist
                        val name = DjKeywords.norm(artist)
                        val found = quietly(8_000, emptyList()) { repository.search(artist).songs.take(25) }
                        build(segment, artist, (known.values + found).filter { " ${DjKeywords.norm(it.artist)} ".contains(" $name ") }.shuffled(random))?.also { memory.usedArtist(artist) }
                    }
                    DjSegment.BECAUSE -> hydrate(DjTaste.sample(open(taste.plays), 6, random), known)
                        .firstOrNull { djPrimaryArtist(it.artist).let { name -> name.isNotEmpty() && name !in session.artists && !memory.artistResting(name) } }?.let { seed ->
                            val name = djPrimaryArtist(seed.artist)
                            session.artists += name
                            build(segment, name, related(listOf(seed), 30).filter(::unheard).shuffled(random))?.also { memory.usedArtist(name) }
                        }
                    DjSegment.DEEP -> {
                        val names = DjTaste.sample(artistsToTry(40), 3, random)
                        val found = coroutineScope { names.map { name -> async { quietly(8_000, emptyList()) { repository.search(name).songs.take(25) } } }.awaitAll().flatten() }
                        val wanted = names.map(DjKeywords::norm)
                        build(segment, null, (known.values + found).filter { s ->
                            (taste.plays[s.id] ?: 0) <= 1 && " ${DjKeywords.norm(s.artist)} ".let { artist -> wanted.any { artist.contains(" $it ") } }
                        }.shuffled(random))
                    }
                    DjSegment.NEW -> build(segment, null, related(hydrate(DjTaste.sample(taste.plays, 3, random), known), 20)
                        .filter { unheard(it) && !it.liked && it.id !in likedIds }.shuffled(random))
                    DjSegment.MIX -> null
                }
                if (set != null) return set
            }
            return null
        }
        // with nothing known the mix can't step in, so the segments keep all their time
        val set = if (budgetMs != null && known.isNotEmpty()) withTimeoutOrNull(budgetMs) { segments() } else segments()
        // a small library runs out of rested tracks, repeating beats going quiet
        set ?: build(DjSegment.MIX, null, known.values.shuffled(random)) ?: build(DjSegment.MIX, null, known.values.shuffled(random), rest = false)
    }

    suspend fun stats(): DjStats? = withContext(Dispatchers.Default) {
        lastStats?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return@withContext it.second }
        DjTaste(history.snapshot(), System.currentTimeMillis()).stats
    }

    private suspend fun hydrate(ids: List<String>, known: Map<String, Song>): List<Song> = coroutineScope {
        val gate = Semaphore(6)
        ids.map { id -> async { known[id] ?: quietly(5_000, null) { gate.withPermit { repository.songFor(id) } } } }.awaitAll().filterNotNull()
    }

    private suspend fun years(): Map<String, Int> {
        val key = key()
        this.years?.takeIf { it.first == key }?.let { return it.second }
        return withTimeoutOrNull(15_000) { loadYears(key).await() }.orEmpty()
    }

    // a big merged account can outlast the wait, the fetch goes on so the next pick finds it
    @Synchronized private fun loadYears(key: String): Deferred<Map<String, Int>> {
        yearsLoad?.takeIf { it.first == key && it.second.isActive }?.let { return it.second }
        return background.async {
            val albums = quietly(120_000, emptyList()) { repository.allAlbums() }
            albums.filter { it.year > 0 }.associate { it.id to it.year }.also { if (albums.isNotEmpty()) this@DjEngine.years = key to it }
        }.also { yearsLoad = key to it }
    }
}
