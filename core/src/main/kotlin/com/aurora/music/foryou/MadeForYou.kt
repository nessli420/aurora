package com.aurora.music.foryou

import com.aurora.music.ai.djPrimaryArtist
import com.aurora.music.data.DetailData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeSection
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.SavedTrack
import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.data.toSavedTrack
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import com.aurora.music.util.accentArgbFor
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.TimeZone
import kotlin.random.Random

data class MixName(val title: String, val subtitle: String, val saved: String)

// the home switches double as the generator's: a hidden row is not built either
fun mixKinds(hiddenHomeSections: Set<String>): Set<MixKind> = if (HomeSection.MIXES in hiddenHomeSections) emptySet() else buildSet {
    if (HomeSection.MIX_TIME !in hiddenHomeSections) add(MixKind.TIME)
    if (HomeSection.MIX_DAILY !in hiddenHomeSections) add(MixKind.DAILY)
    if (HomeSection.MIX_RECOMMENDED !in hiddenHomeSections) add(MixKind.RECOMMENDED)
    if (HomeSection.MIX_DISCOVER !in hiddenHomeSections) add(MixKind.DISCOVER)
}

class MadeForYou(
    private val repository: MusicRepository,
    private val history: PlayHistoryStore,
    private val file: File?,
    private val account: () -> String,
    private val kinds: Flow<Set<MixKind>>,
    private val snapshots: Flow<Map<String, String>>,
    private val saveLike: suspend (String, String?) -> Unit,
    private val keepLikes: suspend (Set<String>) -> Unit,
    private val names: (GeneratedMix) -> MixName,
    private val vectors: suspend () -> Map<String, FloatArray> = { emptyMap() },
    private val skips: suspend (Long) -> Map<String, Int> = { emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
    private val offset: (Long) -> Long = { TimeZone.getDefault().getOffset(it).toLong() },
    private val seed: Long = 0,
    // home's own load goes first; the mixes it already shows are only a day old
    private val settleMs: Long = 4_000,
) {
    private class SavedMix(val slot: String? = null, val kind: String? = null, val day: Long? = null, val index: Int? = null,
        val part: String? = null, val artists: List<String>? = null, val songs: List<SavedTrack>? = null, val art: String? = null)

    private class Saved(val version: Int? = null, val account: String? = null, val day: Long? = null, val kinds: List<String>? = null,
        val mixes: List<SavedMix>? = null, val shown: Map<String, Map<String, Long>>? = null)

    private class Cache(val account: String, val day: Long, val kinds: Set<MixKind>, val mixes: List<GeneratedMix>, val shown: Map<String, Map<String, Long>>)

    private companion object {
        const val VERSION = 2
        const val RETRY_MS = 15 * 60_000L
        const val SHOWN_DAYS = 14
        const val SHOWN_CAP = 600
        const val HYDRATE = 60
        const val SEEDS = 6
        const val SKIP_WINDOW = 90 * MixClock.DAY
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val likeLock = Mutex()
    @Volatile private var cache: Cache? = null
    @Volatile private var loaded = false
    @Volatile private var enabled: Set<MixKind> = emptySet()
    @Volatile private var attempted: Pair<String, Long>? = null
    private val wake = MutableStateFlow(0)
    private val _mixes = MutableStateFlow<List<GeneratedMix>>(emptyList())
    val mixes: StateFlow<List<GeneratedMix>> = _mixes.asStateFlow()
    private val _saved = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val saved: SharedFlow<Boolean> = _saved.asSharedFlow()

    init {
        scope.launch {
            combine(kinds.distinctUntilChanged(), _mixes.subscriptionCount.map { it > 0 }.distinctUntilChanged(), wake) { set, watched, _ -> set to watched }
                .collectLatest { (set, watched) ->
                    enabled = set
                    if (set.isEmpty()) { _mixes.value = emptyList(); return@collectLatest }
                    if (!watched) return@collectLatest
                    while (true) {
                        // its own job: leaving home mid-build must not throw the work away
                        scope.launch { runCatching { refresh() }.onFailure { AppLog.w("Mixes", "refresh failed: ${it.message}") } }.join()
                        delay(clock().untilNextPart + 1_000)
                    }
                }
        }
    }

    private fun clock() = now().let { MixClock(it, offset(it)) }

    private fun key() = account()

    fun recheck() {
        attempted = null
        wake.value++
    }

    // the library was rebuilt from other sources: what is cached may name songs that are gone
    fun invalidate() {
        cache = cache?.let { Cache(it.account, -1, it.kinds, it.mixes, it.shown) }
        recheck()
    }

    fun clear() {
        cache = null
        loaded = true
        attempted = null
        _mixes.value = emptyList()
        background.launch { runCatching { file?.delete() } }
        wake.value++
    }

    private suspend fun publish() {
        val part = clock().part
        val kinds = enabled
        var shown = cache?.takeIf { it.account == key() }?.mixes.orEmpty().filter { it.kind in kinds && (it.part == null || it.part == part) && it.songs.isNotEmpty() }
        // offline only downloads can play, and a mix that loses most of itself is not shown
        if (repository.isOffline) shown = shown.mapNotNull { mix ->
            mix.copy(songs = mix.songs.mapNotNull { s -> if (repository.isDownloaded(s.id)) repository.songFor(s.id) else null }).takeIf { it.songs.size >= MixBuilder.MIN_DISCOVER }
        }
        _mixes.value = shown
    }

    private suspend fun load() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val saved = runCatching { file?.takeIf { it.exists() }?.readText()?.let { gson.fromJson(it, Saved::class.java) } }.getOrNull()
            if (saved != null && saved.version == VERSION) cache = Cache(saved.account.orEmpty(), saved.day ?: -1,
                saved.kinds.orEmpty().mapNotNullTo(HashSet()) { name -> MixKind.entries.firstOrNull { it.name == name } },
                saved.mixes.orEmpty().mapNotNull(::restore), saved.shown.orEmpty())
        }
        loaded = true
    }

    private fun restore(m: SavedMix): GeneratedMix? {
        val kind = MixKind.entries.firstOrNull { it.name == m.kind } ?: return null
        val songs = m.songs.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() }
        if (songs.isEmpty()) return null
        return GeneratedMix(m.slot ?: return null, kind, m.day ?: return null, m.index ?: 0,
            DayPart.entries.firstOrNull { it.name == m.part }, m.artists.orEmpty(), songs, m.art.orEmpty())
    }

    private suspend fun save(c: Cache) = withContext(Dispatchers.IO) {
        val target = file ?: return@withContext
        val saved = Saved(VERSION, c.account, c.day, c.kinds.map { it.name },
            c.mixes.map { m -> SavedMix(m.slot, m.kind.name, m.day, m.index, m.part?.name, m.artists, m.songs.map { it.toSavedTrack() }, m.art) }, c.shown)
        runCatching { target.parentFile?.mkdirs(); persistBackupFileAtomically(target, gson.toJson(saved).toByteArray()) }
    }

    suspend fun refresh() = lock.withLock {
        val kinds = kinds.first().also { enabled = it }
        if (kinds.isEmpty()) { _mixes.value = emptyList(); return@withLock }
        load()
        publish()
        val key = key()
        val clock = clock()
        val current = cache?.takeIf { it.account == key }
        if (repository.isOffline || current != null && current.day == clock.day && current.kinds.containsAll(kinds)) return@withLock
        val attempt = "$key|${clock.day}|$kinds"
        attempted?.let { (k, at) -> if (k == attempt && now() - at < RETRY_MS) return@withLock }
        delay(settleMs)
        val started = System.currentTimeMillis()
        val input = try { gather(kinds, clock, current?.shown.orEmpty()) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (input == null) { attempted = attempt to now(); return@withLock }
        val built = pictured(withContext(Dispatchers.Default) { MixBuilder.build(input) })
        if (key != key()) return@withLock
        val next = Cache(key, clock.day, kinds, built, remember(current?.shown.orEmpty(), built, clock.day))
        cache = next
        publish()
        save(next)
        // likes of editions that are gone have done their job; their playlists stay
        runCatching { keepLikes(built.mapTo(HashSet()) { it.id }) }
        AppLog.d("Mixes", "built ${built.joinToString { "${it.slot}=${it.songs.size}" }} from ${input.known.size} known, ${input.candidates.size} candidates, ${input.vectors.size} analyzed in ${System.currentTimeMillis() - started}ms")
    }

    // each mix on the row shows a different artist where it can; only one time-of-day mix is there at once
    private suspend fun pictured(mixes: List<GeneratedMix>): List<GeneratedMix> = coroutineScope {
        val used = HashSet<String>()
        fun faces(mix: GeneratedMix) = mix.artists.mapNotNull { name -> mix.songs.firstOrNull { djPrimaryArtist(it.artist) == name } }
        val chosen = mixes.filter { it.part == null }.associate { mix -> mix.slot to faces(mix).let { f -> f.firstOrNull { used.add(mixArtistKey(it.artist)) } ?: f.firstOrNull() } } +
            mixes.filter { it.part != null }.associate { mix -> mix.slot to faces(mix).let { f -> f.firstOrNull { mixArtistKey(it.artist) !in used } ?: f.firstOrNull() } }
        mixes.map { mix ->
            async {
                val face = chosen[mix.slot] ?: return@async mix
                val portrait = face.artistId.takeIf { it.isNotBlank() }?.let { id -> quietly(6_000, null) { repository.detail("artist", id)?.info?.artUrl } }
                mix.copy(art = portrait?.takeIf { it.isNotBlank() } ?: face.artworkUrl)
            }
        }.awaitAll()
    }

    private fun remember(old: Map<String, Map<String, Long>>, built: List<GeneratedMix>, day: Long): Map<String, Map<String, Long>> {
        val out = HashMap<String, Map<String, Long>>()
        for (slot in old.keys + built.map { it.slot }) {
            val keys = HashMap(old[slot].orEmpty())
            built.firstOrNull { it.slot == slot }?.songs?.forEach { keys[mixTrackKey(it)] = day }
            val kept = keys.filterValues { day - it <= SHOWN_DAYS }.entries.sortedByDescending { it.value }.take(SHOWN_CAP)
            if (kept.isNotEmpty()) out[slot] = kept.associate { it.key to it.value }
        }
        return out
    }

    // some sources block instead of suspending, so the wait is bounded rather than the work itself
    private suspend fun <T> quietly(ms: Long, default: T, block: suspend () -> T): T {
        val work = background.async { block() }
        return try {
            withTimeoutOrNull(ms) { work.await() } ?: default
        } catch (e: CancellationException) { throw e } catch (_: Exception) { default } finally { if (!work.isCompleted) work.cancel() }
    }

    private suspend fun gather(kinds: Set<MixKind>, clock: MixClock, shown: Map<String, Map<String, Long>>): MixInput? = coroutineScope {
        val events = withContext(Dispatchers.IO) { history.snapshot() }
        val library = async { quietly(30_000, emptyList()) { repository.librarySongs(5000) } }
        val liked = quietly(120_000, emptyList()) { repository.starredSongs() }
        val known = LinkedHashMap<String, Song>()
        for (s in liked + library.await()) if (s.id.isNotEmpty()) known.putIfAbsent(s.id, s)
        if (known.isEmpty()) return@coroutineScope null
        val extraSkips = quietly(10_000, emptyMap()) { skips(clock.now - SKIP_WINDOW) }
        val taste = withContext(Dispatchers.Default) { MixTaste(events, clock, extraSkips) }
        val byKey = withContext(Dispatchers.Default) { known.values.associateByTo(HashMap()) { mixTrackKey(it) } }
        val gate = Semaphore(6)
        val top = taste.top(HYDRATE * 2)
        val hydrated = top.filter { it !in byKey }.take(HYDRATE).mapNotNull { taste.ids[it] }.filter { it !in known }
            .map { id -> async { gate.withPermit { quietly(5_000, null) { repository.songFor(id) } } } }.awaitAll().filterNotNull()
        hydrated.forEach { if (it.id.isNotEmpty() && known.putIfAbsent(it.id, it) == null) byKey.putIfAbsent(mixTrackKey(it), it) }
        // one stray play is no seed; where the history is thin the likes say what to look for
        val replayed = top.filter { (taste.plays[it] ?: 0) >= 2 }.mapNotNull { byKey[it] }
        val candidates = if (MixKind.DISCOVER in kinds && !repository.isOffline) discoveries(replayed, liked, clock) else emptyList()
        val analyzed = quietly(10_000, emptyMap()) { vectors() }
        MixInput(events, clock, known.values.toList(), liked.mapTo(HashSet()) { it.id }, candidates, analyzed, extraSkips, shown,
            repository::isHidden, kinds, seed).also { it.taste = taste }
    }

    private suspend fun discoveries(replayed: List<Song>, liked: List<Song>, clock: MixClock): List<Song> = coroutineScope {
        val random = Random(seed * 31 + clock.day)
        val loved = (replayed.take(SEEDS / 2) + (replayed.drop(SEEDS / 2).take(30) + liked.shuffled(random).take(30)).shuffled(random)).distinctBy { it.id }
        if (loved.isEmpty()) return@coroutineScope emptyList()
        val seeds = loved.take(SEEDS)
        val radios = seeds.map { s -> async { quietly(8_000, emptyList()) { repository.radio(s.id) } } }
        val related = seeds.take(2).map { s ->
            async { quietly(8_000, emptyList()) { repository.related(s).sections.flatMap { it.items }.filterIsInstance<HomeFeedItem.Track>().map { it.song } } } }
        val artists = loved.map { djPrimaryArtist(it.artist) }.filter { it.length >= 2 }.distinctBy { it.lowercase() }.shuffled(random).take(4)
        val searches = artists.map { name ->
            val wanted = mixArtistKey(name)
            async { quietly(8_000, emptyList()) { repository.search(name).songs.take(25) }.filter { mixArtistKey(it.artist) == wanted } }
        }
        (radios + related + searches).awaitAll().flatten()
    }

    private suspend fun all(): List<GeneratedMix> {
        load()
        return cache?.takeIf { it.account == key() }?.mixes.orEmpty()
    }

    // an edition that was replaced while its page was open resolves to the slot's current one
    private suspend fun find(id: String): GeneratedMix? = (mixes.value + all()).let { mixes ->
        mixes.firstOrNull { it.id == id } ?: mixes.firstOrNull { it.slot == mixSlotOf(id) }
    }

    suspend fun detail(id: String): DetailData? {
        val mix = find(id) ?: return null
        val name = names(mix)
        return DetailData(DetailInfo(name.title, name.subtitle, mix.coverUrl, accentArgbFor(mix.slot), false, mix.songs.size, "Made for you"), mix.songs)
    }

    suspend fun toggleLike(id: String): Boolean? = likeLock.withLock {
        val playlist = snapshots.first()[id]
        if (playlist != null) {
            runCatching { repository.deletePlaylist(playlist) }
            saveLike(id, null)
            _saved.tryEmit(false)
            return@withLock false
        }
        val mix = find(id)?.takeIf { it.id == id } ?: return@withLock null
        val created = repository.savePlaylistCopy(names(mix).saved, repository.visible(mix.songs)) ?: return@withLock null
        saveLike(id, created)
        _saved.tryEmit(true)
        true
    }
}
