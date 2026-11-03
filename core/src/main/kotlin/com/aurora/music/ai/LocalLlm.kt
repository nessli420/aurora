package com.aurora.music.ai

import com.aurora.music.util.NativeLibraries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal object LlamaNative {
    val available: Boolean by lazy { runCatching { NativeLibraries.load("aurora_llm") }.isSuccess }
    @JvmStatic external fun load(path: ByteArray, threads: Int, context: Int): Long
    @JvmStatic external fun generate(handle: Long, prefix: ByteArray, prompt: ByteArray, grammar: ByteArray, maxTokens: Int): ByteArray?
    @JvmStatic external fun timings(handle: Long): LongArray
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun free(handle: Long)
}

data class LlmTimings(
    val loadMs: Long, val prefixTokens: Long, val prefixMs: Long,
    val promptTokens: Long, val promptMs: Long, val generatedTokens: Long, val generateMs: Long,
) {
    val totalMs get() = loadMs + prefixMs + promptMs + generateMs
    override fun toString() = "load ${loadMs}ms, prefix $prefixTokens tok/${prefixMs}ms, prompt $promptTokens tok/${promptMs}ms, " +
        "generate $generatedTokens tok/${generateMs}ms" + if (generateMs > 0) " (%.1f tok/s)".format(generatedTokens * 1000.0 / generateMs) else ""
}

class LocalLlm(
    private val model: () -> File?,
    private val threads: Int,
    private val context: Int = 2048,
    private val idleMs: Long = 120_000,
) {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle = 0L
    private var unload: Job? = null
    @Volatile var lastTimings: LlmTimings? = null
        private set

    val available: Boolean get() = LlamaNative.available

    suspend fun complete(prefix: String, prompt: String, grammar: String, maxTokens: Int): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            unload?.cancel()
            var loadMs = 0L
            if (handle == 0L) {
                val file = model() ?: return@withContext null
                if (!LlamaNative.available) return@withContext null
                val start = System.currentTimeMillis()
                handle = LlamaNative.load(file.absolutePath.toByteArray(), threads, context)
                loadMs = System.currentTimeMillis() - start
                if (handle == 0L) return@withContext null
            }
            val h = handle
            try {
                coroutineScope {
                    val run = async { LlamaNative.generate(h, prefix.toByteArray(), prompt.toByteArray(), grammar.toByteArray(), maxTokens) }
                    try { run.await() } catch (e: CancellationException) { LlamaNative.cancel(h); throw e }
                }?.toString(Charsets.UTF_8)
            } finally {
                val t = LlamaNative.timings(h)
                lastTimings = LlmTimings(loadMs, t[0], t[1], t[2], t[3], t[4], t[5])
                unload = scope.launch { delay(idleMs); release() }
            }
        }
    }

    suspend fun release() {
        lock.withLock {
            val h = handle
            handle = 0L
            if (h != 0L) withContext(Dispatchers.IO) { LlamaNative.free(h) }
        }
    }
}
