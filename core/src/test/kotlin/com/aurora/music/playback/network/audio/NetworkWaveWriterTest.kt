package com.aurora.music.playback.network.audio

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class NetworkWaveWriterTest {
    @Test fun shapedNetworkWavePreservesFramesAndContinuesStateAcrossWrites() {
        val large = render(8192, true)
        assertArrayEquals(large, render(17, true))
        val bytes = ByteBuffer.wrap(large).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48000, bytes.getInt(24))
        assertEquals(32768 * 4, bytes.getInt(40))
        bytes.position(44)
        val samples = DoubleArray(32768 * 2) { bytes.short.toDouble() }
        assertEquals(0.0, samples.average(), .0001)
        assertTrue(samples.all { abs(it) <= 2.0 })
        val power = samples.sumOf { it * it } / samples.size
        val covariance = (2 until samples.size).sumOf { samples[it] * samples[it - 2] } / (samples.size - 2)
        assertEquals(.5, power, .03)
        assertEquals(-.25, covariance, .03)
        assertFalse(large.contentEquals(render(8192, false)))
    }

    private fun render(chunk: Int, shaped: Boolean): ByteArray {
        val file = File.createTempFile("aurora-dither-", ".wav")
        try {
            NetworkWaveWriter(file, dither = true, noiseShaping = shaped).use { writer ->
                val block = AudioBlock(AudioStreamFormat(48000, ChannelLayout.STEREO), chunk)
                var remaining = 32768
                while (remaining > 0) {
                    val count = minOf(chunk, remaining)
                    block.begin(count)
                    writer.write(block)
                    remaining -= count
                }
                writer.finish()
                assertEquals(32768L, writer.framesWritten)
            }
            return file.readBytes()
        } finally { file.delete() }
    }
}
