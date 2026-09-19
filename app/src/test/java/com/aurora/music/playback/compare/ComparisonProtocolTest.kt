package com.aurora.music.playback.compare

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ComparisonProtocolTest {
    @Test fun alignmentAndLevelMatchingUseTheSameStereoFrames() {
        val rate = 8000
        val source = DoubleArray(rate * 4) { i -> .3 * sin(2 * PI * (if (i % 2 == 0) 431 else 733) * (i / 2) / rate) }
        val delayed = DoubleArray(source.size + 114) { i -> if (i < 114) 0.0 else source[i - 114] * 3 }
        val matched = ComparisonMatching.match(rate, source, delayed, latencyB = 57)
        assertEquals(source.size, matched.a.size)
        assertTrue(abs(matched.levels.residualDb) < .00001)
        assertArrayEquals(matched.a, matched.b, 1e-7f)
        assertEquals(1.0, matched.levels.gainA, 1e-12)
        assertEquals(1.0 / 3, matched.levels.gainB, 1e-12)
    }

    @Test fun peakProtectionAttenuatesBothWithoutChangingTheMatch() {
        val a = DoubleArray(16000) { if (it % 43 == 0) 12.0 else .01 }
        val b = DoubleArray(16000) { a[it] * .5 }
        val result = ComparisonMatching.match(8000, a, b)
        assertTrue(result.levels.peak <= .891251)
        assertTrue(result.levels.gainA <= 1 && result.levels.gainB <= 1)
        assertArrayEquals(result.a, result.b, 1e-7f)
    }

    @Test fun silenceAndNonfiniteOutputCannotProduceAnApparentlyValidMatch() {
        assertThrows(IllegalArgumentException::class.java) { ComparisonMatching.match(8000, DoubleArray(16000), DoubleArray(16000)) }
        assertThrows(IllegalArgumentException::class.java) {
            ComparisonMatching.match(8000, DoubleArray(16000) { .1 }, DoubleArray(16000) { Double.NaN })
        }
    }

    @Test fun binomialResultsMatchExactKnownCases() {
        assertEquals(1.0, AbxTrial.chanceProbability(16, 0), 0.0)
        assertEquals(1.0 / 65536, AbxTrial.chanceProbability(16, 16), 0.0)
        assertEquals(17.0 / 65536, AbxTrial.chanceProbability(16, 15), 0.0)
        assertEquals(.0384063720703125, AbxTrial.chanceProbability(16, 12), 1e-15)
    }

    @Test fun trialsStayHiddenAndInterruptedRunsHaveNoProbability() {
        val trial = AbxTrial(BooleanArray(8) { it % 2 == 0 })
        assertThrows(IllegalStateException::class.java) { trial.result() }
        trial.guess(true); trial.guess(true); trial.interrupt()
        assertEquals(1, trial.result().correct)
        assertNull(trial.result().probability)
        assertTrue(trial.result().interrupted)
        assertEquals(listOf(AbxAnswer(1, true, true), AbxAnswer(2, true, false)), trial.result().trials)
        assertThrows(IllegalStateException::class.java) { trial.guess(true) }
    }

    @Test fun fixedTrialProtocolCompletesExactlyOnce() {
        val trial = AbxTrial(BooleanArray(8) { true })
        repeat(8) { trial.guess(true) }
        assertTrue(trial.complete)
        assertEquals(8, trial.result().correct)
        assertEquals(1.0 / 256, trial.result().probability!!, 0.0)
        assertThrows(IllegalStateException::class.java) { trial.guess(false) }
    }

    @Test fun switchingIdenticalPathsDoesNotRevealTheSelection() {
        val rate = 8000
        val input = DoubleArray(rate * 4) { sin(it * .034) * .2 }
        val audio = ComparisonMatching.match(rate, input, input)
        val fixed = ComparisonMixer(audio); val switched = ComparisonMixer(audio)
        val expected = FloatArray(514); val actual = FloatArray(514)
        repeat(70) { index ->
            if (index % 3 == 0) {
                fixed.select(true)
                switched.select(index % 2 == 0)
            }
            fixed.render(expected, 257); switched.render(actual, 257)
            assertArrayEquals(expected, actual, 1e-7f)
        }
    }

    @Test fun oppositePolarityUsesTheSameSwitchEnvelopeAsSelectingTheCurrentPath() {
        val input = DoubleArray(16000) { .2 }
        val audio = ComparisonMatching.match(8000, input, input.map { -it }.toDoubleArray())
        val same = ComparisonMixer(audio); val changed = ComparisonMixer(audio)
        val a = FloatArray(1024); val b = FloatArray(1024)
        same.render(a, 512); changed.render(b, 512)
        same.select(true); changed.select(false)
        same.render(a, 512); changed.render(b, 512)
        a.indices.forEach { assertEquals(abs(a[it]), abs(b[it]), 0f) }
    }
}
