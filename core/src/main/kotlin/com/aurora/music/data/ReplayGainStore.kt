package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.concurrent.thread

data class RgEntry(val track: Float = 0f, val album: Float = 0f)

// keyed by absolute path so gains survive a mediastore rescan that renumbers ids
class ReplayGainStore(filesDir: File) {
    private val file = File(filesDir, "rg_gains.json")
    private val gson = Gson()
    private val lock = Any()

    @Volatile private var loaded: Map<String, RgEntry>? = null
    // parsed off main, reads and merges wait here so a write can't replace an unread file
    private val map: Map<String, RgEntry> get() = loaded ?: synchronized(lock) { loaded ?: load().also { loaded = it } }

    init { thread(name = "rg-gains-load", isDaemon = true) { map.size } }

    private fun load(): Map<String, RgEntry> = runCatching {
        if (!file.exists()) emptyMap()
        else gson.fromJson<Map<String, RgEntry>>(file.readText(), object : TypeToken<Map<String, RgEntry>>() {}.type) ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun persist() = runCatching { persistBackupFileAtomically(file, gson.toJson(map).toByteArray(Charsets.UTF_8)) }

    fun get(path: String): RgEntry? = map[path]

    fun gainsFor(path: String): Pair<Float, Float>? =
        map[path]?.takeIf { it.track != 0f || it.album != 0f }?.let { it.track to it.album }

    fun putAll(entries: Map<String, RgEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            loaded = map + entries
            persist()
        }
    }

    val size: Int get() = map.size

    fun clear() = synchronized(lock) { loaded = emptyMap(); persist() }
}
