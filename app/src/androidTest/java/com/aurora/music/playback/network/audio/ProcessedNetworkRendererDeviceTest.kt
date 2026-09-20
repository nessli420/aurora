package com.aurora.music.playback.network.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaExtractor
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingPlaybackPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.ProcessingSnapshot
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.network.ScopedMediaServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class ProcessedNetworkRendererDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val item get() = MediaItem.fromUri("https://private.invalid/audio?token=never-share-this")

    @Test fun pcm24DecodeGraphAndResamplingProduceMeasuredStereoWave() = runBlocking {
        temporary { directory ->
            val source = wave24(44_100, 44_100) { frame, channel ->
                (1_000_000 * sin(2 * PI * (if (channel == 0) 997 else 2017) * frame / 44_100)).toInt()
            }
            val result = ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                snapshot(-6f), outputDirectory = directory)
            assertEquals(44_100L, result.sourceFrames)
            assertEquals(44_100, result.sourceSampleRate)
            assertEquals(48_000, result.sampleRate)
            assertEquals(1000L, result.durationMs)
            assertEquals("audio/wav", result.mimeType)
            assertTrue(result.modified)
            assertEquals(listOf(result.file.name), directory.listFiles()!!.map { it.name })
            assertFalse(result.file.name.contains("private"))
            val actual = pcm(result.file)
            assertEquals(96_000, actual.size)
            var squared = 0.0
            for (frame in 200 until 47_800) for (channel in 0..1) {
                val expected = 1_000_000.0 / 8388608 * 10.0.pow(-6.0 / 20) *
                    sin(2 * PI * (if (channel == 0) 997 else 2017) * frame / 48_000)
                squared += (actual[frame * 2 + channel] / 32768.0 - expected).pow(2)
            }
            assertTrue("rms ${sqrt(squared / (47_600 * 2))}", sqrt(squared / (47_600 * 2)) < .00005)
        }
    }

    @Test fun pcm24LowBitsSurviveUntilTheFinalReceiverConversion() = runBlocking {
        temporary { directory ->
            val source = wave24(48_000, 2048) { frame, channel -> ((frame + channel) % 17 - 8) * 8 }
            val result = ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                snapshot(24f), outputDirectory = directory)
            val actual = pcm(result.file)
            for (frame in 0 until 2048) for (channel in 0..1) {
                val expected = floor(((frame + channel) % 17 - 8) * 8 / 8388608.0 * 10.0.pow(24.0 / 20) * 32768 + .5).toInt()
                assertEquals(expected, actual[frame * 2 + channel].toInt())
            }
            assertTrue(actual.any { it.toInt() != 0 })
        }
    }

    @Test fun flac24LowBitsSurviveCompressedDecodingAndTheSharedGraph() = runBlocking {
        temporary { directory ->
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("network/lowbits-24.flac").use { it.readBytes() }
            val result = ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                snapshot(24f), outputDirectory = directory)
            assertEquals(4096L, result.sourceFrames)
            val samples = pcm(result.file)
            for (frame in 0 until 4096) for (channel in 0..1) {
                val expected = floor(((frame + channel) % 17 - 8) * 8 / 8388608.0 * 10.0.pow(24.0 / 20) * 32768 + .5).toInt()
                assertEquals("frame=$frame channel=$channel", expected, samples[frame * 2 + channel].toInt())
            }
            assertTrue(samples.any { it.toInt() != 0 })
        }
    }

    @Test fun capturedReceiverDownloadAndSeekRangesContainTheProcessedSamples() = runBlocking {
        temporary { directory ->
            val source = wave24(48_000, 4096) { frame, channel -> (frame % 51 - 25) * (if (channel == 0) 8192 else -4096) }
            val result = ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                snapshot(-12f), outputDirectory = directory)
            ScopedMediaServer().use { server ->
                val address = InetAddress.getByName("127.0.0.1")
                server.start(address)
                val grant = server.grant(result.file, result.mimeType, allowedClient = address)
                val client = OkHttpClient()
                val url = grant.url("127.0.0.1", server.port)
                val captured = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("audio/wav", response.header("Content-Type"))
                    checkNotNull(response.body).bytes()
                }
                assertArrayEquals(result.file.readBytes(), captured)
                val pcm = ByteBuffer.wrap(captured).order(ByteOrder.LITTLE_ENDIAN).apply { position(44) }
                repeat(4096) { frame -> repeat(2) { channel ->
                    val expected = floor((frame % 51 - 25) * (if (channel == 0) 8192 else -4096) / 8388608.0 *
                        10.0.pow(-12.0 / 20) * 32768 + .5).toInt()
                    assertEquals(expected, pcm.short.toInt())
                } }
                val start = 44 + 1933 * 4
                client.newCall(Request.Builder().url(url).header("Range", "bytes=$start-${start + 255}").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertArrayEquals(captured.copyOfRange(start, start + 256), checkNotNull(response.body).bytes())
                }
                server.revoke(grant.token)
                client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(404, it.code) }
            }
        }
    }

    @Test fun mp3GaplessMetadataPreservesTheSourceLengthAndSampleAlignment() = runBlocking {
        temporary { directory ->
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("network/gapless.mp3").use { it.readBytes() }
            val result = ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                ProcessedNetworkRenderer.drySnapshot, outputDirectory = directory)
            assertEquals(10_001L, result.sourceFrames)
            val actual = pcm(result.file)
            assertEquals(20_002, actual.size)
            val reference = InstrumentationRegistry.getInstrumentation().context.assets.open("network/gapless-mp3.pcm")
                .use { ByteBuffer.wrap(it.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer() }
            assertEquals(actual.size, reference.remaining())
            val error = sqrt(actual.sumOf { ((it.toInt() - reference.get().toInt()) / 32768.0).pow(2) } / actual.size)
            assertTrue("alignment rms $error", error < .0005)
        }
    }

    @Test fun processedOpusFailsExplicitlyWithoutPartialAudio() = runBlocking {
        temporary { directory ->
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("network/gapless.opus").use { it.readBytes() }
            val failure = runCatching {
                ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                    ProcessedNetworkRenderer.drySnapshot, outputDirectory = directory)
            }.exceptionOrNull()
            assertTrue(failure is NetworkRenderingException)
            assertEquals("Processed output does not support Opus. Use direct output or local playback.", failure?.message)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun aacDecoderUsesTheSameGraphAndProducesSeekableFiniteWave() = runBlocking {
        temporary { directory ->
            val source = File(directory, "fixture.m4a")
            val fixture = encodeAac(source)
            val bytes = source.readBytes()
            source.delete()
            val result = ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(bytes) },
                snapshot(-6f), outputDirectory = directory)
            val samples = pcm(result.file)
            assertEquals(fixture.description, fixture.frames, result.sourceFrames)
            assertEquals(result.sourceFrames * 2, samples.size.toLong())
            val rms = sqrt(samples.drop(5000).dropLast(5000).sumOf { (it / 32768.0).pow(2) } / (samples.size - 10000))
            assertEquals(.25 * 10.0.pow(-6.0 / 20) / sqrt(2.0), rms, .004)
            assertEquals(listOf(result.file.name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun cancellationRemovesSourceAndPartialOutput() = runBlocking {
        temporary { directory ->
            val source = wave24(48_000, 48_000) { _, _ -> 8192 }
            var cancelled = false
            try {
                ProcessedNetworkRenderer.render(context, item, DataSource.Factory { ByteArrayDataSource(source) },
                    ProcessedNetworkRenderer.drySnapshot, outputDirectory = directory,
                    onProgress = { throw CancellationException("cancelled") })
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(0, directory.listFiles()!!.size)
        }
    }

    @Test fun sourceErrorsNeverExposeCredentialsAndSystemEffectsFailExplicitly() = runBlocking {
        temporary { directory ->
            val factory = DataSource.Factory { throw IOException("https://private.invalid?token=never-share-this") }
            val failure = runCatching {
                ProcessedNetworkRenderer.render(context, item, factory, ProcessedNetworkRenderer.drySnapshot, outputDirectory = directory)
            }.exceptionOrNull()
            assertTrue(failure is NetworkRenderingException)
            assertFalse(failure.toString().contains("private.invalid"))
            assertFalse(failure.toString().contains("never-share-this"))
            assertNull(failure!!.cause)
            val system = runCatching {
                ProcessedNetworkRenderer.render(context, item, factory,
                    ProcessedNetworkRenderer.drySnapshot.copy(audio = AudioPrefs(dspMode = DspMode.SYSTEM)), outputDirectory = directory)
            }.exceptionOrNull()
            assertEquals("Use Custom DSP or Off for processed output.", system?.message)
            assertEquals(0, directory.listFiles()!!.size)
        }
    }

    private suspend fun temporary(block: suspend (File) -> Unit) {
        val directory = File(context.cacheDir, "network-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun snapshot(gain: Float) = ProcessingSnapshot(
        AudioPrefs(dspMode = DspMode.CUSTOM), ProcessingPlaybackPrefs(),
        rack = ProcessingRack(enabled = true, nodes = listOf(ProcessingRackNode(UUID.randomUUID().toString(), "Gain",
            RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = gain)))),
    )

    private fun wave24(rate: Int, frames: Int, value: (Int, Int) -> Int): ByteArray =
        ByteBuffer.allocate(44 + frames * 6).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 6); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(rate); putInt(rate * 6); putShort(6); putShort(24)
            put("data".toByteArray()); putInt(frames * 6)
            repeat(frames) { frame -> repeat(2) { channel ->
                val sample = value(frame, channel)
                put(sample.toByte()); put((sample shr 8).toByte()); put((sample shr 16).toByte())
            } }
        }.array()

    private fun pcm(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 8, buffer.getInt(4))
        assertEquals(bytes.size - 44, buffer.getInt(40))
        buffer.position(44)
        return ShortArray(buffer.remaining() / 2) { buffer.short }
    }

    internal data class AacFixture(val frames: Long, val durationMs: Long, val description: String)

    internal fun encodeAac(file: File): AacFixture {
        val targetFrames = 48 * 1024
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var fixture = ""
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frames = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            var packets = 0
            var firstUs = Long.MIN_VALUE
            var lastUs = Long.MIN_VALUE
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!outputEnded) {
                check(System.nanoTime() < deadline)
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        input.clear()
                        val count = minOf(input.remaining() / 4, 1024, targetFrames - frames)
                        repeat(count) { offset ->
                            val sample = (8192 * sin(2 * PI * 997 * (frames + offset) / 48_000)).toInt().toShort()
                            input.putShort(sample); input.putShort(sample)
                        }
                        inputEnded = frames + count == targetFrames
                        codec.queueInputBuffer(index, 0, count * 4, frames * 1_000_000L / 48_000,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frames += count
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                    }
                    in 0..Int.MAX_VALUE -> {
                        val output = checkNotNull(codec.getOutputBuffer(index))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (firstUs == Long.MIN_VALUE) firstUs = info.presentationTimeUs
                            lastUs = info.presentationTimeUs
                            packets++
                            muxer.writeSampleData(track, output, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            fixture = "input=$frames; packets=$packets; first=$firstUs; last=$lastUs"
            assertEquals(fixture, targetFrames, packets * 1024)
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackFormat = extractor.getTrackFormat(0)
            return AacFixture(targetFrames.toLong(), trackFormat.getLong(MediaFormat.KEY_DURATION) / 1000, "$fixture; $trackFormat")
        } finally { extractor.release() }
    }
}
