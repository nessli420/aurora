package com.aurora.music.playback.engine

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** This JNI library belongs to the instrumentation APK, never the production player. */
object PrecisionNativeBenchmark {
    init { System.loadLibrary("aurora_precision_benchmark") }
    external fun process(samples: DoubleArray, coefficients: DoubleArray, state: DoubleArray, channels: Int, frames: Int): Double
}

/** Numerical tests and diagnostic timing on the device, not a real-time scheduler guarantee. */
class PrecisionEngineDeviceTest {
    @Test fun sub16BitContentSurvivesTheProcessedPcm24PathOnDevice() {
        val format = AudioStreamFormat(96_000, ChannelLayout.STEREO)
        val rack = PrecisionSerialRack.compile(format, 2, listOf(
            MonoNodeSpec("mono"), GainNodeSpec("gain", linearGain = 0.5),
            BiquadNodeSpec("eq", bands = listOf(BiquadCoefficients.IDENTITY))))
        val input = byteArrayOf(3, 0, 0, 1, 0, 0, -3, -1, -1, -1, -1, -1)
        val output = ByteBuffer.allocate(input.size)
        PrecisionPcmPipeline(PcmEncoding.SIGNED_24_LE, PcmEncoding.SIGNED_24_LE, rack)
            .process(ByteBuffer.wrap(input), output, 2, 1_000_000, 96_000)
        assertArrayEquals(byteArrayOf(1, 0, 0, 1, 0, 0, -1, -1, -1, -1, -1, -1), output.array())
        assertEquals(1.0 / 8388608.0, rack.outputTap.peakLeft, 0.0)
        assertEquals(1_000_000L, rack.outputTap.presentationTimeUs)
    }

    @Test fun nativeBlockKernelMatchesKotlinAcrossStatefulCallbacks() {
        val bands = bands(48_000)
        val coefficients = flatten(bands)
        val nativeState = DoubleArray(BANDS * CHANNELS * 2)
        val kotlinState = DoubleArray(nativeState.size)
        val nativeSamples = DoubleArray(FRAMES * CHANNELS)
        val kotlinSamples = DoubleArray(nativeSamples.size)
        var maxError = 0.0
        repeat(128) { block ->
            for (i in nativeSamples.indices) {
                val frame = block * FRAMES + i / CHANNELS
                val value = if (block == 0 && i == 0) 0.5 else 0.05 * sin(2 * PI * (if (i % 2 == 0) 997 else 3001) * frame / 48_000)
                nativeSamples[i] = value; kotlinSamples[i] = value
            }
            PrecisionNativeBenchmark.process(nativeSamples, coefficients, nativeState, CHANNELS, FRAMES)
            kotlinKernel(kotlinSamples, coefficients, kotlinState, CHANNELS, FRAMES)
            for (i in nativeSamples.indices) {
                assertTrue(nativeSamples[i].isFinite())
                maxError = maxOf(maxError, abs(nativeSamples[i] - kotlinSamples[i]))
            }
        }
        assertTrue("Maximum native/Kotlin absolute error $maxError", maxError <= 1e-10)
        Log.i(TAG, "Native/Kotlin 64-band parity max absolute error=$maxError over 32768 stereo frames")
    }

