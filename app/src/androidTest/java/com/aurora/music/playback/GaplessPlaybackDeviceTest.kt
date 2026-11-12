package com.aurora.music.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Fixtures in assets/gapless are one 441 Hz stereo sine (L 0.5, R 0.25, 48 kHz) split at [CUTS] and encoded
 * per part with ffmpeg (flac, libmp3lame 320k with its LAME tag, aac 256k m4a with an exact edit list,
 * fragmented aac mp4, libopus 192k in ogg and webm). Each set plays back to back and the PCM is captured
 * where TappingAudioSink sits, directly inside GaplessAudioSink.
 */
class GaplessPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target: Context get() = instrumentation.targetContext

    private class Join(val frames: Int, val configures: Int, val discontinuities: Int, val midError: Double,
        val joinError: List<Double>, val bestLag: List<Int>) {
        override fun toString() = "frames=$frames configures=$configures discontinuities=$discontinuities " +
            "mid=${"%.5f".format(midError)} joins=${joinError.map { "%.5f".format(it) }} lags=$bestLag"
    }

    @Test fun flacJoinsBitExactly() = listOf(false, true).forEach { float ->
        val join = play("flac", float)
        assertEquals("$join", TOTAL, join.frames)
        assertTrue("$join", join.midError < 1e-4 && join.joinError.all { it < 1e-4 })
        assertContinuous(join)
    }

    @Test fun mp3WithLameTagJoinsGaplessly() = listOf(false, true).forEach { float ->
        val join = play("mp3", float)
        assertEquals("$join", TOTAL, join.frames)
        assertLossyJoin(join, mid = 0.003, edge = 0.15)
    }

    @Test fun aacM4aWithEditListJoinsGaplessly() = listOf(false, true).forEach { float ->
        val join = play("m4a", float)
        // only the queue's last track keeps its padding: a timeline end needs the next track's start
        assertTrue("$join", join.frames - TOTAL in 0..1024)
        assertLossyJoin(join, mid = 0.05, edge = 0.15)
    }

    @Test fun oggOpusJoinsGaplessly() = listOf(false, true).forEach { float ->
        val join = play("opus", float)
        // only the queue's last track keeps its padding: a timeline end needs the next track's start
        assertTrue("$join", join.frames - TOTAL in 0..1024)
        assertLossyJoin(join, mid = 0.02, edge = 0.15)
    }

    @Test fun webmOpusJoinsGaplessly() = listOf(false, true).forEach { float ->
        val join = play("webm", float)
        assertEquals("$join", TOTAL, join.frames)
        assertLossyJoin(join, mid = 0.02, edge = 0.15)
    }

    @Test fun fragmentedAacMeasured() {
        Log.i(TAG, "frag.mp4 pcm16 ${play("frag.mp4", false)}")
    }

    @Test fun cueTracksMeasured() {
        Log.i(TAG, "cue pcm16 ${play("flac", false, cue = true)}")
    }

    private fun assertLossyJoin(join: Join, mid: Double, edge: Double) {
        assertEquals("$join", listOf(0, 0, 0), join.bestLag)
        assertTrue("$join", join.midError < mid)
        assertTrue("$join", join.joinError.all { it < edge })
        assertContinuous(join)
    }

    // the inner sinks saw one stream: no per-track reconfigure or drain, so dsp state crosses the join
    private fun assertContinuous(join: Join) {
        assertEquals("$join", 1, join.configures)
        assertEquals("$join", 0, join.discontinuities)
    }

    private fun play(extension: String, float: Boolean, cue: Boolean = false): Join {
        val names = if (cue) listOf("whole.flac") else (1..3).map { "part$it.$extension" }
        val files = names.map { name ->
            File(target.cacheDir, "gapless-test-$name").also { file ->
                instrumentation.context.assets.open("gapless/$name").use { input -> file.outputStream().use(input::copyTo) }
            }
        }
        // a cue sheet clips one album file at millisecond track marks
        val items = if (!cue) files.map { MediaItem.fromUri(Uri.fromFile(it)) } else listOf(0L to 788L, 788L to 1647L, 1647L to C.TIME_END_OF_SOURCE).map { (start, end) ->
            MediaItem.Builder().setUri(Uri.fromFile(files[0]))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()).build()
        }
        val capture = Capture(DefaultAudioSink.Builder(target).setEnableFloatOutput(float).build())
        val thread = HandlerThread("gapless-fixture").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var failure: PlaybackException? = null
        var player: ExoPlayer? = null
        try {
            handler.post {
                val factory = object : DefaultRenderersFactory(target) {
                    override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener,
                        out: ArrayList<Renderer>) {
                        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                        GaplessAudioRenderer.install(out, context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink)
                    }
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        GaplessAudioSink(capture, { 0 })
                }
                player = ExoPlayer.Builder(target, factory).setLooper(thread.looper)
                    .setAudioAttributes(AudioAttributes.DEFAULT, false).build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) done.countDown() }
                            override fun onPlayerError(error: PlaybackException) { failure = error; done.countDown() }
                        })
                        setMediaItems(items)
                        prepare()
                        play()
                    }
            }
            assertTrue("playback timed out", done.await(60, TimeUnit.SECONDS))
            failure?.let { throw AssertionError("playback failed", it) }
        } finally {
            handler.post { player?.release() }
            thread.quitSafely()
            files.forEach(File::delete)
        }
        return analyse(capture, if (cue) listOf(0, 37_824, 79_056, TOTAL) else CUTS)
    }

    private fun analyse(capture: Capture, cuts: List<Int>): Join {
        val left = capture.left()
        fun ideal(n: Int) = 0.5 * sin(2 * PI * 441 * n / RATE)
        fun error(n: Int, lag: Int = 0) = abs(left[n] - ideal(n + lag))
        val joins = cuts.drop(1).dropLast(1)
        var mid = 0.0
        for (n in 2048 until minOf(left.size, TOTAL) - 2048) if (joins.none { abs(n - it) < EDGE }) mid = max(mid, error(n))
        val joinErrors = joins.map { j -> (j - EDGE until minOf(j + EDGE, left.size)).maxOf { error(it) } }
        val lags = cuts.zipWithNext().map { (a, b) ->
            (-3..3).minBy { lag -> (a + 2048 until minOf(b, left.size) - 2048).maxOfOrNull { error(it, lag) } ?: Double.MAX_VALUE }
        }
        return Join(left.size, capture.configures, capture.discontinuities, mid, joinErrors, lags)
            .also { Log.i(TAG, "${if (capture.encoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"} $it") }
    }

    private class Capture(delegate: AudioSink) : ForwardingAudioSink(delegate) {
        private val bytes = ByteArrayOutputStream()
        var encoding = C.ENCODING_INVALID
        var channels = 0
        var configures = 0
        var discontinuities = 0

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            encoding = inputFormat.pcmEncoding
            channels = inputFormat.channelCount
            configures++
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }

        override fun handleDiscontinuity() { discontinuities++; super.handleDiscontinuity() }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val start = buffer.position()
            val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            val copy = ByteArray(buffer.position() - start)
            buffer.duplicate().apply { position(start) }.get(copy)
            synchronized(bytes) { bytes.write(copy) }
            return consumed
        }

        fun left(): DoubleArray {
            val data = ByteBuffer.wrap(synchronized(bytes) { bytes.toByteArray() }).order(ByteOrder.LITTLE_ENDIAN)
            val float = encoding == C.ENCODING_PCM_FLOAT
            val frame = channels * if (float) 4 else 2
            return DoubleArray(data.remaining() / frame) { i ->
                if (float) data.getFloat(i * frame).toDouble() else data.getShort(i * frame) / 32768.0
            }
        }
    }

    // a sink that consumes instantly and reports whatever position the test sets
    private class FakeSink {
        val frames = ArrayList<Short>()
        var configures = 0
        var discontinuities = 0
        var positionUs = 0L
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, arguments ->
            when (method.name) {
                "configure" -> { configures++; null }
                "handleDiscontinuity" -> { discontinuities++; null }
                "handleBuffer" -> {
                    val buffer = (requireNotNull(arguments)[0] as ByteBuffer).order(ByteOrder.LITTLE_ENDIAN)
                    while (buffer.hasRemaining()) frames += buffer.short
                    true
                }
                "getCurrentPositionUs" -> positionUs
                "isEnded" -> true
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "Fake sink"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink
    }

    private fun pcm16(rate: Int, delay: Int = 0, padding: Int = 0) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(1).setSampleRate(rate).setEncoderDelay(delay).setEncoderPadding(padding).build()

    private fun buffer(values: IntRange) = ByteBuffer.allocateDirect(values.count() * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) }; flip() }

    @Test fun trimsEncoderDelayAndPaddingWhileInnerSinkSeesOneStream() {
        val fake = FakeSink()
        val sink = GaplessAudioSink(fake.sink, { 0 })
        sink.configure(pcm16(1000, delay = 100, padding = 50), 0, null)
        assertTrue(sink.handleBuffer(buffer(0 until 1000), 0, 1))
        sink.handleDiscontinuity()
        sink.configure(pcm16(1000, delay = 100, padding = 50), 0, null)
        assertTrue(sink.handleBuffer(buffer(1000 until 2000), 1_000_000, 1))
        sink.playToEndOfStream()
        assertEquals(((100 until 950) + (1100 until 1950)).map { it.toShort() }, fake.frames)
        assertEquals(1, fake.configures)
        assertEquals(0, fake.discontinuities)
        // the inner clock runs continuously, positions come back in each track's media time
        fake.positionUs = 1_000_000
        assertEquals(1_150_000L, sink.getCurrentPositionUs(false))
    }

    @Test fun gaplessOffInsertsOneSecondAndHoldsThePosition() {
        val fake = FakeSink()
        val sink = GaplessAudioSink(fake.sink, { GaplessAudioSink.GAP_MS })
        sink.configure(pcm16(1000), 0, null)
        assertTrue(sink.handleBuffer(buffer(1..500), 0, 1))
        sink.handleDiscontinuity()
        sink.configure(pcm16(1000), 0, null)
        assertTrue(sink.handleBuffer(buffer(501..1000), 500_000, 1))
        sink.playToEndOfStream()
        assertEquals((1..500).map { it.toShort() } + List(1000) { 0.toShort() } + (501..1000).map { it.toShort() }, fake.frames)
        fake.positionUs = 250_000
        assertEquals(250_000L, sink.getCurrentPositionUs(false))
        fake.positionUs = 900_000
        assertEquals(500_000L, sink.getCurrentPositionUs(false))
        fake.positionUs = 1_750_000
        assertEquals(750_000L, sink.getCurrentPositionUs(false))
    }

    @Test fun skipSilenceCutJumpsThePositionAndInnerSkipSilenceStaysOff() {
        val fake = FakeSink()
        val sink = GaplessAudioSink(fake.sink, { 0 })
        sink.setSkipSilenceEnabled(true)
        assertTrue(sink.skipSilenceEnabled)
        sink.configure(pcm16(1000), 0, null)
        val tone = (0 until 1000).map { (8000 * sin(2 * PI * it / 10)).toInt() }
        val input = ByteBuffer.allocateDirect(5000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            tone.forEach { putShort(it.toShort()) }; repeat(3000) { putShort(0) }; tone.forEach { putShort(it.toShort()) }; flip()
        }
        assertTrue(sink.handleBuffer(input, 0, 1))
        sink.playToEndOfStream()
        assertEquals(2300, fake.frames.size)
        assertEquals(tone.map { it.toShort() }, fake.frames.subList(0, 1000))
        assertEquals(tone.map { it.toShort() }, fake.frames.subList(1300, 2300))
        // the second tone starts 1.3 s into the output but 4 s into the track
        fake.positionUs = 1_300_000
        assertEquals(4_000_000L, sink.getCurrentPositionUs(false))
    }

    private companion object {
        const val TAG = "GaplessDeviceTest"
        const val RATE = 48_000
        // a lossy part rebuilds its abrupt cut edge with codec noise for about one frame
        const val EDGE = 1200
        val CUTS = listOf(0, 37_813, 79_050, 118_051)
        val TOTAL = CUTS.last()
    }
}
