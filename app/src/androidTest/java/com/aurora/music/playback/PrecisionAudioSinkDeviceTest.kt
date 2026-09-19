package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/** Decoder-facing sink contract using the real precision processor and a controllable output sink. */
@UnstableApi
class PrecisionAudioSinkDeviceTest {
    @Test fun sameStreamCompatibilitySwitchDoesNotInsertLongConvolutionTail() {
        val fake = RecordingSink().apply { writePattern = intArrayOf(0) }
        val ir = FloatArray(4096).apply { this[0] = .5f; this[lastIndex] = .25f }
        val processor = PrecisionBlockProcessor().apply {
            convolutionEnabled = true
            setImpulse(ImpulseResponse(ir, ir, 48_000), 0f)
        }
        val sink = PrecisionAudioSink(fake.delegate, processor, PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            val frames = 5003
            val source = pcm24(IntArray(frames * 2) { 500_000 })
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (fake.retained == null) {
                assertFalse(sink.handleBuffer(source, 1_000_000, 1))
                assertTrue(SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(1)
            }
            val acceptedFrames = source.position() / 6
            assertTrue(acceptedFrames in 1 until frames)
            sink.setSkipSilenceEnabled(true)
            fake.writePattern = intArrayOf(Int.MAX_VALUE)
            send(sink, source, 1_000_000)
            end(sink)
            assertEquals(2, fake.epochs.size)
            assertEquals(acceptedFrames * 8, fake.epochs[0].bytes.size())
            assertEquals((frames - acceptedFrames) * 4, fake.epochs[1].bytes.size())
        } finally { sink.reset() }
    }

    @Test fun realEndOfStreamPreservesCompleteLongImpulseTail() {
        val fake = RecordingSink().apply { writePattern = intArrayOf(0, 41, 1024) }
        val ir = FloatArray(4096).apply { this[0] = .5f; this[lastIndex] = .25f }
        val processor = PrecisionBlockProcessor().apply {
            convolutionEnabled = true
            setImpulse(ImpulseResponse(ir, ir, 48_000), 0f)
        }
        val sink = PrecisionAudioSink(fake.delegate, processor, PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            val frames = 257
            val source = IntArray(frames * 2).apply { this[lastIndex - 1] = 2_097_152; this[lastIndex] = -2_097_152 }
            send(sink, pcm24(source), 2_000_000)
            end(sink)
            val bytes = fake.epochs.single().bytes.toByteArray()
            assertEquals((frames + ir.size - 1) * 8, bytes.size)
            val last = ByteBuffer.wrap(bytes, bytes.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(.0625f, last.float, 1e-6f)
            assertEquals(-.0625f, last.float, 1e-6f)
        } finally { sink.reset() }
    }

    @Test fun rateConversionKeepsDurationTimestampAndBackpressureAcrossSeek() {
        val fake = RecordingSink().apply { writePattern = intArrayOf(0, 19, 7, 1024) }
        val policy = com.aurora.music.playback.engine.OutputRatePolicy(
            mode = com.aurora.music.playback.engine.OutputRateMode.FIXED, fixedRate = 96_000)
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter(), { policy }, { intArrayOf(48_000, 96_000) })
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            val frames = 5003
            repeat(2) { run ->
                if (run > 0) { sink.flush(); fake.clearCapturedOutput() }
                val start = 1_000_000L + run * 4_000_000L
                send(sink, pcm24(IntArray(frames * 2) { if (it % 2 == 0) 100_000 else -200_000 }), start)
                end(sink)
                val epoch = fake.epochs.single()
                assertEquals(96_000, epoch.format.sampleRate)
                assertEquals(frames * 2 * 8, epoch.bytes.size())
                epoch.starts.forEach { assertEquals(start + it.offsetBytes / 8 * 1_000_000L / 96_000, it.timeUs) }
                assertTrue(fake.zeroWrites > 0)
                assertNull(sink.rateFallbackReason)
            }
        } finally { sink.reset() }
    }

