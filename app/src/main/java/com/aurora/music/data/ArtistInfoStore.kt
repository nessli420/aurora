package com.aurora.music.data

import android.content.Context
import com.aurora.music.data.remote.ArtistInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * On-disk cache of [ArtistInfo] keyed by lowercased artist name, in `filesDir/artist_info.json`.
 * Mirrors [SonicStore]'s version-gated persistence: bumping [VERSION] invalidates the whole cache, so
 * a schema change can't be read back as a half-null object (the Gson missing-key trap). Hits are kept
 * for [FRESH_MS]; misses are cached for the shorter [MISS_MS] so a temporarily-offline lookup retries
 * sooner. All disk work is wrapped so a corrupt/locked file degrades to "no cache".
 */
class ArtistInfoStore(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val file = File(context.filesDir, "artist_info.json")
    private val gson = Gson()
    private val lock = Any()

    private class Entry(val info: ArtistInfo = ArtistInfo(), val fetchedAtMs: Long = 0)
    private class Persisted(val version: Int = 0, val entries: MutableMap<String, Entry> = mutableMapOf())

    @Volatile private var data: Persisted = load()

    private fun load(): Persisted = runCatching {
        if (!file.exists()) return@runCatching Persisted(VERSION)
        val type = object : TypeToken<Persisted>() {}.type
        val p = gson.fromJson<Persisted>(file.readText(), type) ?: Persisted(VERSION)
        if (p.version != VERSION) Persisted(VERSION) else p
    }.getOrDefault(Persisted(VERSION))

    private fun key(name: String) = name.trim().lowercase()

    /** A fresh cached entry, or null if absent/expired (caller should fetch + [put]). */
    fun get(name: String): ArtistInfo? = synchronized(lock) {
        val e = data.entries[key(name)] ?: return null
        val ttl = if (e.info.found) FRESH_MS else MISS_MS
        if (clock() - e.fetchedAtMs > ttl) null else e.info
    }

    fun put(name: String, info: ArtistInfo) {
        synchronized(lock) {
            data.entries[key(name)] = Entry(info, clock())
            runCatching { file.writeText(gson.toJson(data)) }
        }
    }

    private companion object {
        const val VERSION = 1
        const val FRESH_MS = 30L * 24 * 60 * 60 * 1000   // 30 days
        const val MISS_MS = 3L * 24 * 60 * 60 * 1000      // 3 days
    }
}
