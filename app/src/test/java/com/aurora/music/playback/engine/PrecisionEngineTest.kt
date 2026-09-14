package com.aurora.music.playback.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.random.Random

class PrecisionEngineTest {
    private val stereo = AudioStreamFormat(48_000, ChannelLayout.STEREO)

    @Test fun everyIntegerBoundaryRoundTripsExactlyIncludingLeastSignificantBits() {
        for (encoding in listOf(PcmEncoding.SIGNED_16_LE, PcmEncoding.SIGNED_24_LE, PcmEncoding.SIGNED_32_LE)) {
            val random = Random(91023)
            val input = ByteArray(1024 * 2 * encoding.bytesPerSample) { random.nextInt(256).toByte() }
            // Include signed minimum, maximum, zero, and +/- one in every integer format.
            val bits = encoding.integerBits
            val edge = longArrayOf(-(1L shl (bits - 1)), (1L shl (bits - 1)) - 1, 0, 1, -1)
            edge.forEachIndexed { i, value ->
                repeat(encoding.bytesPerSample) { byte -> input[i * encoding.bytesPerSample + byte] = (value shr (byte * 8)).toByte() }
            }
            val output = run(input, encoding, emptyList())
            assertArrayEquals(encoding.name, input, output)
        }
    }

    @Test fun float32RoundTripPreservesFiniteDecoderValuesAndSignedZero() {
        val values = floatArrayOf(-0f, 0f, Float.MIN_VALUE, -Float.MIN_VALUE, 0.25f, 1.25f, -3.5f, Float.MAX_VALUE)
        val input = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(input::putFloat)
        assertArrayEquals(input.array(), run(input.array(), PcmEncoding.FLOAT_32_LE, emptyList()))
    }

    @Test fun binary64SamplesAreNotNarrowedInsideTheRack() {
        val precise = 1.0 + 2.0.pow(-40)
        val input = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(precise).putDouble(-precise).array()
        val output = run(input, PcmEncoding.FLOAT_64_LE,
            listOf(GainNodeSpec("gain"), BiquadNodeSpec("identity", bands = listOf(BiquadCoefficients.IDENTITY))))
        assertArrayEquals(input, output)
    }

    @Test fun quiet24BitStereoSurvivesMonoGainAndEq() {
        val input = byteArrayOf(3, 0, 0, 1, 0, 0, -3, -1, -1, -1, -1, -1)
        val output = run(input, PcmEncoding.SIGNED_24_LE, listOf(
            MonoNodeSpec("mono"), GainNodeSpec("gain", linearGain = 0.5),
            BiquadNodeSpec("eq", bands = listOf(BiquadCoefficients.IDENTITY))))
        assertArrayEquals(byteArrayOf(1, 0, 0, 1, 0, 0, -1, -1, -1, -1, -1, -1), output)
    }

