package com.aurora.music.mix

import com.aurora.music.model.Song
import kotlin.math.*

/** Deterministic, editable transitions; preserves album/playlist order and repeated occurrences. */
object AutoMixPlanner {
    fun plan(name: String, songs: List<Song>, analyses: Map<String, MixAnalysis>, tempoMatch: Boolean = true): MixProject {
        require(songs.isNotEmpty()) { "This collection has no playable tracks." }
        val measured = analyses.values.map { it.rmsDb }.filter { it.isFinite() && it > -45 }.sorted()
        val target = min(-14f, measured.getOrNull(measured.size / 2) ?: -14f)
        val clips = mutableListOf<MixClip>()
        songs.forEach { song ->
            val a = analyses[song.id]
            val previous = clips.lastOrNull()
            val b = previous?.let { analyses[it.song.id] }
            val duration = song.durationSec.toFloat().coerceAtLeast(1f)
            var cue = (a?.audibleStart ?: 0f).coerceIn(0f, min(12f, duration / 4))
            val out = (a?.audibleEnd ?: duration).coerceIn(max(cue + .1f, duration - 12), duration)
            var speed = 1f
            val reliable = a != null && b != null && a.bpm > 0 && b.bpm > 0 && a.confidence >= .15f && b.confidence >= .15f
            val ratio = if (reliable) b!!.bpm * previous!!.speed / a!!.bpm else 1f
            val compatibleTempo = reliable && ratio in .94f..1.06f
            if (tempoMatch && compatibleTempo) speed = ratio.coerceIn(.94f, 1.06f)
            if (compatibleTempo && tempoMatch) {
                val sourceBeat = 60f / a!!.bpm
                cue = (a.beatOffset + ceil((cue - a.beatOffset) / sourceBeat) * sourceBeat).coerceIn(cue, min(cue + sourceBeat, out - .1f))
            }
            val harmony = if (a != null && b != null) harmonicSimilarity(a, b) else 0f
            val beat = if (compatibleTempo) 60f / (b!!.bpm * previous!!.speed) else 0f
            val beats = if (harmony > .7f) 16 else 8
            var overlap = if (beat > 0) beat * beats else if (a == null || b == null) 5f else 3f
            overlap = min(overlap, min(previous?.durationSec?.div(3) ?: 0f, (out - cue) / speed / 3)).coerceAtLeast(0f)
            // Anchor the outgoing boundary to its measured beat phase without discarding musical phrases.
            var prev = previous
            if (prev != null && compatibleTempo) {
                val sourceBeat = 60 / b!!.bpm
                val aligned = b.beatOffset + floor((prev.cueOutSec - b.beatOffset) / sourceBeat) * sourceBeat
                if (aligned > prev.cueInSec + overlap * prev.speed + 1 && prev.cueOutSec - aligned < sourceBeat)
                    prev = prev.copy(cueOutSec = aligned)
            }
            if (prev != null) clips[clips.lastIndex] = prev.copy(fadeOutSec = overlap, bassSwap = compatibleTempo)
            val note = when {
                previous == null -> "Opening track"
                a == null || b == null -> "Gentle fade · analysis unavailable; retry Auto mix to refine"
                compatibleTempo -> "$beats beats · ${if (abs(speed - 1) > .001f) "tempo matched" else "steady tempo"} · bass exchange"
                else -> "Short smooth fade · different tempos"
            }
            clips += MixClip(song = song, startSec = if (prev == null) 0f else prev.endSec - overlap,
                cueInSec = cue, cueOutSec = out, fadeInSec = overlap, fadeOutSec = 0f,
                speed = speed, gainDb = if (a != null && a.rmsDb > -45) (target - a.rmsDb).coerceIn(-12f, 0f) else 0f,
                transitionNote = note, bassSwap = compatibleTempo)
        }
        return MixProject(name = name, clips = clips).normalized()
    }

    private fun harmonicSimilarity(a: MixAnalysis, b: MixAnalysis): Float {
        if (a.sonic.size < 13 || b.sonic.size != a.sonic.size) return 0f
        val start = a.sonic.size - 13
        var dot = 0f; var aa = 0f; var bb = 0f
        for (i in start until start + 12) { val x = a.sonic[i]; val y = b.sonic[i]; dot += x*y; aa += x*x; bb += y*y }
        return dot / sqrt(aa * bb).coerceAtLeast(1e-9f)
    }

    fun maxLayers(project: MixProject): Int {
        val points = project.clips.flatMap { listOf(it.startSec to 1, it.endSec to -1) }
            .sortedWith(compareBy<Pair<Float, Int>> { it.first }.thenBy { it.second })
        var count = 0; var most = 0
        points.forEach { count += it.second; most = max(most, count) }
        return most
    }
}
