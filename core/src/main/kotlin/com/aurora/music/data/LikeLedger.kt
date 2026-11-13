package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// one account in the heart's menu; id is the song there, null when that account has no match for it
data class LikeOption(val key: String, val label: String, val id: String?, val liked: Boolean, val guest: Boolean, val playing: Boolean) {
    val available: Boolean get() = id != null && !guest
}

// a merged source the liked songs list can be narrowed to, keyed by its merge namespace
data class LikedSource(val key: String, val label: String, val count: Int)

// every active account's liked songs, so a song liked on any of them counts wherever it plays
class LikeLedger(private val sources: suspend () -> List<MediaBackend>, private val activeSession: () -> Session?) {
    private class Edit(val seq: Long, val key: String, val song: Song, val liked: Boolean)

    private class Account(private val byId: Map<String, Song>, private val byTitle: Map<String, List<Pair<Song, Song>>>) {
        fun has(id: String) = id in byId
        val size: Int get() = byId.size

        fun matches(song: Song, title: String): List<Song> =
            byTitle[title].orEmpty().filter { sameRecording(song, it.first) }.map { it.second }.distinctBy { it.id }

        fun matchesAny(song: Song, title: String): Boolean = byTitle[title]?.any { sameRecording(song, it.first) } == true

        fun with(song: Song, liked: Boolean): Account {
            val ids = HashMap(byId)
            val titles = HashMap(byTitle)
            ids.remove(song.id)?.let { old -> recordingVariants(old).forEach { v -> titles.computeIfPresent(recordingKey(v.title)) { _, list -> list.filterNot { it.second.id == old.id }.ifEmpty { null } } } }
            if (liked) { ids[song.id] = song; index(titles, song) }
            return Account(ids, titles)
        }

        companion object {
            fun of(songs: List<Song>): Account {
                val titles = HashMap<String, List<Pair<Song, Song>>>()
                // matching reads only these; whole songs would hold a second copy of every liked library
                val ids = songs.map { Song(it.id, it.title, it.artist, "", "", it.durationSec) }.associateBy { it.id }
                ids.values.forEach { index(titles, it) }
                return Account(ids, titles)
            }

            private fun index(titles: HashMap<String, List<Pair<Song, Song>>>, song: Song) =
                recordingVariants(song).forEach { v -> titles.merge(recordingKey(v.title), listOf(v to song)) { a, b -> a + b } }
        }
    }

