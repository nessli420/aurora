package com.aurora.music.playback.dsd

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class DsdPcmDecoderTest {
    @Test fun byteLookupMatchesDirectBitConvolutionIncludingPartialByteAndBothEdges() {
        for (rate in listOf(2_822_400, 5_644_800)) {
            val f = DsdFormat(DsdContainer.DSF, rate, 2, 12_347, 92, 8192)
            val channels = Array(2) { Random(it + 7).nextBytes(f.bytesPerChannel.toInt()) }
            val decoder = DsdPcmDecoder(f)
            val actual = decode(decoder, channels)
            assertEquals(f.pcmFrames.toInt() * 2, actual.size)
            for (frame in 0 until f.pcmFrames.toInt()) for (channel in 0..1) {
                val newest = frame * f.decimation + f.filterTaps / 2 - 1
                var expected = 0.0
                for (tap in decoder.coefficients.indices) {
                    val bit = newest - tap
                    if (bit >= 0 && bit < f.sampleCount) expected += decoder.coefficients[tap] *
                        if (channels[channel][bit / 8].toInt() and (1 shl (7 - bit % 8)) == 0) -1.0 else 1.0
                }
                assertEquals("$rate frame $frame channel $channel", expected.toFloat(), actual[frame * 2 + channel], 2e-7f)
            }
        }
    }

    @Test fun passbandAndUltrasonicRejectionAreBoundedAtBothRates() {
        for (rate in listOf(2_822_400, 5_644_800)) {
            val coefficients = DsdPcmDecoder(DsdFormat(DsdContainer.DFF, rate, 1, 8192, 0, 1024)).coefficients
            fun response(frequency: Double): Double {
                var real = 0.0; var imaginary = 0.0
                for (i in coefficients.indices) {
                    val angle = 2 * PI * frequency * i / rate
                    real += coefficients[i] * cos(angle); imaginary += coefficients[i] * sin(angle)
                }
                return hypot(real, imaginary)
            }
            for (frequency in listOf(0.0, 1000.0, 10_000.0, 20_000.0, 30_000.0)) assertEquals(1.0, response(frequency), .0002)
            for (frequency in listOf(88_200.0, 100_000.0, 200_000.0, 1_000_000.0)) assertTrue("$rate $frequency", response(frequency) < .00006)
        }
    }

    @Test fun generatedDsdToneRetainsAmplitudePitchAndCompensatedTiming() {
        for (rate in listOf(2_822_400, 5_644_800)) {
            val bits = rate / 20
            val f = DsdFormat(DsdContainer.DFF, rate, 1, bits.toLong(), 0, (bits + 7L) / 8)
            val channel = ByteArray(f.bytesPerChannel.toInt())
            var firstError = 0.0; var secondError = 0.0; var previous = 0.0
            for (bit in 0 until bits) {
                firstError += .4 * sin(2 * PI * 1000 * bit / rate) - previous
                secondError += firstError - previous
                previous = if (secondError >= 0) 1.0 else -1.0
                if (previous > 0) channel[bit / 8] = (channel[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            }
            val output = decode(DsdPcmDecoder(f), arrayOf(channel))
            var squaredError = 0.0
            for (i in 100 until output.size - 100) {
                val expected = .4 * sin(2 * PI * 1000 * (i * f.decimation - .5) / rate)
                squaredError += (output[i] - expected).pow(2)
            }
            val rmsError = sqrt(squaredError / (output.size - 200))
            assertTrue("$rate RMS error $rmsError", rmsError < .001)
        }
    }

    @Test fun seekingWithPrerollMatchesContinuousDecodeExactlyAndResetDropsOldHistory() {
        for (container in DsdContainer.entries) for (rate in listOf(2_822_400, 5_644_800)) {
            val f = DsdFormat(container, rate, 2, 300_000, 92, 81_920)
            val channels = Array(2) { Random(37 + it).nextBytes(f.bytesPerChannel.toInt()) }
            val decoder = DsdPcmDecoder(f)
            val full = decode(decoder, channels)
            val frame = 1237L
            val start = f.prerollByte(frame)
            decoder.reset(start, frame)
            val suffix = decode(decoder, channels, start)
            assertArrayEquals(full.copyOfRange(frame.toInt() * 2, full.size), suffix, 0f)
            decoder.reset()
            assertArrayEquals(full, decode(decoder, channels), 0f)
        }
    }

    private fun decode(decoder: DsdPcmDecoder, channels: Array<ByteArray>, start: Long = 0): FloatArray {
        val f = decoder.format
        val output = FloatArray((f.pcmFrames - decoder.outputFrame).toInt() * f.channels)
        val bytes = IntArray(f.channels)
        var source = start; var offset = 0
        while (!decoder.isEnded) {
            val valid = if (source < f.bytesPerChannel) minOf(8L, f.sampleCount - source * 8).toInt() else 0
            for (channel in bytes.indices) bytes[channel] = if (valid > 0) channels[channel][source.toInt()].toInt() and 255 else 0
            if (decoder.push(bytes, valid, output, offset)) offset += f.channels
            source++
        }
        assertEquals(output.size, offset)
        return output
    }
}
