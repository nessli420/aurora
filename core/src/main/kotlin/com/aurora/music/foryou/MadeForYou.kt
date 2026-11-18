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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
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
    if (HomeSection.MIX_STATIONS !in hiddenHomeSections) add(MixKind.STATION)
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

    private class SavedFace(val url: String? = null, val day: Long? = null)

    private class Saved(val version: Int? = null, val account: String? = null, val day: Long? = null, val kinds: List<String>? = null,
        val mixes: List<SavedMix>? = null, val shown: Map<String, Map<String, Long>>? = null, val faces: Map<String, SavedFace>? = null)

    private class Cache(val account: String, val day: Long, val kinds: Set<MixKind>, val mixes: List<GeneratedMix>, val shown: Map<String, Map<String, Long>>,
        val faces: Map<String, SavedFace> = emptyMap())

    private companion object {
        const val VERSION = 2
        const val RETRY_MS = 15 * 60_000L
        const val SHOWN_DAYS = 14
        const val SHOWN_CAP = 600
        const val HYDRATE = 60
        const val SEEDS = 6
        const val SKIP_WINDOW = 90 * MixClock.DAY
        const val FACE_DAYS = 14
    }

    // the row's own few lines, kept apart from the tracks so home has its tiles before those are read
    private val tiles = file?.let { File(it.parentFile, it.nameWithoutExtension + "-tiles.json") }
    private val born = System.currentTimeMillis()
    @Volatile private var previewed = false

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

    // until the session is back the cache speaks for the account it was built for
    private fun mine(account: String) = key().let { it.isEmpty() || it == account }

    fun recheck() {
        attempted = null
        wake.value++
    }

    // the library was rebuilt from other sources: what is cached may name songs that are gone
    fun invalidate() {
        cache = cache?.let { Cache(it.account, -1, it.kinds, it.mixes, it.shown, it.faces) }
        recheck()
    }

    fun clear() {
        cache = null
        loaded = true
        attempted = null
        _mixes.value = emptyList()
        background.launch { runCatching { file?.delete(); tiles?.delete() } }
        wake.value++
    }

    private suspend fun publish() {
        val part = clock().part
        val kinds = enabled
        var shown = cache?.takeIf { mine(it.account) }?.mixes.orEmpty().filter { it.kind in kinds && (it.part == null || it.part == part) && it.songs.isNotEmpty() }
        // offline only downloads can play, and a mix that loses most of itself is not shown
        if (repository.isOffline) shown = shown.mapNotNull { mix ->
            mix.copy(songs = mix.songs.mapNotNull { s -> if (repository.isDownloaded(s.id)) repository.songFor(s.id) else null }).takeIf { it.songs.size >= MixBuilder.MIN_DISCOVER }
        }
        _mixes.value = shown
    }

    private suspend fun preview(kinds: Set<MixKind>) {
        if (previewed || loaded) return
        previewed = true
        val saved = withContext(Dispatchers.IO) { runCatching { tiles?.takeIf { it.exists() }?.readText()?.let { gson.fromJson(it, Saved::class.java) } }.getOrNull() } ?: return
        if (saved.version != VERSION || !mine(saved.account.orEmpty())) return
        val part = clock().part
        _mixes.value = saved.mixes.orEmpty().mapNotNull { restore(it, tile = true) }.filter { it.kind in kinds && (it.part == null || it.part == part) }
        AppLog.d("Mixes", "showing ${_mixes.value.size} tiles ${System.currentTimeMillis() - born}ms after start")
    }

    // app start: home opens on the tiles, the tracks are read when it looks
    suspend fun warm() = lock.withLock {
        val kinds = kinds.first().also { enabled = it }
        if (kinds.isNotEmpty()) preview(kinds)
    }

    private suspend fun load() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val saved = runCatching { file?.takeIf { it.exists() }?.readText()?.let { gson.fromJson(it, Saved::class.java) } }.getOrNull()
            if (saved != null && saved.version == VERSION) cache = Cache(saved.account.orEmpty(), saved.day ?: -1,
                saved.kinds.orEmpty().mapNotNullTo(HashSet()) { name -> MixKind.entries.firstOrNull { it.name == name } },
                saved.mixes.orEmpty().mapNotNull { restore(it) }, saved.shown.orEmpty(), saved.faces.orEmpty())
            // a cache from before the tiles had their own file
            cache?.takeIf { tiles?.exists() == false }?.let { runCatching { writeTiles(it) } }
        }
        loaded = true
    }

    private fun restore(m: SavedMix, tile: Boolean = false): GeneratedMix? {
        val kind = MixKind.entries.firstOrNull { it.name == m.kind } ?: return null
        val songs = m.songs.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() }
        if (songs.isEmpty() && !tile) return null
        return GeneratedMix(m.slot ?: return null, kind, m.day ?: return null, m.index ?: 0,
            DayPart.entries.firstOrNull { it.name == m.part }, m.artists.orEmpty(), songs, m.art.orEmpty())
    }

    private fun saved(c: Cache, tracks: Boolean) = c.mixes.map { m ->
        SavedMix(m.slot, m.kind.name, m.day, m.index, m.part?.name, m.artists, if (tracks) m.songs.map { it.toSavedTrack() } else null, m.art)
    }

    private fun writeTiles(c: Cache) {
        tiles?.let { persistBackupFileAtomically(it, gson.toJson(Saved(VERSION, c.account, c.day, mixes = saved(c, tracks = false))).toByteArray()) }
    }

    private suspend fun save(c: Cache) = withContext(Dispatchers.IO) {
        val target = file ?: return@withContext
        runCatching {
            target.parentFile?.mkdirs()
            persistBackupFileAtomically(target, gson.toJson(Saved(VERSION, c.account, c.day, c.kinds.map { it.name }, saved(c, tracks = true), c.shown, c.faces)).toByteArray())
            writeTiles(c)
        }
    }

    // null while what is cached still stands; otherwise the name of the attempt a rebuild would be
    private fun due(kinds: Set<MixKind>): String? {
        val key = key()
        // no session yet: the account change that follows asks again
        if (key.isEmpty() || repository.isOffline) return null
        val clock = clock()
        val current = cache?.takeIf { it.account == key }
        if (current != null && current.day == clock.day && current.kinds.containsAll(kinds)) return null
        // a source that is down gets another go later, not on every look at home
        return "$key|${clock.day}|$kinds".takeUnless { attempt -> attempted?.let { (k, at) -> k == attempt && now() - at < RETRY_MS } == true }
    }

    private suspend fun show(): Boolean = lock.withLock {
        val kinds = kinds.first().also { enabled = it }
        if (kinds.isEmpty()) { _mixes.value = emptyList(); return@withLock false }
        preview(kinds)
        load()
        publish()
        due(kinds) != null
    }

    // the lock is free while home settles, so a session that arrives meanwhile can show its row
    suspend fun refresh() {
        if (!show()) return
        delay(settleMs)
        rebuild()
    }

    private suspend fun rebuild() = lock.withLock {
        val kinds = enabled
        val attempt = due(kinds) ?: return@withLock
        val key = key()
        val clock = clock()
        val current = cache?.takeIf { it.account == key }
        val started = System.currentTimeMillis()
        val input = try { gather(kinds, clock, current?.shown.orEmpty()) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (input == null) { attempted = attempt to now(); return@withLock }
        val prepared = withContext(Dispatchers.Default) { MixBuilder.prepare(input) }
        val stations = stations(prepared.stationArtists)
        val (built, faces) = pictured(withContext(Dispatchers.Default) { prepared.build(stations) }, cache?.faces.orEmpty(), clock.day)
        if (key != key()) return@withLock
        val next = Cache(key, clock.day, kinds, built, remember(current?.shown.orEmpty(), built, clock.day), faces)
        cache = next
        publish()
        save(next)
        // likes of editions that are gone have done their job; their playlists stay
        runCatching { keepLikes(built.mapTo(HashSet()) { it.id }) }
        AppLog.d("Mixes", "built ${built.joinToString { "${it.slot}=${it.songs.size}" }} from ${input.known.size} known, ${input.candidates.size} candidates, ${input.vectors.size} analyzed in ${System.currentTimeMillis() - started}ms")
    }

    private suspend fun stations(seeds: List<StationSeed>): List<Station> = coroutineScope {
        if (seeds.isEmpty() || repository.isOffline) return@coroutineScope emptyList()
        val views = repository.searchSources.map { it.id }
        val gate = Semaphore(6)
        seeds.map { seed ->
            async {
                val wanted = mixArtistKey(seed.artist)
                val found = (if (views.isEmpty()) listOf(async { gate.withPermit { quietly(8_000, emptyList()) { repository.search(seed.artist).songs } } })
                    else views.map { view -> async { gate.withPermit { quietly(8_000, emptyList()) { repository.search(seed.artist, view).songs } } } })
                    .awaitAll().map { songs -> songs.take(25).filter { creditNames(it.artist, wanted) } }
                // one song per source starts that source's radio; the library's own copy speaks for wherever it lives
                val starts = (listOfNotNull(seed.songs.firstOrNull()) + found.mapNotNull { it.firstOrNull() }).distinctBy { it.id }.take(4)
                val related = starts.map { s -> async { gate.withPermit { quietly(8_000, emptyList()) { repository.radio(s.id) } }.take(30) } }.awaitAll().flatten()
                Station(seed.artist, found.flatten(), related)
            }
        }.awaitAll()
    }

    // a station shows its own artist; the other tiles on the row then each take a different one, and only one time-of-day mix is there at once
    private suspend fun pictured(mixes: List<GeneratedMix>, known: Map<String, SavedFace>, day: Long): Pair<List<GeneratedMix>, Map<String, SavedFace>> = coroutineScope {
        val used = HashSet<String>()
        fun faces(mix: GeneratedMix) = mix.artists.mapNotNull { name -> mix.songs.firstOrNull { djPrimaryArtist(it.artist) == name } }
        val chosen = mixes.filter { it.kind == MixKind.STATION }.associate { mix -> mix.slot to faces(mix).firstOrNull()?.also { used += mixArtistKey(it.artist) } } +
            mixes.filter { it.part == null && it.kind != MixKind.STATION }.associate { mix -> mix.slot to faces(mix).let { f -> f.firstOrNull { used.add(mixArtistKey(it.artist)) } ?: f.firstOrNull() } } +
            mixes.filter { it.part != null }.associate { mix -> mix.slot to faces(mix).let { f -> f.firstOrNull { mixArtistKey(it.artist) !in used } ?: f.firstOrNull() } }
        val fresh = known.filterValues { day - (it.day ?: 0) in 0..FACE_DAYS && !it.url.isNullOrBlank() }
        val done = mixes.map { mix ->
            async {
                val face = chosen[mix.slot] ?: return@async mix to null
                val artist = mixArtistKey(face.artist)
                // an artist's picture rarely changes, so the source is only asked again after a couple of weeks
                val kept = fresh[artist]
                val portrait = kept?.url ?: face.artistId.takeIf { it.isNotBlank() }?.let { id -> quietly(6_000, null) { repository.detail("artist", id)?.info?.artUrl } }.orEmpty()
                mix.copy(art = portrait.ifBlank { face.artworkUrl }) to (artist to (kept ?: SavedFace(portrait, day))).takeIf { portrait.isNotBlank() }
            }
        }.awaitAll()
        done.map { it.first } to fresh + done.mapNotNull { it.second }
    }

    private fun remember(old: Map<String, Map<String, Long>>, built: List<GeneratedMix>, day: Long): Map<String, Map<String, Long>> {
        val out = HashMap<String, Map<String, Long>>()
        for (slot in old.keys + built.map { it.slot }) {
            val keys = HashMap(old[slot].orEmpty())
            built.firstOrNull { it.slot == slot }?.songs?.forEach { keys[mixTrackKey(it)] = day }
            val kept = keys.filterValues { day - it <= SHOWN_DAYS }.entries.sortedByDescending { it.value }.take(SHOWN_CAP)
            if (kept.isNotEmpty()) out[slot] = kept.associate { it.key to it.value }
        }
        val stationed = old[MixBuilder.STATION_ARTISTS].orEmpty() + built.filter { it.kind == MixKind.STATION }.mapNotNull { it.artists.firstOrNull() }.associate { mixArtistKey(it) to day }
        stationed.filterValues { day - it <= SHOWN_DAYS }.takeIf { it.isNotEmpty() }?.let { out[MixBuilder.STATION_ARTISTS] = it }
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
        return cache?.takeIf { mine(it.account) }?.mixes.orEmpty()
    }

    // an edition that was replaced while its page was open resolves to the slot's current one
    private suspend fun find(id: String): GeneratedMix? = (mixes.value.filter { it.songs.isNotEmpty() } + all()).let { mixes ->
        mixes.firstOrNull { it.id == id } ?: mixes.firstOrNull { it.slot == mixSlotOf(id) }
    }

    // each edition that takes the place of the one a queue is on; a queue on a mix keeps the mixes turning like home does
    @OptIn(ExperimentalCoroutinesApi::class)
    fun following(playing: Flow<String?>): Flow<GeneratedMix> =
        playing.distinctUntilChanged().flatMapLatest { id -> if (id == null) emptyFlow() else mixes.mapNotNull { mixSuccessor(id, it) } }

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
