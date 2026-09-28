package com.aurora.music.mix

import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixTest {
    private fun song(id: String) = Song(id, id, "Artist", "Album", "", 180, streamUrl = "file:///test.wav")
    private fun analysis(bpm: Float = 120f, rms: Float = -12f) = MixAnalysis(listOf(.4f), 180f, bpm, .85f, rms,
        List(51) { if (it == 38) 1f else 0f }, List(1800) { .3f }, .12f, .1f, 179.8f)

    @Test fun completeCollectionKeepsOrderAndRepeatedTracksWithOnlyTwoActiveLayers() {
        val songs = (0..299).map { song("${it % 7}") }
        val result = AutoMixPlanner.plan("300 tracks", songs, songs.associate { it.id to analysis() })
        assertEquals(songs.map { it.id }, result.clips.map { it.song.id })
        assertEquals(300, result.normalized().clips.size)
        assertEquals(2, AutoMixPlanner.maxLayers(result))
        result.clips.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endSec - b.startSec, a.fadeOutSec, .006f)
            assertEquals(a.fadeOutSec, b.fadeInSec, .0001f)
        }
    }
    @Test fun unknownTempoDoesNotInventBeatSynchronizationAndLoudnessNeverBoosts() {
        val songs = listOf(song("a"), song("b"), song("a"))
        val result = AutoMixPlanner.plan("Fallback", songs, mapOf("a" to analysis(0f)))
        assertEquals(3, result.clips.size)
        assertTrue(result.clips.all { it.speed == 1f && it.gainDb <= 0f })
        assertTrue(result.clips[1].transitionNote.contains("unavailable"))
        assertTrue(result.clips[1].startSec < result.clips[0].endSec)
    }
    @Test fun matchingIsRestrainedAndCanBeDisabled() {
        val songs = listOf(song("a"), song("b"))
        val data = mapOf("a" to analysis(120f), "b" to analysis(124f))
        val on = AutoMixPlanner.plan("Match", songs, data)
        assertEquals(120f / 124f, on.clips[1].speed, .001f)
        assertTrue(on.clips[1].bassSwap)
        assertEquals(1f, AutoMixPlanner.plan("No matching", songs, data, false).clips[1].speed, 0f)
    }
    @Test fun arbitraryFftRoundTripsModelSizeWithoutChangingPhase() {
        val n = 7680
        val re = FloatArray(n) { (sin(2 * PI * 131 * it / n) * .3 + cos(2 * PI * 977 * it / n) * .2).toFloat() }
        val expected = re.copyOf(); val im = FloatArray(n)
        val fft = ArbitraryFft(n)
        fft.transform(re, im); fft.transform(re, im, true)
        assertTrue(re.indices.maxOf { abs(re[it] - expected[it]) } < .00001f)
        assertTrue(im.maxOf { abs(it) } < .00001f)
    }
    @Test fun spectrogramPreservesStereoAndTiming() {
        val input = Array(2) { c -> FloatArray(StemSpectrogram.FRAMES) { (sin(2 * PI * (441 + c * 310) * it / 44100) * .2).toFloat() } }
        val stft = StemSpectrogram()
        val output = stft.decode(stft.encode(input))
        for (c in 0..1) {
            val mse = (10000 until input[c].size - 10000).sumOf { (output[c][it] - input[c][it]).toDouble().pow(2) } / (input[c].size - 20000)
            assertTrue("STFT error $mse", sqrt(mse) < .001)
        }
    }
}
