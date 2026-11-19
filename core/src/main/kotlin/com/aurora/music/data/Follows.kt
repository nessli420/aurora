package com.aurora.music.data

import com.aurora.music.model.Artist
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// an artist aurora follows for an account whose service can't keep the follow; gson leaves missing fields null
data class FollowedArtist(
    val account: String? = "",
    val type: String? = "",
    val guest: Boolean? = false,
    val id: String? = "",
    val name: String? = "",
    val imageUrl: String? = "",
    val at: Long? = 0,
) {
    fun toArtist(id: String) = Artist(id, name.orEmpty(), imageUrl.orEmpty(), 0)

    // the account's own follows, and a guest's of the same service once an account took its place
    fun waitsFor(session: Session) = !session.isGuest &&
        (account == mergeNamespace(session) || (guest == true && type == session.type.name))
}

// where an artist's follow goes: the service named, or aurora alone when service is null
data class FollowPlace(val account: String, val type: ServerType, val guest: Boolean, val rawId: String, val service: String?)

// followed artists across the library: on each source's service where it can, otherwise kept per account in settings
class Follows(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    local: Flow<List<FollowedArtist>>,
    private val saveLocal: suspend (FollowedArtist, Boolean) -> Unit,
) {
    private val _followed = MutableStateFlow<List<Artist>>(emptyList())
    // one row per artist however many sources follow it
    val followed: StateFlow<List<Artist>> = _followed.asStateFlow()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()
    private val _localIds = MutableStateFlow<Set<String>>(emptySet())
    // the followed ids only aurora keeps
    val localIds: StateFlow<Set<String>> = _localIds.asStateFlow()
    private val _failures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val failures: SharedFlow<Unit> = _failures.asSharedFlow()

    private var server: List<Artist> = emptyList()
    private var kept: List<FollowedArtist> = emptyList()
    // optimistic changes on a service: the artist while following, null while unfollowing
    private val pending = HashMap<String, Artist?>()
    private var load: Job? = null
    private var edits = 0

    init {
        scope.launch { local.collect { kept = it; publish() } }
    }

    // a library change renames ids, so restart drops a fetch still answering for the old ones
    fun refresh(restart: Boolean = false) {
        if (restart) load?.cancel()
        if (load?.isActive == true) return
        load = scope.launch {
            val before = edits
            val fetched = attempt(null) { repository.followedArtists() } ?: return@launch
            if (before != edits) return@launch
            server = fetched
            publish()
        }
    }

    // follows belong to the account, so the incoming one starts from its own
    fun reset() {
        load?.cancel()
        server = emptyList()
        pending.clear()
        publish()
        refresh()
    }

    fun isFollowed(id: String) = id in _ids.value

    suspend fun place(artistId: String): FollowPlace? = attempt(null) { repository.followPlace(artistId) }

    fun toggle(artist: Artist) {
        if (artist.id.isEmpty()) return
        val follow = !isFollowed(artist.id)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val place = place(artist.id) ?: return@launch run { _failures.tryEmit(Unit) }
            val own = kept.firstOrNull { it.account == place.account && it.id == place.rawId }
            // a follow kept here stays here even once the service could take it; moving it is the prompt's job
            if (own != null || (follow && place.service == null)) {
                val entry = own ?: FollowedArtist(place.account, place.type.name, place.guest, place.rawId, artist.name, artist.imageUrl, System.currentTimeMillis())
                attempt(Unit) { saveLocal(entry, follow) }
                if (follow || server.none { it.id == artist.id }) return@launch
            }
            edits++
            pending[artist.id] = artist.takeIf { follow }
            publish()
            val ok = attempt(false) { repository.setFollowed(artist.id, follow) }
            pending.remove(artist.id)
            if (ok) server = if (follow) server.filterNot { it.id == artist.id } + artist else server.filterNot { it.id == artist.id }
            edits++
            publish()
            if (!ok) _failures.tryEmit(Unit)
        }
    }

    private fun publish() {
        val accounts = repository.followAccounts()
        val mine = kept.mapNotNull { a -> accounts[a.account]?.let { wrap -> a.toArtist(wrap(a.id.orEmpty())) } }
        val all = (server.filter { it.id !in pending } + pending.values.filterNotNull() + mine).distinctBy { it.id }
        _localIds.value = mine.mapTo(HashSet()) { it.id }
        _followed.value = all.filter { it.name.isNotBlank() }.distinctBy { TrackMatch.identity(it.name) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        _ids.value = all.mapTo(HashSet()) { it.id }
    }
}

// a saved account that can now keep follows aurora held for it, with those follows
suspend fun followsToMove(sessions: List<Session>, kept: List<FollowedArtist>, keepsFollows: suspend (Session) -> Boolean): Pair<Session, List<FollowedArtist>>? {
    for (session in sessions) {
        val waiting = kept.filter { it.waitsFor(session) }
        if (waiting.isNotEmpty() && attempt(false) { keepsFollows(session) }) return session to waiting
    }
    return null
}

// oldest first, so the service lists them in the order they were followed; each one that lands leaves aurora
suspend fun moveFollows(target: MediaBackend, waiting: List<FollowedArtist>, forget: suspend (FollowedArtist) -> Unit): Int {
    var moved = 0
    for (a in waiting.sortedBy { it.at ?: 0 }) {
        if (attempt(false) { target.setFollowed(a.id.orEmpty(), true) }) { forget(a); moved++ }
    }
    return moved
}
