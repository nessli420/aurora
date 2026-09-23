package com.aurora.music.playback

import android.content.Context
import android.os.SystemClock
import com.aurora.music.data.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Compares a short song excerpt with the video's own audio without retaining decoded PCM. */
internal class VideoAudioAligner(private val context: Context, private val resolver: YoutubeResolver) {
    suspend fun offsetMs(songUrl: String, videoId: String): Long? = withContext(Dispatchers.IO) {
        if (songUrl.isBlank() || songUrl.startsWith("aurora-yt:")) return@withContext null
        val job = currentCoroutineContext()[Job] ?: return@withContext null
        val song = envelope(songUrl, SONG_SECONDS, job) ?: return@withContext null
        val videoAudioUrl = resolver.resolvePlayback(videoId, 360)?.url ?: return@withContext null
        val video = envelope(videoAudioUrl, VIDEO_SECONDS, job) ?: return@withContext null
        VideoAudioOffset.find(song, video)
    }

    private fun envelope(url: String, seconds: Int, job: Job): FloatArray? {
        val values = ArrayList<Float>(seconds * VideoAudioOffset.BINS_PER_SECOND)
        var channels = 0
        var framesPerBin = 0
        var frames = 0
        var square = 0.0
        val deadline = SystemClock.elapsedRealtime() + 45_000
        AudioDecoder.decode(url, { rate, count ->
            channels = count.coerceAtLeast(1)
            framesPerBin = (rate / VideoAudioOffset.BINS_PER_SECOND).coerceAtLeast(1)
        }, { pcm, length ->
            var i = 0
            while (i + channels <= length && values.size < seconds * VideoAudioOffset.BINS_PER_SECOND) {
                var mono = 0.0
                repeat(channels) { mono += pcm[i + it] / 32768.0 }
                mono /= channels
                square += mono * mono
                frames++
                if (frames >= framesPerBin) {
                    values.add(sqrt(square / frames).toFloat())
                    frames = 0
                    square = 0.0
                }
                i += channels
            }
        }, {
            !job.isActive || values.size >= seconds * VideoAudioOffset.BINS_PER_SECOND ||
                SystemClock.elapsedRealtime() >= deadline
        }, context)
        return values.takeIf { job.isActive && it.size >= VideoAudioOffset.MIN_BINS }?.toFloatArray()
    }

    private companion object {
        const val SONG_SECONDS = 15
        const val VIDEO_SECONDS = 45
    }
}

/** A positive offset means that the video contains an intro before the song begins. */
internal object VideoAudioOffset {
    const val BINS_PER_SECOND = 50
    const val MIN_BINS = 8 * BINS_PER_SECOND

    fun find(songRms: FloatArray, videoRms: FloatArray): Long? {
        if (songRms.size < MIN_BINS || videoRms.size < MIN_BINS) return null
        fun energy(rms: FloatArray) = FloatArray(rms.size) { ln1p(rms[it].coerceAtLeast(0f) * 64f) }
        fun onset(e: FloatArray) = FloatArray(e.size) { if (it == 0) 0f else max(0f, e[it] - e[it - 1]) }
        val song = energy(songRms)
        val video = energy(videoRms)
        if (song.maxOrNull()!! - song.minOrNull()!! < 0.08f) return null
        val songOnsets = onset(song)
        val videoOnsets = onset(video)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestOffset = 0
        val scores = ArrayList<Pair<Int, Double>>()
        for (offset in -(song.size - MIN_BINS)..(video.size - MIN_BINS)) {
            val start = max(0, -offset)
            val end = min(song.size, video.size - offset)
            if (end - start < MIN_BINS) continue
            val energyScore = correlation(song, video, start, offset, end)
            val onsetScore = correlation(songOnsets, videoOnsets, start, offset, end)
            val score = (energyScore * 0.55 + onsetScore * 0.45) *
                sqrt((end - start).toDouble() / song.size)
            scores.add(offset to score)
            if (score > bestScore) { bestScore = score; bestOffset = offset }
        }
        val second = scores.asSequence().filter { kotlin.math.abs(it.first - bestOffset) > BINS_PER_SECOND }
            .maxOfOrNull { it.second } ?: Double.NEGATIVE_INFINITY
        if (bestScore < 0.52 || bestScore < 0.72 && bestScore - second < 0.08) return null
        return bestOffset * 1_000L / BINS_PER_SECOND
    }

    private fun correlation(a: FloatArray, b: FloatArray, start: Int, offset: Int, end: Int): Double {
        var sumA = 0.0; var sumB = 0.0
        var sumAA = 0.0; var sumBB = 0.0; var sumAB = 0.0
        for (i in start until end) {
            val x = a[i].toDouble()
            val y = b[i + offset].toDouble()
            sumA += x; sumB += y; sumAA += x * x; sumBB += y * y; sumAB += x * y
        }
        val n = (end - start).toDouble()
        val varA = sumAA - sumA * sumA / n
        val varB = sumBB - sumB * sumB / n
        if (varA < 1e-8 || varB < 1e-8) return 0.0
        return ((sumAB - sumA * sumB / n) / sqrt(varA * varB)).coerceIn(-1.0, 1.0)
    }
}
