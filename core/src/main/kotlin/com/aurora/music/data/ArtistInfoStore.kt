package com.aurora.music.data

import com.aurora.music.data.remote.ArtistInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

// bumping VERSION invalidates the cache so a schema change can't read back half-null gson missing-key trap
class ArtistInfoStore(filesDir: File, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val file = File(filesDir, "artist_info.json")
    private val gson = Gson()
    private val lock = Any()

    private class Entry(val info: ArtistInfo = ArtistInfo(), val fetchedAtMs: Long = 0)
    private class Persisted(val version: Int = 0, val entries: MutableMap<String, Entry> = mutableMapOf())

    @Volatile private var loaded: Persisted? = null
    // parsed on first use since the feature may never run; get and put belong off main
    private val data: Persisted get() = loaded ?: synchronized(lock) { loaded ?: load().also { loaded = it } }

    private fun load(): Persisted = runCatching {
        if (!file.exists()) return@runCatching Persisted(VERSION)
        val type = object : TypeToken<Persisted>() {}.type
        val p = gson.fromJson<Persisted>(file.readText(), type) ?: Persisted(VERSION)
        if (p.version != VERSION) Persisted(VERSION) else p
    }.getOrDefault(Persisted(VERSION))

    private fun key(name: String) = name.trim().lowercase()

    private fun Entry.expired(): Boolean {
        val ttl = if (info.found) FRESH_MS else MISS_MS
        return clock() - fetchedAtMs > ttl
    }

    fun get(name: String): ArtistInfo? = synchronized(lock) {
        val e = data.entries[key(name)] ?: return null
        if (e.expired()) null else e.info
    }

    fun put(name: String, info: ArtistInfo) {
        synchronized(lock) {
            data.entries.values.removeAll { it.expired() }
            data.entries[key(name)] = Entry(info, clock())
            runCatching { persistBackupFileAtomically(file, gson.toJson(data).toByteArray(Charsets.UTF_8)) }
        }
    }

    val size: Int get() = synchronized(lock) { data.entries.size }

    fun bytes(): Long = file.length()

    fun clear() = synchronized(lock) {
        loaded = Persisted(VERSION)
        file.delete()
        Unit
    }

    private companion object {
        const val VERSION = 1
        const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
        const val MISS_MS = 3L * 24 * 60 * 60 * 1000
    }
}
