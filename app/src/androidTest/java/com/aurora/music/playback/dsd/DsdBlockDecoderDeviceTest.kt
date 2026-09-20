package com.aurora.music.playback.dsd

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

class DsdBlockDecoderDeviceTest {
    @Test fun nativeMatchesReferenceAcrossRatesChannelsPartialBitsAndSeeks() {
        for (rate in DsdFormat.supportedBitRates) for (channels in 1..2) {
            val f = DsdFormat(DsdContainer.DFF, rate, channels, 123_457, 0, 15433L * channels)
            val input = Random(9).nextBytes(f.bytesPerChannel.toInt() * channels)
            val expected = FloatArray(f.pcmFrames.toInt() * channels)
            val reference = DsdPcmDecoder(f)
            val values = IntArray(channels)
            var source = 0
            var frame = 0
            while (!reference.isEnded) {
                val bits = minOf(8L, f.sampleCount - source * 8L).coerceAtLeast(0).toInt()
                for (c in values.indices) values[c] = if (bits > 0) input[source * channels + c].toInt() else 0
                if (reference.push(values, bits, expected, frame * channels)) frame++
                source++
            }
            DsdBlockDecoder(f).use { decoder ->
                for (first in listOf(0L, f.pcmFrames / 3, f.pcmFrames - 1, f.pcmFrames, 0L)) {
                    val start = f.prerollByte(first)
                    decoder.reset(start, first)
                    var offset = start.toInt() * channels
                    var out = first.toInt() * channels
                    val pcm = FloatArray(4160)
                    while (!decoder.ended) {
                        val count = minOf(37 * channels, input.size - offset)
                        val block = if (count == 0) null else DsdRawBlock(input.copyOfRange(offset, offset + count), count,
                            offset / channels * 8L, minOf(count / channels * 8L, f.sampleCount - offset / channels * 8L))
                        val produced = decoder.decode(block, pcm)
                        for (i in 0 until produced * channels) assertEquals("$rate/$channels at ${out + i}", expected[out + i], pcm[i], 2e-7f)
                        out += produced * channels
                        offset += count
                    }
                    assertEquals(expected.size, out)
                }
            }
        }
    }

    @Test fun invalidBlocksCannotAdvanceDecoderAndCloseIsIdempotent() {
        val f = DsdFormat(DsdContainer.DFF, 45_158_400, 2, 32768, 0, 8192)
        val decoder = DsdBlockDecoder(f)
        val out = FloatArray(4160)
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(null, out) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(DsdRawBlock(ByteArray(4), 4, 1, 16), out) }
        assertEquals(0L, decoder.outputFrame)
        decoder.close(); decoder.close()
        assertThrows(IllegalStateException::class.java) { decoder.decode(null, out) }
    }

    @Test fun sustainedDsd1024HasRealtimeHeadroom() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dsdPerformance") == "true")
        val seconds = 30
        val rate = 45_158_400
        val f = DsdFormat(DsdContainer.DFF, rate, 2, rate * seconds.toLong(), 0, rate / 8L * seconds * 2)
        val input = DsdFixtures.tone(rate, 1)
        val interleaved = ByteArray(input[0].size * 2) { input[it % 2][it / 2] }
        val block = ByteArray(8192)
        val pcm = FloatArray(4160)
        DsdBlockDecoder(f).use { decoder ->
            var source = 0L
            var checksum = 0.0
            val start = SystemClock.elapsedRealtimeNanos()
            var windowStart = start
            var worst = 0.0
            var windowFrames = 0
            while (!decoder.ended) {
                val count = minOf(4096L, f.bytesPerChannel - source).toInt()
                val startByte = (source * 2 % interleaved.size).toInt()
                val first = minOf(count * 2, interleaved.size - startByte)
                interleaved.copyInto(block, 0, startByte, startByte + first)
                if (first < count * 2) interleaved.copyInto(block, first, 0, count * 2 - first)
                val produced = decoder.decode(if (count == 0) null else DsdRawBlock(block, count * 2, source * 8, count * 8L), pcm)
                for (i in 0 until produced * 2) checksum += pcm[i] * pcm[i]
                source += count
                windowFrames += produced
                if (windowFrames >= 176400) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    worst = maxOf(worst, (now - windowStart) / (windowFrames / 176400.0 * 1e9))
                    windowStart = now; windowFrames = 0
                }
            }
            val elapsed = SystemClock.elapsedRealtimeNanos() - start
            val ratio = elapsed / (seconds * 1e9)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "Native DSD1024 stereo: ${elapsed / 1_000_000} ms / ${seconds * 1000} ms; ratio=$ratio; worst second=$worst; checksum=$checksum\n")
            })
            assertEquals(f.pcmFrames, decoder.outputFrame)
            assertTrue(checksum.isFinite() && checksum > 1)
            assertTrue("No sustained headroom: $ratio / $worst", ratio < .75 && worst < .85)
        }
    }
}
