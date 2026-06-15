package com.aurora.music.data

import android.net.Uri
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device sonic-similarity engine. Analyzes the decodable library (local files + downloads) into
 * [SonicFeatures] vectors via a background scan, and builds "sonically similar" radio queues by
 * z-scoring every dimension across the analyzed set and ranking by cosine similarity to a seed.
 *
 * v1 scope: only local/downloaded tracks can be analyzed (server-only streams aren't decodable
 * on-device); a sonic radio from a non-analyzable seed falls back to the backend's own radio.
 */
class SonicEngine(
    private val localLibrary: LocalLibrary,
    private val downloads: DownloadManager,
    private val store: SonicStore,
) {
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    val analyzedCount: StateFlow<Int> get() = store.count

    /** All tracks we can decode for analysis: on-device library + downloaded files. */
    private suspend fun decodable(): List<Song> {
        localLibrary.ensureLoaded()
        val local = localLibrary.songs.filter { it.path.isNotBlank() }
        val dl = downloads.downloads.value.values.map { it.toSong() }
        return (local + dl).distinctBy { it.id }
    }

    private fun decodePath(song: Song): String? = when {
        song.path.isNotBlank() -> song.path
        song.streamUrl.startsWith("file://") -> Uri.parse(song.streamUrl).path
        else -> downloads.get(song.id)?.audioPath
    }

    /** Analyze every not-yet-analyzed decodable track in the background, in parallel across cores.
     *  Idempotent. Persists in batches (every 40 tracks + at the end / on cancel). */
    fun scan() {
        if (job?.isActive == true) return
        job = scope.launch {
            val songs = decodable()
            store.retainOnly(songs.map { it.id }.toSet())   // drop vectors for tracks that vanished
            val todo = songs.filter { !store.has(it.id) }
            if (todo.isEmpty()) { _progress.value = Progress(false, 0, 0); return@launch }
            _progress.value = Progress(true, 0, todo.size)

            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            val workers = min(4, max(1, Runtime.getRuntime().availableProcessors() - 1))
            try {
                coroutineScope {
                    repeat(workers) {
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                val i = next.getAndIncrement()
                                if (i >= todo.size) break
                                val s = todo[i]
                                val path = decodePath(s)
                                if (path != null) {
                                    val vec = runCatching { SonicFeatures.analyze(path) { !isActive } }.getOrNull()
                                    if (vec != null) store.putDeferred(s.id, vec)
                                }
                                val d = done.incrementAndGet()
                                _progress.value = Progress(true, d, todo.size, s.title)
                                if (d % 40 == 0) store.flush()
                            }
                        }
                    }
                }
            } finally {
                store.flush()  // persist whatever finished, even on cancel
            }
            _progress.value = Progress(false, done.get(), todo.size)
        }
    }

    fun cancel() { job?.cancel() }

    /**
     * Build a sonically-similar radio queue seeded by [seed]: the seed first, then the nearest
     * neighbours by z-scored cosine similarity. Returns empty if the seed can't be analyzed or there
     * aren't enough analyzed tracks to compare against (caller should fall back to backend radio).
     */
    suspend fun buildRadio(seed: Song, count: Int = 40): List<Song> = withContext(Dispatchers.Default) {
        val pool = decodable()
        val byId = pool.associateBy { it.id }

        var seedVec = store.get(seed.id)
        if (seedVec == null) {
            val p = decodePath(seed) ?: return@withContext emptyList()
            seedVec = runCatching { SonicFeatures.analyze(p) }.getOrNull() ?: return@withContext emptyList()
            store.put(seed.id, seedVec)
        }

        // Only compare against vectors we can still resolve to a playable Song.
        val vectors = store.snapshot().filterKeys { it in byId }
        if (vectors.size < 2) return@withContext emptyList()

        val dims = SonicFeatures.DIMS
        val mean = DoubleArray(dims)
        val std = DoubleArray(dims)
        for (v in vectors.values) for (d in 0 until dims) mean[d] += v[d]
        for (d in 0 until dims) mean[d] /= vectors.size
        for (v in vectors.values) for (d in 0 until dims) { val x = v[d] - mean[d]; std[d] += x * x }
        for (d in 0 until dims) std[d] = sqrt(std[d] / vectors.size).coerceAtLeast(1e-6)

        fun z(v: FloatArray): DoubleArray = DoubleArray(dims) { (v[it] - mean[it]) / std[it] }
        val sz = z(seedVec)

        val ranked = vectors.entries
            .asSequence()
            .filter { it.key != seed.id }
            .map { it.key to cosine(sz, z(it.value)) }
            .sortedByDescending { it.second }
            .take(count)
            .mapNotNull { byId[it.first] }
            .toList()

        if (ranked.isEmpty()) emptyList() else listOf(seed) + ranked
    }

    // ── Tempo / key (auto-DJ) ───────────────────────────────────────────────

    data class TrackKey(val bpm: Int, val camelot: String, val name: String)

    /** BPM + musical key (Camelot + name) derived from a stored vector, or null if not analyzed. */
    fun keyInfo(songId: String): TrackKey? = store.get(songId)?.let { v ->
        val (tonic, major) = estimateKey(v)
        val cam = (if (major) MAJOR_CAM[tonic] else MINOR_CAM[tonic]).toString() + (if (major) "B" else "A")
        TrackKey(bpmOf(v), cam, NOTE_NAMES[tonic] + (if (major) "" else "m"))
    }

    private fun bpmOf(v: FloatArray): Int = (60f + v[SonicFeatures.DIMS - 1] * 120f).toInt().coerceIn(40, 220)

    /** Krumhansl-Schmuckler: correlate the 12 chroma bins against rotated major/minor profiles. */
    private fun estimateKey(v: FloatArray): Pair<Int, Boolean> {
        val chromaStart = SonicFeatures.DIMS - 1 - 12   // 12 chroma bins precede the tempo dim
        val chroma = DoubleArray(12) { v[chromaStart + it].toDouble() }
        var bestCorr = -2.0; var bestTonic = 0; var bestMajor = true
        for (mode in 0..1) {
            val prof = if (mode == 0) MAJOR_PROFILE else MINOR_PROFILE
            for (r in 0 until 12) {
                val rotated = DoubleArray(12) { chroma[(r + it) % 12] }
                val c = pearson(prof, rotated)
                if (c > bestCorr) { bestCorr = c; bestTonic = r; bestMajor = mode == 0 }
            }
        }
        return bestTonic to bestMajor
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) { val xa = a[i] - ma; val xb = b[i] - mb; num += xa * xb; da += xa * xa; db += xb * xb }
        val den = kotlin.math.sqrt(da * db)
        return if (den > 0) num / den else 0.0
    }

    /** Encode Camelot as number*10 + (1 major / 0 minor) for cheap adjacency scoring. */
    private fun camelotCode(v: FloatArray): Int {
        val (tonic, major) = estimateKey(v)
        return (if (major) MAJOR_CAM[tonic] else MINOR_CAM[tonic]) * 10 + (if (major) 1 else 0)
    }

    private fun harmonicScore(a: Int, b: Int): Double {
        val numA = a / 10; val letA = a % 10
        val numB = b / 10; val letB = b % 10
        val diff = kotlin.math.abs(numA - numB)
        val adjacent = diff == 1 || diff == 11   // wheel wraps 12<->1
        return when {
            numA == numB && letA == letB -> 1.0          // same key
            numA == numB -> 0.85                          // relative major/minor
            letA == letB && adjacent -> 0.75             // neighbour on the wheel
            else -> 0.25
        }
    }

    /**
     * Build a harmonic auto-DJ set: a greedy walk from [seed] that picks each next track by blending
     * sonic similarity (6.1 cosine), Camelot key compatibility, and tempo proximity — so the set flows
     * in key and BPM. Returns empty if the seed isn't analyzable or too little is analyzed.
     */
    suspend fun buildAutoDj(seed: Song, count: Int = 40): List<Song> = withContext(Dispatchers.Default) {
        val pool = decodable()
        val byId = pool.associateBy { it.id }
        var seedVec = store.get(seed.id)
        if (seedVec == null) {
            val p = decodePath(seed) ?: return@withContext emptyList()
            seedVec = runCatching { SonicFeatures.analyze(p) }.getOrNull() ?: return@withContext emptyList()
            store.put(seed.id, seedVec)
        }
        val vectors = store.snapshot().filterKeys { it in byId }
        if (vectors.size < 2) return@withContext emptyList()

        val dims = SonicFeatures.DIMS
        val mean = DoubleArray(dims); val std = DoubleArray(dims)
        for (v in vectors.values) for (d in 0 until dims) mean[d] += v[d]
        for (d in 0 until dims) mean[d] /= vectors.size
        for (v in vectors.values) for (d in 0 until dims) { val x = v[d] - mean[d]; std[d] += x * x }
        for (d in 0 until dims) std[d] = kotlin.math.sqrt(std[d] / vectors.size).coerceAtLeast(1e-6)

        val zmap = HashMap<String, DoubleArray>(vectors.size)
        val bpmMap = HashMap<String, Int>(vectors.size)
        val camMap = HashMap<String, Int>(vectors.size)
        for ((id, v) in vectors) {
            zmap[id] = DoubleArray(dims) { (v[it] - mean[it]) / std[it] }
            bpmMap[id] = bpmOf(v)
            camMap[id] = camelotCode(v)
        }

        val chosen = ArrayList<String>(count)
        val used = HashSet<String>()
        chosen.add(seed.id); used.add(seed.id)
        var curId = seed.id
        while (chosen.size < count && used.size < vectors.size) {
            val cz = zmap[curId] ?: break
            val cbpm = bpmMap[curId] ?: 120
            val ccam = camMap[curId] ?: 0
            var bestId: String? = null; var bestScore = -1e9
            for ((id, z) in zmap) {
                if (id in used) continue
                val sim = (cosine(cz, z) + 1.0) / 2.0                                   // 0..1
                val harm = harmonicScore(ccam, camMap[id] ?: 0)                          // 0..1
                val tempo = 1.0 - (kotlin.math.abs(cbpm - (bpmMap[id] ?: cbpm)) / 16.0).coerceIn(0.0, 1.0)
                val score = 0.45 * sim + 0.35 * harm + 0.20 * tempo
                if (score > bestScore) { bestScore = score; bestId = id }
            }
            val nb = bestId ?: break
            chosen.add(nb); used.add(nb); curId = nb
        }
        chosen.mapNotNull { byId[it] }
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val den = sqrt(na) * sqrt(nb)
        return if (den > 0) dot / den else 0.0
    }

    private companion object {
        // Krumhansl-Schmuckler key profiles (relative weights per scale degree).
        val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        // Camelot wheel numbers by pitch class (0=C). Major keys = "B" side, minor = "A" side.
        val MAJOR_CAM = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        val MINOR_CAM = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
