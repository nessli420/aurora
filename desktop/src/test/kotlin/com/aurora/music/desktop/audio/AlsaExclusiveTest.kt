package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AlsaErrors
import com.aurora.music.desktop.natives.AlsaException
import com.aurora.music.desktop.natives.AlsaNative
import com.aurora.music.desktop.natives.AlsaOutput
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.platform.HostPlatform
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class AlsaExclusiveTest {
    private val tracks = Tracks()
    private val engines = mutableListOf<PlaybackEngine>()
    private val root: File = Files.createTempDirectory("aurora-alsa").toFile()
    private val rates = LinuxOutputBackend.RATES.toIntArray()

    @Before fun alsaOnly() {
        assumeTrue(HostPlatform.isLinux && AlsaNative.available)
    }

    @After fun tearDown() {
        engines.forEach { it.close() }
        tracks.close()
        root.deleteRecursively()
    }

    private class Routed(private val devices: Map<String, String>, private val paced: Boolean = false) : ExclusiveHardware {
        val opened = AtomicInteger()
        override fun devices() = devices.keys.map { HardwareDevice(it, "Capture", "USB Audio", "USB-Audio", "plughw:9,0") }
        override fun capabilities(deviceId: String, rates: IntArray) = AlsaHardware.capabilities(devices.getValue(deviceId), rates)
        override fun open(deviceId: String, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int, rates: IntArray): HardwareOutput {
            val output = AlsaHardware.open(devices.getValue(deviceId), sampleRate, encoding, bufferMs, rates)
            opened.incrementAndGet()
            return if (paced) Paced(output) else output
        }
    }

    private class Paced(private val output: HardwareOutput) : HardwareOutput by output {
        private val frameBytes = output.encoding.bytesPerSample * 2
        private var accepted = 0L
        private var credit = output.sampleRate * 3L / 10
        private var resumedAt = 0L

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            val now = System.nanoTime()
            val earned = if (resumedAt > 0) (now - resumedAt) * output.sampleRate / 1_000_000_000L else 0L
            val allowed = ((credit + earned - accepted) * frameBytes).coerceIn(0, length.toLong()).toInt()
            val written = if (allowed > 0) output.write(bytes, offset, allowed - allowed % frameBytes, timeoutMs) else 0
            accepted += written / frameBytes
            return written
        }

        @Synchronized
        override fun resume() {
            output.resume()
            resumedAt = System.nanoTime()
            credit = accepted + output.sampleRate * 3L / 10
        }

        @Synchronized
        override fun pause() {
            output.pause()
            resumedAt = 0
        }

        @Synchronized
        override fun flush() {
            output.flush()
            resumedAt = 0
            accepted = 0
            credit = output.sampleRate * 3L / 10
        }
    }

    private fun engine(backend: OutputBackend) = DesktopPlaybackEngine(backend, random = Random(3)).also { engines += it }

    private fun ints(file: File): IntArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(buffer.remaining() / 4) { buffer.getInt() }
    }

    @Test fun aNativeStreamPlaysPausesAndFlushesOnTheNullDevice() {
        AlsaOutput.open("null", 48_000, OutputEncoding.S16, 100, rates).use { output ->
            assertTrue(output.capabilities()!!.all { it and (1 shl OutputEncoding.S16.ordinal) != 0 })
            val bytes = ByteArray(4_800 * output.frameBytes)
            assertEquals(bytes.size, output.write(bytes, 0, bytes.size, 1_000))
            output.resume()
            eventually(5_000) { output.status().framesPlayed >= 4_800 }
            output.status().let {
                assertTrue(it.playing)
                assertTrue(it.exclusive)
                assertFalse(it.deviceInvalidated)
                assertTrue(it.positionNanos > 0)
            }
            output.pause()
            assertFalse(output.status().playing)
            output.flush()
            output.status().let {
                assertEquals(0, it.framesPlayed)
                assertEquals(0, it.framesBuffered)
            }
            assertEquals(bytes.size, output.write(bytes, 0, bytes.size, 1_000))
            output.resume()
            eventually(5_000) { output.status().framesPlayed >= 4_800 }
        }
    }

    @Test fun missingDevicesFailCleanly() {
        val missing = "hw:CARD=AuroraMissing,DEV=0"
        val error = assertThrows(AlsaException::class.java) { AlsaOutput.open(missing, 48_000, OutputEncoding.S16, 100, rates) }
        assertTrue(error.error < 0)
        assertTrue(AlsaHardware.capabilities(missing, rates).error < 0)
        assertTrue(AlsaHardware.capabilities("null", rates).masks.isNotEmpty())
    }

    @Test fun twentyFourBitPlaybackReachesTheDeviceUnchanged() {
        val capture = File(root, "capture.raw")
        val hardware = Routed(mapOf("hw:CARD=Capture,DEV=0" to "file:FILE=${capture.path},FORMAT=raw"))
        val backend = LinuxOutputBackend(FakeBackend(), hardware, "PipeWire")
        val samples = IntArray(8_000 * 2) { ((it * 2_654_435_761L + 97) % 16_777_216 - 8_388_608).toInt() }
        val file = tracks.wav("hires", 44_100, 24, 8_000) { frame, channel -> samples[frame * 2 + channel] }
        val engine = engine(backend)
        engine.setOutput("hw:CARD=Capture,DEV=0", exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            log.await(20_000) { EngineEvent.Ended in it }
            assertTrue(log.all<EngineEvent.Failed>().isEmpty())
            assertTrue(log.all<EngineEvent.OutputFallback>().isEmpty())
        }
        val state = engine.await { it.phase == EnginePhase.ENDED }
        assertTrue(state.output!!.exclusive)
        assertEquals(44_100, state.output!!.sampleRate)
        assertEquals(OutputEncoding.S24_IN_32, state.output!!.encoding)
        assertTrue(state.processing.bitPerfect)
        engine.close()
        val heard = ints(capture)
        assertArrayEquals(samples.map { it shl 8 }.toIntArray(), heard.copyOf(samples.size))
        assertTrue(heard.drop(samples.size).all { it == 0 })
    }

    @Test fun pausingAnExclusiveStreamResumesWithoutReopeningTheDevice() {
        val hardware = Routed(mapOf("hw:CARD=Null,DEV=0" to "null"), paced = true)
        val backend = LinuxOutputBackend(FakeBackend(), hardware, "PipeWire")
        val file = tracks.wav("long", 48_000, 16, 48_000 * 10) { frame, channel -> (frame * 13 + channel) % 2_000 - 1_000 }
        val engine = engine(backend)
        engine.setOutput("hw:CARD=Null,DEV=0", exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            engine.await { it.isPlaying && it.output?.exclusive == true }
            repeat(3) {
                engine.pause()
                engine.await { !it.isPlaying }
                engine.play()
                engine.await { it.isPlaying }
            }
            Thread.sleep(300)
            assertEquals(1, hardware.opened.get())
            assertTrue(log.all<EngineEvent.Failed>().isEmpty())
            assertTrue(log.all<EngineEvent.OutputFallback>().isEmpty())
            assertEquals(1, log.transitions().size)
        }
    }

    @Test fun aBusyDeviceFallsBackToSharedModeWithTheReason() {
        val shared = FakeBackend()
        val slept = CopyOnWriteArrayList<Long>()
        val busy = object : ExclusiveHardware {
            override fun devices() = listOf(HardwareDevice("hw:CARD=DAC,DEV=0", "DAC", "USB Audio", "USB-Audio", "plughw:1,0"))
            override fun capabilities(deviceId: String, rates: IntArray) = HardwareProbe(AlsaErrors.EBUSY, IntArray(0))
            override fun open(deviceId: String, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int, rates: IntArray): HardwareOutput =
                throw AlsaException(AlsaErrors.EBUSY)
        }
        val backend = LinuxOutputBackend(shared, busy, "PipeWire", busyWaitMs = 1_000, sleep = { slept += it })
        val file = tracks.wav("busy", 48_000, 16, 4_800) { frame, _ -> frame % 100 }
        val engine = engine(backend)
        engine.setOutput("hw:CARD=DAC,DEV=0", exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            log.await { EngineEvent.Ended in it }
            val reason = log.all<EngineEvent.OutputFallback>().single().reason
            assertTrue(reason, "PipeWire" in reason)
            assertTrue(log.all<EngineEvent.Failed>().isEmpty())
        }
        assertFalse(shared.last.exclusive)
        assertEquals(4, slept.size)
        assertEquals(ExclusiveFormats.Busy, backend.formats("hw:CARD=DAC,DEV=0"))
    }
}
