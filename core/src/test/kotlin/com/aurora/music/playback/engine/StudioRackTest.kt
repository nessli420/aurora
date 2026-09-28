package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.*

class StudioRackTest {
    private val rate = 48000
    private fun node(kind: RackNodeKind) = ProcessingRackNode(UUID.randomUUID().toString(), kind.name, kind)
    private fun signal(frames: Int = rate, hz: Double = 1000.0, level: Double = .3) = DoubleArray(frames * 2) { level * sin(2 * PI * hz * (it / 2) / rate) }
    private fun rms(data: DoubleArray, from: Int = data.size / 2, to: Int = data.size) = sqrt((from until to).sumOf { data[it] * data[it] } / (to - from))
    private fun render(kernel: AdvancedRackKernel, input: DoubleArray, chunk: Int = 257): DoubleArray {
        val block = AudioBlock(AudioStreamFormat(rate, ChannelLayout.STEREO), chunk)
        val result = DoubleArray(input.size)
        var frame = 0
        while (frame < input.size / 2) {
            val count = min(chunk, input.size / 2 - frame)
            block.begin(count); input.copyInto(block.samples, 0, frame * 2, (frame + count) * 2)
            kernel.process(block); block.samples.copyInto(result, frame * 2, 0, count * 2); frame += count
        }
        return result
    }
    private fun graphRender(stage: ProcessingRackNode, input: DoubleArray, drainTail: Boolean = true): DoubleArray {
        val graph = ProductionSerialRack.compile(ProcessingRack(enabled = true, nodes = listOf(stage)), rate, null)
        val block = AudioBlock(AudioStreamFormat(rate, ChannelLayout.STEREO), 256)
        val output = ArrayList<Double>()
        fun drain() { repeat(10000) { val result = graph.getOutput() ?: return; for (i in 0 until result.sampleCount) output += result.samples[i] } }
        var frame = 0
        while (frame < input.size / 2) {
            val count = min(251, input.size / 2 - frame)
            block.begin(count); input.copyInto(block.samples, 0, frame * 2, (frame + count) * 2)
            assertTrue(graph.queueInput(block)); drain(); frame += count
        }
        graph.queueEndOfStream(drainTail); drain(); assertTrue(graph.isEnded)
        return output.toDoubleArray()
    }
    @Test fun persistedStudioSettingsRoundTripAndOldOptionalFieldsRemainAbsent() {
        val nodes = listOf(node(RackNodeKind.DYNAMICS).copy(dynamics = RackDynamicsEffect(mode = RackDynamicsMode.DEESSER)),
            node(RackNodeKind.TONE).copy(tone = RackTone(mode = RackToneMode.TUBE)),
            node(RackNodeKind.SPACE).copy(space = RackSpace(mode = RackSpaceMode.PING_PONG)),
            node(RackNodeKind.MODULATION).copy(modulation = RackModulation(mode = RackModulationMode.PHASER)))
        val rack = ProcessingRack(enabled = true, nodes = nodes)
        assertEquals(rack, ProcessingRackCodec.decode(ProcessingRackCodec.encode(rack)).getOrThrow())
        val old = ProcessingRack(nodes = listOf(node(RackNodeKind.GAIN)))
        assertEquals(old, ProcessingRackCodec.decode(ProcessingRackCodec.encode(old)).getOrThrow())
        val encoded = JsonParser.parseString(ProcessingRackCodec.encode(rack)).asJsonObject
        encoded.getAsJsonArray("nodes")[1].asJsonObject.getAsJsonObject("tone").remove("amount")
        assertTrue(ProcessingRackCodec.decode(encoded.toString()).isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(rack.copy(nodes = listOf(nodes[1].copy(tone = RackTone(amount = Double.NaN))))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(rack.copy(nodes = listOf(nodes[2].copy(space = RackSpace(feedback = 1.0))))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(rack.copy(nodes = listOf(nodes[3].copy(kind = RackNodeKind.GAIN)))) }.isFailure)
    }
    @Test fun bypassAndZeroWetPreserveEveryInputSampleAndAddNoTail() {
        val input = signal(1999).also { for (i in 1 until it.size step 2) it[i] *= -.4 }
        for (kind in listOf(RackNodeKind.DYNAMICS, RackNodeKind.TONE, RackNodeKind.SPACE, RackNodeKind.MODULATION)) {
            assertArrayEquals(kind.name, input, graphRender(node(kind).copy(bypass = true), input), 0.0)
            assertArrayEquals(kind.name, input, graphRender(node(kind).copy(wet = 0f), input), 0.0)
        }
    }
    @Test fun zeroAmountDepthAndUnityExpanderAreExactIdentity() {
        val input = signal(1901)
        for (mode in RackToneMode.entries) assertArrayEquals(input, render(ToneKernel(RackTone(mode = mode, amount = 0.0), rate), input), 0.0)
        for (mode in RackModulationMode.entries) assertArrayEquals(input, render(ModulationKernel(RackModulation(mode = mode, depth = 0.0), rate), input), 0.0)
        val unity = RackDynamicsEffect(dynamics = RackDynamics(ratio = 1.0))
        assertArrayEquals(input, render(DynamicsEffectKernel(unity, rate), input), 0.0)
    }
    @Test fun expanderHonorsRatioRangeAndLinkedStereo() {
        val settings = RackDynamicsEffect(dynamics = RackDynamics(thresholdDb = -20.0, ratio = 3.0, releaseMs = 5.0,
            kneeDb = 0.0, rangeDb = 24.0, detector = RackDetector.PEAK))
        val input = DoubleArray(rate * 2) { if (it % 2 == 0) .01 else .005 }
        val output = render(DynamicsEffectKernel(settings, rate), input)
        assertEquals(.01 * 10.0.pow(-24.0 / 20), output[output.size - 2], 1e-12)
        assertEquals(output[output.size - 2] * .5, output.last(), 1e-12)
        assertArrayEquals(DoubleArray(2000) { .5 }, render(DynamicsEffectKernel(settings, rate), DoubleArray(2000) { .5 }), 0.0)
    }
    @Test fun gateOpensHoldsAndClosesWithoutRemovingTheOtherChannel() {
        val settings = RackDynamicsEffect(mode = RackDynamicsMode.GATE, holdMs = 50.0,
            dynamics = RackDynamics(thresholdDb = -30.0, attackMs = .1, releaseMs = 5.0, detector = RackDetector.PEAK))
        val kernel = DynamicsEffectKernel(settings, rate)
        val input = DoubleArray(rate * 2) { if (it / 2 < rate / 4) .3 else .003 }
        val output = render(kernel, input)
        assertEquals(.3, output[2000], 1e-12)
        assertEquals(.003, output[(rate / 4 + 1000) * 2], 1e-12)
        assertTrue(output.last() < .003 * .00002)
        assertTrue(kernel.reductionDb < -95.9)
    }
    @Test fun deEsserAttenuatesSibilanceWhilePreservingLowFrequencyContent() {
        val settings = RackDynamicsEffect(mode = RackDynamicsMode.DEESSER, frequencyHz = 4000.0,
            dynamics = RackDynamics(thresholdDb = -35.0, ratio = 10.0, attackMs = .1, releaseMs = 20.0, rangeDb = 12.0))
        val high = signal(hz = 12000.0)
        val ratio = rms(render(DynamicsEffectKernel(settings, rate), high)) / rms(high)
        val k = tan(PI * settings.frequencyHz / rate); val w = tan(PI * 12000 / rate)
        val gain = 10.0.pow(-12.0 / 20)
        val expected = hypot(1 + (gain - 1) * w * w / (k * k + w * w), (gain - 1) * k * w / (k * k + w * w))
        assertEquals(expected, ratio, .002)
        assertTrue(ratio < .4)
        val low = signal(hz = 100.0)
        assertEquals(1.0, rms(render(DynamicsEffectKernel(settings, rate), low)) / rms(low), .001)
    }
    @Test fun nonlinearToneReportsCompensatedLatencyAndAddsHarmonicsWithoutDc() {
        for (mode in listOf(RackToneMode.TAPE, RackToneMode.TUBE)) {
            val settings = RackTone(mode = mode, amount = 1.0, driveDb = 18.0)
            val kernel = ToneKernel(settings, rate)
            assertEquals(32, kernel.latencyFrames)
            val input = signal(level = .8)
            val output = render(kernel, input)
            assertTrue(rms(output) < rms(input) * .5)
            val mean = output.drop(rate).average()
            assertEquals(0.0, mean, .0001)
            var fundamental = 0.0; var harmonic = 0.0
            for (frame in rate / 2 until rate) {
                fundamental += output[frame * 2] * sin(2 * PI * 1000 * (frame - 32) / rate)
                harmonic += output[frame * 2] * sin(2 * PI * 3000 * (frame - 32) / rate)
            }
            assertTrue(abs(harmonic) > abs(fundamental) * .05)
            for (i in output.indices step 2) assertEquals(output[i], output[i + 1], 0.0)
        }
    }
    @Test fun bassToneBoostsLowFrequencyWithinItsCapAndExciterIsBandSelective() {
        val settings = RackTone(mode = RackToneMode.BASS, driveDb = 12.0, amount = 1.0, frequencyHz = 100.0)
        val low = signal(hz = 20.0)
        assertEquals(6.0, 20 * log10(rms(render(ToneKernel(settings, rate), low)) / rms(low)), .03)
        val high = signal(hz = 10000.0)
        assertEquals(0.0, 20 * log10(rms(render(ToneKernel(settings, rate), high)) / rms(high)), .01)
        val exciter = RackTone(mode = RackToneMode.EXCITER, driveDb = 18.0, amount = 1.0, frequencyHz = 4000.0)
        assertEquals(1.0, rms(render(ToneKernel(exciter, rate), low)) / rms(low), .001)
    }
    @Test fun stereoDelayAndPingPongPlaceEchoesOnTheCorrectChannels() {
        val impulse = DoubleArray(1 * 2).apply { this[0] = 1.0 }
        val delay = node(RackNodeKind.SPACE).copy(space = RackSpace(timeMs = 10.0, feedback = 0.0))
        val actual = graphRender(delay, impulse)
        assertEquals(481 * 2, actual.size)
        assertEquals(1.0, actual[480 * 2], 0.0)
        assertTrue(actual.indices.filter { it != 960 }.all { actual[it] == 0.0 })
        val ping = render(SpaceKernel(RackSpace(mode = RackSpaceMode.PING_PONG, timeMs = 10.0, feedback = .5), rate),
            DoubleArray(2400 * 2).apply { this[0] = 1.0 })
        assertEquals(1.0, ping[480 * 2], 0.0)
        assertEquals(0.0, ping[480 * 2 + 1], 0.0)
        assertTrue(ping[960 * 2 + 1] > .2)
        assertEquals(0.0, ping[960 * 2], 0.0)
    }
    @Test fun reverbDrainsADeclaredDecayingTailAndFlushClearsIt() {
        val settings = RackSpace(mode = RackSpaceMode.REVERB, timeMs = 1.0, decaySeconds = .2)
        val stage = node(RackNodeKind.SPACE).copy(space = settings)
        val kernel = SpaceKernel(settings, rate)
        val output = graphRender(stage, doubleArrayOf(1.0, 1.0))
        assertEquals((1 + kernel.tailFrames) * 2, output.size)
        assertTrue(rms(output, 2000, 6000) > rms(output, output.size - 4000))
        assertTrue(output.all(Double::isFinite))
        render(kernel, DoubleArray(10000).apply { this[0] = 1.0 })
        kernel.reset()
        assertTrue(render(kernel, DoubleArray(20000)).all { it == 0.0 })
        assertEquals(2, graphRender(stage, doubleArrayOf(1.0, 1.0), false).size)
    }
    @Test fun tremoloHasExpectedDepthAndStereoPhase() {
        val settings = RackModulation(mode = RackModulationMode.TREMOLO, rateHz = 1.0, depth = 1.0, stereoPhase = 180.0)
        val output = render(ModulationKernel(settings, rate), DoubleArray(rate * 2) { 1.0 })
        assertEquals(.5, output[0], 1e-12)
        assertEquals(0.0, output[rate / 4 * 2], 1e-9)
        assertEquals(1.0, output[rate / 4 * 2 + 1], 1e-9)
        assertTrue(output.all { it in 0.0..1.0 })
    }
    @Test fun allModesKeepChunkContinuityResetAndIndependentChannels() {
        val factories = RackModulationMode.entries.map { mode -> { ModulationKernel(RackModulation(mode = mode, feedback = .65), rate) as AdvancedRackKernel } } +
            RackToneMode.entries.map { mode -> { ToneKernel(RackTone(mode = mode), rate) as AdvancedRackKernel } } +
            RackSpaceMode.entries.map { mode -> { SpaceKernel(RackSpace(mode = mode, timeMs = 5.0), rate) as AdvancedRackKernel } }
        val input = signal(5000).also { for (i in 1 until it.size step 2) it[i] = 0.0 }
        for (factory in factories) {
            val kernel = factory()
            val output = render(kernel, input)
            assertArrayEquals(output, render(factory(), input, 131), 0.0)
            assertTrue(output.all(Double::isFinite))
            kernel.reset(); assertArrayEquals(output, render(kernel, input), 0.0)
            val continued = factory(); continued.copyStateFrom(kernel)
            assertArrayEquals(render(kernel, input), render(continued, input), 0.0)
            if (kernel !is SpaceKernel) assertTrue(output.indices.filter { it % 2 == 1 }.all { output[it] == 0.0 })
        }
    }
    @Test fun preparationRejectsUnboundedCpuMemoryAndTailRequests() {
        val tones = ProcessingRack(nodes = List(16) { node(RackNodeKind.TONE) })
        assertTrue(runCatching { ProductionSerialRack.compile(tones, 96000, null) }.isFailure)
        val delays = ProcessingRack(nodes = List(4) { node(RackNodeKind.SPACE).copy(space = RackSpace(timeMs = 750.0, feedback = .65)) })
        assertTrue(runCatching { ProductionSerialRack.compile(delays, 768000, null) }.isFailure)
        val stage = node(RackNodeKind.SPACE).copy(space = RackSpace(feedback = .5))
        assertTrue(RackHeadroomAnalyzer.analyze(ProcessingRack(nodes = listOf(stage)), rate, null).estimatedBoostDb >= 6.02)
    }
}
