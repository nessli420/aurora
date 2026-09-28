package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ConvolutionPreparationState
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.*

class AdvancedRackTest {
    private fun node(kind: RackNodeKind, name: String = kind.name) = ProcessingRackNode(UUID.nameUUIDFromBytes(name.toByteArray()).toString(), name, kind)
    private fun render(rack: ProcessingRack, input: DoubleArray, rate: Int = 48000, impulse: ImpulseResponse? = null,
        impulses: Map<String, ImpulseResponse> = emptyMap(), volume: Double = 1.0): DoubleArray {
        val graph = ProductionSerialRack.compile(rack, rate, impulse, impulses, volume)
        val block = AudioBlock(AudioStreamFormat(rate, ChannelLayout.STEREO), 256)
        val output = ArrayList<Double>(); var position = 0
        fun drain() { var n = 0; while (n++ < 1000) { val result = graph.getOutput() ?: break; repeat(result.sampleCount) { output += result.samples[it] } } }
        while (position < input.size / 2) {
            val count = min(253, input.size / 2 - position)
            block.begin(count, position * 1_000_000L / rate, position.toLong())
            input.copyInto(block.samples, 0, position * 2, (position + count) * 2)
            assertTrue(graph.queueInput(block)); drain(); position += count
        }
        graph.queueEndOfStream(); drain(); assertTrue(graph.isEnded)
        return output.toDoubleArray()
    }
    private fun tone(rate: Int, frequency: Double, frames: Int = rate, level: Double = .1) = DoubleArray(frames * 2) { level * sin(2 * PI * frequency * (it / 2) / rate) }
    private fun rms(data: DoubleArray, start: Int, end: Int = data.size) = sqrt((start until end).sumOf { data[it] * data[it] } / (end - start))