    @Test fun unsupportedRatePolicyFallsBackToSourceWithReason() {
        val fake = RecordingSink()
        val policy = com.aurora.music.playback.engine.OutputRatePolicy(
            mode = com.aurora.music.playback.engine.OutputRateMode.FIXED, fixedRate = 192_000)
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter(), { policy }, { intArrayOf(48_000) })
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            send(sink, pcm24(IntArray(514) { 100 }), 0)
            end(sink)
            assertEquals(48_000, fake.epochs.single().format.sampleRate)
            assertNotNull(sink.rateFallbackReason)
        } finally { sink.reset() }
    }

    @Test fun rateConversionCanRecoverAnInitiallyUnknownTimestamp() {
        val fake = RecordingSink()
        val policy = com.aurora.music.playback.engine.OutputRatePolicy(
            mode = com.aurora.music.playback.engine.OutputRateMode.FIXED, fixedRate = 96_000)
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter(), { policy }, { intArrayOf(96_000) })
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            send(sink, pcm24(IntArray(256) { 1000 }), C.TIME_UNSET)
            send(sink, pcm24(IntArray(1792) { 1000 }), 900_000L + 128 * 1_000_000L / 48_000)
            end(sink)
            val starts = fake.epochs.single().starts
            assertTrue(starts.any { it.timeUs == C.TIME_UNSET })
            assertTrue(starts.any { it.timeUs != C.TIME_UNSET })
            starts.filter { it.timeUs != C.TIME_UNSET }.forEach {
                assertEquals(900_000L + it.offsetBytes / 8 * 1_000_000L / 96_000, it.timeUs)
            }
        } finally { sink.reset() }
    }

    @Test fun quietPcm24SurvivesEffectsAndConvolutionWithoutIntermediateQuantization() {
        val fake = RecordingSink()
        val processor = PrecisionBlockProcessor().apply {
            enabled = true
            update(DspParams(preampDb = -6f, limiterEnabled = false))
            convolutionEnabled = true
            setImpulse(ImpulseResponse(floatArrayOf(.5f), floatArrayOf(.25f), 48_000), 0f)
        }
        val meter = PcmLevelMeter()
        val sink = PrecisionAudioSink(fake.delegate, processor, meter)
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            // Longer than a meter window and deliberately not an FFT block multiple.
            val frames = 5_503
            val values = IntArray(frames * 2) { listOf(-127, -7, -1, 1, 7, 127)[it % 6] }
            val input = pcm24(values)
            send(sink, input, 700_000)
            end(sink)
            assertTrue(sink.precisionActive)
            assertEquals(C.ENCODING_PCM_FLOAT, fake.epochs.single().format.pcmEncoding)
            val bytes = fake.epochs.single().bytes.toByteArray()
            assertEquals("EOS emits exactly the source frame count", frames * 8, bytes.size)
            val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val gain = 10.0.pow(-6.0 / 20.0)
            repeat(values.size) { sample ->
                val expected = (values[sample] / 8_388_608.0 * gain * if (sample % 2 == 0) .5 else .25).toFloat()
                val actual = output.float
                assertTrue("Sub-PCM16 sample $sample survives", actual != 0f)
                assertEquals("sample $sample", expected.toDouble(), actual.toDouble(), abs(expected.toDouble()) * 1e-5 + 1e-12)
            }
            assertEquals(0L, requireNotNull(meter.snapshot()).invalidSamples)
            assertEquals(0L, requireNotNull(meter.snapshot()).fullScaleSamples)
            assertTrue(fake.endCalls > 0)
        } finally { sink.reset() }
    }

    @Test fun retainedOutputRetriesKeepTheirIdentityTimestampAndEverySourceFrame() {
        val fake = RecordingSink().apply { writePattern = intArrayOf(0, 17, 0, 5, 2_048) }
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            val frames = 1_003
            val values = IntArray(frames * 2) { (it * 71 % 16_381) - 8_190 }
            val input = pcm24(values, prefixBytes = 13)
            val original = input.duplicate().let { ByteArray(it.remaining()).also(it::get) }
            val initialPosition = input.position()
            val startUs = 1_234_000L
            send(sink, input, startUs)
            end(sink)
            assertEquals(input.limit(), input.position())
            val unchanged = input.duplicate().apply { position(initialPosition) }
                .let { ByteArray(it.remaining()).also(it::get) }
            assertArrayEquals("Only source position changes", original, unchanged)
            val epoch = fake.epochs.single()
            assertTrue("Delegate actually applied backpressure", fake.zeroWrites > 0)
            assertTrue("Delegate actually consumed partial buffers", fake.partialWrites > 0)
            assertEquals(frames * 8, epoch.bytes.size())
            epoch.starts.forEach { start ->
                assertEquals("Each output block retains its first-source-frame time",
                    startUs + start.offsetBytes / 8 * 1_000_000L / 48_000, start.timeUs)
            }
            val result = ByteBuffer.wrap(epoch.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { value -> assertEquals((value / 8_388_608.0).toFloat(), result.float, 0f) }
        } finally { sink.reset() }
    }

    @Test fun configureWaitsUntilRetainedOldOutputHasDrained() {
        val fake = RecordingSink().apply { writePattern = intArrayOf(0) }
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT, 48_000), 0, null)
            val old = pcm24(IntArray(256 * 2) { if (it % 2 == 0) 4_096 else -8_192 })
            sink.handleBuffer(old, 200_000, 1)
            assertEquals("First source block was accepted", old.limit(), old.position())
            assertTrue("Output is still held by the delegate", fake.retained?.hasRemaining() == true)
            sink.configure(format(C.ENCODING_PCM_24BIT, 44_100), 0, null)
            assertEquals("Configure only stages the next format", 1, fake.epochs.size)
            fake.writePattern = intArrayOf(23)
            val next = pcm24(IntArray(511 * 2) { if (it % 2 == 0) -16_384 else 32_768 })
            send(sink, next, 3_000_000)
            end(sink)
            assertEquals(listOf(48_000, 44_100), fake.epochs.map { it.format.sampleRate })
            assertEquals(256 * 8, fake.epochs[0].bytes.size())
            assertEquals(511 * 8, fake.epochs[1].bytes.size())
            assertEquals(200_000L, fake.epochs[0].starts.first().timeUs)
            assertEquals(3_000_000L, fake.epochs[1].starts.first().timeUs)
            val oldOutput = ByteBuffer.wrap(fake.epochs[0].bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(256) { assertEquals(4_096 / 8_388_608f, oldOutput.float, 0f); assertEquals(-8_192 / 8_388_608f, oldOutput.float, 0f) }
        } finally { sink.reset() }
    }

    @Test fun flushDiscardsPartialConvolutionAndEffectsHistoryBeforeASeek() {
        val fake = RecordingSink()
        val processor = PrecisionBlockProcessor().apply {
            enabled = true
            update(DspParams(limiterEnabled = false, delayLeftMs = 1f, delayRightMs = 2f,
                parametric = listOf(DspBand(700f, 3f, 1f))))
            convolutionEnabled = true
            val ir = FloatArray(1_500).apply { this[0] = .5f; this[1_400] = .25f }
            setImpulse(ImpulseResponse(ir, ir.copyOf(), 48_000), 0f)
        }
        val sink = PrecisionAudioSink(fake.delegate, processor, PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            send(sink, pcm24(IntArray(503 * 2).apply { this[0] = 2_097_152; this[1] = -1_048_576 }), 0)
            sink.flush()
            assertFalse(sink.precisionActive)
            fake.clearCapturedOutput()
            send(sink, pcm24(IntArray(2_105 * 2)), 5_000_000)
            end(sink)
            val bytes = fake.epochs.flatMap { it.bytes.toByteArray().asIterable() }.toByteArray()
            assertEquals((2_105 + 1_499) * 8, bytes.size)
            val silence = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (silence.hasRemaining()) assertEquals("No pre-seek filter, delay or IR history", 0f, silence.float, 0f)
            assertEquals(5_000_000L, fake.epochs.last().starts.first().timeUs)
        } finally { sink.reset() }
    }

    @Test fun trimmedOrMappedSourcesKeepThePcm16CompatibilityBoundary() {
        val fake = RecordingSink()
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            val source = format(C.ENCODING_PCM_24BIT).buildUpon().setEncoderDelay(3).setEncoderPadding(7).build()
            val channels = intArrayOf(1, 0)
            sink.configure(source, 0, channels)
            val values = IntArray(37 * 2) { if (it % 2 == 0) 32_768 else -65_536 }
            send(sink, pcm24(values), 400_000)
            end(sink)
            assertFalse(sink.precisionActive)
            assertNotNull(sink.fallbackReason)
            val epoch = fake.epochs.single()
            assertEquals(C.ENCODING_PCM_16BIT, epoch.format.pcmEncoding)
            assertEquals(3, epoch.format.encoderDelay)
            assertEquals(7, epoch.format.encoderPadding)
            assertArrayEquals(channels, epoch.outputChannels)
            // The delegate owns encoder trimming and channel mapping; this fake records its input.
            assertEquals(values.size * 2, epoch.bytes.size())
            val output = ByteBuffer.wrap(epoch.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { assertEquals((it / 256).toShort(), output.short) }
        } finally { sink.reset() }
    }

    @Test fun aPaddedStreamKeepsFollowingStreamsCompatibleUntilTheSinkIsReset() {
        val fake = RecordingSink()
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            val ordinary = format(C.ENCODING_PCM_24BIT)
            val padded = ordinary.buildUpon().setEncoderDelay(3).setEncoderPadding(7).build()
            val samples = IntArray(37 * 2) { if (it % 2 == 0) 32_768 else -65_536 }
            sink.configure(padded, 0, null)
            send(sink, pcm24(samples), 100_000)
            assertEquals(C.ENCODING_PCM_16BIT, fake.epochs.single().format.pcmEncoding)
            assertEquals(3, fake.epochs.single().format.encoderDelay)
            assertEquals(7, fake.epochs.single().format.encoderPadding)

            // This stream has no trim metadata, but must give Media3's previous trimmer a
            // compatible reconfiguration so its buffered end padding is handled correctly.
            sink.configure(ordinary, 0, null)
            send(sink, pcm24(samples), 800_000)
            end(sink)
            assertEquals(listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT), fake.epochs.map { it.format.pcmEncoding })
            assertEquals(0, fake.epochs.last().format.encoderDelay)
            assertEquals(0, fake.epochs.last().format.encoderPadding)
            assertFalse(sink.precisionActive)
            val reason = requireNotNull(sink.fallbackReason)
            assertTrue(reason, reason.contains("padd", ignoreCase = true) || reason.contains("trim", ignoreCase = true))
            assertEquals(samples.size * 2, fake.epochs.last().bytes.size())

            sink.reset()
            sink.configure(ordinary, 0, null)
            send(sink, pcm24(samples), 2_000_000)
            end(sink)
            assertEquals(3, fake.epochs.size)
            assertEquals(C.ENCODING_PCM_FLOAT, fake.epochs.last().format.pcmEncoding)
            assertTrue(sink.precisionActive)
            assertNull(sink.fallbackReason)
            assertEquals(samples.size * 4, fake.epochs.last().bytes.size())
        } finally { sink.reset() }
    }

    @Test fun enablingSilenceSkippingMovesToStickyPcm16WithoutDroppingFrames() {
        val fake = RecordingSink()
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            val samples = IntArray(256 * 2) { if (it % 2 == 0) 2_097_152 else -1_048_576 }
            send(sink, pcm24(samples), 0)
            assertEquals(C.ENCODING_PCM_FLOAT, fake.epochs.single().format.pcmEncoding)
            sink.setSkipSilenceEnabled(true)
            send(sink, pcm24(samples), 256 * 1_000_000L / 48_000)
            sink.setSkipSilenceEnabled(false)
            send(sink, pcm24(samples), 512 * 1_000_000L / 48_000)
            end(sink)
            assertEquals(listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT), fake.epochs.map { it.format.pcmEncoding })
            assertEquals(256 * 8, fake.epochs[0].bytes.size())
            assertEquals(512 * 4, fake.epochs[1].bytes.size())
            assertFalse(sink.precisionActive)
            val output = ByteBuffer.wrap(fake.epochs[1].bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(512) { assertEquals(8_192.toShort(), output.short); assertEquals((-4_096).toShort(), output.short) }
            assertEquals(listOf(true, false), fake.skipChanges.takeLast(2))
        } finally { sink.reset() }
    }

    @Test fun silenceFallbackDuringBackpressureKeepsTheOriginalDecoderTimeAnchor() {
        val fake = RecordingSink().apply { writePattern = intArrayOf(0) }
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            sink.configure(format(C.ENCODING_PCM_24BIT), 0, null)
            val frames = 700
            val values = IntArray(frames * 2) { if (it % 2 == 0) 2_097_152 else -1_048_576 }
            val input = pcm24(values)
            val startUs = 2_300_000L
            assertFalse(sink.handleBuffer(input, startUs, 1))
            val acceptedFrames = input.position() / 6
            assertTrue("Transition interrupts a partially accepted decoder buffer", acceptedFrames in 1 until frames)
            assertTrue(fake.retained?.hasRemaining() == true)
            sink.setSkipSilenceEnabled(true)
            fake.writePattern = intArrayOf(17)
            // Retry the same original decoder buffer and PTS, with its already-advanced position.
            send(sink, input, startUs)
            end(sink)
            assertEquals(listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT), fake.epochs.map { it.format.pcmEncoding })
            assertEquals(acceptedFrames * 8, fake.epochs[0].bytes.size())
            assertEquals((frames - acceptedFrames) * 4, fake.epochs[1].bytes.size())
            fake.epochs[1].starts.forEach { start ->
                assertEquals("Compatibility output continues at its source-frame timestamp",
                    startUs + (acceptedFrames + start.offsetBytes / 4) * 1_000_000L / 48_000, start.timeUs)
            }
            val output = ByteBuffer.wrap(fake.epochs[1].bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames - acceptedFrames) { assertEquals(8_192.toShort(), output.short); assertEquals((-4_096).toShort(), output.short) }
        } finally { sink.reset() }
    }

    @Test fun formatSupportRequiresDecodedPcmEvenWhenTheDelegateAcceptsEncodedAudio() {
        val fake = RecordingSink()
        val sink = PrecisionAudioSink(fake.delegate, PrecisionBlockProcessor(), PcmLevelMeter())
        try {
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format(C.ENCODING_PCM_24BIT)))
            val encoded = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(48_000).setChannelCount(2).build()
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
            assertFalse(sink.supportsFormat(encoded))
        } finally { sink.reset() }
    }

    private fun format(encoding: Int, rate: Int = 48_000): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(rate).setChannelCount(2).setPcmEncoding(encoding).build()

    private fun pcm24(values: IntArray, prefixBytes: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(prefixBytes + values.size * 3).apply {
            repeat(prefixBytes) { put(0x5a.toByte()) }
            values.forEach { value -> put(value.toByte()); put((value ushr 8).toByte()); put((value ushr 16).toByte()) }
            flip(); position(prefixBytes)
        }

    private fun send(sink: PrecisionAudioSink, input: ByteBuffer, timeUs: Long) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!sink.handleBuffer(input, timeUs, 1)) {
            assertTrue("Source/output made no progress", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(1)
        }
        assertEquals("Every source frame accepted", input.limit(), input.position())
    }

    private fun end(sink: PrecisionAudioSink) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            sink.playToEndOfStream()
            assertTrue("EOS drain made no progress", SystemClock.elapsedRealtime() < deadline)
            if (!sink.isEnded) SystemClock.sleep(1)
        } while (!sink.isEnded)
    }

    private data class BufferStart(val offsetBytes: Int, val timeUs: Long)
    private data class Epoch(val format: Format, val outputChannels: IntArray?,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(), val starts: MutableList<BufferStart> = mutableListOf())

    private class RecordingSink {
        val epochs = mutableListOf<Epoch>()
        val skipChanges = mutableListOf<Boolean>()
        var writePattern = intArrayOf(Int.MAX_VALUE)
        var retained: ByteBuffer? = null
            private set
        private var retainedTimeUs = 0L
        private var callCount = 0
        var zeroWrites = 0
            private set
        var partialWrites = 0
            private set
        var endCalls = 0
            private set
        private var ended = false
        private var skipEnabled = false
        private var parameters = PlaybackParameters.DEFAULT

        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "supportsFormat" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "configure" -> {
                    assertFalse("Old output must drain before configuring the next format", retained?.hasRemaining() == true)
                    val arguments = requireNotNull(args)
                    epochs += Epoch(arguments[0] as Format, (arguments[2] as? IntArray)?.copyOf())
                    ended = false
                    null
                }
                "handleBuffer" -> {
                    val arguments = requireNotNull(args)
                    val buffer = arguments[0] as ByteBuffer
                    val timeUs = arguments[1] as Long
                    val epoch = epochs.last()
                    if (!buffer.hasRemaining()) true else {
                        if (retained != null) {
                            assertSame("Retry must use the retained output buffer", retained, buffer)
                            assertEquals("Retry must retain the block timestamp", retainedTimeUs, timeUs)
                        } else {
                            retained = buffer; retainedTimeUs = timeUs
                            epoch.starts += BufferStart(epoch.bytes.size(), timeUs)
                        }
                        val bytesPerFrame = epoch.format.channelCount * if (epoch.format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
                        val frameBudget = writePattern[callCount++ % writePattern.size]
                        val count = minOf(buffer.remaining().toLong(), frameBudget.toLong() * bytesPerFrame).toInt()
                        if (count == 0) zeroWrites++
                        else if (count < buffer.remaining()) partialWrites++
                        val bytes = ByteArray(count)
                        buffer.get(bytes); epoch.bytes.write(bytes)
                        if (!buffer.hasRemaining()) { retained = null; true } else false
                    }
                }
                "playToEndOfStream" -> {
                    assertFalse("Delegate EOS follows all retained output", retained?.hasRemaining() == true)
                    endCalls++; ended = true
                    null
                }
                "isEnded" -> ended && retained == null
                "hasPendingData" -> retained?.hasRemaining() == true
                "flush", "reset" -> { retained = null; ended = false; null }
                "setSkipSilenceEnabled" -> { skipEnabled = requireNotNull(args)[0] as Boolean; skipChanges += skipEnabled; null }
                "getSkipSilenceEnabled" -> skipEnabled
                "setPlaybackParameters" -> { parameters = requireNotNull(args)[0] as PlaybackParameters; null }
                "getPlaybackParameters" -> parameters
                "getCurrentPositionUs" -> 0L
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "Precision recording sink"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink

        fun clearCapturedOutput() {
            epochs.forEach { it.bytes.reset(); it.starts.clear() }
        }
    }
}
