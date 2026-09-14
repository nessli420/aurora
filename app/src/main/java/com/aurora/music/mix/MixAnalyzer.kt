package com.aurora.music.mix

import android.content.Context
import android.util.AtomicFile
import com.aurora.music.data.AudioDecoder
import com.aurora.music.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

data class MixAnalysis(val peaks: List<Float>, val seconds: Float, val bpm: Float, val confidence: Float, val rmsDb: Float,
    val sonic: List<Float> = emptyList(), val energy: List<Float> = emptyList(), val beatOffset: Float = 0f,
    val audibleStart: Float = 0f, val audibleEnd: Float = seconds)

/** Reads a playable source into bounded waveform/energy bins. No library download or retained audio. */
class MixAnalyzer(private val context: Context, private val resolve: (String) -> String?,
    private val onAnalyzed: (String, String, FloatArray) -> Unit = { _, _, _ -> }) {
    // Durable, account-scoped fingerprints are shared by Sonic Discovery and automatic mixes.
    private val directory = File(context.filesDir, "sonic-analysis-v3").apply { mkdirs() }
    private val lock = Mutex() // one decoder/network analysis at a time, leaving playback resources free
    private fun file(account: String, song: Song) = AtomicFile(File(directory,
        java.security.MessageDigest.getInstance("SHA-256").digest("$account|${song.id}|${song.durationSec}".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"))

    suspend fun cached(account: String, song: Song): MixAnalysis? = withContext(Dispatchers.IO) { read(file(account, song)) }
    private fun read(f: AtomicFile): MixAnalysis? = runCatching {
        val json = JSONObject(String(f.readFully(), Charsets.UTF_8))
        val peaks = json.getJSONArray("peaks")
        MixAnalysis((0 until peaks.length()).map { peaks.getDouble(it).toFloat() }, json.getDouble("seconds").toFloat(),
            json.getDouble("bpm").toFloat(), json.getDouble("confidence").toFloat(), json.getDouble("rms").toFloat(),
            json.getJSONArray("sonic").floats(), json.getJSONArray("energy").floats(),
            json.optDouble("beatOffset", 0.0).toFloat(), json.optDouble("audibleStart", 0.0).toFloat(),
            json.getDouble("audibleEnd").toFloat())
    }.getOrNull()

    suspend fun analyze(account: String, song: Song, onProgress: (Float) -> Unit): MixAnalysis = lock.withLock {
        withContext(Dispatchers.IO) {
            val cache = file(account, song)
            read(cache)?.let { onAnalyzed(account, song.id, it.sonic.toFloatArray()); return@withContext it }
            val url = song.streamUrl.let { if (it.startsWith("aurora-yt:")) resolve(it).orEmpty() else it }
            require(url.isNotBlank()) { "This song has no playable source. Reconnect to its library and try again." }
            val job = currentCoroutineContext()[Job]!!
            val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
            var sr = 44100
            var channels = 2
            var frames = 0L
            var square = 0.0
            var samples = 0L
            val binSeconds = 0.02f
            val maxBins = 180_000 // bounded: one hour; longer tracks can still be mixed manually
            val peaks = ArrayList<Float>()
            val energy = ArrayList<Float>()
            var binFrames = 0
            var binPeak = 0f
            var binSquare = 0.0
            var lastProgress = -1
            var sonicSamples = FloatArray(0)
            var sonicCount = 0
            val complete = AudioDecoder.decode(url, { rate, ch ->
                sr = rate; channels = ch.coerceAtLeast(1); sonicSamples = FloatArray(sr * 30)
            }, { pcm, length ->
                var i = 0
                while (i + channels <= length && peaks.size < maxBins) {
                    var frameSquare = 0f
                    var mono = 0f
                    repeat(channels) { channel ->
                        val value = pcm[i + channel] / 32768f
                        binPeak = max(binPeak, abs(value)); frameSquare += value * value
                        mono += value
                    }
                    if (sonicCount < sonicSamples.size) sonicSamples[sonicCount++] = mono / channels
                    binSquare += frameSquare / channels; square += frameSquare / channels; samples++
                    i += channels; frames++; binFrames++
                    if (binFrames >= (sr * binSeconds).toInt().coerceAtLeast(1)) {
                        peaks.add(binPeak); energy.add(sqrt(binSquare / binFrames).toFloat())
                        binFrames = 0; binPeak = 0f; binSquare = 0.0
                    }
                }
                val percent = ((frames.toDouble() / sr / song.durationSec.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 99)
                if (percent != lastProgress) { lastProgress = percent; onProgress(percent / 100f) }
            }, { !job.isActive || peaks.size >= maxBins || android.os.SystemClock.elapsedRealtime() >= deadline }, context)
            job.ensureActive()
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
            // Persist at 10 waveform points/sec, retaining transient peaks across channels.
            val waveform = peaks.chunked(5).map { it.maxOrNull() ?: 0f }
            val result = MixAnalysis(waveform, frames.toFloat() / sr, bpm, confidence,
                (20 * log10(sqrt(square / samples).coerceAtLeast(1e-8))).toFloat(), sonic.toList(),
                energy.chunked(5).map { it.average().toFloat() }, beatOffset, firstAudible, lastAudible)
            val json = JSONObject().put("peaks", JSONArray(waveform)).put("seconds", result.seconds)
                .put("bpm", bpm).put("confidence", confidence).put("rms", result.rmsDb)
                .put("sonic", JSONArray(result.sonic)).put("energy", JSONArray(result.energy)).put("beatOffset", beatOffset)
                .put("audibleStart", firstAudible).put("audibleEnd", lastAudible)
            val out = cache.startWrite()
            try { out.write(json.toString().toByteArray()); cache.finishWrite(out) }
            catch (t: Throwable) { cache.failWrite(out); throw t }
            onAnalyzed(account, song.id, sonic)
            onProgress(1f)
            result
        }
    }

    private fun JSONArray.floats() = (0 until length()).map { getDouble(it).toFloat() }

    private fun estimateTempo(energy: List<Float>): Pair<Float, Float> {
        if (energy.size < 500) return 0f to 0f
        val onset = FloatArray(energy.size) { i -> if (i == 0) 0f else max(0f, energy[i] - energy[i - 1]) }
        var best = 0.0; var bestLag = 0
        // 50 Hz onset envelope: autocorrelation is an estimate, not a beat-grid guarantee.
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
