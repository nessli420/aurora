package com.aurora.music.data

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Account-scoped index into shared stream analysis. Old unscoped data is left untouched. */
class SonicStore(context: Context) {
    private val directory = File(context.filesDir, "sonic-index-v4").apply { mkdirs() }
    private val legacyDirectory = File(context.filesDir, "sonic-index-v3")
    private val lock = Any()
    private val accounts = ConcurrentHashMap<String, MutableMap<String, FloatArray>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var active = ""
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> get() { preload(active); return _count }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun folder(account: String) = File(directory, hash(account)).apply { mkdirs() }
    private fun file(account: String, id: String) = AtomicFile(File(folder(account), hash(id) + ".json"))
    private fun legacy(account: String) = File(legacyDirectory, hash(account) + ".json")
    private fun record(f: File): Pair<String, FloatArray>? = runCatching {
        val json = JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))
        val a = json.getJSONArray("vector")
        if (a.length() != SonicFeatures.DIMS) null else json.getString("id") to FloatArray(a.length()) { a.getDouble(it).toFloat() }
    }.getOrNull()
    private fun data(account: String): MutableMap<String, FloatArray> = accounts.getOrPut(account) {
        runCatching {
            val records = folder(account).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::record).toMap().toMutableMap()
            val legacyFile = legacy(account)
            if (legacyFile.exists()) runCatching {
                val json = JSONObject(legacyFile.readText())
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
    // vectors load on first radio or analysis use not on every launch
    fun selectAccount(account: String) = synchronized(lock) {
        active = account
        _count.value = if (accounts.containsKey(account) || _count.subscriptionCount.value > 0) data(account).size else 0
    }
    private fun load(account: String) = synchronized(lock) { val size = data(account).size; if (account == active) _count.value = size }
    private fun preload(account: String) {
        if (accounts.containsKey(account)) return
        if (Looper.myLooper() != Looper.getMainLooper()) { load(account); return }
        if (!pending.add(account)) return
        thread(isDaemon = true, name = "sonic-index") {
            try { load(account) } finally { pending.remove(account) }
        }
    }
    fun has(id: String, account: String = active) = synchronized(lock) { data(account).containsKey(id) }
    fun get(id: String, account: String = active): FloatArray? {
        if (!accounts.containsKey(account) && !legacy(account).exists()) {
            val f = File(File(directory, hash(account)), hash(id) + ".json")
            return if (f.isFile) record(f)?.takeIf { it.first == id }?.second else null
        }
        return synchronized(lock) { data(account)[id]?.copyOf() }
    }
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
    fun bytes(): Long = directory.diskBytes() + legacyDirectory.diskBytes()
    fun clear() = synchronized(lock) {
        accounts.clear()
        directory.deleteRecursively()
        directory.mkdirs()
        legacyDirectory.deleteRecursively()
        _count.value = 0
    }
}
