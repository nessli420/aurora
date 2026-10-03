package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.natives.OutputStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToLong

class JavaSoundOutput(
    private val line: SourceDataLine,
    override val deviceId: String?,
    override val sampleRate: Int,
    override val id: Long = ids.incrementAndGet(),
) : AudioOutput {
    override val exclusive: Boolean get() = false
    override val encoding: OutputEncoding get() = OutputEncoding.F32

    private val lineFormat = line.format
    private val channels = lineFormat.channels
    private val inFrameBytes = channels * OutputEncoding.F32.bytesPerSample
    private val outFrameBytes = lineFormat.frameSize
    private val outSampleBytes = outFrameBytes / channels
    private val passthrough = lineFormat.encoding == AudioFormat.Encoding.PCM_FLOAT
    private var scratch = ByteArray(0)
    private var written = 0L
    private var base = 0L
    private var playing = false

    @Volatile
    private var closed = false

    override fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        val frames = length / inFrameBytes
        var accepted = 0
        while (true) {
            synchronized(this) {
                check(!closed) { "Audio output is closed." }
                val count = minOf(frames - accepted, line.available() / outFrameBytes)
                if (count > 0) {
                    convert(bytes, offset + accepted * inFrameBytes, count)
                    line.write(scratch, 0, count * outFrameBytes)
                    written += count
                    accepted += count
                }
            }
            if (accepted == frames || (timeoutMs >= 0 && System.nanoTime() >= deadline)) return accepted * inFrameBytes
            Thread.sleep(1)
        }
    }

    @Synchronized
    override fun resume() {
        check(!closed) { "Audio output is closed." }
        line.start()
        playing = true
    }

    @Synchronized
    override fun pause() {
        if (closed) return
        line.stop()
        playing = false
    }

    @Synchronized
    override fun flush() {
        if (closed) return
        line.stop()
        line.flush()
        playing = false
        written = 0
        base = line.longFramePosition
    }

    @Synchronized
    override fun status(): OutputStatus {
        check(!closed) { "Audio output is closed." }
        val played = (line.longFramePosition - base).coerceIn(0, written)
        val bufferFrames = line.bufferSize / outFrameBytes
        return OutputStatus(
            framesPlayed = played,
            framesBuffered = written - played,
            underruns = 0,
            playing = playing,
            deviceInvalidated = false,
            exclusive = false,
            lastError = 0,
            latencyMicros = bufferFrames * 1_000_000L / sampleRate,
            positionNanos = System.nanoTime(),
            deviceBufferFrames = bufferFrames,
            ringFrames = bufferFrames,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { line.stop() }
        runCatching { line.flush() }
        line.close()
    }

    private fun convert(bytes: ByteArray, offset: Int, frames: Int) {
        val size = frames * outFrameBytes
        if (scratch.size < size) scratch = ByteArray(size)
        if (passthrough) {
            System.arraycopy(bytes, offset, scratch, 0, size)
            return
        }
        val source = ByteBuffer.wrap(bytes, offset, frames * inFrameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val bits = outSampleBytes * 8
        val peak = (1L shl (bits - 1)) - 1
        var at = 0
        repeat(frames * channels) {
            val sample = (source.getFloat().coerceIn(-1f, 1f).toDouble() * peak).roundToLong()
            for (byte in 0 until outSampleBytes) scratch[at++] = (sample shr (byte * 8)).toByte()
        }
    }

    companion object {
        private val ids = AtomicLong()
    }
}

object JavaSoundBackend : OutputBackend {
    private const val CHANNELS = 2
    private val SAMPLE_BITS = listOf(32, 24, 16)

    override fun devices(): List<AudioDevice> {
        val mixers = playbackMixers()
        val default = mixers.firstOrNull { it.name.startsWith("default") } ?: mixers.firstOrNull()
        return mixers.map { AudioDevice(it.name, it.description.ifBlank { it.name }, DeviceKind.UNKNOWN, it == default) }
    }

    override fun mixRate(deviceId: String?): Int? = null

    override fun supportsExclusive(deviceId: String?, sampleRate: Int, encoding: OutputEncoding) = false

    override fun open(deviceId: String?, exclusive: Boolean, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int): AudioOutput {
        require(!exclusive) { "Exclusive mode is not available on this system." }
        require(encoding == OutputEncoding.F32) { "Shared output takes 32-bit float samples." }
        val mixer = deviceId?.let { id -> playbackMixers().firstOrNull { it.name == id } }?.let(AudioSystem::getMixer)
        val format = formats(sampleRate).firstOrNull { supported(mixer, it) }
            ?: throw IllegalStateException("No supported audio format at $sampleRate Hz.")
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
        line.open(format, (sampleRate.toLong() * bufferMs / 1000).toInt().coerceAtLeast(1) * format.frameSize)
        return JavaSoundOutput(line, mixer?.mixerInfo?.name, sampleRate)
    }

    override fun listen(listener: (DeviceEvent) -> Unit): AutoCloseable = AutoCloseable {}

    override fun keepAwake(enabled: Boolean) = Unit

    private fun formats(sampleRate: Int): List<AudioFormat> =
        listOf(AudioFormat(AudioFormat.Encoding.PCM_FLOAT, sampleRate.toFloat(), 32, CHANNELS, CHANNELS * 4, sampleRate.toFloat(), false)) +
            SAMPLE_BITS.map { AudioFormat(sampleRate.toFloat(), it, CHANNELS, true, false) }

    private fun supported(mixer: Mixer?, format: AudioFormat): Boolean {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        return runCatching { mixer?.isLineSupported(info) ?: AudioSystem.isLineSupported(info) }.getOrDefault(false)
    }

    private fun playbackMixers(): List<Mixer.Info> = runCatching {
        AudioSystem.getMixerInfo().filter { info ->
            runCatching { AudioSystem.getMixer(info).sourceLineInfo.any { SourceDataLine::class.java.isAssignableFrom(it.lineClass) } }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())
}
