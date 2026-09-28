package com.aurora.music.desktop.audio.decode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class FfmpegDecoderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun mp3GaplessTrimMatchesTheReferenceDecode() {
        val reference = TestAssets.reference("gapless-mp3.pcm")
        FfmpegDecoder.open(TestAssets.file("gapless.mp3").path).use { decoder ->
            assertEquals("mp3", decoder.info.codec)
            assertEquals(48_000, decoder.info.sampleRate)
            assertEquals(2, decoder.info.channels)
            assertEquals(SourceSampleFormat(SampleKind.LOSSY, 0), decoder.info.sampleFormat)
            assertEquals(208L, decoder.info.durationMs)
            assertTrue(decoder.info.seekable)
            val samples = decodeAll(decoder)
            assertEquals(10_001 * 2, samples.size)
            assertEquals(reference.size, samples.size)
            assertEquals(10_001L, decoder.positionFrames)
            assertMatchesReference(reference, samples, 0)
        }
    }

    @Test fun opusPreSkipAndEndTrimmingGiveTheTrueLength() {
        FfmpegDecoder.open(TestAssets.file("gapless.opus").path).use { decoder ->
            assertEquals("opus", decoder.info.codec)
            assertEquals("ogg", decoder.info.container)
            assertEquals(48_000, decoder.info.sampleRate)
            assertEquals(SampleKind.LOSSY, decoder.info.sampleFormat.kind)
            val samples = decodeAll(decoder, 777)
            assertEquals(10_001 * 2, samples.size)
            assertEquals(10_001L, decoder.positionFrames)
            assertTrue(samples.any { abs(it) > 0.01 })
        }
    }

    @Test fun flac24KeepsEveryLowBit() {
        FfmpegDecoder.open(TestAssets.file("lowbits-24.flac").path).use { decoder ->
            assertEquals("flac", decoder.info.codec)
            assertEquals(SourceSampleFormat(SampleKind.INTEGER, 24), decoder.info.sampleFormat)
            assertTrue(decoder.info.sampleFormat.lossless)
            val samples = decodeAll(decoder)
            assertEquals(4096 * 2, samples.size)
            for (frame in 0 until 4096) for (channel in 0..1) {
                assertEquals("frame=$frame channel=$channel", lowBits(frame, channel), samples[frame * 2 + channel], 0.0)
            }
        }
    }

    @Test fun integerAndFloatPcmRoundTripExactly() {
        val random = Random(7)
        val pcm16 = IntArray(4000) { random.nextInt(-32768, 32768) }
        val pcm24 = IntArray(4000) { random.nextInt(-8388608, 8388608) }
        val pcm32 = IntArray(4000) { random.nextInt() }
        val float = FloatArray(4000) { random.nextFloat() * 2 - 1 }
        assertEquals(pcm16.map { it / 32768.0 }, decodeWav(44_100, 2, 16) { f, c -> pcm16[f * 2 + c] }.toList())
        assertEquals(pcm24.map { it / 8388608.0 }, decodeWav(96_000, 2, 24) { f, c -> pcm24[f * 2 + c] }.toList())
        assertEquals(pcm32.map { it / 2147483648.0 }, decodeWav(192_000, 2, 32) { f, c -> pcm32[f * 2 + c] }.toList())
        assertEquals(float.map { it.toDouble() }, decodeWav(48_000, 2, 32, float = true) { f, c -> float[f * 2 + c] }.toList())
    }

    @Test fun wavReportsItsSourceFormat() {
        fun info(bits: Int, float: Boolean = false): StreamInfo {
            val file = temp.newFile()
            file.writeBytes(wavBytes(88_200, 1, bits, 100, float) { _, _ -> 0 })
            return FfmpegDecoder.open(file.path).use { it.info }
        }
        assertEquals(SourceSampleFormat(SampleKind.INTEGER, 16), info(16).sampleFormat)
        assertEquals(SourceSampleFormat(SampleKind.INTEGER, 24), info(24).sampleFormat)
        assertEquals(SourceSampleFormat(SampleKind.INTEGER, 32), info(32).sampleFormat)
        assertEquals(SourceSampleFormat(SampleKind.FLOAT, 32), info(32, float = true).sampleFormat)
        assertEquals(88_200, info(16).sampleRate)
        assertEquals(1, info(16).channels)
        assertEquals("wav", info(16).container)
    }

    @Test fun monoIsDuplicatedToBothChannelsExactly() {
        val random = Random(11)
        val pcm = IntArray(3000) { random.nextInt(-8388608, 8388608) }
        val samples = decodeWav(48_000, 1, 24, frames = 3000) { f, _ -> pcm[f] }
        assertEquals(6000, samples.size)
        for (frame in pcm.indices) {
            assertEquals(pcm[frame] / 8388608.0, samples[frame * 2], 0.0)
            assertEquals(pcm[frame] / 8388608.0, samples[frame * 2 + 1], 0.0)
        }
    }

    @Test fun surroundIsDownmixedWithoutExceedingFullScale() {
        val segment = 1000
        val samples = decodeWav(48_000, 6, 16, frames = segment * 7) { f, c ->
            when (val part = f / segment) {
                6 -> if (c == 3) 0 else 32767
                else -> if (c == part) 16384 else 0
            }
        }
        fun at(part: Int, channel: Int) = samples[(part * segment + segment / 2) * 2 + channel]
        assertTrue(at(0, 0) > 0.1)
        assertEquals(0.0, at(0, 1), 0.0)
        assertEquals(0.0, at(1, 0), 0.0)
        assertTrue(at(1, 1) > 0.1)
        assertTrue(at(2, 0) > 0.05)
        assertEquals(at(2, 0), at(2, 1), 0.0)
        assertEquals(0.0, at(3, 0), 0.0)
        assertEquals(0.0, at(3, 1), 0.0)
        assertTrue(at(4, 0) > 0.05)
        assertEquals(0.0, at(4, 1), 0.0)
        assertEquals(0.0, at(5, 0), 0.0)
        assertTrue(at(5, 1) > 0.05)
        assertTrue(samples.all { abs(it) <= 1.0 })
        assertTrue(at(6, 0) > 0.99)
    }

    @Test fun seeksLandOnTheExactFrame() {
        val file = temp.newFile("ramp.wav")
        file.writeBytes(wavBytes(48_000, 2, 24, 144_000) { f, c -> ramp(f, c) })
        FfmpegDecoder.open(file.path).use { decoder ->
            val block = DoubleArray(512)
            for (target in listOf(0L, 1L, 4_799L, 100_000L, 12_345L, 143_999L)) {
                decoder.seekToFrame(target)
                assertEquals(target, decoder.positionFrames)
                val frames = decoder.read(block)
                assertTrue(frames > 0)
                for (i in 0 until frames) for (c in 0..1) assertEquals(ramp((target + i).toInt(), c) / 8388608.0, block[i * 2 + c], 0.0)
                assertEquals(target + frames, decoder.positionFrames)
            }
            decoder.seek(1_500)
            assertEquals(72_000L, decoder.positionFrames)
            assertEquals(1_500L, decoder.positionMs)
            assertTrue(decoder.read(block) > 0)
            assertEquals(ramp(72_000, 0) / 8388608.0, block[0], 0.0)
            decoder.seek(10_000)
            assertEquals(-1, decoder.read(block))
            decoder.seek(2_999)
            var remaining = 0L
            while (true) remaining += decoder.read(block).takeIf { it >= 0 } ?: break
            assertEquals(144_000L - 143_952L, remaining)
        }
    }

    @Test fun flacSeekDiscardsToTheTargetInsideABlock() {
        FfmpegDecoder.open(TestAssets.file("lowbits-24.flac").path).use { decoder ->
            decoder.seek(50)
            assertEquals(2_400L, decoder.positionFrames)
            val samples = decodeAll(decoder, 333)
            assertEquals((4096 - 2400) * 2, samples.size)
            for (i in 0 until 4096 - 2400) for (c in 0..1) assertEquals(lowBits(2400 + i, c), samples[i * 2 + c], 0.0)
            decoder.seekToFrame(0)
            assertEquals(4096 * 2, decodeAll(decoder).size)
        }
    }

    @Test fun mp3SeeksStayAlignedWithTheReference() {
        val reference = TestAssets.reference("gapless-mp3.pcm")
        FfmpegDecoder.open(TestAssets.file("gapless.mp3").path).use { decoder ->
            val full = decodeAll(decoder)
            for (target in listOf(4_800L, 7_001L, 0L, 1L, 9_999L)) {
                decoder.seekToFrame(target)
                assertEquals(target, decoder.positionFrames)
                val tail = decodeAll(decoder, 500)
                assertEquals((10_001 - target) * 2, tail.size.toLong())
                assertMatchesReference(reference, tail, target.toInt())
                assertArrayEquals(full.copyOfRange(target.toInt() * 2, full.size), tail, 0.0)
            }
        }
    }

    @Test fun opusSeeksKeepTheTrimmedLengthAndAlignment() {
        FfmpegDecoder.open(TestAssets.file("gapless.opus").path).use { decoder ->
            val full = decodeAll(decoder)
            decoder.seekToFrame(0)
            assertArrayEquals(full, decodeAll(decoder), 0.0)
            decoder.seek(100)
            assertEquals(4_800L, decoder.positionFrames)
            val tail = decodeAll(decoder)
            assertEquals((10_001 - 4_800) * 2, tail.size)
            val error = sqrt(tail.indices.sumOf { (tail[it] - full[4_800 * 2 + it]).pow(2) } / tail.size)
            assertTrue("rms $error", error < 1e-3)
        }
    }

    @Test fun endOfStreamIsStickyAndSeekRestartsIt() {
        FfmpegDecoder.open(TestAssets.file("lowbits-24.flac").path).use { decoder ->
            val block = DoubleArray(8192)
            var total = 0
            while (true) total += decoder.read(block, 1000).takeIf { it >= 0 } ?: break
            assertEquals(4096, total)
            assertEquals(-1, decoder.read(block))
            assertEquals(-1, decoder.read(block, 0))
            assertEquals(4096L, decoder.positionFrames)
            decoder.seekToFrame(4000)
            assertEquals(96, decoder.read(block))
            assertEquals(-1, decoder.read(block))
        }
    }

    @Test fun steadyStateDecodingDoesNotAllocate() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        for (channels in listOf(2, 1)) {
            val file = temp.newFile()
            file.writeBytes(wavBytes(48_000, channels, 16, 720_000) { f, c -> (f * 31 + c) % 20000 - 10000 })
            FfmpegDecoder.open(file.path).use { decoder ->
                val block = DoubleArray(512)
                repeat(200) { decoder.read(block) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
                repeat(2000) { assertTrue(decoder.read(block) > 0) }
                val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
                assertTrue("channels=$channels allocated=$allocated", allocated < 1024)
            }
        }
    }

    @Test fun closeIsIdempotentAndReadAfterCloseFails() {
        val decoder = FfmpegDecoder.open(TestAssets.file("gapless.mp3").path)
        decoder.close()
        decoder.close()
        assertTrue(runCatching { decoder.read(DoubleArray(2)) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun missingFilesAndGarbageFailWithFfmpegMessages() {
        val missing = runCatching { FfmpegDecoder.open(File(temp.root, "missing.flac").path) }.exceptionOrNull()
        assertTrue(missing is DecoderException)
        assertTrue(missing!!.message!!, missing.message!!.contains("No such file or directory"))
        val garbage = temp.newFile("garbage.mp3").apply { writeBytes(ByteArray(4096) { (it * 7).toByte() }) }
        val invalid = runCatching { FfmpegDecoder.open(garbage.path) }.exceptionOrNull()
        assertTrue(invalid is DecoderException)
        assertTrue((invalid as DecoderException).code < 0)
    }

    private fun decodeWav(rate: Int, channels: Int, bits: Int, frames: Int = 2000, float: Boolean = false, sample: (Int, Int) -> Number): DoubleArray {
        val file = temp.newFile()
        file.writeBytes(wavBytes(rate, channels, bits, frames, float, sample))
        return FfmpegDecoder.open(file.path).use { decoder ->
            assertEquals(rate, decoder.info.sampleRate)
            decodeAll(decoder, 300)
        }
    }

    private fun assertMatchesReference(reference: ShortArray, samples: DoubleArray, offsetFrames: Int) {
        var worst = 0
        for (i in samples.indices) worst = maxOf(worst, abs(toPcm16(samples[i]) - reference[offsetFrames * 2 + i]))
        assertTrue("worst difference $worst LSB", worst <= 1)
    }

    private fun lowBits(frame: Int, channel: Int) = ((frame + channel) % 17 - 8) * 8 / 8388608.0

    private fun ramp(frame: Int, channel: Int) = if (channel == 0) frame - 4_194_304 else 4_194_303 - frame
}
