package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// fields nullable-safe for gson forward compat
data class LocalPlaylist(
    val id: String = "",
    val title: String? = "",
    val subtitle: String? = "",
    val trackIds: List<String>? = emptyList(),
    val foreign: List<SavedTrack>? = null,
)

private data class LocalState(
    val playlists: List<LocalPlaylist>? = emptyList(),
    val likedIds: List<String>? = emptyList(),
)

class LocalStore(filesDir: File) {
    private val file = File(filesDir, "local_store.json")
    private val gson = Gson()
    private val lock = Any()
    private var revision = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeQueued = AtomicBoolean(false)

    class BackupRollback internal constructor(internal val previous: String, internal val revision: Long)

    @Volatile private var state: LocalState = load()

    private fun load(): LocalState = runCatching {
        if (!file.exists()) return LocalState()
        gson.fromJson(file.readText(), object : TypeToken<LocalState>() {}.type) ?: LocalState()
    }.getOrDefault(LocalState())

    private fun persist() {
        revision++
        if (!writeQueued.compareAndSet(false, true)) return
        scope.launch {
            // reads state under the lock when it runs, so a queued write can't undo a backup restore
            synchronized(lock) {
                writeQueued.set(false)
                runCatching { persistBackupFileAtomically(file, gson.toJson(state).toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    fun playlists(): List<LocalPlaylist> = state.playlists.orEmpty()
    fun playlist(id: String): LocalPlaylist? = state.playlists.orEmpty().firstOrNull { it.id == id }

    fun createPlaylist(name: String): String = synchronized(lock) {
        val id = "local-pl-" + UUID.randomUUID().toString().take(8)
        state = state.copy(playlists = state.playlists.orEmpty() + LocalPlaylist(id, name, "", emptyList()))
        persist()
        id
    }

    fun updatePlaylist(id: String, name: String?, subtitle: String?) = synchronized(lock) {
        state = state.copy(playlists = state.playlists.orEmpty().map {
            if (it.id == id) it.copy(title = name ?: it.title, subtitle = subtitle ?: it.subtitle) else it
        })
        persist()
    }

    fun deletePlaylist(id: String) = synchronized(lock) {
        state = state.copy(playlists = state.playlists.orEmpty().filterNot { it.id == id })
        persist()
    }

    fun addTracks(id: String, trackIds: List<String>, foreign: List<SavedTrack> = emptyList()) = synchronized(lock) {
        val added = foreign.map { it.id }.toSet()
        state = state.copy(playlists = state.playlists.orEmpty().map {
            if (it.id == id) it.copy(trackIds = (it.trackIds.orEmpty() + trackIds).distinct(),
                foreign = it.foreign.orEmpty().filterNot { t -> t.id in added } + foreign) else it
        })
        persist()
    }

    fun removeTracks(id: String, trackIds: List<String>) = synchronized(lock) {
        val drop = trackIds.toSet()
        state = state.copy(playlists = state.playlists.orEmpty().map {
            if (it.id == id) it.copy(trackIds = it.trackIds.orEmpty().filterNot { t -> t in drop },
                foreign = it.foreign?.filterNot { t -> t.id in drop }) else it
        })
        persist()
    }

    fun exportJson(): String = gson.toJson(state)
    fun importJson(json: String) = synchronized(lock) {
        runCatching { gson.fromJson(json, object : TypeToken<LocalState>() {}.type) as? LocalState }.getOrNull()?.let {
            state = it; persist()
        }
    }

    suspend fun restoreBackupJson(json: String) {
        replaceBackupJson(json)
    }

    suspend fun replaceBackupJson(json: String): BackupRollback = withContext(Dispatchers.IO) {
        val restored = requireNotNull(gson.fromJson(json, LocalState::class.java)) { "Local library is missing." }
        val bytes = gson.toJson(restored).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val previous = gson.toJson(state)
            persistBackupFileAtomically(file, bytes)
            state = restored
            BackupRollback(previous, ++revision)
        }
    }

    suspend fun rollbackBackup(token: BackupRollback): Boolean = withContext(Dispatchers.IO) {
        val previous = requireNotNull(gson.fromJson(token.previous, LocalState::class.java))
        synchronized(lock) {
            if (revision != token.revision) {
                persistBackupFileAtomically(file, gson.toJson(state).toByteArray(Charsets.UTF_8))
                false
            } else {
                persistBackupFileAtomically(file, token.previous.toByteArray(Charsets.UTF_8))
                state = previous
                revision++
                true
            }
        }
    }

    fun likedIds(): Set<String> = state.likedIds.orEmpty().toSet()

    fun setLiked(id: String, liked: Boolean): Boolean = synchronized(lock) {
        val cur = state.likedIds.orEmpty()
        val next = if (liked) (cur + id).distinct() else cur.filterNot { it == id }
        state = state.copy(likedIds = next)
        persist()
        true
    }
}
