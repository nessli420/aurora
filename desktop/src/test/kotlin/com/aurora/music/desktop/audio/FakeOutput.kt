package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.natives.OutputStatus
import com.aurora.music.desktop.natives.WasapiException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal class FakeBackend(
    var devices: List<AudioDevice> = listOf(AudioDevice("speakers", "Speakers", DeviceKind.SPEAKERS, true)),
    private val mixRate: Int = 48_000,
    private val exclusive: Set<Pair<Int, OutputEncoding>> = emptySet(),
    private val speed: Double = Double.POSITIVE_INFINITY,
    private val ringFrames: Int = 8_192,
    private val refuseExclusive: Boolean = false,
) : OutputBackend {
    val opened = CopyOnWriteArrayList<FakeOutput>()
    val awake = CopyOnWriteArrayList<Boolean>()
    private val listeners = CopyOnWriteArrayList<(DeviceEvent) -> Unit>()
    private val ids = AtomicLong()

    val last: FakeOutput get() = opened.last()

    override fun devices() = devices
    override fun mixRate(deviceId: String?) = mixRate
    override fun supportsExclusive(deviceId: String?, sampleRate: Int, encoding: OutputEncoding) = (sampleRate to encoding) in exclusive

    override fun open(deviceId: String?, exclusive: Boolean, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int): AudioOutput {
        if (exclusive && refuseExclusive) throw WasapiException(WasapiException.DEVICE_IN_USE)
        val resolved = deviceId ?: devices.firstOrNull { it.isDefault }?.id
        return FakeOutput(ids.incrementAndGet(), resolved, exclusive, sampleRate, encoding, ringFrames, speed).also { opened += it }
    }

    override fun listen(listener: (DeviceEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun keepAwake(enabled: Boolean) {
        awake += enabled
    }

    fun fire(event: DeviceEvent) = listeners.forEach { it(event) }
}

internal class FakeOutput(
    override val id: Long,
    override val deviceId: String?,
    override val exclusive: Boolean,
    override val sampleRate: Int,
    override val encoding: OutputEncoding,
    ringFrames: Int,
    private val speed: Double,
) : AudioOutput {
    private val frameBytes = encoding.bytesPerSample * 2
    private val ring = ByteArray(ringFrames * frameBytes)
    private val heard = ByteArrayOutputStream()
    private var read = 0
    private var buffered = 0
    private var played = 0L
    private var playing = false
    private var clock = 0L
    @Volatile var closed = false; private set
    @Volatile var invalidated = false
    @Volatile var resumeFailure = 0
    @Volatile var flushes = 0; private set

    override fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (invalidated) throw WasapiException(WasapiException.DEVICE_INVALIDATED)
        check(!closed)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var accepted = 0
        while (true) {
            synchronized(this) {
                advance()
                val count = minOf(length - accepted, ring.size - buffered)
                for (i in 0 until count) ring[(read + buffered + i) % ring.size] = bytes[offset + accepted + i]
                buffered += count
                accepted += count
            }
            if (accepted == length || System.nanoTime() >= deadline) return accepted
            Thread.sleep(1)
        }
    }

    @Synchronized
    override fun resume() {
        if (resumeFailure != 0) throw WasapiException(resumeFailure)
        advance()
        playing = true
        clock = System.nanoTime()
    }

    @Synchronized
    override fun pause() {
        advance()
        playing = false
    }

    @Synchronized
    override fun flush() {
        playing = false
        read = 0
        buffered = 0
        played = 0
        flushes++
    }

    @Synchronized
    override fun status(): OutputStatus {
        advance()
        return OutputStatus(played, (buffered / frameBytes).toLong(), 0, playing, invalidated, exclusive, 0, 0,
            System.nanoTime(), 480, ring.size / frameBytes)
    }

    override fun close() {
        closed = true
    }

    @Synchronized
    fun heardBytes(): ByteArray = heard.toByteArray()

    fun heardFrames(): Long = synchronized(this) { heard.size() / frameBytes.toLong() }

    fun heardFloats(): FloatArray {
        val buffer = ByteBuffer.wrap(heardBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buffer.remaining() / 4) { buffer.getFloat() }
    }

    fun heardInts(): IntArray {
        val buffer = ByteBuffer.wrap(heardBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(buffer.remaining() / 4) { buffer.getInt() }
    }

    private fun advance() {
        if (!playing) return
        val now = System.nanoTime()
        val frames = if (speed.isInfinite()) buffered / frameBytes
            else minOf(buffered / frameBytes.toLong(), ((now - clock) * sampleRate * speed / 1e9).toLong()).toInt()
        if (frames <= 0) return
        clock = if (speed.isInfinite() || frames == buffered / frameBytes) now else clock + (frames * 1e9 / sampleRate / speed).toLong()
        val count = frames * frameBytes
        for (i in 0 until count) heard.write(ring[(read + i) % ring.size].toInt())
        read = (read + count) % ring.size
        buffered -= count
        played += frames
    }
}
