package com.aurora.music.playback.dsd

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DsdPerformanceDeviceTest {
    @Test fun stereoDecimationHasMeasuredRealtimeHeadroom() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dsdPerformance") == "true")
        for (rate in DsdFormat.supportedBitRates) {
            val seconds = 2
            val source = DsdFixtures.tone(rate, seconds)
            val format = DsdFormat(DsdContainer.DFF, rate, 2, rate * seconds.toLong(), 0, source[0].size * 2L)
            val decoder = DsdPcmDecoder(format)
            val bytes = IntArray(2)
            val output = FloatArray(2)
            var checksum = 0.0
            fun decode(): Long {
                decoder.reset()
                var offset = 0
                val start = SystemClock.elapsedRealtimeNanos()
                while (!decoder.isEnded) {
                    val valid = if (offset < source[0].size) 8 else 0
                    if (valid > 0) { bytes[0] = source[0][offset].toInt(); bytes[1] = source[1][offset].toInt() }
                    if (decoder.push(bytes, valid, output, 0)) checksum += output[0] * output[0] + output[1] * output[1]
                    offset++
                }
                return SystemClock.elapsedRealtimeNanos() - start
            }
            decode()
            val elapsed = decode()
            val realtimeRatio = elapsed.toDouble() / (seconds * 1_000_000_000L)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "DSD${rate / 44100} stereo: ${elapsed / 1_000_000} ms for ${seconds * 1000} ms; ratio=$realtimeRatio\n")
            })
            assertTrue("DSD${rate / 44100} requires faster decoding: ratio=$realtimeRatio", realtimeRatio < 0.85)
            assertTrue(checksum.isFinite() && checksum > 1.0)
            assertEquals(format.pcmFrames, decoder.outputFrame)
        }
    }
}