    @Test fun headroomIsNotClippedBetweenNodes() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.75f).putFloat(-0.75f).array()
        assertArrayEquals(input, run(input, PcmEncoding.FLOAT_32_LE,
            listOf(GainNodeSpec("up", linearGain = 2.0), GainNodeSpec("down", linearGain = 0.5))))
    }

    @Test fun monoAndWetDryHaveDeterministicPerNodeStereoTaps() {
        val rack = PrecisionSerialRack.compile(stereo, 2, listOf(GainNodeSpec("gain", linearGain = 2.0, wet = 0.5), MonoNodeSpec("mono")))
        val block = AudioBlock(stereo, 2)
        block.begin(2, 123_456L, 42)
        doubleArrayOf(0.5, -0.25, -0.5, 0.25).copyInto(block.samples)
        rack.process(block)
        assertArrayEquals(doubleArrayOf(0.1875, 0.1875, -0.1875, -0.1875), block.samples, 0.0)
        assertEquals(0.5, rack.inputTap.peakLeft, 0.0)
        assertEquals(0.25, rack.inputTap.rmsRight, 0.0)
        assertEquals(0.75, rack.nodeTaps[0].peakLeft, 0.0)
        assertEquals(0.375, rack.nodeTaps[0].rmsRight, 0.0)
        assertEquals(0.1875, rack.outputTap.peakLeft, 0.0)
        assertEquals(123_456L, rack.outputTap.presentationTimeUs)
        assertEquals(42L, block.firstFramePosition)
    }

    @Test fun stableBiquadImpulseMatchesAnalyticRecurrenceAndDoesNotLeakAcrossChannels() {
        val rack = PrecisionSerialRack.compile(stereo, 8,
            listOf(BiquadNodeSpec("eq", bands = listOf(BiquadCoefficients(0.5, 0.25, 0.0, -0.5, 0.0)))))
        val block = AudioBlock(stereo, 8)
        block.begin(8); block.samples[0] = 1.0
        rack.process(block)
        val expectedLeft = doubleArrayOf(0.5, 0.5, 0.25, 0.125, 0.0625, 0.03125, 0.015625, 0.0078125)
        expectedLeft.forEachIndexed { i, value -> assertEquals(value, block.samples[i * 2], 0.0); assertEquals(0.0, block.samples[i * 2 + 1], 0.0) }
        assertNull(rack.tailFrames)
        assertEquals(0, rack.latencyFrames)
        rack.reset(); block.samples.fill(0.0); rack.process(block)
        assertTrue(block.samples.all { it == 0.0 })
    }

    @Test fun filterStateIsInvariantToAudioCallbackPartitioning() {
        val coefficients = (0 until 64).map { BiquadCoefficients.peaking(48_000, 40.0 * 1.09.pow(it), if (it % 2 == 0) 1.5 else -1.5, 1.2) }
        val samples = DoubleArray(1024 * 2) { Random(it).nextDouble(-0.1, 0.1) }
        fun filtered(chunk: Int): DoubleArray {
            val rack = PrecisionSerialRack.compile(stereo, 1024, listOf(BiquadNodeSpec("eq", bands = coefficients)))
            val output = DoubleArray(samples.size)
            val block = AudioBlock(stereo, 1024)
            var frame = 0
            while (frame < 1024) {
                val frames = minOf(chunk, 1024 - frame)
                block.begin(frames); samples.copyInto(block.samples, 0, frame * 2, (frame + frames) * 2)
                rack.process(block); block.samples.copyInto(output, frame * 2, 0, frames * 2)
                frame += frames
            }
            assertEquals(64, coefficients.size)
            assertTrue(rack.descriptors.all { it.capabilities.arithmeticPrecision == SamplePrecision.FLOAT_64 && it.capabilities.statePrecision == SamplePrecision.FLOAT_64 })
            return output
        }
        assertArrayEquals(filtered(1024), filtered(17), 0.0)
    }

    @Test fun bypassedNodesLeaveSamplesUnchanged() {
        val samples = byteArrayOf(1, 0, 0, 1, 0, 0)
        assertArrayEquals(samples, run(samples, PcmEncoding.SIGNED_24_LE,
            listOf(GainNodeSpec("gain", linearGain = 64.0, bypass = true), MonoNodeSpec("mono", wet = 0.0))))
    }

    @Test fun explicitBypassPreservesAllBitsAndDoesNotDither() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0x7fc01234).putInt(Int.MIN_VALUE).array()
        val rack = PrecisionSerialRack.compile(stereo, 1, listOf(GainNodeSpec("gain", linearGain = 64.0)))
        val pipeline = PrecisionPcmPipeline(PcmEncoding.FLOAT_32_LE, PcmEncoding.FLOAT_32_LE, rack, TpdfDither())
        val out = ByteBuffer.allocate(8)
        pipeline.bypass(ByteBuffer.wrap(bytes), out, 1)
        assertArrayEquals(bytes, out.array())
    }

    @Test fun smoothedGainUsesFramesNotInterleavedSamplesAndContinuesAcrossBlocks() {
        val rack = PrecisionSerialRack.compile(stereo, 4, listOf(GainNodeSpec("gain")))
        val block = AudioBlock(stereo, 4)
        rack.setGainTarget("gain", 0.0, 4)
        block.begin(2); block.samples.fill(1.0); rack.process(block)
        assertArrayEquals(doubleArrayOf(0.75, 0.75, 0.5, 0.5), block.samples.copyOf(4), 0.0)
        block.begin(2); block.samples.fill(1.0); rack.process(block)
        assertArrayEquals(doubleArrayOf(0.25, 0.25, 0.0, 0.0), block.samples.copyOf(4), 0.0)
    }

    @Test fun integerOutputQuantizesOnlyAtFinalBoundary() {
        val block = AudioBlock(stereo, 2)
        block.begin(2); doubleArrayOf(2.0, -2.0, 1.0 / 65536, -1.0 / 65536).copyInto(block.samples)
        val output = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.encode(block, PcmEncoding.SIGNED_16_LE, output)
        output.flip()
        assertEquals(32767.toShort(), output.short); assertEquals((-32768).toShort(), output.short)
        assertEquals(1.toShort(), output.short); assertEquals(0.toShort(), output.short)
    }

    @Test fun tpdfDitherIsDeterministicAndRemainsWithinOneLsbAtSilence() {
        val block = AudioBlock(stereo, 1024); block.begin(1024)
        val a = ByteBuffer.allocate(4096); val b = ByteBuffer.allocate(4096)
        PcmBoundary.encode(block, PcmEncoding.SIGNED_16_LE, a, TpdfDither(733))
        PcmBoundary.encode(block, PcmEncoding.SIGNED_16_LE, b, TpdfDither(733))
        assertArrayEquals(a.array(), b.array())
        a.flip(); a.order(ByteOrder.LITTLE_ENDIAN)
        var nonzero = 0
        while (a.hasRemaining()) { val value = a.short.toInt(); assertTrue(value in -1..1); if (value != 0) nonzero++ }
        assertTrue(nonzero in 300..800)
    }

    @Test fun nonFiniteDecoderSamplesAreSanitizedBeforeRecursiveNodes() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(0.25f).putFloat(-0.25f).array()
        val out = ByteBuffer.wrap(run(bytes, PcmEncoding.FLOAT_32_LE,
            listOf(BiquadNodeSpec("eq", bands = listOf(BiquadCoefficients.IDENTITY))))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, out.float, 0f); assertEquals(0f, out.float, 0f)
        assertEquals(0.25f, out.float, 0f); assertEquals(-0.25f, out.float, 0f)
    }

    @Test fun compileRejectsUnsupportedOrUnboundedSchedules() {
        fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
        rejects { PrecisionSerialRack.compile(stereo, 256, listOf(GainNodeSpec("same"), MonoNodeSpec("same"))) }
        rejects { PrecisionSerialRack.compile(stereo, 256, List(33) { GainNodeSpec("g$it") }) }
        rejects { PrecisionSerialRack.compile(stereo, 256, listOf(BiquadNodeSpec("eq", bands = List(65) { BiquadCoefficients.IDENTITY }))) }
        rejects { PrecisionSerialRack.compile(AudioStreamFormat(48_000, ChannelLayout.MONO), 256, listOf(MonoNodeSpec("mono"))) }
        rejects { PrecisionSerialRack.compile(stereo, 256, listOf(GainNodeSpec("gain", linearGain = Double.NaN))) }
        rejects { BiquadCoefficients(1.0, 0.0, 0.0, 2.0, 0.0) }
    }

    @Test fun rejectedPcmBuffersDoNotConsumeAnyInput() {
        val rack = PrecisionSerialRack.compile(stereo, 1, emptyList())
        val input = ByteBuffer.wrap(ByteArray(6))
        val output = ByteBuffer.allocate(3)
        try { PrecisionPcmPipeline(PcmEncoding.SIGNED_24_LE, PcmEncoding.SIGNED_24_LE, rack).process(input, output, 1); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, input.position()); assertEquals(0, output.position())
    }

    @Test fun compileCopiesCallerOwnedBandList() {
        val bands = mutableListOf(BiquadCoefficients.IDENTITY)
        val rack = PrecisionSerialRack.compile(stereo, 1, listOf(BiquadNodeSpec("eq", bands = bands)))
        bands[0] = BiquadCoefficients(0.0, 0.0, 0.0, 0.0, 0.0)
        val block = AudioBlock(stereo, 1); block.begin(1); block.samples.fill(0.5); rack.process(block)
        assertArrayEquals(doubleArrayOf(0.5, 0.5), block.samples, 0.0)
    }

    private fun run(bytes: ByteArray, encoding: PcmEncoding, specs: List<RackNodeSpec>): ByteArray {
        val frames = bytes.size / (encoding.bytesPerSample * 2)
        val rack = PrecisionSerialRack.compile(stereo, frames, specs)
        val output = ByteBuffer.allocate(bytes.size)
        PrecisionPcmPipeline(encoding, encoding, rack).process(ByteBuffer.wrap(bytes), output, frames)
        return output.array()
    }
}
