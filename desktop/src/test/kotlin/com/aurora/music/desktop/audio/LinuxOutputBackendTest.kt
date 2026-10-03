package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AlsaErrors
import com.aurora.music.desktop.natives.AlsaException
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.natives.OutputStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class LinuxOutputBackendTest {
    private val usb = HardwareDevice("hw:CARD=DAC,DEV=0", "FiiO K5 Pro", "USB Audio", "USB-Audio", "plughw:1,0")
    private val hdmi = HardwareDevice("hw:CARD=PCH,DEV=3", "HDA Intel PCH", "HDMI 0", "HDA-Intel", "plughw:0,3")
    private val analog = HardwareDevice("hw:CARD=PCH,DEV=0", "HDA Intel PCH", "ALC1220 Analog", "HDA-Intel", "plughw:0,0")
    private val s16And24At4448 = (1 shl OutputEncoding.S16.ordinal) or (1 shl OutputEncoding.S24_IN_32.ordinal) or (1 shl OutputEncoding.S32.ordinal)

    private class Output(override val deviceId: String, override val sampleRate: Int, override val encoding: OutputEncoding) : HardwareOutput {
        override val id = 1L
        override val exclusive = true
        override fun capabilities(): IntArray? = null
        override fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int) = length
        override fun resume() {}
        override fun pause() {}
        override fun flush() {}
        override fun status() = OutputStatus(0, 0, 0, false, false, true, 0, 0, 0, 0, 0)
        override fun close() {}
    }

    private inner class Hardware(
        var probe: (String) -> HardwareProbe = { HardwareProbe(0, LinuxOutputBackend.RATES.map { rate -> if (rate <= 48_000) s16And24At4448 else 0 }.toIntArray()) },
        val failures: MutableList<Int> = mutableListOf(),
    ) : ExclusiveHardware {
        val probes = CopyOnWriteArrayList<String>()
        val opens = CopyOnWriteArrayList<OutputEncoding>()
        override fun devices() = listOf(analog, hdmi, usb)
        override fun capabilities(deviceId: String, rates: IntArray): HardwareProbe {
            probes += deviceId
            val found = probe(deviceId)
            if (found.error < 0) return found
            return HardwareProbe(0, rates.map { rate -> found.masks.getOrElse(LinuxOutputBackend.RATES.indexOf(rate)) { 0 } }.toIntArray())
        }
        override fun open(deviceId: String, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int, rates: IntArray): HardwareOutput {
            opens += encoding
            failures.removeFirstOrNull()?.let { throw AlsaException(it) }
            return Output(deviceId, sampleRate, encoding)
        }
    }

    private val shared = FakeBackend(devices = listOf(
        AudioDevice("default [default]", "Direct Audio Device: default", DeviceKind.UNKNOWN, true),
        AudioDevice("HDA Intel PCH [plughw:0,0]", "ALC1220 Analog", DeviceKind.UNKNOWN, false),
    ))

    @Test fun hardwareDevicesReplaceTheSharedListBehindTheSoundServer() {
        val backend = LinuxOutputBackend(shared, Hardware(), "PipeWire")
        val devices = backend.devices()
        assertEquals(listOf("default [default]", analog.id, hdmi.id, usb.id), devices.map { it.id })
        assertEquals(listOf("PipeWire", "HDA Intel PCH · ALC1220 Analog", "HDA Intel PCH · HDMI 0", "FiiO K5 Pro"), devices.map { it.name })
        assertEquals(listOf(true, false, false, false), devices.map { it.isDefault })
        assertEquals(listOf(DeviceKind.SPEAKERS, DeviceKind.DIGITAL_DISPLAY, DeviceKind.UNKNOWN), devices.drop(1).map { it.kind })
        assertTrue(backend.exclusiveAvailable)
        val plain = LinuxOutputBackend(shared, null, "PipeWire")
        assertEquals(shared.devices, plain.devices())
        assertFalse(plain.exclusiveAvailable)
    }

    @Test fun capabilitiesAreProbedOnceAndDriveNegotiation() {
        val hardware = Hardware()
        val backend = LinuxOutputBackend(shared, hardware, "PipeWire")
        backend.devices()
        assertTrue(backend.supportsExclusive(usb.id, 44_100, OutputEncoding.S24_IN_32))
        assertTrue(backend.supportsExclusive(usb.id, 48_000, OutputEncoding.S16))
        assertFalse(backend.supportsExclusive(usb.id, 96_000, OutputEncoding.S16))
        assertFalse(backend.supportsExclusive(usb.id, 44_100, OutputEncoding.S24))
        assertFalse(backend.supportsExclusive(usb.id, 44_100, OutputEncoding.F32))
        assertEquals(listOf(usb.id), hardware.probes.toList())
        val negotiated = OutputNegotiator(backend).negotiate(usb.id, true, 96_000, com.aurora.music.playback.engine.OutputRatePolicy())
        assertTrue(negotiated.exclusive)
        assertEquals(48_000, negotiated.sampleRate)
        assertEquals(OutputEncoding.S24_IN_32, negotiated.encoding)
        assertEquals(ExclusiveFormats.Supported(listOf(44_100, 48_000), setOf(OutputEncoding.S16, OutputEncoding.S24_IN_32, OutputEncoding.S32)),
            backend.formats(usb.id))
    }

    @Test fun theSoundServerDeviceExplainsHowToGetExclusiveMode() {
        val backend = LinuxOutputBackend(shared, Hardware(), "PipeWire")
        assertFalse(backend.supportsExclusive(null, 48_000, OutputEncoding.S16))
        assertFalse(backend.supportsExclusive("default [default]", 48_000, OutputEncoding.S16))
        val reason = backend.exclusiveUnavailableReason("default [default]")!!
        val negotiated = OutputNegotiator(backend).negotiate("default [default]", true, 44_100, com.aurora.music.playback.engine.OutputRatePolicy())
        assertFalse(negotiated.exclusive)
        assertEquals(reason, negotiated.fallbackReason)
        assertEquals(ExclusiveFormats.NoDevice, backend.formats(null))
        assertNull(backend.exclusiveUnavailableReason(usb.id))
        assertNull(LinuxOutputBackend(shared, null, "PipeWire").exclusiveUnavailableReason(null))
    }

    @Test fun aDeviceOnlySeenBusyIsTriedAndARefusedFormatFallsBackToOneItAccepts() {
        var busy = true
        val hardware = Hardware(probe = { if (busy) HardwareProbe(AlsaErrors.EBUSY, IntArray(0)) else HardwareProbe(0, LinuxOutputBackend.RATES.map { (1 shl OutputEncoding.S16.ordinal) }.toIntArray()) },
            failures = mutableListOf(AlsaErrors.EINVAL))
        val backend = LinuxOutputBackend(shared, hardware, "PipeWire")
        backend.devices()
        assertTrue(backend.supportsExclusive(usb.id, 44_100, OutputEncoding.S24_IN_32))
        assertEquals(ExclusiveFormats.Busy, backend.formats(usb.id))
        busy = false
        val output = backend.open(usb.id, true, 44_100, OutputEncoding.S24_IN_32, 200)
        assertEquals(OutputEncoding.S16, output.encoding)
        assertEquals(listOf(OutputEncoding.S24_IN_32, OutputEncoding.S16), hardware.opens.toList())
        assertFalse(backend.supportsExclusive(usb.id, 44_100, OutputEncoding.S24_IN_32))
    }

    @Test fun busyDevicesAreRetriedForAWhileThenExplained() {
        val slept = CopyOnWriteArrayList<Long>()
        val hardware = Hardware(failures = MutableList(3) { AlsaErrors.EBUSY })
        val backend = LinuxOutputBackend(shared, hardware, "PipeWire", busyWaitMs = 2_000, sleep = { slept += it })
        backend.devices()
        assertTrue(backend.open(usb.id, true, 48_000, OutputEncoding.S16, 200).exclusive)
        assertEquals(3, slept.size)
        val stuck = LinuxOutputBackend(shared, Hardware(failures = MutableList(100) { AlsaErrors.EBUSY }), "PipeWire", busyWaitMs = 500, sleep = {})
        stuck.devices()
        val error = assertThrows(AlsaException::class.java) { stuck.open(usb.id, true, 48_000, OutputEncoding.S16, 200) }
        assertTrue(error.message!!, "PulseAudio" in error.message!!)
        assertFalse(error.deviceInvalidated)
        val gone = LinuxOutputBackend(shared, Hardware(failures = mutableListOf(AlsaErrors.ENODEV)), "PipeWire", sleep = {})
        gone.devices()
        assertTrue(assertThrows(AlsaException::class.java) { gone.open(usb.id, true, 48_000, OutputEncoding.S16, 200) }.deviceInvalidated)
    }

    @Test fun sharedPlaybackGoesThroughTheSoundServerUnlessThereIsNone() {
        val routed = LinuxOutputBackend(shared, Hardware(), "PipeWire")
        routed.devices()
        routed.open(analog.id, false, 48_000, OutputEncoding.F32, 200)
        assertEquals("default [default]", shared.last.deviceId)
        val direct = LinuxOutputBackend(shared, Hardware(), LinuxOutputBackend.DIRECT)
        direct.devices()
        direct.open(analog.id, false, 48_000, OutputEncoding.F32, 200)
        assertEquals("HDA Intel PCH [plughw:0,0]", shared.last.deviceId)
        direct.open(usb.id, false, 48_000, OutputEncoding.F32, 200)
        assertEquals("default [default]", shared.last.deviceId)
    }

    @Test fun theSoundServerIsDetectedFromItsSockets() {
        val dir: File = Files.createTempDirectory("aurora-runtime").toFile()
        try {
            assertEquals("ALSA", LinuxOutputBackend.soundServer(dir.path))
            File(dir, "pulse").mkdirs()
            File(dir, "pulse/native").createNewFile()
            assertEquals("PulseAudio", LinuxOutputBackend.soundServer(dir.path))
            File(dir, "pipewire-0").createNewFile()
            assertEquals("PipeWire", LinuxOutputBackend.soundServer(dir.path))
            assertEquals("ALSA", LinuxOutputBackend.soundServer(null))
        } finally {
            dir.deleteRecursively()
        }
    }
}
