package com.aurora.music.playback

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PcmSpectrumTest {
    private fun feed(tap: PcmSpectrumTap, gain: Double, start: Long = 1_000_000, frames: Int = 2048) {
        repeat(frames) { i ->
            val sample = gain * sin(2 * PI * 64 * i / 2048)
            tap.observe(sample, -sample, 48_000, if (start == C.TIME_UNSET) start else start + i * 1_000_000L / 48_000)
        }
    }

    @Test fun alignedAntiphaseStereoRetainsPowerAndMeasuresAttenuation() {
        val before = PcmSpectrumTap(); val after = PcmSpectrumTap()
        feed(before, .5); feed(after, .25)
        val result = requireNotNull(PcmSpectrumAnalyzer.aligned(before.windows(), after.windows()))
        assertEquals(20 * log10(.5), result.beforeDb[64].toDouble(), .0001)
        assertEquals(20 * log10(.25), requireNotNull(result.afterDb)[64].toDouble(), .0001)
        assertTrue(result.beforeDb[70] < -100f)
    }

    @Test fun mismatchedMissingPartialAndResetWindowsNeverOverlay() {
        val before = PcmSpectrumTap(); val after = PcmSpectrumTap()
        feed(before, .5); feed(after, .25, 2_000_000)
        assertNull(PcmSpectrumAnalyzer.aligned(before.windows(), after.windows()))
        after.reset(); feed(after, .25, C.TIME_UNSET)
        assertTrue(after.windows().isEmpty())
        after.reset(); feed(after, .25, frames = 2047)
        assertTrue(after.windows().isEmpty())
        before.reset(); assertTrue(before.windows().isEmpty())
    }

    @Test fun boundedHistoryRetainsOnlyCompleteRecentWindows() {
        val tap = PcmSpectrumTap()
        feed(tap, .5, frames = 2048 * 30 + 1)
        assertEquals(7, tap.windows().size)
        assertTrue(tap.windows().all { it.left.size == 2048 && it.timeUs > 1_500_000 })
    }

    @Test fun independentResetRealignsContiguousSamplesAcrossWindowBoundaries() {
        val before = PcmSpectrumTap(); val after = PcmSpectrumTap()
        feedFrames(before, .5, 0, 2048 * 6)
        feedFrames(after, .25, 0, 100)
        after.reset()
        feedFrames(after, .25, 100, 2048 * 5)
        assertTrue(before.windows().none { input -> after.windows().any { it.timeUs == input.timeUs } })
        val result = requireNotNull(PcmSpectrumAnalyzer.aligned(before.windows(), after.windows()))
        assertEquals(20 * log10(.5), result.beforeDb[64].toDouble(), .0001)
        assertEquals(20 * log10(.25), requireNotNull(result.afterDb)[64].toDouble(), .0001)
        assertTrue(result.beforeDb[70] < -100f)
    }

    @Test fun alignmentDoesNotJoinWindowsAcrossMissingSamples() {
        val tap = PcmSpectrumTap()
        feed(tap, .5, frames = 4096)
        val input = tap.windows().sortedBy { it.timeUs }
        val output = input.first().copy(timeUs = input.first().timeUs + 100 * 1_000_000L / 48_000)
        val discontinuous = listOf(input.first(), input.last().copy(timeUs = input.last().timeUs + 10_000))
        assertNull(PcmSpectrumAnalyzer.aligned(discontinuous, listOf(output)))
        assertNull(PcmSpectrumAnalyzer.aligned(listOf(input.first()), listOf(output)))
    }

    @Test fun discontinuityClearsOldHistoryAndAdvancesGeneration() {
        val tap = PcmSpectrumTap()
        feed(tap, .5)
        val previous = tap.generation
        feed(tap, .25, start = 2_000_000, frames = 2047)
        assertTrue(tap.generation > previous)
        assertTrue(tap.windows().isEmpty())
        tap.observe(.25, -.25, 48_000, 2_000_000 + 2047 * 1_000_000L / 48_000)
        assertEquals(2_000_000L, tap.windows().single().timeUs)
        val contiguousGeneration = tap.generation
        tap.observe(.25, -.25, 48_000, 2_000_000 + 2048 * 1_000_000L / 48_000)
        assertEquals(contiguousGeneration, tap.generation)
        tap.reset()
        assertTrue(tap.generation > contiguousGeneration)
        assertTrue(tap.windows().isEmpty())
    }

    @Test fun sourceUsesLatestCaptureAfterASeekToAnEarlierTimestamp() {
        val tap = PcmSpectrumTap()
        feed(tap, .5)
        val old = tap.windows().single()
        val recent = old.copy(timeUs = 0, measuredAt = old.measuredAt + 1)
        val result = requireNotNull(PcmSpectrumAnalyzer.source(listOf(old, recent)))
        assertEquals(0L, result.presentationStartUs)
        assertEquals(recent.measuredAt, result.measuredAtNanos)
        assertNull(result.afterDb)
    }

    @Test fun mismatchedRatesAndMissingOutputStillShowTheSourceSpectrum() {
        val tap = PcmSpectrumTap()
        feed(tap, .5)
        val input = tap.windows()
        val mismatched = input.map { it.copy(rate = 44_100) }
        val now = input.single().measuredAt
        for (output in listOf(emptyList(), mismatched)) {
            val result = requireNotNull(PcmSpectrumAnalyzer.snapshot(input, output, now))
            assertEquals(48_000, result.sampleRate)
            assertEquals(20 * log10(.5), result.beforeDb[64].toDouble(), .0001)
            assertNull(result.afterDb)
        }
    }

    @Test fun staleOutputFallsBackToFreshSourceAndExpiredCaptureIsUnavailable() {
        val tap = PcmSpectrumTap()
        feed(tap, .5)
        val input = tap.windows()
        val now = input.single().measuredAt
        val old = input.map { it.copy(measuredAt = now - 3_000_000_000L) }
        val result = requireNotNull(PcmSpectrumAnalyzer.snapshot(input, old, now))
        assertNull(result.afterDb)
        assertNull(PcmSpectrumAnalyzer.aligned(input, old))
        assertNull(PcmSpectrumAnalyzer.snapshot(old, old, now))
    }

    @Test fun silenceHasFiniteSourcePowerWithoutInventingAnAfterMeasurement() {
        val tap = PcmSpectrumTap()
        feed(tap, 0.0)
        val result = requireNotNull(PcmSpectrumAnalyzer.source(tap.windows()))
        assertTrue(result.beforeDb.all { it.isFinite() && it <= -150f })
        assertNull(result.afterDb)
        assertNull(PcmSpectrumAnalyzer.source(emptyList()))
    }

    private fun feedFrames(tap: PcmSpectrumTap, gain: Double, firstFrame: Int, frames: Int) {
        repeat(frames) { offset ->
            val frame = firstFrame + offset
            val sample = gain * sin(2 * PI * 64 * frame / 2048)
            tap.observe(sample, -sample, 48_000, 1_000_000 + frame * 1_000_000L / 48_000)
        }
    }
}
