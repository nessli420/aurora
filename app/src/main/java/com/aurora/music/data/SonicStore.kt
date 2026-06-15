package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SonicStore(context: Context) {
    private val file = File(context.filesDir, "sonic_features.json")
    private val gson = Gson()
    private val lock = Any()

    private class Persisted(
        val version: Int = 0,
        val vectors: MutableMap<String, FloatArray> = mutableMapOf(),
    )

    @Volatile private var data: Persisted = load()
    private val _count = MutableStateFlow(data.vectors.size)
    val count: StateFlow<Int> = _count.asStateFlow()

    private fun load(): Persisted = runCatching {
        if (!file.exists()) return@runCatching Persisted(SonicFeatures.VERSION)
        val type = object : TypeToken<Persisted>() {}.type
        val p = gson.fromJson<Persisted>(file.readText(), type) ?: Persisted(SonicFeatures.VERSION)
        if (p.version != SonicFeatures.VERSION) Persisted(SonicFeatures.VERSION)
        else {
            p.vectors.entries.removeAll { it.value.size != SonicFeatures.DIMS }
            p
        }
    }.getOrDefault(Persisted(SonicFeatures.VERSION))

    fun has(id: String): Boolean = data.vectors.containsKey(id)
    fun get(id: String): FloatArray? = data.vectors[id]

    fun put(id: String, vec: FloatArray) {
        synchronized(lock) {
            data.vectors[id] = vec
            persist()
        }
        _count.value = data.vectors.size
    }

    // insert without writing to disk caller must flush
    fun putDeferred(id: String, vec: FloatArray) {
        synchronized(lock) { data.vectors[id] = vec }
        _count.value = data.vectors.size
    }

    fun flush() = synchronized(lock) { persist() }

    fun snapshot(): Map<String, FloatArray> = synchronized(lock) { HashMap(data.vectors) }

    fun retainOnly(ids: Set<String>) {
        synchronized(lock) {
            val before = data.vectors.size
            data.vectors.keys.retainAll(ids)
            if (data.vectors.size != before) persist()
        }
        _count.value = data.vectors.size
    }

    private fun persist() = runCatching { file.writeText(gson.toJson(data)) }
}
