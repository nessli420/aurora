package com.aurora.music.playback

import android.content.Context
import com.aurora.music.data.Chromaprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal class VideoAudioAligner(private val context: Context, private val resolver: YoutubeResolver) {
    suspend fun plan(songUrl: String, videoId: String): VideoSyncPlan? = withContext(Dispatchers.IO) {
        if (songUrl.isBlank() || songUrl.startsWith("aurora-yt:") || !Chromaprint.available) return@withContext null
        val job = currentCoroutineContext()[Job] ?: return@withContext null
        val song = Chromaprint.rawFingerprint(songUrl, 45, context) { !job.isActive } ?: return@withContext null
        val videoUrl = resolver.resolvePlayback(videoId, 360)?.url ?: return@withContext null
        val video = Chromaprint.rawFingerprint(videoUrl, 65, context) { !job.isActive } ?: return@withContext null
        if (song.itemDurationMs != video.itemDurationMs) return@withContext null
        val anchors = VideoFingerprintMatch.anchors(song.values, video.values, song.itemDurationMs)
        VideoFingerprintMatch.plan(anchors)
    }
}

internal data class VideoSyncSegment(val audioStartMs: Long, val offsetMs: Long)

internal data class VideoSyncPlan(val segments: List<VideoSyncSegment>) {
    fun offsetAt(audioMs: Long): Long = segments.lastOrNull { it.audioStartMs <= audioMs }?.offsetMs ?: 0L
}

internal object VideoFingerprintMatch {
    data class Anchor(val songMs: Long, val offsetMs: Long, val error: Double, val margin: Double)

    fun anchors(song: IntArray, video: IntArray, itemMs: Int): List<Anchor> {
        if (itemMs <= 0) return emptyList()
        val window = (10_000.0 / itemMs).roundToInt()
        val stride = (5_000.0 / itemMs).roundToInt().coerceAtLeast(1)
        if (song.size < window || video.size < window) return emptyList()
        return (0..song.size - window step stride).map { songStart ->
            val results = ArrayList<Pair<Int, Double>>()
            for (videoStart in 0..video.size - window) {
                val offset = (videoStart - songStart) * itemMs
                if (offset < -15_000 || offset > 45_000) continue
                var distance = 0
                for (i in 0 until window) {
                    distance += Integer.bitCount(song[songStart + i] xor video[videoStart + i])
                }
                results.add(videoStart to distance.toDouble() / window)
            }
            val best = results.minByOrNull { it.second } ?: return@map null
            val runnerUp = results.asSequence().filter { abs(it.first - best.first) * itemMs >= 2_000 }
                .minOfOrNull { it.second } ?: 32.0
            Anchor(songStart.toLong() * itemMs, (best.first - songStart).toLong() * itemMs,
                best.second, runnerUp - best.second)
        }.filterNotNull()
    }

    fun plan(anchors: List<Anchor>): VideoSyncPlan? {
        val strong = anchors.filter { it.error <= 9.0 && it.margin >= 0.8 }
        if (strong.size < 2) return null
        val groups = ArrayList<List<Anchor>>()
        var run = ArrayList<Anchor>()
        for (anchor in strong) {
            if (run.isNotEmpty() && (abs(anchor.offsetMs - run.last().offsetMs) > 900 ||
                    anchor.songMs - run.last().songMs > 12_000)) {
                groups.add(run)
                run = ArrayList()
            }
            run.add(anchor)
        }
        if (run.isNotEmpty()) groups.add(run)
        val supported = groups.filter { group ->
            group.size >= 2 && (group.sumOf { it.margin } >= 4.0 ||
                group.size >= 4 && group.map { it.error }.average() <= 3.0)
        }
        if (supported.isEmpty()) return null
        val strongest = supported.maxBy { it.size }
        val credible = supported.filter { it.size * 3 >= strongest.size || it === strongest }
        val segments = ArrayList<VideoSyncSegment>()
        for (group in credible) {
            val offset = group.map { it.offsetMs }.sorted()[group.size / 2]
            if (segments.isEmpty()) {
                if (group.first().songMs > 5_000) segments.add(VideoSyncSegment(0, 0))
                if (offset != segments.lastOrNull()?.offsetMs) {
                    segments.add(VideoSyncSegment(
                        if (group.first().songMs <= 5_000) 0 else group.first().songMs, offset))
                }
            } else if (offset - segments.last().offsetMs >= 1_000) {
                segments.add(VideoSyncSegment(group.first().songMs, offset))
            }
        }
        return VideoSyncPlan(segments)
    }
}
