package com.aurora.music.playback.engine

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.RackEqChannel
import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.PrecisionBlockProcessor
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class ProductionSerialRackTest {
    private val format = AudioStreamFormat(48_000, ChannelLayout.STEREO)
    private fun node(key: String, kind: RackNodeKind, audio: AudioPrefs = AudioPrefs(), wet: Float = 1f, bypass: Boolean = false) =
        ProcessingRackNode(UUID.nameUUIDFromBytes(key.toByteArray()).toString(), key, kind, bypass, wet, audio)
    private fun rack(vararg nodes: ProcessingRackNode) = ProcessingRack(enabled = true, nodes = nodes.toList())

    @Test fun orderChangesNonlinearProcessingAndWetBypassDuplicateHaveIndependentMeaning() {
        val gain = node("gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = 6f))
        val saturation = node("saturation", RackNodeKind.SATURATION, AudioPrefs(dspSaturation = .8f))
        val source = DoubleArray(128 * 2) { .4 }
        val before = stream(ProductionSerialRack.compile(rack(gain, saturation), 48_000, null), source)
        val after = stream(ProductionSerialRack.compile(rack(saturation, gain), 48_000, null), source)
        assertTrue(abs(before.last() - after.last()) > .05)
        val wet = stream(ProductionSerialRack.compile(rack(gain.copy(wet = .25f)), 48_000, null), source)
        assertEquals(.4 * (.75 + .25 * 10.0.pow(6.0 / 20.0)), wet.last(), 1e-12)
        val bypass = stream(ProductionSerialRack.compile(rack(gain.copy(bypass = true)), 48_000, null), source)
        assertArrayEquals(source, bypass, 0.0)
        val duplicate = gain.copy(id = UUID.randomUUID().toString())
        val twice = stream(ProductionSerialRack.compile(rack(gain, duplicate), 48_000, null), source)
        assertEquals(.4 * 10.0.pow(12.0 / 20.0), twice.last(), 1e-12)
    }

    @Test fun channelEqChangesOnlySelectedChannelIncludingPartialWetAndGraphicEq() {
        val source = DoubleArray(12_000 * 2) { .1 * sin(2 * PI * 1_000 * (it / 2) / 48_000) }
        for (channel in listOf(RackEqChannel.LEFT, RackEqChannel.RIGHT)) for (wet in listOf(1f, .37f)) {
            val eq = node("eq", RackNodeKind.EQ, AudioPrefs(dspParametric = listOf(ParamBand(1_000f, 6f, 1f))), wet)
                .copy(eqChannel = channel)
            val actual = stream(ProductionSerialRack.compile(rack(eq), 48_000, null), source, 253)
            val selected = if (channel == RackEqChannel.LEFT) 0 else 1
            source.indices.filter { it % 2 != selected }.forEach { assertEquals(source[it].toBits(), actual[it].toBits()) }
            val inputEnergy = source.indices.filter { it > 16_000 && it % 2 == selected }.sumOf { source[it] * source[it] }
            val outputEnergy = actual.indices.filter { it > 16_000 && it % 2 == selected }.sumOf { actual[it] * actual[it] }
            assertEquals(1 - wet + wet * 10.0.pow(6.0 / 20), sqrt(outputEnergy / inputEnergy), 1e-5)
            val graphic = eq.copy(audio = AudioPrefs(dspGraphicBands = List(31) { 3f }))
            val graphicOutput = stream(ProductionSerialRack.compile(rack(graphic), 48_000, null), source)
            source.indices.filter { it % 2 != selected }.forEach { assertEquals(source[it].toBits(), graphicOutput[it].toBits()) }
        }
    }

    @Test fun independentChannelNodesDoNotStackAndRoutingChangeDoesNotMigrateOldHistory() {
        val left = node("left", RackNodeKind.EQ, AudioPrefs(dspParametric = listOf(ParamBand(1_000f, 6f, 1f))))
            .copy(eqChannel = RackEqChannel.LEFT)
        val right = left.copy(id = UUID.randomUUID().toString(), eqChannel = RackEqChannel.RIGHT,
            audio = AudioPrefs(dspParametric = listOf(ParamBand(1_000f, -6f, 1f))))
        val source = DoubleArray(12_000 * 2) { .1 * sin(2 * PI * 1_000 * (it / 2) / 48_000) }
        val actual = stream(ProductionSerialRack.compile(rack(left, right), 48_000, null), source)
        for (channel in 0..1) {
            val energy = actual.indices.filter { it > 16_000 && it % 2 == channel }.sumOf { actual[it] * actual[it] }
            val input = source.indices.filter { it > 16_000 && it % 2 == channel }.sumOf { source[it] * source[it] }
            assertEquals(10.0.pow((if (channel == 0) 6.0 else -6.0) / 20), sqrt(energy / input), 1e-5)
        }
        val previous = ProductionSerialRack.compile(rack(left), 48_000, null)
        previous.queueInput(block(DoubleArray(32).apply { this[0] = .5 }, 0)); previous.getOutput()
        val changed = ProductionSerialRack.compile(rack(left.copy(eqChannel = RackEqChannel.RIGHT)), 48_000, null)
        changed.copyNodeHistoriesFrom(previous)
        assertArrayEquals(DoubleArray(128), stream(changed, DoubleArray(128)), 0.0)
    }

    @Test fun sixtyFourthPeakingBandIsAudibleAndTheSixtyFifthIsRejected() {
        val bands = List(64) { ParamBand(1_000f, if (it == 63) 6f else 0f, 1f) }
        val eq = node("eq", RackNodeKind.EQ, AudioPrefs(dspParametric = bands))
        val source = DoubleArray(12_000 * 2) { .1 * sin(2 * PI * 1_000 * (it / 2) / 48_000) }
        val actual = stream(ProductionSerialRack.compile(rack(eq), 48_000, null), source)
        val ratio = rms(actual, 8_000) / rms(source, 8_000)
        assertEquals(10.0.pow(6.0 / 20.0), ratio, 1e-5)
        assertThrows(IllegalArgumentException::class.java) {
            ProductionSerialRack.compile(rack(eq.copy(audio = eq.audio.copy(dspParametric = bands + bands.last()))), 48_000, null)
        }
    }

    @Test fun convolutionInTheMiddleMixesCorrespondingDryFramesAndPreservesFullTail() {
        val gain = node("gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f))
        val convolution = node("ir", RackNodeKind.CONVOLUTION, AudioPrefs(), wet = .25f)
        val restore = node("restore", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = 6f))
        val impulse = ImpulseResponse(floatArrayOf(0f, .5f), floatArrayOf(.5f, .25f), 48_000)
        val graph = ProductionSerialRack.compile(rack(gain, convolution, restore), 48_000, impulse)
        val source = DoubleArray(1_907 * 2) { ((it % 29) - 14) * 2.0.pow(-35) }
        val actual = stream(graph, source, 253)
        assertEquals(source.size + 2, actual.size)
        assertEquals(source[source.lastIndex - 1] * .5 * .25, actual[actual.lastIndex - 1], 2e-20)
        assertEquals(source[source.lastIndex] * .25 * .25, actual[actual.lastIndex], 2e-20)
        source.indices.forEach { i ->
            val previous = if (i >= 2) source[i - 2] else 0.0
            val filtered = if (i % 2 == 0) previous * .5 else source[i] * .5 + previous * .25
            assertEquals(source[i] * .75 + filtered * .25, actual[i], 2e-20)
        }
    }

    @Test fun finalLimiterActsAfterConvolutionWhenOrderedLast() {
        val convolution = node("ir", RackNodeKind.CONVOLUTION)
        val limiter = node("limiter", RackNodeKind.LIMITER, AudioPrefs(dspLimiterCeilingDb = -6f))
        val impulse = ImpulseResponse(floatArrayOf(4f), floatArrayOf(4f), 48_000)
        val source = DoubleArray(12_000 * 2) { 1.0 }
        val final = stream(ProductionSerialRack.compile(rack(convolution, limiter), 48_000, impulse), source)
        val before = stream(ProductionSerialRack.compile(rack(limiter, convolution), 48_000, impulse), source)
        assertEquals(10.0.pow(-6.0 / 20.0), final.last(), 1e-9)
        assertEquals(4 * 10.0.pow(-6.0 / 20.0), before.last(), 1e-9)
    }

    @Test fun missingImpulseIsReportedWithoutDisablingOtherRackNodes() {
        val processor = PrecisionBlockProcessor()
        val gain = node("gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f))
        val convolution = node("ir", RackNodeKind.CONVOLUTION)
        processor.updateRack(rack(gain, convolution)); processor.configure(48_000)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        assertTrue(processor.queueInput(block(DoubleArray(128 * 2) { .5 }, 0)))
        val output = requireNotNull(processor.getOutput())
        assertEquals(.5 * 10.0.pow(-6.0 / 20.0), output.samples[0], 1e-12)
        assertTrue(processor.rackActive)
        assertFalse(processor.convolutionProcessingActive)
        assertTrue(processor.rackDescription.contains("CONVOLUTION (unavailable: no impulse response)"))
        assertNotNull(processor.convolutionUnavailableReason)
        assertNull(processor.preparationFailure)
        processor.reset()
    }

    @Test fun stableNodeHistorySurvivesAnEditAndSeekRemovesIt() {
        val delay = node("delay", RackNodeKind.DELAY, AudioPrefs(dspDelayLeftMs = 1f))
        val original = ProductionSerialRack.compile(rack(delay), 48_000, null)
        repeat(4) { index ->
            val samples = DoubleArray(256 * 2)
            if (index == 3) samples[232 * 2] = 1.0
            assertTrue(original.queueInput(block(samples, index * 256L)))
            val output = original.getOutput()
            if (index < 3) assertNull(output)
            else {
                val emitted = requireNotNull(output)
                assertArrayEquals(DoubleArray(1024 * 2), emitted.samples.copyOf(emitted.sampleCount), 0.0)
            }
        }
        assertFalse(original.hasPendingData)
        val changed = ProductionSerialRack.compile(rack(delay.copy(wet = .5f)), 48_000, null)
        changed.copyNodeHistoriesFrom(original)
        assertTrue(changed.queueInput(block(DoubleArray(64 * 2), 1024)))
        assertNull(changed.getOutput())
        changed.queueEndOfStream()
        val output = requireNotNull(changed.getOutput())
        assertEquals(48, changed.tailFrames)
        assertEquals(64 + changed.tailFrames, output.frameCount)
        assertEquals(1024L, output.firstFramePosition)
        val expected = DoubleArray(output.sampleCount).apply { this[24 * 2] = .5 }
        assertArrayEquals(expected, output.samples.copyOf(output.sampleCount), 1e-12)
        assertNull(changed.getOutput()); assertTrue(changed.isEnded)
        changed.reset()
        assertArrayEquals(DoubleArray((64 + changed.tailFrames) * 2), stream(changed, DoubleArray(64 * 2)), 0.0)
    }

    @Test fun preparedGraphGainChangeCrossfadesRatherThanJumping() {
        val processor = ProductionRackProcessor()
        val old = rack(node("gain", RackNodeKind.GAIN))
        processor.update(old, DspParams(limiterEnabled = false), false, false, 0f, null)
        processor.configure(48_000); await { processor.preparationState == ConvolutionPreparationState.READY }
        val warmup = process(processor, DoubleArray(256 * 2) { 1.0 }, 0)
        assertEquals(1.0, warmup.last(), 1e-12)
        val quiet = old.copy(nodes = listOf(old.nodes.single().copy(audio = AudioPrefs(dspPreampDb = -12f))))
        processor.update(quiet, DspParams(limiterEnabled = false), false, false, 0f, null)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        val output = process(processor, DoubleArray(2_304 * 2) { 1.0 }, 256)
        assertTrue(output.first() > .99)
        assertEquals(10.0.pow(-12.0 / 20.0), output.last(), 1e-4)
        for (i in 2 until output.size step 2) {
            assertTrue("Continuous20ms transition", abs(output[i] - output[i - 2]) < .01)
            assertTrue(output[i] <= output[i - 2] + 1e-12)
        }
        processor.reset()
    }

    @Test fun convolutionGraphSwapAlignsIrregularInputAndEosFrames() {
        val processor = ProductionRackProcessor()
        val gain = node("gain", RackNodeKind.GAIN)
        val conv = node("ir", RackNodeKind.CONVOLUTION)
        val impulse = ImpulseResponse(floatArrayOf(1f), floatArrayOf(1f), 48_000)
        processor.update(rack(gain, conv), DspParams(), false, false, 0f, impulse)
        processor.configure(48_000); await { processor.preparationState == ConvolutionPreparationState.READY }
        val all = ArrayList<Double>()
        all += process(processor, DoubleArray(1_024 * 2) { .125 }, 0, 256).toList()
        processor.update(rack(conv, gain), DspParams(), false, false, 0f, impulse)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        all += process(processor, DoubleArray(3_713 * 2) { .125 }, 1_024, 227).toList()
        processor.queueEndOfStream()
        await {
            processor.getOutput()?.let { b -> repeat(b.sampleCount) { all += b.samples[it] } }
            processor.isEnded
        }
        assertEquals((1_024 + 3_713) * 2, all.size)
        all.forEach { assertEquals(.125, it, 1e-12) }
        processor.reset()
    }

    @Test fun precisionBlockProcessorReturnsToLegacyWhenRackIsDisabled() {
        val processor = PrecisionBlockProcessor()
        val gain = rack(node("gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f)))
        processor.updateRack(gain); processor.configure(48_000)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        assertTrue(processor.queueInput(block(DoubleArray(128 * 2) { 1.0 }, 0)))
        assertEquals(10.0.pow(-6.0 / 20.0), requireNotNull(processor.getOutput()).samples[0], 1e-12)
        assertTrue(processor.rackActive)
        processor.updateRack(null)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        var last = 0.0
        repeat(12) { index ->
            val input = block(DoubleArray(128 * 2) { 1.0 }, 128L + index * 128)
            await { processor.queueInput(input) }
            processor.getOutput()?.let { last = it.samples[it.sampleCount - 1] }
        }
        processor.queueEndOfStream()
        await { processor.getOutput()?.let { last = it.samples[it.sampleCount - 1] }; processor.isEnded }
        assertFalse(processor.rackActive)
        assertEquals(1.0, last, 1e-12)
        processor.reset()
    }

    @Test fun irregularInputDoesNotDelayPreparedGraphEditsUntilACommonMultipleOfBlockSizes() {
        val processor = ProductionRackProcessor()
        val gain = node("gain", RackNodeKind.GAIN)
        val convolution = node("ir", RackNodeKind.CONVOLUTION)
        val impulse = ImpulseResponse(floatArrayOf(1f), floatArrayOf(1f), 48_000)
        processor.update(rack(gain, convolution), DspParams(), false, false, 0f, impulse)
        processor.configure(48_000); await { processor.preparationState == ConvolutionPreparationState.READY }
        var emitted = process(processor, DoubleArray(227 * 2) { .125 }, 0, 227).size
        assertEquals(0, emitted)
        processor.update(rack(convolution, gain), DspParams(), false, false, 0f, impulse)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        repeat(5) { index ->
            emitted += process(processor, DoubleArray(227 * 2) { .125 }, 227L + index * 227, 227).size
        }
        assertTrue("Edit starts at the next1024-frame boundary despite227-frame inputs",
            processor.description.startsWith("CONVOLUTION"))
        processor.queueEndOfStream()
        await { processor.getOutput()?.let { emitted += it.sampleCount }; processor.isEnded }
        assertEquals(6 * 227 * 2, emitted)
        processor.reset()
    }

    @Test fun firstLiveRackEnableDrainsLegacyPartialConvolutionWithoutCallingTrackEos() {
        val processor = PrecisionBlockProcessor().apply { convolutionEnabled = true }
        processor.setImpulse(ImpulseResponse(floatArrayOf(1f), floatArrayOf(1f), 48_000), 0f)
        processor.configure(48_000)
        await { processor.preparationState == ConvolutionPreparationState.READY }
        assertTrue(processor.queueInput(block(DoubleArray(97 * 2) { .25 }, 0)))
        assertNull(processor.getOutput())
        processor.updateRack(rack(node("gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f))))
        await { processor.preparationState == ConvolutionPreparationState.READY }
        val next = block(DoubleArray(128 * 2) { .25 }, 97)
        assertFalse(processor.queueInput(next))
        val oldOutput = requireNotNull(processor.getOutput())
        assertEquals(97, oldOutput.frameCount)
        repeat(oldOutput.sampleCount) { assertEquals(.25, oldOutput.samples[it], 1e-12) }
        assertTrue(processor.queueInput(next))
        val newOutput = requireNotNull(processor.getOutput())
        assertEquals(128, newOutput.frameCount)
        assertEquals(.25 * 10.0.pow(-6.0 / 20.0), newOutput.samples[0], 1e-12)
        processor.queueEndOfStream()
        await { processor.getOutput(); processor.isEnded }
        processor.reset()
    }

    private fun stream(graph: ProductionSerialRack, input: DoubleArray, chunk: Int = 256): DoubleArray {
        val result = ArrayList<Double>()
        var offset = 0
        fun drain() { while (true) { val b = graph.getOutput() ?: break; repeat(b.sampleCount) { result += b.samples[it] } } }
        while (offset < input.size / 2) {
            val count = minOf(chunk, input.size / 2 - offset)
            val b = block(input.copyOfRange(offset * 2, (offset + count) * 2), offset.toLong())
            var attempts = 0
            while (!graph.queueInput(b)) { drain(); check(attempts++ < 20) }
            drain(); offset += count
        }
        graph.queueEndOfStream(); drain(); assertTrue(graph.isEnded)
        return result.toDoubleArray()
    }

    private fun process(processor: ProductionRackProcessor, samples: DoubleArray, first: Long, chunk: Int = 256): DoubleArray {
        val result = ArrayList<Double>()
        var offset = 0
        fun drain() { while (true) { val b = processor.getOutput() ?: break; repeat(b.sampleCount) { result += b.samples[it] } } }
        while (offset < samples.size / 2) {
            val count = minOf(chunk, samples.size / 2 - offset)
            val input = block(samples.copyOfRange(offset * 2, (offset + count) * 2), first + offset)
            await { drain(); processor.queueInput(input) }
            drain(); offset += count
        }
        return result.toDoubleArray()
    }

    private fun block(samples: DoubleArray, first: Long) = AudioBlock(format, 256).apply {
        begin(samples.size / 2, first * 1_000_000L / format.sampleRate, first)
        samples.copyInto(this.samples)
    }
    private fun rms(samples: DoubleArray, skip: Int): Double = sqrt(samples.drop(skip).sumOf { it * it } / (samples.size - skip))
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out waiting for processing" }; Thread.sleep(1) }
    }
}
