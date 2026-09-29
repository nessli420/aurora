package com.aurora.music.desktop.natives

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WasapiOutputLiveTest {
    @Test fun aPausedStreamStaysValidAcrossIdleProbesAndResumes() {
        assumeTrue("set AURORA_LIVE_AUDIO=1 to use the real audio device", System.getenv("AURORA_LIVE_AUDIO") == "1")
        val rate = AudioDevices.mixFormat()?.sampleRate ?: 48_000
        WasapiOutput.open(sampleRate = rate, bufferMs = 200).use { output ->
            val silence = ByteArray(rate / 10 * output.frameBytes)
            output.write(silence, timeoutMs = 1_000)
            output.resume()
            output.pause()
            Thread.sleep(2_500)
            val paused = output.status()
            assertFalse(paused.playing)
            assertFalse(paused.deviceInvalidated)
            assertEquals(0, paused.lastError)
            output.resume()
            assertTrue(output.status().playing)
            assertEquals(silence.size, output.write(silence, timeoutMs = 1_000))
        }
    }
}
