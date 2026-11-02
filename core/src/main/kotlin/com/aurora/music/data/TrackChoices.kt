package com.aurora.music.data

import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class MatchChoice(val videoId: String = "", val title: String = "", val artist: String = "", val durationSec: Int = 0)

// what the listener decided for single tracks: the video a matched track plays, and the songs a playlist leaves out
class TrackChoices(dir: File, private val persist: (File, ByteArray) -> Unit = ::persistBackupFileAtomically) {
    private data class Saved(val matches: Map<String, MatchChoice>? = emptyMap(), val hidden: Map<String, List<String>>? = emptyMap())

    private val file = File(dir, "track_choices.json")
    private val gson = Gson()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saved = runCatching { gson.fromJson(file.readText(), Saved::class.java) }.getOrNull() ?: Saved()
    private val _matches = MutableStateFlow(saved.matches.orEmpty())
    private val _hidden = MutableStateFlow(saved.hidden.orEmpty().mapValues { it.value.toSet() })
    val matches: StateFlow<Map<String, MatchChoice>> = _matches.asStateFlow()
    val hidden: StateFlow<Map<String, Set<String>>> = _hidden.asStateFlow()

    fun matchFor(key: String): MatchChoice? = _matches.value[key]

    fun setMatch(key: String, choice: MatchChoice?) = synchronized(lock) {
        _matches.value = if (choice == null) _matches.value - key else _matches.value + (key to choice)
        save()
    }

    fun hiddenIn(kind: String, id: String): Set<String> = _hidden.value[collectionKey(kind, id)].orEmpty()

    fun isHidden(kind: String, id: String, songId: String): Boolean = stripMergeNamespace(songId) in hiddenIn(kind, id)

    fun setHidden(kind: String, id: String, songId: String, hide: Boolean) = synchronized(lock) {
        val key = collectionKey(kind, id)
        val songs = hiddenIn(kind, id).let { if (hide) it + stripMergeNamespace(songId) else it - stripMergeNamespace(songId) }
        _hidden.value = if (songs.isEmpty()) _hidden.value - key else _hidden.value + (key to songs)
        save()
    }

    // the songs a collection plays; the one the listener picked plays even when hidden
    fun visible(kind: String, id: String, songs: List<Song>, picked: Song? = null): List<Song> {
        val hidden = hiddenIn(kind, id).takeIf { it.isNotEmpty() } ?: return songs
        return songs.filter { it === picked || stripMergeNamespace(it.id) !in hidden }
    }

    private fun save() {
        // each write takes the newest state, so writes finishing out of order still leave the latest on disk
        scope.launch {
            synchronized(file) {
                val snapshot = Saved(_matches.value, _hidden.value.mapValues { it.value.sorted() })
                runCatching { persist(file, gson.toJson(snapshot).toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    companion object {
        fun collectionKey(kind: String, id: String) = "$kind:${stripMergeNamespace(id)}"

        // a track without its own audio plays a youtube search: aurora-yt://<key>?q=..; a direct video is aurora-yt://video/<id>
        fun matchKey(streamUrl: String): String? {
            if (!streamUrl.startsWith("aurora-yt://")) return null
            val key = streamUrl.removePrefix("aurora-yt://").substringBefore('?').substringBefore('/')
            return key.takeIf { it.isNotBlank() && it != "video" && "q=" in streamUrl }
        }
    }
}
