package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class RgEntry(val track: Float = 0f, val album: Float = 0f)

// keyed by absolute path so gains survive a mediastore rescan that renumbers ids
class ReplayGainStore(context: Context) {
    private val file = File(context.filesDir, "rg_gains.json")
    private val gson = Gson()
    private val lock = Any()

    @Volatile private var map: Map<String, RgEntry> = load()

    private fun load(): Map<String, RgEntry> = runCatching {
        if (!file.exists()) emptyMap()
        else gson.fromJson<Map<String, RgEntry>>(file.readText(), object : TypeToken<Map<String, RgEntry>>() {}.type) ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun persist() = runCatching { file.writeText(gson.toJson(map)) }

    fun get(path: String): RgEntry? = map[path]

    fun gainsFor(path: String): Pair<Float, Float>? =
        map[path]?.takeIf { it.track != 0f || it.album != 0f }?.let { it.track to it.album }

    fun putAll(entries: Map<String, RgEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            map = map + entries
            persist()
        }
    }

    val size: Int get() = map.size

    fun clear() = synchronized(lock) { map = emptyMap(); persist() }
}
