package com.aurora.music.playback

import com.aurora.music.playback.engine.SamplePrecision
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImpulseResponseWavTest {
    @Test fun pcm32WavKeepsBitsBelowFloat32Resolution() {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_073_741_825).putInt(-1_073_741_825).putInt(1).putInt(-1).array()
        withWav(payload, bits = 32, channels = 2) { file ->
            val ir = ConvolutionProcessor.loadWavResult(file).getOrThrow()
            assertEquals(SamplePrecision.PCM_SIGNED_32, ir.sourcePrecision)
            assertEquals(2, ir.frameCount)
            assertEquals(1_073_741_825 / 2147483648.0, ir.preciseLeft[0], 0.0)
            assertEquals(-1_073_741_825 / 2147483648.0, ir.preciseRight[0], 0.0)
            assertEquals(1 / 2147483648.0, ir.preciseLeft[1], 0.0)
            assertNotEquals(ir.left[0].toDouble(), ir.preciseLeft[0], 0.0)
        }
    }

    @Test fun pcm24StereoAndMonoPcm8UseTheirActualSourcePrecision() {
        withWav(byteArrayOf(1, 0, 0, -1, -1, -1), bits = 24, channels = 2) { file ->
            val ir = ConvolutionProcessor.loadWavResult(file).getOrThrow()
            assertEquals(SamplePrecision.PCM_SIGNED_24, ir.sourcePrecision)
            assertEquals(1 / 8388608.0, ir.preciseLeft[0], 0.0)
            assertEquals(-1 / 8388608.0, ir.preciseRight[0], 0.0)
        }
        withWav(byteArrayOf(0, 127, -128, -1), bits = 8, channels = 1) { file ->
            val ir = ConvolutionProcessor.loadWavResult(file).getOrThrow()
            assertEquals(SamplePrecision.PCM_SIGNED_8, ir.sourcePrecision)
            assertArrayEquals(doubleArrayOf(-1.0, -1 / 128.0, 0.0, 127 / 128.0), ir.preciseLeft, 0.0)
            assertArrayEquals(ir.preciseLeft, ir.preciseRight, 0.0)
        }
    }

    @Test fun floatWavRejectsNonFiniteSamplesAndPreservesFiniteValues() {
        for (sample in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            withWav(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(sample).array(), 32, 1, 3) {
                assertTrue(ConvolutionProcessor.loadWavResult(it).isFailure)
            }
        }
        withWav(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.125f).array(), 32, 1, 3) {
            val ir = ConvolutionProcessor.loadWavResult(it).getOrThrow()
            assertEquals(SamplePrecision.FLOAT_32, ir.sourcePrecision)
            assertEquals(0.125, ir.preciseLeft[0], 0.0)
        }
    }

    @Test fun malformedChunkLengthsAlignmentAndCodecFailClosed() {
        withWav(byteArrayOf(1, 0), 16, 1) { file ->
            RandomAccessFile(file, "rw").use { it.seek(40); it.writeInt(Integer.reverseBytes(500)) }
            assertTrue(ConvolutionProcessor.loadWavResult(file).isFailure)
        }
        withWav(byteArrayOf(1, 0, 1), 16, 1) { assertTrue(ConvolutionProcessor.loadWavResult(it).isFailure) }
        withWav(byteArrayOf(1, 0, 1, 0), 16, 1, 6) { assertTrue(ConvolutionProcessor.loadWavResult(it).isFailure) }
    }

    @Test fun decodedFrameLimitFailsBeforeLargeSampleAllocation() {
        val file = File.createTempFile("aurora-ir-bounds", ".wav")
        try {
            val frames = ConvolutionProcessor.MAX_DECODED_IR_FRAMES + 1
            val dataBytes = frames * 2
            RandomAccessFile(file, "rw").use {
                it.write(header(dataBytes, 16, 1, 1))
                it.setLength(44L + dataBytes)
            }
            val failure = ConvolutionProcessor.loadWavResult(file).exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("source frames"))
        } finally { file.delete() }
    }

    @Test fun floatArrayConstructorDefensivelyOwnsOriginalSamples() {
        val left = floatArrayOf(0.125f); val right = floatArrayOf(-0.25f)
        val ir = ImpulseResponse(left, right, 48_000)
        left[0] = 0f; right[0] = 0f
        assertEquals(0.125, ir.preciseLeft[0], 0.0)
        assertEquals(-0.25, ir.preciseRight[0], 0.0)
        assertEquals(SamplePrecision.FLOAT_32, ir.sourcePrecision)
    }

    @Test fun extensiblePcm32RetainsQuietBitsAndValidatesItsSubformatGuid() {
        val bytes = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952); putInt(64); putInt(0x45564157)
            putInt(0x20746d66); putInt(40); putShort(0xfffe.toShort()); putShort(1)
            putInt(48_000); putInt(192_000); putShort(4); putShort(32)
            putShort(22); putShort(32); putInt(4)
            putInt(1); putInt(0x00100000); putInt(0xaa000080.toInt()); putInt(0x719b3800)
            putInt(0x61746164); putInt(4); putInt(1_073_741_825)
        }.array()
        val file = File.createTempFile("aurora-ir-extensible", ".wav")
        try {
            file.writeBytes(bytes)
            val ir = ConvolutionProcessor.loadWavResult(file).getOrThrow()
            assertEquals(32, ir.sourceValidBits)
            assertEquals(1_073_741_825 / 2147483648.0, ir.preciseLeft[0], 0.0)
            bytes[57] = 0
            file.writeBytes(bytes)
            assertTrue(ConvolutionProcessor.loadWavResult(file).isFailure)
        } finally { file.delete() }
    }

    private fun withWav(payload: ByteArray, bits: Int, channels: Int, code: Int = 1, block: (File) -> Unit) {
        val file = File.createTempFile("aurora-ir", ".wav")
        try {
            file.outputStream().use { it.write(header(payload.size, bits, channels, code)); it.write(payload); if (payload.size % 2 != 0) it.write(0) }
            block(file)
        } finally { file.delete() }
    }

    private fun header(bytes: Int, bits: Int, channels: Int, code: Int) =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952); putInt(36 + bytes + bytes % 2); putInt(0x45564157)
            putInt(0x20746d66); putInt(16); putShort(code.toShort()); putShort(channels.toShort())
            putInt(48_000); putInt(48_000 * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            putInt(0x61746164); putInt(bytes)
        }.array()
}
