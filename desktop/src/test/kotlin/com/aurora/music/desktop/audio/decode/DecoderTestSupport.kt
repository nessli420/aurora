package com.aurora.music.desktop.audio.decode

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO

internal object TestAssets {
    private const val RELATIVE = "app/src/androidTest/assets/network"

    val directory: File by lazy {
        System.getProperty("aurora.repo")?.let { File(it, RELATIVE) }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, RELATIVE) }
                .firstOrNull { it.isDirectory }
            ?: error("Could not find $RELATIVE")
    }

    fun file(name: String) = File(directory, name)

    fun reference(name: String): ShortArray {
        val buffer = ByteBuffer.wrap(file(name).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }
}

internal fun decodeAll(decoder: FfmpegDecoder, blockFrames: Int = 1000): DoubleArray {
    val block = DoubleArray(blockFrames * 2)
    val out = ByteArrayOutputStream()
    val bytes = ByteBuffer.allocate(block.size * 8).order(ByteOrder.LITTLE_ENDIAN)
    while (true) {
        val frames = decoder.read(block)
        if (frames < 0) break
        bytes.clear()
        for (i in 0 until frames * 2) bytes.putDouble(block[i])
        out.write(bytes.array(), 0, frames * 16)
    }
    val all = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer()
    return DoubleArray(all.remaining()).also { all.get(it) }
}

internal fun toPcm16(sample: Double): Int = Math.round(sample * 32768).toInt().coerceIn(-32768, 32767)

internal fun wavBytes(rate: Int, channels: Int, bits: Int, frames: Int, float: Boolean = false, sample: (Int, Int) -> Number): ByteArray {
    val width = bits / 8
    val data = frames * channels * width
    val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray()).putInt(16)
    buffer.putShort((if (float) 3 else 1).toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * width)
    buffer.putShort((channels * width).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(data)
    repeat(frames) { frame ->
        repeat(channels) { channel ->
            val value = sample(frame, channel)
            when {
                float -> buffer.putFloat(value.toFloat())
                bits == 16 -> buffer.putShort(value.toInt().toShort())
                bits == 24 -> value.toInt().let { buffer.put(it.toByte()).put((it shr 8).toByte()).put((it shr 16).toByte()) }
                else -> buffer.putInt(value.toInt())
            }
        }
    }
    return buffer.array()
}

internal fun png(): ByteArray = ByteArrayOutputStream().also {
    ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xff3366) }, "png", it)
}.toByteArray()

internal fun taggedMp3(mp3: ByteArray, cover: ByteArray): ByteArray {
    val audio = if (String(mp3, 0, 3) == "ID3") mp3.copyOfRange(10 + unsyncsafe(mp3, 6), mp3.size) else mp3
    fun text(value: String) = byteArrayOf(3) + value.toByteArray()
    val frames = listOf(
        "TIT2" to text("Gapless ü 音"),
        "TPE1" to text("Aurora Artist"),
        "TALB" to text("Aurora Album"),
        "TPE2" to text("Various Artists"),
        "TRCK" to text("3/12"),
        "TPOS" to text("2/2"),
        "TDRC" to text("2024"),
        "TCON" to text("Ambient"),
        "TXXX" to text("REPLAYGAIN_TRACK_GAIN") + byteArrayOf(0) + "-6.20 dB".toByteArray(),
        "TXXX" to text("REPLAYGAIN_ALBUM_GAIN") + byteArrayOf(0) + "+1,5 dB".toByteArray(),
        "TXXX" to text("REPLAYGAIN_TRACK_PEAK") + byteArrayOf(0) + "0.988831".toByteArray(),
        "APIC" to byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + cover,
    )
    val body = ByteArrayOutputStream()
    for ((id, data) in frames) {
        body.write(id.toByteArray())
        body.write(syncsafe(data.size))
        body.write(byteArrayOf(0, 0))
        body.write(data)
    }
    return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(body.size()) + body.toByteArray() + audio
}

internal fun taggedFlac(flac: ByteArray, comments: List<String>, cover: ByteArray): ByteArray {
    check(String(flac, 0, 4) == "fLaC")
    val blocks = mutableListOf<Pair<Int, ByteArray>>()
    var position = 4
    while (true) {
        val header = flac[position].toInt()
        val length = (flac[position + 1].toInt() and 0xff shl 16) or (flac[position + 2].toInt() and 0xff shl 8) or (flac[position + 3].toInt() and 0xff)
        blocks += (header and 0x7f) to flac.copyOfRange(position + 4, position + 4 + length)
        position += 4 + length
        if (header and 0x80 != 0) break
    }
    val vorbis = ByteBuffer.allocate(8 + "aurora".length + comments.sumOf { 4 + it.toByteArray().size }).order(ByteOrder.LITTLE_ENDIAN)
    vorbis.putInt("aurora".length).put("aurora".toByteArray()).putInt(comments.size)
    comments.forEach { vorbis.putInt(it.toByteArray().size).put(it.toByteArray()) }
    val picture = ByteBuffer.allocate(32 + "image/png".length + cover.size)
    picture.putInt(3).putInt("image/png".length).put("image/png".toByteArray()).putInt(0)
    picture.putInt(2).putInt(2).putInt(24).putInt(0).putInt(cover.size).put(cover)
    val kept = blocks.filter { it.first != 4 && it.first != 6 } + listOf(4 to vorbis.array(), 6 to picture.array())
    val out = ByteArrayOutputStream()
    out.write("fLaC".toByteArray())
    kept.forEachIndexed { index, (type, data) ->
        out.write(type or (if (index == kept.lastIndex) 0x80 else 0))
        out.write(byteArrayOf((data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(data)
    }
    out.write(flac, position, flac.size - position)
    return out.toByteArray()
}

private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())

private fun unsyncsafe(bytes: ByteArray, offset: Int) = (0 until 4).fold(0) { acc, i -> acc shl 7 or (bytes[offset + i].toInt() and 0x7f) }

internal class TestServer(private val handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            runCatching { handler(exchange) }
            exchange.close()
        }
        executor = this@TestServer.executor
        start()
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
