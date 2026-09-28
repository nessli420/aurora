package com.aurora.music.playback.chain

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.OutputDither
import com.aurora.music.playback.engine.OutputDitherMode
import com.aurora.music.playback.engine.PcmBoundary
import com.aurora.music.playback.engine.PcmEncoding
import java.nio.ByteBuffer

class PcmOutputEncoder {
    private val dither = OutputDither()
    private var view: ByteBuffer? = null
    private var packed = ByteBuffer.allocate(0)

    fun reset() = dither.reset()

    fun encode(block: AudioBlock, encoding: PcmEncoding, containerBytes: Int, mode: OutputDitherMode, out: ByteArray, offset: Int = 0): Int {
        require(containerBytes >= encoding.bytesPerSample) { "The container is narrower than the encoding" }
        val samples = block.sampleCount
        val bytes = samples * containerBytes
        require(offset >= 0 && offset + bytes <= out.size)
        val selected = dither.select(if (encoding.integerBits > 0) mode else OutputDitherMode.OFF)
        if (containerBytes == encoding.bytesPerSample) {
            val target = view?.takeIf { it.array() === out } ?: ByteBuffer.wrap(out).also { view = it }
            target.limit(offset + bytes).position(offset)
            PcmBoundary.encode(block, encoding, target, selected)
            return bytes
        }
        val width = encoding.bytesPerSample
        if (packed.capacity() < samples * width) packed = ByteBuffer.allocate(samples * width)
        packed.clear()
        PcmBoundary.encode(block, encoding, packed, selected)
        val source = packed.array()
        val pad = containerBytes - width
        var i = 0
        while (i < samples) {
            val base = offset + i * containerBytes
            for (b in 0 until pad) out[base + b] = 0
            System.arraycopy(source, i * width, out, base + pad, width)
            i++
        }
        return bytes
    }
}
