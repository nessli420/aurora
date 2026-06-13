package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Scanned ReplayGain values for one file (dB, ReplayGain 2.0 / -18 LUFS reference). */
data class RgEntry(val track: Float = 0f, val album: Float = 0f)

/**
 * Persists locally-scanned ReplayGain gains, keyed by absolute file path so they survive a
 * MediaStore rescan (which can renumber `_ID`s). Aurora can't write tags into the file from the
 * scanner, so the gains live here and are overlaid onto [Song]s as they're built; the attenuate-only
 * playback path in PlaybackService then applies them.
 */
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

    /** (trackGainDb, albumGainDb) for [path], or null if never scanned / both zero. */
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
