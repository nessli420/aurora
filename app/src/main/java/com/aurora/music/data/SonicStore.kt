package com.aurora.music.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Account-scoped index into shared stream analysis. Old unscoped data is left untouched. */
class SonicStore(context: Context) {
    private val directory = File(context.filesDir, "sonic-index-v4").apply { mkdirs() }
    private val legacyDirectory = File(context.filesDir, "sonic-index-v3")
    private val lock = Any()
    private val accounts = mutableMapOf<String, MutableMap<String, FloatArray>>()
    @Volatile private var active = ""
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun folder(account: String) = File(directory, hash(account)).apply { mkdirs() }
    private fun file(account: String, id: String) = AtomicFile(File(folder(account), hash(id) + ".json"))
    private fun data(account: String): MutableMap<String, FloatArray> = accounts.getOrPut(account) {
        runCatching {
            val records = folder(account).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { f -> runCatching {
                val json = JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))
                val a = json.getJSONArray("vector")
                if (a.length() != SonicFeatures.DIMS) null else json.getString("id") to FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }.getOrNull() }.toMap().toMutableMap()
            val legacy = File(legacyDirectory, hash(account) + ".json")
            if (legacy.exists()) runCatching {
                val json = JSONObject(legacy.readText())
                json.keys().forEach { id ->
                    val a = json.getJSONArray(id)
                    if (id !in records && a.length() == SonicFeatures.DIMS) {
                        val vector = FloatArray(a.length()) { a.getDouble(it).toFloat() }
                        val target = file(account, id); val output = target.startWrite()
                        try { output.write(JSONObject().put("id", id).put("vector", a).toString().toByteArray()); target.finishWrite(output) }
                        catch (t: Throwable) { target.failWrite(output); throw t }
                        records[id] = vector
                    }
                }
            }
            records
        }.getOrDefault(mutableMapOf())
    }
    fun selectAccount(account: String) = synchronized(lock) { active = account; _count.value = data(account).size }
    fun has(id: String, account: String = active) = synchronized(lock) { data(account).containsKey(id) }
    fun get(id: String, account: String = active): FloatArray? = synchronized(lock) { data(account)[id]?.copyOf() }
    fun put(id: String, vec: FloatArray, account: String = active) = synchronized(lock) {
        require(vec.size == SonicFeatures.DIMS && vec.all { it.isFinite() })
        if (data(account)[id]?.contentEquals(vec) == true) return@synchronized
        data(account)[id] = vec.copyOf()
        // One atomic record per track avoids rewriting a growing library index after every song.
        val json = JSONObject().put("id", id).put("vector", JSONArray(vec.toList()))
        val target = file(account, id)
        val out = target.startWrite()
        try { out.write(json.toString().toByteArray()); target.finishWrite(out) }
        catch (t: Throwable) { target.failWrite(out); throw t }
        if (account == active) _count.value = data(account).size
    }
    fun snapshot(account: String = active): Map<String, FloatArray> = synchronized(lock) { HashMap(data(account)) }
}
