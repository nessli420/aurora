package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** Real Media3 AudioProcessor boundary, without a player, shared settings or an AudioTrack. */
@UnstableApi
class PrecisionRackAudioProcessorDeviceTest {
    @Test fun optionalDitherPreservesBypassedSamplesAndOnlyQuantizesProcessedOutput() {
        val bypassed = rack(node("Bypassed gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f)).copy(bypass = true))
        val values = IntArray(32768) { (it * 31 % 60001) - 30000 }
        for (shaping in listOf(false, true)) {
            val adapter = configured(bypassed).apply { tpdfDither = true; noiseShaping = shaping }
            try {
                val input = pcm16(values)
                val expected = remainingBytes(input)
                val result = ByteArrayOutputStream()
                feed(adapter, input, result); finish(adapter, result)
                assertArrayEquals(expected, result.toByteArray())
            } finally { adapter.reset() }
            val processed = configured(rack(node("Gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f)))).apply {
                tpdfDither = true; noiseShaping = shaping
            }
            try {
                val result = ByteArrayOutputStream()
                feed(processed, pcm16(IntArray(65536) { 0 }), result); finish(processed, result)
                val noise = shorts(result.toByteArray())
                assertTrue(noise.any { it != 0 })
                assertTrue(noise.all { abs(it) <= if (shaping) 2 else 1 })
                assertTrue(abs(noise.average()) < .02)
                processed.flush()
                awaitReady(processed.engine)
                val replay = ByteArrayOutputStream()
                feedChunks(processed, IntArray(65536), replay, chunkFrames = 113, outputFramesPerRead = 17)
                finish(processed, replay)
                assertArrayEquals("Flush clears noise history; buffer sizes do not change output", result.toByteArray(), replay.toByteArray())
            } finally { processed.reset() }
        }
    }

    @Test fun dryPcm16IsBitIdenticalIncludingFullScaleAndOneLsbSamples() {
        val adapter = configured()
        try {
            // Every signed PCM16 value, paired asymmetrically, including both full-scale limits.
            val values = IntArray(65_536 * 2) { sample ->
                val frame = sample / 2
                if (sample % 2 == 0) frame - 32_768 else 32_767 - frame
            }
            val input = pcm16(values, prefixBytes = 11)
            val bytes = remainingBytes(input)
            val originalPosition = input.position()
            val result = ByteArrayOutputStream()
            feed(adapter, input, result)
            finish(adapter, result)
            assertArrayEquals("The compatibility boundary never attenuates, dithers or swaps dry PCM16", bytes, result.toByteArray())
            assertArrayEquals("The caller still owns the source contents", bytes,
                remainingBytes(input.duplicate().apply { position(originalPosition) }))
            assertEquals(input.limit(), input.position())
            assertFalse(adapter.engine.rackActive)
            assertTrue(adapter.isEnded())
            val afterEnd = pcm16(intArrayOf(5, -5))
            adapter.queueInput(afterEnd)
            assertEquals("EOS rejects new input until flush", 0, afterEnd.position())
        } finally { adapter.reset() }
    }

    @Test fun unreadOutputBlocksSourceConsumptionAndKeepsItsOriginalBytes() {
        val adapter = configured()
        try {
            val values = IntArray(1_003 * 2) { (it * 97 % 24_001) - 12_000 }
            // The boundary must decode the PCM byte order, regardless of the ByteBuffer flag.
            val input = pcm16(values, prefixBytes = 7).order(ByteOrder.BIG_ENDIAN)
            val expected = remainingBytes(input)
            val result = ByteArrayOutputStream()
            val originalPosition = input.position()
            adapter.queueInput(input)
            assertEquals("Accept one bounded block", originalPosition + 256 * 4, input.position())
            val held = adapter.getOutput()
            assertEquals(256 * 4, held.remaining())
            val heldBytes = remainingBytes(held)
            consume(held, 17 * 4, result)
            val heldPosition = held.position()
            val sourcePosition = input.position()
            repeat(3) {
                adapter.queueInput(input)
                assertEquals("Do not consume source while its prior output is held", sourcePosition, input.position())
                assertEquals("Do not rewind the downstream buffer", heldPosition, held.position())
                assertFalse("Each output buffer is transferred only once", adapter.getOutput().hasRemaining())
            }
            assertArrayEquals(heldBytes.copyOfRange(17 * 4, heldBytes.size), remainingBytes(held))
            consume(held, held.remaining(), result)
            feed(adapter, input, result, outputFramesPerRead = 23)
            finish(adapter, result, outputFramesPerRead = 11)
            assertArrayEquals("Partial downstream consumption cannot lose, repeat or replace frames", expected, result.toByteArray())
        } finally { adapter.reset() }
    }

    @Test fun gainConvolutionWetMixAndSuffixGainUseOneFinalPcm16Quantization() {
        val wet = .35f
        val graph = rack(
            node("Input gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f)),
            node("Asymmetric FIR", RackNodeKind.CONVOLUTION, AudioPrefs(dspConvMakeupDb = 1f), wet = wet),
            node("Output gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = 8f)),
        )
        val ir = ImpulseResponse(floatArrayOf(0f, .5f), floatArrayOf(.25f, .125f), 48_000)
        val adapter = configured(graph, ir)
        try {
            val frames = 3_713 // Partial FFT block at EOS, with different L/R histories.
            val values = IntArray(frames * 2) { sample -> ((sample * 137) % 4_093) - 2_046 }
            val result = ByteArrayOutputStream()
            feedChunks(adapter, values, result, chunkFrames = 227, outputFramesPerRead = 19)
            assertTrue(adapter.engine.rackActive)
            assertTrue(adapter.engine.convolutionProcessingActive)
            finish(adapter, result, outputFramesPerRead = 31)
            assertEquals("The exact FIR tail follows the source", (frames + 1) * 4, result.size())
            val actual = shorts(result.toByteArray())
            val gain = 10.0.pow(2.0 / 20.0)
            val makeup = 10.0.pow(1.0 / 20.0)
            actual.indices.forEach { sample ->
                val source = values.getOrElse(sample) { 0 }.toDouble()
                val previous = if (sample >= 2) values.getOrElse(sample - 2) { 0 }.toDouble() else 0.0
                val filtered = if (sample % 2 == 0) previous * .5 else source * .25 + previous * .125
                val expected = quantized((source * (1.0 - wet.toDouble()) + filtered * makeup * wet.toDouble()) * gain)
                assertTrue("One final boundary, sample $sample: expected $expected, got ${actual[sample]}", abs(expected - actual[sample]) <= 1)
            }
        } finally { adapter.reset() }
    }

    @Test fun eosDoesNotEndOrReplaceOutputStillOwnedByTheDownstreamProcessor() {
        val adapter = configured()
        try {
            val input = pcm16(constantStereo(37, 5_432, -7_654))
            val expected = remainingBytes(input)
            adapter.queueInput(input)
            assertFalse(input.hasRemaining())
            val held = adapter.getOutput()
            val result = ByteArrayOutputStream()
            consume(held, 3 * 4, result)
            val tail = remainingBytes(held)
            adapter.queueEndOfStream()
            assertFalse("EOS still waits for the consumer's final partial buffer", adapter.isEnded())
            assertArrayEquals(tail, remainingBytes(held))
            assertFalse("Do not transfer the same retained output twice", adapter.getOutput().hasRemaining())
            consume(held, held.remaining(), result)
            assertTrue(adapter.isEnded())
            assertFalse(adapter.getOutput().hasRemaining())
            assertArrayEquals(expected, result.toByteArray())
        } finally { adapter.reset() }
    }

    @Test fun quietSamplesSurviveAttenuationAndRestorationAcrossAnIdentityIr() {
        val adapter = configured(rack(
            node("Quiet", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -12f)),
            node("Identity IR", RackNodeKind.CONVOLUTION),
            node("Restore", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = 12f)),
        ), ImpulseResponse(floatArrayOf(1f), floatArrayOf(1f), 48_000))
        try {
            val levels = intArrayOf(-31, -7, -3, -2, -1, 0, 1, 2, 3, 7, 31)
            val input = pcm16(IntArray(1_503 * 2) { levels[it % levels.size] })
            val expected = remainingBytes(input)
            val result = ByteArrayOutputStream()
            feed(adapter, input, result)
            finish(adapter, result)
            // An intermediate PCM16 conversion after -12 dB would erase the one-LSB values.
            assertArrayEquals(expected, result.toByteArray())
        } finally { adapter.reset() }
    }

    @Test fun liveRackDisableAndReenablePreserveHeldOutputAndBlendToTheSelectedGain() {
        val graph = rack(node("Rack gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -12f)))
        val engine = PrecisionBlockProcessor().apply {
            enabled = true
            update(DspParams(preampDb = -3f, limiterEnabled = false))
            updateRack(graph)
        }
        val adapter = configured(engine = engine)
        try {
            awaitReady(engine)
            val values = constantStereo(4_096, 6_000, -4_000)
            val input = pcm16(values)
            val result = ByteArrayOutputStream()
            adapter.queueInput(input)
            val held = adapter.getOutput()
            assertEquals(256 * 4, held.remaining())
            val initialBytes = remainingBytes(held)
            val inputPosition = input.position()
            engine.updateRack(null)
            awaitReady(engine)
            adapter.queueInput(input)
            assertEquals(inputPosition, input.position())
            assertArrayEquals("An edit cannot overwrite already transferred output", initialBytes, remainingBytes(held))
            consume(held, held.remaining(), result)
            feed(adapter, input, result)
            assertEquals(values.size * 2, result.size())
            val disabled = shorts(result.toByteArray())
            assertEquals(quantized(6_000 * 10.0.pow(-12.0 / 20.0)), disabled[0])
            assertEquals(quantized(6_000 * 10.0.pow(-3.0 / 20.0)), disabled[disabled.size - 2])
            assertEquals(quantized(-4_000 * 10.0.pow(-3.0 / 20.0)), disabled.last())
            assertContinuous(disabled, increasing = true)
            assertFalse("Disabling returns to the current standard settings", engine.rackActive)

            engine.updateRack(graph.copy(nodes = graph.nodes.map { it.copy(audio = it.audio.copy(dspPreampDb = -6f)) }))
            awaitReady(engine)
            val enabled = ByteArrayOutputStream()
            feedChunks(adapter, constantStereo(3_107, 6_000, -4_000), enabled, chunkFrames = 173)
            finish(adapter, enabled)
            assertEquals(3_107 * 4, enabled.size())
            val again = shorts(enabled.toByteArray())
            assertContinuous(again, increasing = false)
            assertEquals(quantized(6_000 * 10.0.pow(-6.0 / 20.0)), again[again.size - 2])
            assertEquals(quantized(-4_000 * 10.0.pow(-6.0 / 20.0)), again.last())
            assertTrue(engine.rackActive)
        } finally { adapter.reset() }
    }

    @Test fun flushDropsPendingIrFramesAndFilterDelayHistoryBeforeNewAudio() {
        val impulse = FloatArray(1_501).apply { this[0] = .5f; this[1_400] = .125f }
        val adapter = configured(rack(
            node("Resonance", RackNodeKind.EQ, AudioPrefs(dspParametric = listOf(ParamBand(700f, 6f, 2f)))),
            node("Alignment", RackNodeKind.DELAY, AudioPrefs(dspDelayLeftMs = 5f, dspDelayRightMs = 7f)),
            node("Room", RackNodeKind.CONVOLUTION),
        ), ImpulseResponse(impulse, impulse.copyOf(), 48_000))
        try {
            val warmup = ByteArrayOutputStream()
            feed(adapter, pcm16(IntArray(503 * 2).apply { this[0] = 12_000; this[1] = -8_000 }), warmup)
            assertEquals("The original stream owns a partial convolution block", 0, warmup.size())
            assertTrue(adapter.engine.hasPendingData)
            adapter.flush()
            awaitReady(adapter.engine)
            val afterSeek = ByteArrayOutputStream()
            feed(adapter, pcm16(IntArray(2_105 * 2)), afterSeek)
            finish(adapter, afterSeek)
            assertEquals((2_105 + adapter.engine.rackTailFrames) * 4, afterSeek.size())
            assertArrayEquals("A seek cannot leak previous IR, EQ or channel-delay history", ByteArray((2_105 + adapter.engine.rackTailFrames) * 4), afterSeek.toByteArray())
        } finally { adapter.reset() }
    }

    @Test fun configureStagesTheNextRateAndFlushStartsItWithNoPreviousOutput() {
        val adapter = configured()
        try {
            val first = pcm16(constantStereo(37, 1_234, -2_345))
            adapter.queueInput(first)
            val held = adapter.getOutput()
            val expected = remainingBytes(held)
            val nextFormat = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
            assertEquals(nextFormat, adapter.configure(nextFormat))
            assertArrayEquals("configure alone preserves the old stream's owned output", expected, remainingBytes(held))
            held.position(held.limit())
            adapter.flush()
            val next = pcm16(IntArray(521 * 2) { it % 997 - 498 })
            val nextBytes = remainingBytes(next)
            val result = ByteArrayOutputStream()
            feed(adapter, next, result)
            finish(adapter, result)
            assertArrayEquals(nextBytes, result.toByteArray())

            assertEquals(AudioFormat.NOT_SET, adapter.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)))
            assertFalse(adapter.isActive())
            adapter.flush()
            assertFalse(adapter.getOutput().hasRemaining())
            assertEquals(AudioFormat.NOT_SET, adapter.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
            assertFalse(adapter.isActive())
        } finally { adapter.reset() }
    }

    private fun configured(graph: ProcessingRack? = null, impulse: ImpulseResponse? = null,
        engine: PrecisionBlockProcessor = PrecisionBlockProcessor()): PrecisionRackAudioProcessor {
        if (impulse != null) engine.setImpulse(impulse, 0f)
        if (graph != null) engine.updateRack(graph)
        return PrecisionRackAudioProcessor(engine).also {
            val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
            assertEquals(format, it.configure(format))
            assertTrue(it.isActive())
            it.flush()
            if (graph != null) awaitReady(engine)
        }
    }

    private fun awaitReady(engine: PrecisionBlockProcessor) = await("Prepare rack") {
        check(engine.preparationState != ConvolutionPreparationState.FAILED) { engine.preparationFailure ?: "Rack preparation failed" }
        engine.preparationState == ConvolutionPreparationState.READY
    }

    private fun feedChunks(adapter: PrecisionRackAudioProcessor, values: IntArray, result: ByteArrayOutputStream,
        chunkFrames: Int, outputFramesPerRead: Int = Int.MAX_VALUE) {
        var sample = 0
        while (sample < values.size) {
            val end = minOf(values.size, sample + chunkFrames * 2)
            feed(adapter, pcm16(values.copyOfRange(sample, end)), result, outputFramesPerRead)
            sample = end
        }
    }

    private fun feed(adapter: PrecisionRackAudioProcessor, input: ByteBuffer, result: ByteArrayOutputStream,
        outputFramesPerRead: Int = Int.MAX_VALUE) {
        var deadline = SystemClock.elapsedRealtime() + 5_000
        while (input.hasRemaining()) {
            val position = input.position()
            val written = result.size()
            adapter.queueInput(input)
            drain(adapter, result, outputFramesPerRead)
            if (input.position() != position || result.size() != written) {
                deadline = SystemClock.elapsedRealtime() + 5_000
            } else {
                assertTrue("Consume PCM16 input stalled", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(1)
            }
        }
    }

    private fun finish(adapter: PrecisionRackAudioProcessor, result: ByteArrayOutputStream,
        outputFramesPerRead: Int = Int.MAX_VALUE) {
        adapter.queueEndOfStream()
        await("Drain PCM16 EOS") {
            drain(adapter, result, outputFramesPerRead)
            adapter.isEnded()
        }
        assertFalse(adapter.getOutput().hasRemaining())
    }

    private fun drain(adapter: PrecisionRackAudioProcessor, result: ByteArrayOutputStream, framesPerRead: Int) {
        repeat(32) {
            val output = adapter.getOutput()
            if (!output.hasRemaining()) return
            assertEquals("PCM16 stereo output must contain complete frames", 0, output.remaining() % 4)
            while (output.hasRemaining()) {
                val count = minOf(output.remaining().toLong(), framesPerRead.toLong() * 4).toInt()
                consume(output, count, result)
            }
        }
        error("Output did not converge without new input")
    }

    private fun consume(buffer: ByteBuffer, bytes: Int, result: ByteArrayOutputStream) {
        val data = ByteArray(bytes)
        buffer.get(data)
        result.write(data)
    }

    private fun pcm16(values: IntArray, prefixBytes: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(prefixBytes + values.size * 2 + 9).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(prefixBytes) { put(0x6b.toByte()) }
            values.forEach { putShort(it.toShort()) }
            limit(position())
            position(prefixBytes)
        }

    private fun remainingBytes(buffer: ByteBuffer): ByteArray = buffer.duplicate().let { ByteArray(it.remaining()).also(it::get) }
    private fun shorts(bytes: ByteArray): IntArray = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { b -> IntArray(bytes.size / 2) { b.short.toInt() } }
    private fun constantStereo(frames: Int, left: Int, right: Int) = IntArray(frames * 2) { if (it % 2 == 0) left else right }
    private fun quantized(sampleUnits: Double) = floor(sampleUnits + .5).toInt().coerceIn(-32_768, 32_767)
    private fun rack(vararg nodes: ProcessingRackNode) = ProcessingRack(enabled = true, name = "Adapter fixture", nodes = nodes.toList())
    private fun node(name: String, kind: RackNodeKind, audio: AudioPrefs = AudioPrefs(), wet: Float = 1f) =
        ProcessingRackNode(UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString(), name, kind, wet = wet, audio = audio)

    private fun assertContinuous(values: IntArray, increasing: Boolean) {
        for (sample in 2 until values.size step 2) {
            val change = values[sample] - values[sample - 2]
            assertTrue("A live edit must not drop a block or jump gain at sample $sample ($change)", abs(change) <= 8)
            assertTrue("The constant-input transition is monotonic", if (increasing) change >= -1 else change <= 1)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            assertTrue("$label timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(1)
        }
    }
}