    @Test fun recordsKotlinNativeAndCompletePipelineCallbackTimings() {
        val results = JSONArray()
        for (rate in intArrayOf(48_000, 96_000, 192_000)) {
            val bands = bands(rate)
            val coefficients = flatten(bands)
            val original = DoubleArray(FRAMES * CHANNELS) { i ->
                0.1 * sin(2 * PI * (if (i % 2 == 0) 440 else 997) * (i / CHANNELS) / rate) +
                    1e-8 * sin(2 * PI * 19_000 * (i / CHANNELS) / rate)
            }
            val samples = DoubleArray(original.size)
            val state = DoubleArray(BANDS * CHANNELS * 2)
            for (native in booleanArrayOf(false, true)) {
                state.fill(0.0)
                val elapsed = LongArray(ITERATIONS)
                repeat(WARMUP + ITERATIONS) { iteration ->
                    System.arraycopy(original, 0, samples, 0, samples.size)
                    val start = System.nanoTime()
                    if (native) PrecisionNativeBenchmark.process(samples, coefficients, state, CHANNELS, FRAMES)
                    else kotlinKernel(samples, coefficients, state, CHANNELS, FRAMES)
                    val time = System.nanoTime() - start
                    if (iteration >= WARMUP) elapsed[iteration - WARMUP] = time
                }
                assertTrue(samples.all { it.isFinite() })
                results.put(timing(if (native) "native_block_jni_kernel" else "kotlin_reference_kernel", rate, elapsed))
            }

            val format = AudioStreamFormat(rate, ChannelLayout.STEREO)
            val rack = PrecisionSerialRack.compile(format, FRAMES, listOf(
                GainNodeSpec("preamp", linearGain = 0.5), BiquadNodeSpec("eq", bands = bands)))
            val pipeline = PrecisionPcmPipeline(PcmEncoding.SIGNED_24_LE, PcmEncoding.FLOAT_32_LE, rack)
            val sourceBlock = AudioBlock(format, FRAMES)
            sourceBlock.begin(FRAMES); original.copyInto(sourceBlock.samples)
            val input = ByteBuffer.allocateDirect(FRAMES * CHANNELS * 3)
            PcmBoundary.encode(sourceBlock, PcmEncoding.SIGNED_24_LE, input)
            val output = ByteBuffer.allocateDirect(FRAMES * CHANNELS * 4)
            val elapsed = LongArray(ITERATIONS)
            repeat(WARMUP + ITERATIONS) { iteration ->
                input.rewind(); output.clear()
                val start = System.nanoTime()
                pipeline.process(input, output, FRAMES, iteration.toLong() * FRAMES * 1_000_000 / rate, iteration.toLong() * FRAMES)
                val time = System.nanoTime() - start
                if (iteration >= WARMUP) elapsed[iteration - WARMUP] = time
            }
            assertTrue(rack.outputTap.peakLeft.isFinite())
            results.put(timing("kotlin_pcm24_to_float32_full_rack_with_taps", rate, elapsed))
        }
        val report = JSONObject().put("schemaVersion", 1)
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("framesPerCallback", FRAMES).put("channels", CHANNELS).put("biquads", BANDS)
            .put("warmupCallbacks", WARMUP).put("measuredCallbacks", ITERATIONS)
            .put("limitation", "Instrumentation worker timing, not the Android audio thread; no convolution, graph replacement, sink or physical output included. Native uses block JNI copies; production rack remains Kotlin-only and is not active in playback.")
            .put("results", results)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.getExternalFilesDir(null), "precision-engine-benchmark.json")
        reportFile.writeText(report.toString(2))
        Log.i(TAG, report.toString())
        println("Precision benchmark: ${reportFile.absolutePath}\n${report.toString(2)}")
    }

    private fun timing(name: String, rate: Int, times: LongArray): JSONObject {
        val deadlineNs = FRAMES.toDouble() * 1_000_000_000 / rate
        val misses = times.count { it > deadlineNs }
        times.sort()
        return JSONObject().put("implementation", name).put("sampleRate", rate)
            .put("deadlineUs", deadlineNs / 1000.0)
            .put("p50Us", times[times.size / 2] / 1000.0)
            .put("p95Us", times[(times.size * 95 / 100).coerceAtMost(times.lastIndex)] / 1000.0)
            .put("maxUs", times.last() / 1000.0).put("deadlineMisses", misses)
    }

    private fun bands(rate: Int): List<BiquadCoefficients> = (0 until BANDS).map {
        BiquadCoefficients.peaking(rate, 30.0 * 600.0.pow(it.toDouble() / (BANDS - 1)), if (it % 2 == 0) 1.5 else -1.5, 1.2)
    }

    private fun flatten(bands: List<BiquadCoefficients>): DoubleArray = DoubleArray(bands.size * 5).also { array ->
        bands.forEachIndexed { i, c -> array[i * 5] = c.b0; array[i * 5 + 1] = c.b1; array[i * 5 + 2] = c.b2; array[i * 5 + 3] = c.a1; array[i * 5 + 4] = c.a2 }
    }

    /** Test-only flat-array reference matching the JNI kernel, without rack meters or conversion. */
    private fun kotlinKernel(samples: DoubleArray, coefficients: DoubleArray, state: DoubleArray, channels: Int, frames: Int): Double {
        val bandCount = coefficients.size / 5
        var frame = 0
        while (frame < frames) {
            var channel = 0
            while (channel < channels) {
                var x = samples[frame * channels + channel]
                var band = 0
                while (band < bandCount) {
                    val c = band * 5
                    val s = (band * channels + channel) * 2
                    val y = coefficients[c] * x + state[s]
                    state[s] = coefficients[c + 1] * x - coefficients[c + 3] * y + state[s + 1]
                    state[s + 1] = coefficients[c + 2] * x - coefficients[c + 4] * y
                    x = y; band++
                }
                samples[frame * channels + channel] = x; channel++
            }
            frame++
        }
        return samples[frames * channels - 1]
    }

    companion object {
        private const val TAG = "AuroraPrecisionBench"
        private const val FRAMES = 256
        private const val CHANNELS = 2
        private const val BANDS = 64
        private const val WARMUP = 256
        private const val ITERATIONS = 1000
    }
}