    @Test fun lrAndMidSideBranchesRecombineWithoutChangingSamples() {
        val input = DoubleArray(1907 * 2) { sin(it * .13) * .1 }
        for ((a, b) in listOf(RackChannel.LEFT to RackChannel.RIGHT, RackChannel.MID to RackChannel.SIDE)) {
            val left = node(RackNodeKind.GAIN, "first").copy(inputs = listOf(RackInput(channel = a)))
            val right = node(RackNodeKind.GAIN, "second").copy(inputs = listOf(RackInput(channel = b)))
            val rack = ProcessingRack(nodes = listOf(left, right), output = listOf(RackInput(left.id), RackInput(right.id)))
            assertArrayEquals(input, render(rack, input), 2e-17)
        }
    }
    @Test fun msEncodeDecodeAndPolarityAreExact() {
        val encode = node(RackNodeKind.UTILITY, "encode").copy(inputs = listOf(RackInput(channel = RackChannel.ENCODE_MS)))
        val decode = node(RackNodeKind.UTILITY, "decode").copy(inputs = listOf(RackInput(encode.id, RackChannel.DECODE_MS)))
        val input = DoubleArray(3333 * 2) { sin(it * .041) * .3 }
        assertArrayEquals(input, render(ProcessingRack(nodes = listOf(encode, decode)), input), 6e-17)
        val invert = node(RackNodeKind.UTILITY).copy(utility = RackUtility(ll = -1.0, rr = -1.0))
        assertArrayEquals(input.map { -it }.toDoubleArray(), render(ProcessingRack(nodes = listOf(invert)), input), 0.0)
    }
    @Test fun delayedBranchesCompensateAtMergeAndReportFullTail() {
        for (rate in listOf(48000, 96000)) {
            val delayed = node(RackNodeKind.ALIGNMENT_DELAY).copy(utility = RackUtility(delayMs = 2.0), inputs = listOf(RackInput()))
            val direct = node(RackNodeKind.GAIN).copy(inputs = listOf(RackInput()))
            val rack = ProcessingRack(nodes = listOf(delayed, direct), output = listOf(RackInput(delayed.id, gainDb = -6.020599913279624), RackInput(direct.id, gainDb = -6.020599913279624)))
            val input = DoubleArray(4002).apply { this[0] = .5; this[1] = -.25 }
            val graph = ProductionSerialRack.compile(rack, rate, null)
            assertEquals(rate / 500, graph.latencyFrames)
            val actual = render(rack, input, rate)
            assertEquals(input.size + graph.tailFrames * 2, actual.size)
            assertEquals(.5, actual[graph.latencyFrames * 2], 1e-15)
            assertEquals(-.25, actual[graph.latencyFrames * 2 + 1], 1e-15)
            assertTrue(actual.take(graph.latencyFrames * 2).all { it == 0.0 })
        }
    }
    @Test fun multipleImpulseStagesProduceFullLinearConvolutionTail() {
        val first = node(RackNodeKind.CONVOLUTION, "one").let { it.copy(impulseId = it.id) }; val second = node(RackNodeKind.CONVOLUTION, "two").let { it.copy(impulseId = it.id) }
        val a = ImpulseResponse(floatArrayOf(1f, .5f), floatArrayOf(1f, .5f), 48000)
        val b = ImpulseResponse(floatArrayOf(1f, -.25f), floatArrayOf(1f, -.25f), 48000)
        val input = doubleArrayOf(1.0, 1.0)
        val output = render(ProcessingRack(nodes = listOf(first, second)), input, impulses = mapOf(first.id to a, second.id to b))
        assertArrayEquals(doubleArrayOf(1.0, 1.0, .25, .25, -.125, -.125), output, 1e-14)
    }
    @Test fun artisticChannelDelayContributesTailWithoutAligningTheTwoChannels() {
        val delay = node(RackNodeKind.DELAY).copy(audio = AudioPrefs(dspDelayLeftMs = 1f, dspDelayRightMs = 2f))
        val fir = node(RackNodeKind.CONVOLUTION)
        val impulse = ImpulseResponse(floatArrayOf(1f, .5f), floatArrayOf(1f, .5f), 48000)
        val rack = ProcessingRack(nodes = listOf(delay, fir))
        val graph = ProductionSerialRack.compile(rack, 48000, impulse)
        assertEquals(0, graph.latencyFrames)
        assertEquals(97, graph.tailFrames)
        val output = render(rack, doubleArrayOf(1.0, -.5), impulse = impulse)
        assertEquals(98 * 2, output.size)
        assertEquals(1.0, output[48 * 2], 1e-14)
        assertEquals(.5, output[49 * 2], 1e-14)
        assertEquals(-.5, output[96 * 2 + 1], 1e-14)
        assertEquals(-.25, output[97 * 2 + 1], 1e-14)
    }
    @Test fun serialArtisticAndLegacyDelayDrainWithoutAnImpulseStage() {
        for (kind in listOf(RackNodeKind.DELAY, RackNodeKind.LEGACY_DSP)) {
            val delay = node(kind).copy(audio = AudioPrefs(dspDelayLeftMs = 1f, dspDelayRightMs = 2f, dspLimiterEnabled = false))
            val rack = ProcessingRack(nodes = listOf(delay))
            val graph = ProductionSerialRack.compile(rack, 48000, null)
            assertEquals(0, graph.latencyFrames)
            assertEquals(96, graph.tailFrames)
            val output = render(rack, doubleArrayOf(.5, -.25))
            val expected = DoubleArray(97 * 2).apply { this[48 * 2] = .5; this[96 * 2 + 1] = -.25 }
            assertArrayEquals(expected, output, 1e-14)
            assertEquals(0, ProductionSerialRack.compile(rack.copy(nodes = listOf(delay.copy(bypass = true))), 48000, null).tailFrames)
        }
    }
    @Test fun parallelHeadroomIncludesSummingAndDynamicBoostCaps() {
        val a = node(RackNodeKind.GAIN, "a").copy(inputs = listOf(RackInput()))
        val b = node(RackNodeKind.GAIN, "b").copy(inputs = listOf(RackInput()))
        val rack = ProcessingRack(nodes = listOf(a, b), output = listOf(RackInput(a.id), RackInput(b.id)), autoHeadroom = true)
        val headroom = RackHeadroomAnalyzer.analyze(rack, 48000, null)
        assertEquals(20 * log10(2.0), headroom.estimatedBoostDb, 1e-10)
        val input = DoubleArray(2048) { 1.0 }
        assertEquals(10.0.pow(-1.0 / 20), render(rack, input).max(), 1e-12)
        val dynamic = node(RackNodeKind.DYNAMIC_EQ).copy(dynamic = RackDynamicEq(upward = true, dynamics = RackDynamics(makeupDb = 3.0, rangeDb = 12.0)))
        assertEquals(15.0, RackHeadroomAnalyzer.analyze(ProcessingRack(nodes = listOf(dynamic)), 48000, null).estimatedBoostDb, 1e-10)
    }
    @Test fun processingFlagIgnoresBypassedAndDisconnectedStagesButCountsRouting() {
        val gain = node(RackNodeKind.GAIN).copy(audio = AudioPrefs(dspPreampDb = 6f))
        val bypass = ProcessingRack(nodes = listOf(gain.copy(bypass = true)))
        assertFalse(ProductionSerialRack.compile(bypass, 48000, null).processingChangesSamples)
        assertFalse(ProductionSerialRack.compile(ProcessingRack(nodes = listOf(gain), output = listOf(RackInput())), 48000, null).processingChangesSamples)
        assertTrue(ProductionSerialRack.compile(bypass.copy(output = listOf(RackInput(channel = RackChannel.LEFT))), 48000, null).processingChangesSamples)
    }
    @Test fun lr4MultibandFlatRecombinationAtBothRatesAndCrossovers() {
        for (rate in listOf(48000, 96000)) for (frequency in listOf(30.0, 200.0, 500.0, 2500.0, 10000.0)) {
            val m = node(RackNodeKind.MULTIBAND).copy(multiband = RackMultiband(bands = List(3) { RackDynamics(ratio = 1.0) }))
            val input = tone(rate, frequency)
            val output = render(ProcessingRack(nodes = listOf(m)), input, rate)
            assertEquals("$rate/$frequency", 1.0, rms(output, rate) / rms(input, rate), 2e-7)
        }
    }
    @Test fun multibandSoloMuteAndStereoLinkWorkIndependently() {
        val input = tone(48000, 60.0, level = .5)
        val base = RackMultiband(bands = List(3) { RackDynamics(thresholdDb = -30.0, ratio = 4.0, attackMs = .1) })
        val n = node(RackNodeKind.MULTIBAND).copy(multiband = base)
        val output = render(ProcessingRack(nodes = listOf(n)), input)
        assertTrue(rms(output, 48000) < rms(input, 48000) * .3)
        for (i in output.indices step 2) assertEquals(output[i], output[i + 1], 0.0)
        val mute = n.copy(multiband = base.copy(bands = base.bands.map { it.copy(mute = true) }))
        assertTrue(render(ProcessingRack(nodes = listOf(mute)), input).all { it == 0.0 })
        val highSolo = n.copy(multiband = base.copy(bands = base.bands.mapIndexed { i, d -> d.copy(solo = i == 2, ratio = 1.0) }))
        assertTrue(rms(render(ProcessingRack(nodes = listOf(highSolo)), input), 48000) < .00001)
    }
    @Test fun detectorDrivenEqHonorsRangeAndSeparatesDetectorFrequency() {
        val input = tone(48000, 1000.0, level = .5)
        val d = RackDynamicEq(dynamics = RackDynamics(thresholdDb = -40.0, ratio = 20.0, attackMs = .1, rangeDb = 9.0))
        val n = node(RackNodeKind.DYNAMIC_EQ).copy(dynamic = d)
        val output = render(ProcessingRack(nodes = listOf(n)), input)
        assertEquals(10.0.pow(-9.0 / 20), rms(output, 48000) / rms(input, 48000), .005)
        val offDetector = n.copy(dynamic = d.copy(detectorHz = 100.0, detectorQ = 12.0, dynamics = d.dynamics.copy(thresholdDb = -20.0)))
        assertEquals(1.0, rms(render(ProcessingRack(nodes = listOf(offDetector)), input), 48000) / rms(input, 48000), .001)
    }
    @Test fun relativeLoudnessIsUnityAtReferenceAndBoostsWithinCapsBelowIt() {
        val n = node(RackNodeKind.LOUDNESS).copy(loudness = RackLoudness(referenceVolume = .8, bassCapDb = 8.0, trebleCapDb = 3.0))
        val input = tone(48000, 30.0, frames = 96000)
        assertArrayEquals(input, render(ProcessingRack(nodes = listOf(n)), input, volume = .8), 1e-13)
        val low = render(ProcessingRack(nodes = listOf(n)), input, volume = .008)
        val db = 20 * log10(rms(low, 144000) / rms(input, 144000))
        assertTrue("$db", db in 7.7..8.1)
    }
    @Test fun dcBlockAndMonoBassRemoveTheirTargetComponents() {
        val n = node(RackNodeKind.UTILITY).copy(utility = RackUtility(dcBlock = true))
        val output = render(ProcessingRack(nodes = listOf(n)), DoubleArray(96000) { .25 })
        assertTrue(abs(output.last()) < 1e-10)
        val mono = n.copy(utility = RackUtility(monoBassHz = 200.0))
        val input = tone(48000, 20.0).also { for (i in 1 until it.size step 2) it[i] = -it[i] }
        val bass = render(ProcessingRack(nodes = listOf(mono)), input)
        assertTrue(rms(bass, 48000) / rms(input, 48000) < .011)
    }
    @Test fun replacingImpulseKeepsTheOldDelayedTailAfterTheShortCrossfade() {
        val stage = node(RackNodeKind.CONVOLUTION)
        val rack = ProcessingRack(enabled = true, nodes = listOf(stage))
        val delayed = FloatArray(4097).apply { this[4096] = 1f }
        val old = ImpulseResponse(delayed, delayed.copyOf(), 48000)
        val replacement = ImpulseResponse(floatArrayOf(1f), floatArrayOf(1f), 48000)
        val processor = ProductionRackProcessor()
        processor.update(rack, DspParams(), false, true, 0f, old); processor.configure(48000)
        fun await() {
            val end = System.nanoTime() + 5_000_000_000
            while (processor.preparationState != ConvolutionPreparationState.READY && System.nanoTime() < end) Thread.sleep(2)
            assertEquals(ConvolutionPreparationState.READY, processor.preparationState)
        }
        await()
        val output = ArrayList<Double>(); val block = AudioBlock(AudioStreamFormat(48000, ChannelLayout.STEREO), 256)
        fun drain() { repeat(1000) { val b = processor.getOutput() ?: return; repeat(b.sampleCount) { output += b.samples[it] } } }
        repeat(32) { index ->
            if (index == 4) { processor.update(rack, DspParams(), false, true, 0f, replacement); await() }
            block.begin(256, index * 256_000_000L / 48000, index * 256L); block.samples.fill(0.0)
            if (index == 0) { block.samples[0] = .5; block.samples[1] = -.25 }
            assertTrue(processor.queueInput(block)); drain()
        }
        processor.queueEndOfStream(); drain()
        assertTrue(processor.isEnded)
        assertEquals(.5, output[4096 * 2], 1e-13)
        assertEquals(-.25, output[4096 * 2 + 1], 1e-13)
        assertEquals(8192 * 2, output.size)
        processor.reset()
    }
    @Test fun liveLatencyChangesAlignBranchesAndRetainShorterPathDelayUntilSeek() {
        val processor = ProductionRackProcessor()
        val direct = node(RackNodeKind.GAIN)
        val delayed = node(RackNodeKind.ALIGNMENT_DELAY).copy(utility = RackUtility(delayMs = 1.0))
        fun update(stage: ProcessingRackNode) {
            processor.update(ProcessingRack(enabled = true, nodes = listOf(stage)), DspParams(), false, false, 0f, null)
        }
        fun await() {
            val deadline = System.nanoTime() + 5_000_000_000
            while (processor.preparationState != ConvolutionPreparationState.READY && System.nanoTime() < deadline) Thread.sleep(2)
            assertEquals(ConvolutionPreparationState.READY, processor.preparationState)
        }
        update(direct); processor.configure(48000); await()
        val values = ArrayList<Double>(); val block = AudioBlock(AudioStreamFormat(48000, ChannelLayout.STEREO), 256)
        fun drain() { repeat(1000) { val b = processor.getOutput() ?: return; repeat(b.sampleCount) { values += b.samples[it] } } }
        repeat(48) { index ->
            if (index == 16) { update(delayed); await() }
            if (index == 32) { update(direct); await() }
            block.begin(256, index * 256_000_000L / 48000, index * 256L)
            for (i in 0 until block.sampleCount) block.samples[i] = .1 * sin(2 * PI * 613 * (index * 256 + i / 2) / 48000)
            assertTrue(processor.queueInput(block)); drain()
        }
        assertEquals(48, processor.latencyFrames)
        for (frame in listOf(6000, 7000, 10000, 11000)) {
            assertEquals(.1 * sin(2 * PI * 613 * (frame - 48) / 48000), values[frame * 2], 1e-11)
        }
        for (i in 2 until values.size step 2) assertTrue("sample $i", abs(values[i] - values[i - 2]) < .04)
        processor.queueEndOfStream(); drain(); assertTrue(processor.isEnded)
        assertEquals((48 * 256 + 48) * 2, values.size)
        processor.flush(); assertEquals(0, processor.latencyFrames)
        processor.reset()
    }
    @Test fun identicalNonlinearFirGraphEditPreservesHistoryAndNeverDoublesDc() {
        for (factor in listOf(1, 2)) verifyIdenticalNonlinearFirGraphEdit(factor)
    }
    private fun verifyIdenticalNonlinearFirGraphEdit(oversampling: Int) {
        val fir = node(RackNodeKind.CONVOLUTION)
        val saturation = node(RackNodeKind.SATURATION).copy(audio = AudioPrefs(dspSaturation = .5f), oversampling = oversampling)
        val rack = ProcessingRack(enabled = true, nodes = listOf(fir, saturation))
        val taps = FloatArray(4097).apply { this[0] = .5f; this[4096] = .5f }
        val impulse = ImpulseResponse(taps, taps.copyOf(), 48000)
        val source = DoubleArray(16384 * 2) { index ->
            val frame = index / 2
            if (frame in 8192..12287 || frame >= 15360) 0.0 else .2 * sin(2 * PI * 613 * frame / 48000)
        }
        val expected = render(rack, source, impulse = impulse)
        val processor = ProductionRackProcessor()
        processor.update(rack, DspParams(), false, false, 0f, impulse); processor.configure(48000)
        fun await() {
            val deadline = System.nanoTime() + 5_000_000_000
            while (processor.preparationState != ConvolutionPreparationState.READY && System.nanoTime() < deadline) Thread.sleep(2)
            assertEquals(ConvolutionPreparationState.READY, processor.preparationState)
        }
        await()
        val actual = ArrayList<Double>(); val block = AudioBlock(AudioStreamFormat(48000, ChannelLayout.STEREO), 256)
        fun drain() { repeat(1000) { val output = processor.getOutput() ?: return; repeat(output.sampleCount) { actual += output.samples[it] } } }
        repeat(64) { index ->
            if (index == 32) { processor.update(rack.copy(name = "Renamed"), DspParams(), false, false, 0f, impulse); await() }
            block.begin(256, index * 256_000_000L / 48000, index * 256L)
            source.copyInto(block.samples, 0, index * 512, (index + 1) * 512)
            assertTrue(processor.queueInput(block)); drain()
        }
        processor.queueEndOfStream(); drain(); assertTrue(processor.isEnded)
        assertArrayEquals(expected, actual.toDoubleArray(), 1e-12)
        assertEquals(-.033, actual.last(), 1e-12)
        processor.reset()
    }
    @Test fun sameStreamDrainTruncatesLongImpulseAndAlignmentTails() {
        val ir = ImpulseResponse(FloatArray(8193).apply { this[8192] = .5f }, FloatArray(8193).apply { this[8192] = .5f }, 48000)
        val stage = node(RackNodeKind.CONVOLUTION)
        val delay = node(RackNodeKind.ALIGNMENT_DELAY).copy(utility = RackUtility(delayMs = 10.0))
        val rack = ProcessingRack(enabled = true, nodes = listOf(stage, delay))
        for (full in listOf(false, true)) {
            val graph = ProductionSerialRack.compile(rack, 48000, ir)
            val block = AudioBlock(AudioStreamFormat(48000, ChannelLayout.STEREO), 256)
            block.begin(97, 0, 0); block.samples[0] = 1.0; block.samples[1] = 1.0
            assertTrue(graph.queueInput(block)); graph.queueEndOfStream(full)
            var count = 0
            repeat(100) { val output = graph.getOutput(); if (output != null) count += output.frameCount }
            assertTrue(graph.isEnded)
            assertEquals(97 + if (full) 8192 + 480 else 0, count)
        }
    }
}
