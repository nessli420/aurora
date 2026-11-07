package com.aurora.music.mix

import com.aurora.music.data.diskBytes
import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.model.Song
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reads a playable source into bounded waveform/energy bins. No library download or retained audio. */
class MixAnalyzer(root: File, private val resolve: (Song) -> ResolvedStream,
    private val onAnalyzed: (String, String, FloatArray) -> Unit = { _, _, _ -> }) {
    // durable, account-scoped fingerprints are shared by sonic discovery and automatic mixes
    private val directory = File(root, "sonic-analysis-v3").apply { mkdirs() }
    private val lock = Mutex() // one decoder/network analysis at a time, leaving playback resources free
    private val foreground = AtomicInteger()
    private class Yielded : Exception()
    private fun file(account: String, song: Song) = File(directory,
        java.security.MessageDigest.getInstance("SHA-256").digest("$account|${song.id}|${song.durationSec}".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json")

    suspend fun cached(account: String, song: Song): MixAnalysis? = withContext(Dispatchers.IO) { read(file(account, song)) }
    fun bytes(): Long = directory.diskBytes()
    fun clear() { directory.listFiles()?.forEach { it.delete() } }

    private fun read(f: File): MixAnalysis? = runCatching {
        val json = JsonParser.parseString(f.readText()).asJsonObject
        val peaks = json.getAsJsonArray("peaks")
        MixAnalysis(FloatArray(peaks.size()) { peaks[it].asFloat }.asList(), json.get("seconds").asFloat, json.get("bpm").asFloat,
            json.get("confidence").asFloat, json.get("rms").asFloat, json.getAsJsonArray("sonic").map { it.asFloat },
            beatOffset = json.get("beatOffset")?.asFloat ?: 0f, audibleStart = json.get("audibleStart")?.asFloat ?: 0f,
            audibleEnd = json.get("audibleEnd").asFloat)
    }.getOrNull()

    // only the scan passes its low-priority dispatcher, and its track gives way to any other caller
    suspend fun analyze(account: String, song: Song, background: CoroutineDispatcher? = null, onProgress: (Float) -> Unit): MixAnalysis {
        cached(account, song)?.let { onAnalyzed(account, song.id, it.sonic.toFloatArray()); return it }
        if (background == null) foreground.incrementAndGet()
        try { return decode(account, song, background, onProgress) }
        catch (_: Yielded) {
            while (foreground.get() > 0) delay(200)
            return analyze(account, song, background, onProgress)
        } finally { if (background == null) foreground.decrementAndGet() }
    }

    private suspend fun decode(account: String, song: Song, background: CoroutineDispatcher?, onProgress: (Float) -> Unit): MixAnalysis = lock.withLock {
        withContext(background ?: Dispatchers.IO) {
            val cache = file(account, song)
            read(cache)?.let { onAnalyzed(account, song.id, it.sonic.toFloatArray()); return@withContext it }
            val stream = runCatching { resolve(song) }.getOrNull()
            require(stream != null && stream.url.isNotBlank()) { "This song has no playable source. Reconnect to its library and try again." }
            val job = currentCoroutineContext()[Job]!!
            fun yielding() = background != null && foreground.get() > 0
            val interrupt = AtomicBoolean()
            val deadline = System.currentTimeMillis() + 180_000
            val binSeconds = 0.02f
            val maxBins = 180_000 // bounded: one hour; longer tracks can still be mixed manually
            val peaks = ArrayList<Float>()
            val energy = ArrayList<Float>()
            var frames = 0L
            var square = 0.0
            var samples = 0L
            var binFrames = 0
            var binPeak = 0f
            var binSquare = 0.0
            var lastProgress = -1
            var complete = false
            val decoder = FfmpegDecoder.open(stream.url, HttpOptions(stream.headers, stream.userAgent), interrupt)
            val sr = decoder.info.sampleRate.coerceAtLeast(1)
            val sonicSamples = FloatArray(sr * 30)
            var sonicCount = 0
            try {
                val buffer = DoubleArray(8192)
                while (peaks.size < maxBins) {
                    if (!job.isActive || yielding() || System.currentTimeMillis() >= deadline) break
                    val read = try { decoder.read(buffer) } catch (_: Exception) { break }
                    if (read < 0) { complete = true; break }
                    for (f in 0 until read) {
                        if (peaks.size >= maxBins) break
                        val left = buffer[f * 2].toFloat()
                        val right = buffer[f * 2 + 1].toFloat()
                        binPeak = max(binPeak, max(abs(left), abs(right)))
                        val frameSquare = (left * left + right * right) / 2f
                        if (sonicCount < sonicSamples.size) sonicSamples[sonicCount++] = (left + right) / 2f
                        binSquare += frameSquare; square += frameSquare; samples++
                        frames++; binFrames++
                        if (binFrames >= (sr * binSeconds).toInt().coerceAtLeast(1)) {
                            peaks.add(binPeak); energy.add(sqrt(binSquare / binFrames).toFloat())
                            binFrames = 0; binPeak = 0f; binSquare = 0.0
                        }
                    }
                    val percent = ((frames.toDouble() / sr / song.durationSec.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 99)
                    if (percent != lastProgress) { lastProgress = percent; onProgress(percent / 100f) }
                }
                if (peaks.size >= maxBins) complete = true
            } finally {
                decoder.close()
            }
            job.ensureActive()
            if (!complete && yielding()) throw Yielded()
            check(complete) { "Analysis could not finish this stream. Retry on a stable connection; manual mixing is available." }
            check(samples > sr) { "Not enough decodable audio to analyze." }
            if (binFrames > 0) { peaks.add(binPeak); energy.add(sqrt(binSquare / binFrames).toFloat()) }
            val (bpm, confidence) = estimateTempo(energy)
            val sonic = com.aurora.music.data.SonicFeatures.analyzeSamples(sonicSamples, sonicCount, sr) { !job.isActive }
                ?: error("Not enough audio for Sonic discovery.")
            if (bpm > 0) sonic[sonic.lastIndex] = ((bpm - 60) / 120).coerceIn(0f, 1f)
            val threshold = max(0.001f, (energy.maxOrNull() ?: 0f) * 0.015f)
            val firstAudible = energy.indexOfFirst { it > threshold }.coerceAtLeast(0) * binSeconds
            val lastAudible = ((energy.indexOfLast { it > threshold } + 1).coerceAtLeast(1) * binSeconds).coerceAtMost(frames.toFloat() / sr)
            val periodBins = if (bpm > 0) (3000 / bpm).roundToInt().coerceAtLeast(1) else 1
            val phases = FloatArray(periodBins)
            for (i in 1 until energy.size) phases[i % periodBins] += max(0f, energy[i] - energy[i - 1])
            val beatOffset = (phases.indices.maxByOrNull { phases[it] } ?: 0) * binSeconds
            // persisted at 10 waveform points a second, keeping transient peaks across channels
            val waveform = FloatArray((peaks.size + 4) / 5) { c -> (c * 5 until minOf(peaks.size, c * 5 + 5)).maxOf { peaks[it] } }.asList()
            val result = MixAnalysis(waveform, frames.toFloat() / sr, bpm, confidence,
                (20 * log10(sqrt(square / samples).coerceAtLeast(1e-8))).toFloat(), sonic.toList(),
                beatOffset = beatOffset, audibleStart = firstAudible, audibleEnd = lastAudible)
            fun array(values: List<Float>) = JsonArray().apply { values.forEach { add(it) } }
            val json = JsonObject().apply {
                add("peaks", array(waveform)); addProperty("seconds", result.seconds); addProperty("bpm", bpm)
                addProperty("confidence", confidence); addProperty("rms", result.rmsDb); add("sonic", array(result.sonic))
                addProperty("beatOffset", beatOffset)
                addProperty("audibleStart", firstAudible); addProperty("audibleEnd", lastAudible)
            }
            persistBackupFileAtomically(cache, json.toString().toByteArray())
            onAnalyzed(account, song.id, sonic)
            onProgress(1f)
            result
        }
    }

    private fun estimateTempo(energy: List<Float>): Pair<Float, Float> {
        if (energy.size < 500) return 0f to 0f
        val onset = FloatArray(energy.size) { i -> if (i == 0) 0f else max(0f, energy[i] - energy[i - 1]) }
        var best = 0.0; var bestLag = 0
        // 50 hz onset envelope: autocorrelation is an estimate, not a beat-grid guarantee
        for (lag in 17..50) {
            var dot = 0.0; var a = 0.0; var b = 0.0
            for (i in lag until onset.size) {
                dot += onset[i] * onset[i - lag]; a += onset[i] * onset[i]; b += onset[i - lag] * onset[i - lag]
            }
            val score = dot / sqrt(a * b).coerceAtLeast(1e-12)
            if (score > best) { best = score; bestLag = lag }
        }
        return if (bestLag == 0 || best < 0.08) 0f to best.toFloat() else (3000f / bestLag) to best.toFloat().coerceIn(0f, 1f)
    }
}
