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
import java.util.UUID

data class DevicePlaylist(val id: String = "", val name: String? = "", val description: String? = "", val songs: List<Song>? = emptyList())

// album, artist and playlist likes as kind:id, newest first
private data class DeviceShelf(val liked: List<String>? = emptyList(), val playlists: List<DevicePlaylist>? = emptyList())

// songs liked while signed out of a catalogue, plus what an account its service keeps no library for saves; they stay on this device until an account that can hold them takes them
class GuestLikes(filesDir: File? = null) {
    private val file = filesDir?.let { File(it, "guest_likes.json") }
    private val shelfFile = filesDir?.let { File(it, "guest_library.json") }
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

    @Volatile private var shelves: Map<String, DeviceShelf> = runCatching {
        if (shelfFile == null || !shelfFile.exists()) emptyMap()
        else gson.fromJson<Map<String, DeviceShelf>>(shelfFile.readText(), object : TypeToken<Map<String, DeviceShelf>>() {}.type) ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun of(type: ServerType): List<Song> = map[type.name].orEmpty()
    fun count(type: ServerType): Int = of(type).size
    fun ids(type: ServerType): Set<String> = of(type).mapTo(LinkedHashSet()) { it.id }
    fun likedCollections(type: ServerType): List<String> = shelves[type.name]?.liked.orEmpty()
    fun playlists(type: ServerType): List<DevicePlaylist> = shelves[type.name]?.playlists.orEmpty()
    fun playlist(type: ServerType, id: String): DevicePlaylist? = playlists(type).firstOrNull { it.id == id }
    // everything an account could take: liked songs, other likes and playlists
    fun pending(type: ServerType): Int = count(type) + likedCollections(type).size + playlists(type).size

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

    fun setCollectionLiked(type: ServerType, kind: String, id: String, liked: Boolean) = shelf(type) { s ->
        val key = "$kind:$id"
        s.copy(liked = (if (liked) listOf(key) else emptyList()) + s.liked.orEmpty().filterNot { it == key })
    }

    fun createPlaylist(type: ServerType, name: String, songs: List<Song> = emptyList()): String {
        val id = DEVICE_PLAYLIST + UUID.randomUUID().toString().take(8)
        shelf(type) { it.copy(playlists = it.playlists.orEmpty() + DevicePlaylist(id, name.trim(), "", songs.map(::kept).distinctBy { s -> s.id })) }
        return id
    }

    // null deletes the playlist; false when there was none by that id
    fun changePlaylist(type: ServerType, id: String, change: (DevicePlaylist) -> DevicePlaylist?): Boolean {
        if (playlist(type, id) == null) return false
        shelf(type) { s -> s.copy(playlists = s.playlists.orEmpty().mapNotNull { if (it.id == id) change(it) else it }) }
        return true
    }

    fun addToPlaylist(type: ServerType, id: String, songs: List<Song>) =
        changePlaylist(type, id) { p -> p.copy(songs = (p.songs.orEmpty() + songs.map(::kept)).distinctBy { it.id }) }

    // the signed-in account a type's device likes and playlists are waiting for, with how many there are
    suspend fun waitingFor(sessions: List<Session>, canTake: suspend (Session) -> Boolean = { true }): Pair<Session, Int>? = sessions
        .filter { !it.isGuest && it.type.supportsGuest && pending(it.type) > 0 }
        .firstOrNull { canTake(it) }
        ?.let { it to pending(it.type) }

    // oldest first, so the account's likes keep the order they were made in; each one that lands leaves the device
    suspend fun moveTo(target: MediaBackend, pauseMs: Long = MOVE_PAUSE_MS): Int {
        if (!attempt(false) { target.acceptsLikes() }) return 0
        val type = target.session.type
        var moved = 0
        suspend fun step(landed: Boolean, done: () -> Unit) {
            if (landed) { done(); moved++ }
            if (pauseMs > 0) delay(pauseMs)
        }
        for (song in of(type).reversed()) step(attempt(false) { target.setStarred(song.id, true, "song") }) { remove(type, listOf(song.id)) }
        for (key in likedCollections(type).reversed()) {
            val (kind, id) = key.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
            step(attempt(false) { target.setStarred(id, true, kind) }) { setCollectionLiked(type, kind, id, false) }
        }
        for (p in playlists(type)) step(attempt(false) { target.copyOf(p) }) { changePlaylist(type, p.id) { null } }
        return moved
    }

    // shortcut: a playlist that fails halfway stays here and is made again in full next time, upgrade if partial copies show up
    private suspend fun MediaBackend.copyOf(p: DevicePlaylist): Boolean {
        val id = createPlaylistWithId(p.name.orEmpty().ifBlank { "Playlist" }) ?: return false
        if (!p.description.isNullOrBlank()) updatePlaylist(id, null, p.description)
        return p.songs.orEmpty().map { it.id }.chunked(100).all { addToPlaylist(id, it) }
    }

    fun discard(type: ServerType) = edit(type) { emptyList() }

    private fun kept(song: Song) = song.copy(playbackSource = null, playbackCollection = null)

    private fun shelf(type: ServerType, change: (DeviceShelf) -> DeviceShelf) {
        synchronized(lock) {
            val next = change(shelves[type.name] ?: DeviceShelf())
            shelves = if (next.liked.isNullOrEmpty() && next.playlists.isNullOrEmpty()) shelves - type.name else shelves + (type.name to next)
            runCatching { shelfFile?.writeText(gson.toJson(shelves)) }
        }
        _revision.update { it + 1 }
    }

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
        const val DEVICE_PLAYLIST = "device-pl-"
    }
}
