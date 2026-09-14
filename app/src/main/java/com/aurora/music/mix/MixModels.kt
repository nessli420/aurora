package com.aurora.music.mix

import com.aurora.music.model.Song
import java.util.UUID
import kotlin.math.*

enum class FadeCurve(val label: String) { SMOOTH("Smooth"), LINEAR("Linear"), POWER("Equal power"), CUT("Cut") }

data class MixClip(
    val id: String = UUID.randomUUID().toString(),
    val song: Song,
    val startSec: Float = 0f,
    val cueInSec: Float = 0f,
    val cueOutSec: Float = song.durationSec.toFloat(),
    val fadeInSec: Float = 0f,
    val fadeOutSec: Float = 8f,
    val curve: FadeCurve = FadeCurve.SMOOTH,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val bassDb: Float = 0f,
    val midDb: Float = 0f,
    val trebleDb: Float = 0f,
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val fadeInBend: Float = 1f,
    val fadeOutBend: Float = 1f,
    val transitionNote: String = "",
    val bassSwap: Boolean = false,
    val stem: StemMode = StemMode.FULL,
    val stemUri: String = "",
) {
    val durationSec: Float get() = (cueOutSec - cueInSec).coerceAtLeast(0.1f) / speed.coerceIn(0.5f, 2f)
    val endSec: Float get() = startSec + durationSec
    fun normalized(): MixClip {
        val duration = song.durationSec.toFloat().coerceAtLeast(1f)
        val cueIn = cueInSec.finite(0f).coerceIn(0f, duration - 0.1f)
        val cueOut = cueOutSec.finite(duration).coerceIn(cueIn + 0.1f, duration)
        val rate = speed.finite(1f).coerceIn(0.5f, 2f)
        val length = (cueOut - cueIn) / rate
        return copy(startSec = startSec.finite(0f).coerceAtLeast(0f), cueInSec = cueIn, cueOutSec = cueOut,
            speed = rate, fadeInSec = fadeInSec.finite(0f).coerceIn(0f, length),
            fadeOutSec = fadeOutSec.finite(0f).coerceIn(0f, length), gainDb = gainDb.finite(0f).coerceIn(-60f, 0f),
            pan = pan.finite(0f).coerceIn(-1f, 1f), bassDb = bassDb.finite(0f).coerceIn(-24f, 6f),
            midDb = midDb.finite(0f).coerceIn(-24f, 6f), trebleDb = trebleDb.finite(0f).coerceIn(-24f, 6f),
            pitchSemitones = pitchSemitones.finite(0f).coerceIn(-12f, 12f),
            fadeInBend = fadeInBend.finite(1f).coerceIn(0.25f, 4f), fadeOutBend = fadeOutBend.finite(1f).coerceIn(0.25f, 4f))
    }
}

data class MixProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled mix",
    val clips: List<MixClip> = emptyList(),
    val masterDb: Float = -1f,
    val protectPeaks: Boolean = true,
    val loop: Boolean = false,
) {
    val durationSec: Float get() = clips.maxOfOrNull { it.endSec } ?: 0f
    fun normalized() = copy(name = name.trim().take(100).ifEmpty { "Untitled mix" },
        clips = clips.map { it.normalized() }, masterDb = masterDb.finite(-1f).coerceIn(-60f, 0f))
    companion object { const val MAX_TRACKS = 10000; const val MAX_LAYERS = 8 }
}

enum class StemMode(val label: String) { FULL("Original"), VOCALS("Vocals only"), BACKING("Backing only") }

data class MixCollectionRequest(val kind: String, val id: String, val name: String, val token: String = UUID.randomUUID().toString())

private fun Float.finite(fallback: Float) = if (isFinite()) this else fallback

object MixMath {
    fun rise(progress: Float, curve: String): Float {
        val t = progress.coerceIn(0f, 1f)
        return when (curve) {
            "LINEAR" -> t
            "POWER" -> sin(t * PI.toFloat() / 2)
            "CUT" -> if (t < 0.5f) 0f else 1f
            else -> t * t * (3 - 2 * t)
        }
    }
    fun crossfade(t: Float, curve: String, protected: Boolean): Pair<Float, Float> {
        val incoming = rise(t, curve)
        val outgoing = if (curve == "POWER") rise(1 - t, curve) else 1 - incoming
        val divisor = if (protected) max(1f, incoming + outgoing) else 1f
        return outgoing / divisor to incoming / divisor
    }
    fun amplitude(db: Float) = 10.0.pow(db / 20.0).toFloat()
    fun envelope(clip: MixClip, positionSec: Float): Float {
        val t = positionSec - clip.startSec
        if (t < 0f || t >= clip.durationSec || clip.muted) return 0f
        val a = if (clip.fadeInSec > 0) rise(t / clip.fadeInSec, clip.curve.name).pow(clip.fadeInBend) else 1f
        val b = if (clip.fadeOutSec > 0) rise((clip.durationSec - t) / clip.fadeOutSec, clip.curve.name).pow(clip.fadeOutBend) else 1f
        return min(a, b) * amplitude(clip.gainDb)
    }
    fun gains(project: MixProject, positionSec: Float): List<Float> {
        val solo = project.clips.any { it.solo }
        val raw = project.clips.map { if (solo && !it.solo) 0f else envelope(it, positionSec) }
        val divisor = if (project.protectPeaks) max(1f, raw.sum()) else 1f
        return raw.map { it / divisor * amplitude(project.masterDb) }
    }
}
