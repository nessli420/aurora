package com.aurora.music.playback.chain

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.OutputDitherMode
import com.aurora.music.playback.engine.PcmBoundary
import com.aurora.music.playback.engine.PcmEncoding
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmOutputEncoderTest {
    private val block = AudioBlock(AudioStreamFormat(48_000, ChannelLayout.STEREO), 1024).apply {
        begin(512)
        for (i in 0 until 1024) samples[i] = ((i * 7919) % 16_777_216 - 8_388_608) / 8_388_608.0
        samples[0] = 0.5; samples[1] = -1.0
    }

    @Test fun twentyFourInThirtyTwoIsLeftJustifiedAndExact() {
        val out = ByteArray(4096 + 8)
        assertEquals(4096, PcmOutputEncoder().encode(block, PcmEncoding.SIGNED_24_LE, 4, OutputDitherMode.OFF, out, 8))
        val words = ByteBuffer.wrap(out, 8, 4096).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Byte>(0, 0, 0, 0x40), out.slice(8 until 12))
        assertEquals(listOf<Byte>(0, 0, 0, 0x80.toByte()), out.slice(12 until 16))
        for (i in 0 until block.sampleCount) {
            val word = words.getInt(8 + i * 4)
            assertEquals(0, word and 0xff)
            assertEquals(block.samples[i], (word shr 8) / 8_388_608.0, 0.0)
        }
    }

    @Test fun packedEncodingMatchesPcmBoundaryAndFloatIgnoresDither() {
        val packed = ByteArray(3072)
        PcmOutputEncoder().encode(block, PcmEncoding.SIGNED_24_LE, 3, OutputDitherMode.OFF, packed)
        val reference = ByteBuffer.allocate(3072)
        PcmBoundary.encode(block, PcmEncoding.SIGNED_24_LE, reference)
        assertArrayEquals(reference.array(), packed)
        val float = ByteArray(4096)
        PcmOutputEncoder().encode(block, PcmEncoding.FLOAT_32_LE, 4, OutputDitherMode.TPDF, float)
        val floats = ByteBuffer.wrap(float).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until block.sampleCount) assertEquals(block.samples[i].toFloat(), floats.getFloat(i * 4))
    }

    @Test fun ditherOnlyChangesTheLeastSignificantBitsWhenSelected() {
        val quiet = AudioBlock(AudioStreamFormat(48_000, ChannelLayout.STEREO), 1024).apply {
            begin(1024)
            for (i in 0 until 2048) samples[i] = 0.25 + i * 1e-9
        }
        val plain = ByteArray(4096)
        val dithered = ByteArray(4096)
        PcmOutputEncoder().encode(quiet, PcmEncoding.SIGNED_16_LE, 2, OutputDitherMode.OFF, plain)
        PcmOutputEncoder().encode(quiet, PcmEncoding.SIGNED_16_LE, 2, OutputDitherMode.TPDF, dithered)
        val a = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val b = ByteBuffer.wrap(dithered).order(ByteOrder.LITTLE_ENDIAN)
        var changed = 0
        for (i in 0 until 2048) {
            val delta = b.getShort(i * 2) - a.getShort(i * 2)
            assertTrue(delta in -1..1)
            if (delta != 0) changed++
        }
        assertTrue(changed > 100)
    }
}
