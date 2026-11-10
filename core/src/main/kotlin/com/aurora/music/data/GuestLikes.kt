package com.aurora.music.data

import com.aurora.music.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

// songs liked while signed out of a catalogue; they stay on this device until signing in adds them to the account
class GuestLikes(filesDir: File? = null) {
    private val file = filesDir?.let { File(it, "guest_likes.json") }
    private val gson = Gson()
    private val lock = Any()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    // newest first, keyed by the catalogue's ServerType name
    @Volatile private var map: Map<String, List<Song>> = load()

    private fun load(): Map<String, List<Song>> = runCatching {
        if (file == null || !file.exists()) emptyMap()
        else gson.fromJson<Map<String, List<Song>>>(file.readText(), object : TypeToken<Map<String, List<Song>>>() {}.type)
            ?.mapValues { (_, songs) -> songs.filter { it.id.isNotBlank() } } ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun of(type: ServerType): List<Song> = map[type.name].orEmpty()
    fun count(type: ServerType): Int = of(type).size
    fun ids(type: ServerType): Set<String> = of(type).mapTo(LinkedHashSet()) { it.id }

    fun add(type: ServerType, song: Song) = edit(type) { list ->
        // the source fills playback in again when it serves the song, so only what describes it is kept
        listOf(song.copy(liked = true, playbackSource = null, playbackCollection = null)) + list.filterNot { it.id == song.id }
    }

    fun remove(type: ServerType, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val gone = ids.toSet()
        edit(type) { list -> list.filterNot { it.id in gone } }
    }

    // a guest source's own like: songs only, since albums, artists and playlists need the account
    suspend fun setStarred(type: ServerType, id: String, starred: Boolean, kind: String, lookup: suspend (String) -> Song?): Boolean {
        if (kind != "song") return false
        if (!starred) { remove(type, listOf(id)); return true }
        val song = attempt(null) { lookup(id) } ?: return false
        add(type, song.copy(id = id))
        return true
    }

    // the signed-in account a type's guest likes are waiting for, with how many there are
    fun waitingFor(sessions: List<Session>): Pair<Session, Int>? = sessions
        .firstOrNull { !it.isGuest && it.type.supportsGuest && count(it.type) > 0 }
        ?.let { it to count(it.type) }

    // oldest first, so the account's likes keep the order they were made in; each one that lands leaves the device
    suspend fun moveTo(target: MediaBackend, pauseMs: Long = MOVE_PAUSE_MS): Int {
        if (!target.signedIn) return 0
        val type = target.session.type
        var moved = 0
        for (song in of(type).reversed()) {
            if (attempt(false) { target.setStarred(song.id, true, "song") }) {
                remove(type, listOf(song.id))
                moved++
            }
            if (pauseMs > 0) delay(pauseMs)
        }
        return moved
    }

    fun discard(type: ServerType) = edit(type) { emptyList() }

    private fun edit(type: ServerType, change: (List<Song>) -> List<Song>) {
        synchronized(lock) {
            val next = change(of(type))
            map = if (next.isEmpty()) map - type.name else map + (type.name to next)
            runCatching { file?.writeText(gson.toJson(map)) }
        }
        _revision.update { it + 1 }
    }

    companion object {
        // spaces account writes out so a long list doesn't trip the service's request limit
        const val MOVE_PAUSE_MS = 300L
    }
}
