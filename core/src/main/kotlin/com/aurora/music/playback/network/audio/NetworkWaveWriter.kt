package com.aurora.music.playback.network.audio

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.PcmBoundary
import com.aurora.music.playback.engine.PcmEncoding
import com.aurora.music.playback.engine.OutputDither
import com.aurora.music.playback.engine.OutputDitherMode
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetworkAudioSizeException : IllegalArgumentException("Processed audio exceeds the 512 MB limit.")

class NetworkWaveWriter(
    file: File,
    val sampleRate: Int = SAMPLE_RATE,
    private val maxBytes: Long = MAX_BYTES,
    dither: Boolean = false,
    noiseShaping: Boolean = false,
) : Closeable {
    private val output: RandomAccessFile
    private val bytes = ByteBuffer.allocate(8192 * FRAME_BYTES)
    private val noise = OutputDither().select(when {
        !dither -> OutputDitherMode.OFF
        noiseShaping -> OutputDitherMode.NOISE_SHAPED
        else -> OutputDitherMode.TPDF
    })
    private var finished = false
    var framesWritten: Long = 0; private set

    init {
        require(sampleRate in 8_000..192_000 && maxBytes in HEADER_BYTES.toLong()..MAX_BYTES)
        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        output.write(header(0, sampleRate))
    }

    fun write(block: AudioBlock, gain: Double = 1.0) {
        check(!finished)
        require(block.format.sampleRate == sampleRate && block.format.channelCount == 2)
        require(block.frameCount <= 8192 && gain.isFinite() && gain in 0.0..1.0)
        if (HEADER_BYTES + (framesWritten + block.frameCount) * FRAME_BYTES > maxBytes) throw NetworkAudioSizeException()
        if (gain != 1.0) for (index in 0 until block.sampleCount) block.samples[index] *= gain
        bytes.clear()
        PcmBoundary.encode(block, PcmEncoding.SIGNED_16_LE, bytes, noise)
        output.write(bytes.array(), 0, bytes.position())
        framesWritten += block.frameCount
    }

    fun finish() {
        check(!finished)
        require(framesWritten > 0) { "The track contains no audio." }
        output.seek(0)
        output.write(header(framesWritten * FRAME_BYTES, sampleRate))
        output.fd.sync()
        finished = true
    }

    override fun close() = output.close()

    companion object {
        const val MIME_TYPE = "audio/wav"
        const val SAMPLE_RATE = 48_000
        const val FRAME_BYTES = 4
        const val HEADER_BYTES = 44
        const val MAX_BYTES = 512L * 1024 * 1024

        fun header(dataBytes: Long, sampleRate: Int = SAMPLE_RATE): ByteArray {
            require(dataBytes in 0..MAX_BYTES - HEADER_BYTES && dataBytes % FRAME_BYTES == 0L)
            require(sampleRate in 8_000..192_000)
            return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((dataBytes + 36).toInt())
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(2); putInt(sampleRate); putInt(sampleRate * FRAME_BYTES)
                putShort(FRAME_BYTES.toShort()); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
            }.array()
        }
    }
}
