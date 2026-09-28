package com.aurora.music.playback.network.audio

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.ProductionSerialRack
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class NetworkPcmProcessorTest {
    @Test fun lowLevelInputIsAmplifiedBeforeOnlyIntegerConversion() {
        val gain = node(RackNodeKind.GAIN, AudioPrefs(dspPreampDb = 24f))
        val rack = ProcessingRack(enabled = true, nodes = listOf(gain))
        val source = DoubleArray(1933 * 2) { ((it % 17) - 8) * 8 / 8388608.0 }
        val actual = render(source, 48_000, rack).second
        for (index in source.indices) {
            assertEquals(floor(source[index] * 10.0.pow(24.0 / 20) * 32768 + .5).toInt(), actual[index].toInt())
        }
        assertTrue(actual.any { it.toInt() != 0 })
    }

    @Test fun monoTrimmingAndReplayGainKeepExactFramesAndChannels() {
        val source = DoubleArray(1951) { ((it % 123) - 61) / 256.0 }
        val (metadata, pcm) = render(source, 48_000, channels = 1, delay = 73, padding = 97, gain = .25)
        assertEquals(1781L, metadata)
        assertEquals(1781 * 2, pcm.size)
        for (frame in 0 until 1781) {
            val expected = floor(source[frame + 73] * .25 * 32768 + .5).toInt()
            assertEquals(expected, pcm[frame * 2].toInt())
            assertEquals(expected, pcm[frame * 2 + 1].toInt())
        }
    }

    @Test fun fullFirTailIsFinalizedInTheWaveLength() {
        val ir = FloatArray(65).apply { this[0] = .5f; this[lastIndex] = .25f }
        val rack = ProcessingRack(enabled = true, nodes = listOf(node(RackNodeKind.CONVOLUTION)))
        val source = DoubleArray(257 * 2).apply { this[size - 2] = .5; this[size - 1] = -.25 }
        val (frames, pcm) = render(source, 48_000, rack, impulse = ImpulseResponse(ir, ir, 48_000))
        assertEquals(257L, frames)
        assertEquals(321 * 2, pcm.size)
        assertEquals(8192, pcm[256 * 2].toInt())
        assertEquals(-4096, pcm[256 * 2 + 1].toInt())
        assertEquals(4096, pcm[320 * 2].toInt())
        assertEquals(-2048, pcm[320 * 2 + 1].toInt())
    }

    @Test fun rateConversionPreservesPitchDurationAndChannelSeparation() {
        val source = DoubleArray(44_100 * 2) { index ->
            if (index % 2 == 0) .4 * sin(2 * PI * 997 * (index / 2) / 44_100) else 0.0
        }
        val (_, actual) = render(source, 44_100)
        assertEquals(48_000 * 2, actual.size)
        var error = 0.0
        for (frame in 100 until 47_900) {
            val expected = .4 * sin(2 * PI * 997 * frame / 48_000)
            error += (actual[frame * 2] / 32768.0 - expected).pow(2)
            assertEquals(0, actual[frame * 2 + 1].toInt())
        }
        assertTrue("rms error ${sqrt(error / 47_800)}", sqrt(error / 47_800) < .00004)
    }

    @Test fun downsamplingRejectsEnergyAboveReceiverNyquist() {
        val source = DoubleArray(24_000 * 2) { .4 * sin(2 * PI * 30_000 * (it / 2) / 96_000) }
        val (_, actual) = render(source, 96_000)
        assertEquals(12_000 * 2, actual.size)
        assertTrue(actual.drop(200).dropLast(200).maxOf { abs(it.toInt()) } <= 2)
    }

    @Test fun diskLimitRejectsBeforeExtendingFile() {
        val file = File.createTempFile("aurora-network-limit-", ".wav")
        try {
            NetworkWaveWriter(file, maxBytes = 60).use { writer ->
                val block = AudioBlock(AudioStreamFormat(48_000, ChannelLayout.STEREO), 5).apply { begin(5) }
                assertThrows(IllegalArgumentException::class.java) { writer.write(block) }
                assertEquals(44L, file.length())
            }
        } finally { file.delete() }
    }

    @Test fun cancellationStopsBeforeAnySamplesAreQueued() {
        val file = File.createTempFile("aurora-network-cancel-", ".wav")
        try {
            NetworkWaveWriter(file).use { writer ->
                val graph = ProductionSerialRack.compile(ProcessingRack(enabled = true), 48_000, null)
                val processor = NetworkPcmProcessor(graph, writer, checkCancelled = { throw InterruptedException() })
                val block = AudioBlock(graph.format, 256).apply { begin(256) }
                assertThrows(InterruptedException::class.java) { processor.append(block) }
                assertEquals(0L, writer.framesWritten)
            }
        } finally { file.delete() }
    }

    private fun render(
        samples: DoubleArray, rate: Int, rack: ProcessingRack = ProcessingRack(enabled = true),
        channels: Int = 2, impulse: ImpulseResponse? = null, delay: Int = 0, padding: Int = 0, gain: Double = 1.0,
    ): Pair<Long, ShortArray> {
        val file = File.createTempFile("aurora-network-test-", ".wav")
        try {
            var sourceFrames = 0L
            NetworkWaveWriter(file).use { writer ->
                val graph = ProductionSerialRack.compile(rack, rate, impulse)
                val processor = NetworkPcmProcessor(graph, writer, gain, delay, padding)
                val block = AudioBlock(AudioStreamFormat(rate, if (channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO), 193)
                var frame = 0
                while (frame < samples.size / channels) {
                    val count = minOf(block.capacityFrames, samples.size / channels - frame)
                    block.begin(count)
                    samples.copyInto(block.samples, 0, frame * channels, (frame + count) * channels)
                    processor.append(block)
                    frame += count
                }
                processor.finish()
                sourceFrames = processor.sourceFrames
            }
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals(48_000, header.getInt(24))
            assertEquals(bytes.size - 44, header.getInt(40))
            header.position(44)
            val pcm = header.asShortBuffer()
            return sourceFrames to ShortArray(pcm.remaining()).also { pcm.get(it) }
        } finally { file.delete() }
    }

    private fun node(kind: RackNodeKind, audio: AudioPrefs = AudioPrefs()) =
        ProcessingRackNode(UUID.randomUUID().toString(), kind.name, kind, audio = audio)
}
