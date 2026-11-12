package com.aurora.music.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class GaplessTrimmerTest {
    private val rate = 48_000
    private val keep = rate * 150 / 1000

    private class Output(val samples: DoubleArray, val sources: LongArray) {
        val frames get() = sources.size
    }

    private fun db(value: Double) = 10.0.pow(value / 20)

    private fun tone(frames: Int, dbfs: Double, start: Int = 0, hz: Double = 440.0) =
        DoubleArray(frames * 2) { i -> db(dbfs) * sin(2 * PI * hz * (start + i / 2) / rate) * if (i % 2 == 0) 1.0 else 0.5 }

    private fun silence(frames: Int) = DoubleArray(frames * 2)

    private fun join(vararg parts: DoubleArray) = parts.reduce { a, b -> a + b }

    private fun process(streams: List<DoubleArray>, skip: Boolean, chunk: (Int) -> Int = { 4096 }, gap: Int = 0,
        delay: Int = 0, padding: Int = 0): Output {
        val trimmer = GaplessTrimmer(2, rate).apply { skipSilence = skip }
        val samples = ArrayList<Double>()
        val sources = ArrayList<Long>()
        val buffer = DoubleArray(2 * 997)
        fun drain() {
            while (true) {
                val frames = trimmer.read(buffer, 0, 997)
                if (frames == 0) return
                for (i in 0 until frames) {
                    samples += buffer[2 * i]; samples += buffer[2 * i + 1]
                    sources += if (trimmer.readSource == GaplessTrimmer.GAP) GaplessTrimmer.GAP else trimmer.readSource + i
                }
            }
        }
        var call = 0
        streams.forEachIndexed { index, stream ->
            if (index > 0) trimmer.boundary(gap)
            trimmer.startStream(delay, padding)
            var at = 0
            while (at < stream.size / 2) {
                val frames = min(max(1, chunk(call++)), stream.size / 2 - at)
                trimmer.queue(stream, at * 2, frames)
                at += frames
                drain()
            }
        }
        trimmer.endOfStream()
        drain()
        assertFalse(trimmer.holding)
        return Output(samples.toDoubleArray(), sources.toLongArray())
    }

    @Test fun encoderDelayAndPaddingAreDroppedSoSplitToneJoinsBitExactly() {
        val whole = tone(3 * 20_000 + 777, -6.0)
        val cuts = listOf(0, 20_111, 41_003, whole.size / 2)
        val junk = DoubleArray(2 * 1105) { 0.7 }
        val tail = DoubleArray(2 * 576) { -0.7 }
        val streams = cuts.zipWithNext { a, b -> junk + whole.copyOfRange(a * 2, b * 2) + tail }
        val out = process(streams, skip = false, delay = 1105, padding = 576)
        assertArrayEquals(whole, out.samples, 0.0)
        var expected = 0L
        var frame = 0
        streams.forEach { stream ->
            val kept = stream.size / 2 - 1105 - 576
            repeat(kept) { assertEquals(expected + 1105 + it, out.sources[frame++]) }
            expected += stream.size / 2
        }
    }

    @Test fun skipOffIsBitExactForAnyChunking() {
        val input = join(tone(30_000, -3.0), silence(150_000), tone(5_000, -60.0), silence(30_000), tone(9_000, -12.0))
        for (size in listOf(1, 7, 333, 4096)) {
            val out = process(listOf(input), skip = false, chunk = { size })
            assertArrayEquals(input, out.samples, 0.0)
        }
    }

    @Test fun longDigitalSilenceMidTrackShrinksToShortFadedPauses() {
        val out = process(listOf(join(tone(rate, -6.0), silence(3 * rate), tone(rate, -6.0, start = 4 * rate))), skip = true)
        val pause = out.frames - 2 * rate
        assertTrue("pause $pause", pause in 2 * keep - rate / 100..2 * keep + rate / 100)
        assertEquals(0L, out.sources.first())
        assertEquals(5L * rate - 1, out.sources.last())
        // the music on both sides of the cut is untouched
        assertArrayEquals(tone(rate, -6.0), out.samples.copyOfRange(0, rate * 2), 0.0)
        assertArrayEquals(tone(rate, -6.0, start = 4 * rate), out.samples.copyOfRange(out.samples.size - rate * 2, out.samples.size), 0.0)
    }

    @Test fun cutsThroughLowLevelSignalFadeInsteadOfClicking() {
        val floor = -70.0
        val out = process(listOf(join(tone(rate, -6.0), tone(4 * rate, floor, start = rate, hz = 50.0), tone(rate, -6.0, start = 5 * rate))), skip = true)
        assertTrue(out.frames < 3 * rate)
        val amplitude = db(floor)
        val limit = amplitude * (2 * PI * 50 / rate + PI / (rate / 100)) * 1.5
        for (frame in rate + 1 until out.frames - rate) {
            assertTrue("step at $frame", abs(out.samples[frame * 2] - out.samples[frame * 2 - 2]) <= limit)
        }
    }

    @Test fun shortPausesStayBitExact() {
        val input = join(tone(rate, -6.0), silence(rate * 19 / 10), tone(rate, -6.0))
        assertArrayEquals(input, process(listOf(input), skip = true).samples, 0.0)
    }

    @Test fun quietPassagesFarAboveTheFloorAreNeverCut() {
        val input = join(tone(2 * rate, -3.0), tone(6 * rate, -45.0, start = 2 * rate), tone(rate, -3.0, start = 8 * rate))
        assertArrayEquals(input, process(listOf(input), skip = true).samples, 0.0)
    }

    @Test fun thresholdFollowsTheProgrammeLevel() {
        val hiss = tone(3 * rate, -70.0, hz = 3000.0)
        val afterLoud = process(listOf(join(tone(rate, -1.0), hiss, tone(rate, -1.0))), skip = true)
        assertTrue(afterLoud.frames < 3 * rate)
        val afterQuiet = process(listOf(join(tone(20 * rate, -40.0), hiss, tone(rate, -40.0))), skip = true)
        assertEquals(24 * rate, afterQuiet.frames)
    }

    @Test fun trackBoundaryTrimsTrailingAndLeadingSilence() {
        val first = join(tone(rate, -6.0), silence(rate * 6 / 5))
        val second = join(silence(rate * 4 / 5), tone(rate, -6.0))
        val out = process(listOf(first, second), skip = true)
        val pause = out.frames - 2 * rate
        assertTrue("pause $pause", pause in keep..2 * keep + rate / 100)
    }

    @Test fun loudToLoudBoundaryIsAnExactJoinWithSkipOn() {
        val whole = tone(2 * rate + 321, -6.0)
        val streams = listOf(whole.copyOfRange(0, 2 * 50_003), whole.copyOfRange(2 * 50_003, whole.size))
        assertArrayEquals(whole, process(streams, skip = true).samples, 0.0)
    }

    @Test fun timelineOverrunDropsUntaggedPaddingUpToTheHold() {
        val whole = tone(30_000, -6.0)
        fun run(overrun: Int): DoubleArray {
            val trimmer = GaplessTrimmer(2, rate).apply { tailHold = 2400 }
            val buffer = DoubleArray(2 * 30_000)
            val out = ArrayList<Double>()
            fun drain() { while (true) { val n = trimmer.read(buffer, 0, 30_000); if (n == 0) return; for (i in 0 until n * 2) out += buffer[i] } }
            trimmer.startStream(0, 0)
            trimmer.queue(whole.copyOfRange(0, 2 * 12_345) + DoubleArray(2 * 700) { 0.3 }, 0, 13_045)
            drain()
            assertEquals(13_045L, trimmer.streamEnd)
            trimmer.boundary(overrun = overrun)
            trimmer.startStream(0, 0)
            trimmer.queue(whole.copyOfRange(2 * 12_345, whole.size), 0, 30_000 - 12_345)
            trimmer.endOfStream()
            drain()
            return out.toDoubleArray()
        }
        assertArrayEquals(whole, run(700), 0.0)
        assertEquals(30_700, run(0).size / 2)
        assertEquals(30_700 - 2400, run(5_000).size / 2)
    }

    @Test fun gapInsertsExactSilenceMarkedAsGap() {
        val a = tone(10_000, -6.0)
        val b = tone(10_000, -6.0, start = 10_000)
        val out = process(listOf(a, b), skip = false, gap = 24_000)
        assertArrayEquals(a + silence(24_000) + b, out.samples, 0.0)
        assertEquals(24_000, out.sources.count { it == GaplessTrimmer.GAP })
        assertEquals(10_000L, out.sources[34_000])
    }

    @Test fun outputIsChunkInvariantWithSkipOn() {
        val streams = listOf(
            join(silence(rate / 3), tone(rate, -4.0), silence(3 * rate), tone(rate / 2, -50.0), tone(rate, -9.0)),
            join(tone(rate / 2, -7.0), silence(rate / 2)),
            join(silence(rate), tone(2 * rate, -48.0), silence(rate / 4)),
        )
        val reference = process(streams, skip = true, delay = 312, padding = 900)
        val random = java.util.Random(7)
        for (chunk in listOf<(Int) -> Int>({ 1 }, { 13 }, { 480 }, { 4096 }, { 1 + random.nextInt(3000) })) {
            val out = process(streams, skip = true, chunk = chunk, delay = 312, padding = 900)
            assertArrayEquals(reference.samples, out.samples, 0.0)
            assertArrayEquals(reference.sources, out.sources)
        }
    }
}