    private val accounts = ConcurrentHashMap<String, Account>()
    private val edits = ArrayDeque<Edit>()
    private var seq = 0L
    private val twins = object : LinkedHashMap<String, Song?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>?) = size > 128
    }
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    // the account a song's id belongs to, and its id there
    fun owner(id: String): Pair<String, String>? {
        val i = id.indexOf(MERGE_NAMESPACE_SEP)
        if (i > 0) return id.substring(0, i) to id.substring(i + 1)
        return activeSession()?.let { mergeNamespace(it) to id }
    }

    private fun likes(key: String, account: Account, song: Song, title: String, owner: Pair<String, String>?) =
        owner?.first == key && account.has(owner.second) || account.matchesAny(song, title)

    fun likedAnywhere(song: Song, title: String = recordingKey(song.title)): Boolean {
        val owner = owner(song.id)
        return accounts.any { (key, account) -> likes(key, account, song, title, owner) }
    }

    // null until that account's likes have loaded
    fun likedOn(key: String, song: Song): Boolean? = accounts[key]?.let { likes(key, it, song, recordingKey(song.title), owner(song.id)) }

    suspend fun refresh() = coroutineScope {
        val all = withContext(Dispatchers.IO) { sources() }
        if (accounts.keys.retainAll(all.map { mergeNamespace(it.session) }.toSet())) bump()
        // callers sit on the ui thread; whole liked libraries are mapped here
        all.map { async(Dispatchers.IO) { load(it) } }.awaitAll()
        Unit
    }

    private suspend fun load(source: MediaBackend) {
        if (!source.signedIn) return
        val key = mergeNamespace(source.session)
        val from = synchronized(this) { seq }
        val fetched = attempt(null) {
            withTimeoutOrNull(if (source.session.type.isCatalogue) CATALOGUE_TIMEOUT_MS else SERVER_TIMEOUT_MS) { source.starredSongs() }
        }
        // servers answer a failed request with an empty list, so an empty answer keeps what was known
        if (fetched.isNullOrEmpty()) return
        withContext(Dispatchers.Default) {
            val account = Account.of(fetched)
            synchronized(this@LikeLedger) {
                // likes changed while the list was on its way still count
                accounts[key] = edits.filter { it.key == key && it.seq > from }.fold(account) { acc, edit -> acc.with(edit.song, edit.liked) }
            }
        }
        bump()
    }

    suspend fun options(song: Song, ownLiked: Boolean): List<LikeOption> = coroutineScope {
        val all = sources()
        val owner = owner(song.id)
        val copy = copyOf(song)
        val title = recordingKey(song.title)
        val labels = labels(all)
        all.mapIndexed { index, source ->
            async {
                val key = mergeNamespace(source.session)
                val playing = owner?.first == key
                val account = accounts[key]
                val liked = account?.let { likes(key, it, song, title, owner) } == true || playing && ownLiked
                // a guest source keeps its own song's like on this device, so that one row still works
                val local = playing && !source.signedIn && owner != null && source.likesLocally(owner.second)
                val id = when {
                    local -> owner?.second
                    !source.signedIn -> null
                    playing -> owner?.second
                    liked -> account?.matches(song, title)?.firstOrNull()?.id
                    copy?.first == key -> copy.second
                    else -> twin(source, key, song)?.id
                }
                LikeOption(key, labels[index], id, liked, !source.signedIn && !local, playing)
            }
        }.awaitAll().sortedByDescending { it.playing }
    }

    // the given sources whose likes have loaded and hold any
    fun likedSources(sources: List<MediaBackend>): List<LikedSource> {
        val labels = labels(sources)
        return sources.mapIndexedNotNull { i, source ->
            val key = mergeNamespace(source.session)
            accounts[key]?.size?.takeIf { it > 0 }?.let { LikedSource(key, labels[i], it) }
        }
    }

    private fun labels(sources: List<MediaBackend>): List<String> = sourceLabels(sources)

    // null when no active account goes by that key
    suspend fun set(song: Song, key: String, liked: Boolean): Boolean? {
        val source = sources().firstOrNull { mergeNamespace(it.session) == key } ?: return null
        if (!source.signedIn) return false
        val owner = owner(song.id)?.takeIf { it.first == key }
        val plain = song.copy(playbackSource = null)
        val targets = if (liked) {
            val copy = copyOf(song)?.takeIf { it.first == key }
            val id = owner?.second ?: copy?.second ?: twin(source, key, song)?.id ?: return false
            listOf(plain.copy(id = id))
        } else {
            val matched = accounts[key]?.matches(song, recordingKey(song.title)).orEmpty()
            (listOfNotNull(owner?.let { plain.copy(id = it.second) }) + matched).distinctBy { it.id }
        }
        val applied = record(key, targets, liked)
        val ok = targets.all { attempt(false) { source.setStarred(it.id, liked, "song") } }
        if (!ok) revert(key, applied)
        return ok
    }

    private fun copyOf(song: Song) = song.playbackSource?.songId?.takeIf { MERGE_NAMESPACE_SEP in it }?.let(::owner)

    private suspend fun twin(source: MediaBackend, key: String, song: Song): Song? {
        val cacheKey = "$key\u0000${song.id}"
        synchronized(twins) { if (twins.containsKey(cacheKey)) return twins[cacheKey] }
        // a failed search isn't remembered, so the next try asks again
        val found = attempt(null) { withTimeoutOrNull(TWIN_TIMEOUT_MS) { source.search("${recordingTitle(song.title)} ${song.artist}".trim()).songs } }
            ?: return null
        val twin = found.firstOrNull { c -> recordingVariants(c).any { sameRecording(song, it) } } ?: found.firstOrNull { twinMatches(song, it) }
        synchronized(twins) { twins[cacheKey] = twin }
        return twin
    }

    private fun record(key: String, songs: List<Song>, liked: Boolean): List<Edit> {
        val added = synchronized(this) {
            songs.map { Edit(++seq, key, it, liked) }.also { added ->
                edits.addAll(added)
                while (edits.size > MAX_EDITS) edits.removeFirst()
                accounts[key]?.let { account -> accounts[key] = added.fold(account) { acc, edit -> acc.with(edit.song, liked) } }
            }
        }
        bump()
        return added
    }

    private fun revert(key: String, applied: List<Edit>) {
        synchronized(this) {
            edits.removeAll(applied.toSet())
            accounts[key]?.let { account -> accounts[key] = applied.fold(account) { acc, edit -> acc.with(edit.song, !edit.liked) } }
        }
        bump()
    }

    private fun bump() = _revision.update { it + 1 }

    private companion object {
        const val SERVER_TIMEOUT_MS = 20_000L
        const val CATALOGUE_TIMEOUT_MS = 90_000L
        const val TWIN_TIMEOUT_MS = 8_000L
        const val MAX_EDITS = 64
    }
}

// the active library's accounts, then the other signed-in ones a song can also be liked on
class LikeAccounts(private val build: (Session) -> MediaBackend?) {
    private var built: Triple<MediaBackend, List<Session>, List<MediaBackend>>? = null

    @Synchronized fun of(active: MediaBackend?, saved: List<Session>): List<MediaBackend> {
        if (active == null) return emptyList()
        built?.let { (backend, sessions, accounts) -> if (backend === active && sessions == saved) return accounts }
        val own = (active as? MergedBackend)?.sources ?: listOf(active)
        val keys = own.mapTo(HashSet()) { it.session.accountKey() }
        // spotify accounts share one web player and the device has one local library
        val single = own.mapNotNullTo(HashSet()) { it.session.type.takeIf { type -> type in ONE_ACCOUNT } }
        val others = saved.filter { it.type.supportsMergedLibrary && keys.add(it.accountKey()) && (it.type !in ONE_ACCOUNT || single.add(it.type)) }
        return (own + others.mapNotNull(build)).also { built = Triple(active, saved, it) }
    }

    private companion object {
        val ONE_ACCOUNT = setOf(ServerType.SPOTIFY, ServerType.LOCAL)
    }
}

// a source's name in menus, with the account when two sources share a type
internal fun sourceLabels(sources: List<MediaBackend>): List<String> {
    val types = sources.groupingBy { it.session.type }.eachCount()
    return sources.map { it.session.typeLabel + if ((types[it.session.type] ?: 0) > 1) " · ${it.session.username}" else "" }
}

private fun sameRecording(a: Song, b: Song) = recordingMatches(a, b) &&
    (a.durationSec <= 0 || b.durationSec <= 0 || abs(a.durationSec - b.durationSec) <= TrackMatch.DURATION_TOLERANCE_SEC)

internal suspend fun <T> attempt(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    fallback
}
