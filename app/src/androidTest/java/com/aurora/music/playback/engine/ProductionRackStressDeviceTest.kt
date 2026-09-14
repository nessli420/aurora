package com.aurora.music.playback.engine

import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.PrecisionBlockProcessor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Synthetic instrumentation-worker timing; never opens AudioTrack or changes user settings. */
class ProductionRackStressDeviceTest {
    @Test fun recordsDenseProductionRackTimingAndChecksExactDurationAndReset() {
        val results = JSONArray()
        for (rate in intArrayOf(48_000, 96_000)) results.put(exercise(rate))
        val report = JSONObject().put("schemaVersion", 1)
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("framesPerInput", FRAMES).put("channels", 2)
            .put("parametricBands", 64).put("impulseFramesPerChannel", 4096)
            .put("warmupInputs", WARMUP).put("results", results)
            .put("limitation", "Synthetic instrumentation worker, not an audio callback or physical output. Timing sums queueInput/getOutput CPU wall time per accepted 256-frame input; waveform generation, output validation, preparation and JSON writing are excluded. Scheduler pauses remain included. Heap and PSS are process-wide snapshots without forced GC, not allocation or leak measurements. No hardware latency, underrun, battery or thermal claim.")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "r1e-engine-stress.json")
        file.writeText(report.toString(2))
        Log.i("AuroraRackStress", report.toString())
        println("Production rack stress report: ${file.absolutePath}\n${report.toString(2)}")
    }

    private fun exercise(rate: Int): JSONObject {
        val memoryBefore = memory()
        val engine = PrecisionBlockProcessor()
        val graph = denseRack()
        val left = FloatArray(4096) { i -> if (i == 0) .6f else if (i % 73 == 0) (.008 * exp(-i / 1200.0)).toFloat() else 0f }
        val right = FloatArray(4096) { i -> if (i == 0) .55f else if (i % 89 == 0) (-.007 * exp(-i / 1300.0)).toFloat() else 0f }
        val impulse = ImpulseResponse(left, right, rate)
        engine.updateRack(graph)
        engine.setImpulse(impulse, 0f)
        engine.configure(rate)
        awaitReady(engine)
        val block = AudioBlock(AudioStreamFormat(rate, ChannelLayout.STEREO), FRAMES)
        val waveform = DoubleArray(FRAMES * 2) { i ->
            .12 * sin(2 * PI * (if (i % 2 == 0) 997 else 3001) * (i / 2) / rate) +
                1e-9 * sin(2 * PI * 17_000 * (i / 2) / rate)
        }
        val observed = Observed()
        var submitted = 0L
        repeat(WARMUP) {
            prepareInput(block, waveform, submitted)
            submit(engine, block, observed)
            submitted += FRAMES
        }
        val memoryAfterWarmup = memory()
        val timings = LongArray(MAX_MEASUREMENTS)
        var measurements = 0
        val measuredStart = System.nanoTime()
        do {
            prepareInput(block, waveform, submitted)
            timings[measurements++] = submit(engine, block, observed)
            submitted += FRAMES
        } while (measurements < timings.size && System.nanoTime() - measuredStart < MEASURED_NS)
        val measuredWallNs = System.nanoTime() - measuredStart
        // A non-partition-sized final input verifies EOS without counting a padded FFT tail.
        block.begin(97, submitted * 1_000_000L / rate, submitted)
        waveform.copyInto(block.samples, endIndex = block.sampleCount)
        submit(engine, block, observed); submitted += 97
        finish(engine, observed)
        assertEquals("Every accepted source frame has one output frame", submitted, observed.frames)
        assertTrue(engine.rackActive)
        assertTrue(engine.convolutionProcessingActive)
        assertTrue(observed.peak > 1e-6)
        val memoryAfterRun = memory()

        // Saturation intentionally generates DC on zero input. Compare with a fresh graph,
        // rather than assuming silence maps to zero, to detect stale delay/filter/IR history.
        engine.flush()
        val afterSeek = Observed(97)
        block.begin(97, 0, 0); block.samples.fill(0.0)
        submit(engine, block, afterSeek); finish(engine, afterSeek)
        assertEquals(97L, afterSeek.frames)
        assertArrayEquals(freshSilence(graph, impulse, rate, 97), afterSeek.captured, 0.0)
        engine.reset(); engine.configure(rate); awaitReady(engine)
        val afterReset = Observed(17)
        block.begin(17, 0, 0); block.samples.fill(0.0)
        submit(engine, block, afterReset); finish(engine, afterReset)
        assertEquals(17L, afterReset.frames)
        assertArrayEquals(freshSilence(graph, impulse, rate, 17), afterReset.captured, 0.0)
        engine.reset()

        val sorted = timings.copyOf(measurements).apply { sort() }
        val budgetNs = FRAMES * 1_000_000_000.0 / rate
        return JSONObject().put("sampleRate", rate).put("graph", graph.nodes.joinToString(" -> ") { it.kind.name })
            .put("measuredInputs", measurements).put("measuredWallMs", measuredWallNs / 1_000_000.0)
            .put("summedProcessingMs", sorted.sum() / 1_000_000.0)
            .put("p50Us", sorted[measurements / 2] / 1000.0)
            .put("p95Us", sorted[(measurements * 95 / 100).coerceAtMost(sorted.lastIndex)] / 1000.0)
            .put("maxUs", sorted.last() / 1000.0).put("equivalent256FrameBudgetUs", budgetNs / 1000.0)
            .put("callsExceedingEquivalentBudget", sorted.count { it > budgetNs })
            .put("acceptedFramesIncludingWarmupAndEos", submitted).put("outputFrames", observed.frames)
            .put("outputPeak", observed.peak).put("outputChecksum", observed.checksum)
            .put("seekClearedHistory", true).put("resetClearedHistory", true)
            .put("memoryBeforePreparation", memoryBefore).put("memoryAfterWarmup", memoryAfterWarmup)
            .put("memoryAfterMeasuredRun", memoryAfterRun)
    }

    private fun prepareInput(block: AudioBlock, waveform: DoubleArray, frame: Long) {
        block.begin(FRAMES, frame * 1_000_000L / block.format.sampleRate, frame)
        waveform.copyInto(block.samples)
    }

    /** Output is inspected before the next mutation, matching the engine's take-once contract. */
    private fun submit(engine: PrecisionBlockProcessor, input: AudioBlock, observed: Observed): Long {
        var elapsed = 0L
        var attempts = 0
        var accepted: Boolean
        do {
            val start = System.nanoTime()
            accepted = engine.queueInput(input)
            elapsed += System.nanoTime() - start
            elapsed += drain(engine, observed)
            check(++attempts <= 32) { "Prepared engine made no bounded input progress" }
        } while (!accepted)
        return elapsed
    }

    private fun drain(engine: PrecisionBlockProcessor, observed: Observed): Long {
        var elapsed = 0L
        var count = 0
        while (count++ < 32) {
            val start = System.nanoTime()
            val output = engine.getOutput()
            elapsed += System.nanoTime() - start
            if (output == null) return elapsed
            observed.take(output)
        }
        error("Engine output exceeded the bounded drain allowance")
    }

    private fun finish(engine: PrecisionBlockProcessor, observed: Observed) {
        engine.queueEndOfStream()
        var attempts = 0
        while (!engine.isEnded) {
            drain(engine, observed)
            check(++attempts <= 32) { "Engine did not finish EOS" }
        }
        assertFalse(engine.hasPendingData)
    }

    private fun awaitReady(engine: PrecisionBlockProcessor) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (engine.preparationState == ConvolutionPreparationState.PREPARING && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(engine.preparationFailure, ConvolutionPreparationState.READY, engine.preparationState)
    }

    private fun freshSilence(rack: ProcessingRack, impulse: ImpulseResponse, rate: Int, frames: Int): DoubleArray {
        val fresh = ProductionSerialRack.compile(rack, rate, impulse)
        val silence = AudioBlock(AudioStreamFormat(rate, ChannelLayout.STEREO), frames)
        silence.begin(frames, 0, 0)
        assertTrue(fresh.queueInput(silence))
        fresh.queueEndOfStream()
        val expected = Observed(frames)
        var attempts = 0
        while (!fresh.isEnded) {
            fresh.getOutput()?.let { expected.take(it) }
            check(++attempts <= 32) { "Fresh reference did not finish EOS" }
        }
        assertEquals(frames.toLong(), expected.frames)
        return checkNotNull(expected.captured)
    }

    private class Observed(captureFrames: Int = 0) {
        var frames = 0L
        var peak = 0.0
        var checksum = 0.0
        val captured = if (captureFrames > 0) DoubleArray(captureFrames * 2) else null
        fun take(block: AudioBlock) {
            captured?.let { samples ->
                check((frames + block.frameCount) * 2 <= samples.size)
                block.samples.copyInto(samples, (frames * 2).toInt(), endIndex = block.sampleCount)
            }
            var i = 0
            while (i < block.sampleCount) {
                val sample = block.samples[i++]
                check(sample.isFinite()) { "Non-finite output from dense serial rack" }
                peak = maxOf(peak, abs(sample)); checksum += sample
            }
            frames += block.frameCount
        }
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaCommittedBytes", runtime.totalMemory()).put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("processPssKb", Debug.getPss())
    }

    private fun denseRack(): ProcessingRack {
        fun node(kind: RackNodeKind, audio: AudioPrefs, wet: Float = 1f) = ProcessingRackNode(
            UUID.nameUUIDFromBytes("r1e-stress:$kind".toByteArray(Charsets.UTF_8)).toString(), kind.name, kind, wet = wet, audio = audio)
        return ProcessingRack(enabled = true, name = "Synthetic stress", nodes = listOf(
            node(RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -9f)),
            node(RackNodeKind.EQ, AudioPrefs(dspGraphicBands = emptyList(), dspParametric = List(64) {
                ParamBand((30 * 600.0.pow(it / 63.0)).toFloat(), if (it % 2 == 0) .5f else -.5f, 1.2f)
            })),
            node(RackNodeKind.SATURATION, AudioPrefs(dspSaturation = .1f), .75f),
            node(RackNodeKind.STEREO, AudioPrefs(dspWidth = .9f, dspBalance = .05f)),
            node(RackNodeKind.CROSSFEED, AudioPrefs(dspCrossfeed = .15f)),
            node(RackNodeKind.COMPRESSOR, AudioPrefs(dspCompThreshDb = -12f, dspCompRatio = 2f)),
            node(RackNodeKind.DELAY, AudioPrefs(dspDelayLeftMs = .25f, dspDelayRightMs = .5f)),
            node(RackNodeKind.CONVOLUTION, AudioPrefs(), .7f),
            node(RackNodeKind.LIMITER, AudioPrefs(dspLimiterCeilingDb = -.5f)),
        ))
    }

    companion object {
        private const val FRAMES = 256
        private const val WARMUP = 128
        private const val MAX_MEASUREMENTS = 65_536
        private const val MEASURED_NS = 4_000_000_000L
    }
}
