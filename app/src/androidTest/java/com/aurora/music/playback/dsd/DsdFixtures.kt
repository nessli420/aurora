package com.aurora.music.playback.dsd

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

internal object DsdFixtures {
    fun tone(rate: Int, seconds: Int): Array<ByteArray> {
        val period = rate / 11025 * 10
        return Array(2) { channel ->
            var error = 0.0
            val pattern = ByteArray(period / 8)
            repeat(period) { bit ->
                val target = sin(2 * PI * bit / period) * if (channel == 0) 0.35 else 0.2
                val value = if (target + error >= 0) 1.0 else -1.0
                error += target - value
                if (value > 0) pattern[bit / 8] = (pattern[bit / 8].toInt() or (128 ushr (bit % 8))).toByte()
            }
            ByteArray(rate / 8 * seconds) { pattern[it % pattern.size] }
        }
    }

    fun dsf(channels: Array<ByteArray>, rate: Int = 2_822_400,
        sampleCount: Long = channels[0].size * 8L, lsb: Boolean = true, title: String = "DSF fixture"): ByteArray {
        val data = ByteArrayOutputStream()
        val blocks = (channels[0].size + 4095) / 4096
        repeat(blocks) { block -> channels.forEach { channel -> repeat(4096) { offset ->
            val index = block * 4096 + offset
            val value = if (index < channel.size) channel[index].toInt() and 255 else 0x5a
            data.write(if (lsb) Integer.reverse(value) ushr 24 else value)
        } } }
        val id3 = id3(title)
        val metadataOffset = 92L + data.size()
        return ByteArrayOutputStream().apply {
            write("DSD ".toByteArray()); integer(28, 8, true); integer(metadataOffset + id3.size, 8, true); integer(metadataOffset, 8, true)
            write("fmt ".toByteArray()); integer(52, 8, true)
            listOf(1, 0, channels.size, channels.size, rate, if (lsb) 1 else 8).forEach { integer(it.toLong(), 4, true) }
            integer(sampleCount, 8, true); integer(4096, 4, true); integer(0, 4, true)
            write("data".toByteArray()); integer(data.size() + 12L, 8, true); write(data.toByteArray()); write(id3)
        }.toByteArray()
    }

    fun dff(channels: Array<ByteArray>, rate: Int = 2_822_400, compression: String = "DSD ", title: String = "DFF fixture"): ByteArray {
        val properties = ByteArrayOutputStream().apply {
            write("SND ".toByteArray())
            write(chunk("FS  ", payload { integer(rate.toLong(), 4) }))
            write(chunk("CHNL", payload { integer(channels.size.toLong(), 2); write(if (channels.size == 2) "SLFTSRGT".toByteArray() else "C   ".toByteArray()) }))
            write(chunk("CMPR", compression.toByteArray() + byteArrayOf(0)))
        }.toByteArray()
        val data = ByteArray(channels[0].size * channels.size) { index -> channels[index % channels.size][index / channels.size] }
        val text = chunk("DITI", payload { integer(title.length.toLong(), 4); write(title.toByteArray()) }) +
            chunk("DIAR", payload { integer(12, 4); write("Aurora tests".toByteArray()) })
        val body = "DSD ".toByteArray() + chunk("FVER", byteArrayOf(1, 5, 0, 0)) +
            chunk("PROP", properties) + chunk("JUNK", byteArrayOf(3)) + chunk("DSD ", data) + chunk("DIIN", text)
        return "FRM8".toByteArray() + payload { integer(body.size.toLong(), 8) } + body
    }

    private fun id3(title: String): ByteArray {
        val text = byteArrayOf(3) + title.toByteArray()
        val frame = "TIT2".toByteArray() + syncSafe(text.size) + byteArrayOf(0, 0) + text
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncSafe(frame.size) + frame
    }
    private fun syncSafe(value: Int) = ByteArray(4) { (value ushr (7 * (3 - it)) and 127).toByte() }
    private fun chunk(id: String, body: ByteArray) = id.toByteArray() + payload { integer(body.size.toLong(), 8) } + body +
        if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun payload(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    private fun ByteArrayOutputStream.integer(value: Long, count: Int, little: Boolean = false) {
        repeat(count) { write((value ushr (8 * if (little) it else count - 1 - it) and 255).toInt()) }
    }
}
