package com.aurora.music.mix

import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MixMathTest {
    private val song = Song("test", "Test", "", "", "", 20)
    @Test fun protectedCrossfadesNeverExceedFullScaleForCorrelatedAudio() {
        for (curve in FadeCurve.entries) {
            var lastOut = 1f; var lastIn = 0f
            for (step in 0..10000) {
                val (out, incoming) = MixMath.crossfade(step / 10000f, curve.name, true)
                assertTrue("$curve at $step clips identical full-scale tracks", out + incoming <= 1.000001f)
                assertTrue(out >= 0 && incoming >= 0)
                assertTrue("outgoing rises", out <= lastOut + 0.000001f)
                assertTrue("incoming falls", incoming >= lastIn - 0.000001f)
                lastOut = out; lastIn = incoming
            }
            assertEquals(0f, lastOut, 0.000001f); assertEquals(1f, lastIn, 0.000001f)
        }
    }
    @Test fun unprotectedPowerCurvePreservesPower() {
        for (i in 0..1000) {
            val (a, b) = MixMath.crossfade(i / 1000f, "POWER", false)
            assertEquals(1f, a * a + b * b, 0.000001f)
        }
    }
    @Test fun eightOverlappingTracksRespectHeadroomAndMuteSolo() {
        val clips = (0..7).map { MixClip(song = song, startSec = it.toFloat(), fadeInSec = 3f, fadeOutSec = 5f, curve = FadeCurve.POWER) }
        val project = MixProject(clips = clips)
        for (t in 0..3000) {
            val gains = MixMath.gains(project, t / 100f)
            assertTrue(gains.all { it.isFinite() && it >= 0f })
            assertTrue(gains.sum() <= MixMath.amplitude(-1f) + 0.000001f)
        }
        val solo = project.copy(clips = clips.mapIndexed { i, c -> c.copy(solo = i == 3) })
        val levels = MixMath.gains(solo, 10f)
        assertTrue(levels[3] > 0f)
        levels.forEachIndexed { i, v -> if (i != 3) assertEquals(0f, v, 0f) }
        assertEquals(0f, MixMath.envelope(clips.first().copy(muted = true), 4f), 0f)
    }
    @Test fun cuesAndTempoHaveOneConsistentTimeline() {
        val c = MixClip(song = song, startSec = 4f, cueInSec = 2f, cueOutSec = 18f, speed = 2f, fadeOutSec = 1f)
        assertEquals(8f, c.durationSec, 0f); assertEquals(12f, c.endSec, 0f)
        assertEquals(0f, MixMath.envelope(c, 3.99f), 0f)
        assertTrue(MixMath.envelope(c, 4f) > 0f)
        assertEquals(0f, MixMath.envelope(c, 12f), 0f)
    }
    @Test fun malformedSettingsAreFiniteAndBounded() {
        val c = MixClip(song = song, startSec = Float.NaN, cueInSec = 50f, cueOutSec = -3f, speed = 0f, gainDb = Float.POSITIVE_INFINITY).normalized()
        assertTrue(c.startSec.isFinite()); assertTrue(c.durationSec > 0f); assertTrue(c.cueOutSec <= 20f)
        assertTrue(c.speed >= 0.5f); assertTrue(c.gainDb <= 0f)
    }
}
