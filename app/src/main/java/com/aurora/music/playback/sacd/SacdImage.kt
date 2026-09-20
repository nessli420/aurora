package com.aurora.music.playback.sacd

import java.nio.ByteBuffer
import java.nio.charset.Charset

interface SacdInput {
    val length: Long
    fun read(position: Long, size: Int): ByteArray
}

data class SacdTrack(val number: Int, val title: String, val artist: String,
    val firstSector: Long, val sectorCount: Long, val firstFrame: Int, val frames: Int)

data class SacdDisc(val album: String, val artist: String, val sectorBytes: Int, val sectorPrefix: Int,
    val areaStart: Long, val areaEnd: Long, val frameFormat: Int, val tracks: List<SacdTrack>)

class SacdImage(private val input: SacdInput) {
    val disc: SacdDisc
    private var sectorBytes = 2048
    private var sectorPrefix = 0
    private var accessStep = 0
    private var access = emptyList<Pair<Long, Int>>()
    private val frameSectors = java.util.TreeMap<Int, Long>()

    init {
        require(input.length in 2048L * 512..1_099_511_627_776L) { "Invalid SACD image size." }
        var master: ByteArray? = null
        var masterSector = 0L
        for ((size, prefix) in listOf(2048 to 0, 2064 to 12, 2054 to 6)) {
            if (master != null) break
            for (lsn in listOf(510L, 520L, 530L)) {
                val position = lsn * size + prefix
                if (position > input.length - 2048) continue
                val candidate = read(position, 2048)
                if (candidate.id(0) == "SACDMTOC") {
                    master = candidate; masterSector = lsn; sectorBytes = size; sectorPrefix = prefix
                    break
                }
            }
        }
        val toc = requireNotNull(master) { "No SACD table of contents found." }
        require(toc.u8(8) == 1 && toc.u8(9) <= 20) { "Unsupported SACD version." }
        val stereo = listOf(toc.u32(64), toc.u32(68)).filter { it > 0 }.distinct()
        require(stereo.isNotEmpty()) { "This SACD has no stereo area." }
        val area = stereo.firstNotNullOfOrNull { lsn ->
            if (lsn > sectorCount - 1) null else sector(lsn).takeIf { it.id(0) == "TWOCHTOC" }?.let { lsn to it }
        } ?: throw IllegalArgumentException("The SACD stereo table is missing.")
        val header = area.second
        val areaLength = header.u16(10)
        require(areaLength in 3..4096 && area.first <= sectorCount - areaLength) { "Invalid SACD area size." }
        require(header.u8(8) == 1 && header.u8(9) <= 20 && header.u8(20) == 4 && header.u8(32) == 2) { "Unsupported SACD audio format." }
        val format = header.u8(21) and 15
        require(format in listOf(0, 2, 3)) { "Unsupported SACD frame format." }
        val first = header.u32(72); val last = header.u32(76)
        require(first >= area.first + areaLength && first <= last && last < sectorCount) { "Invalid SACD audio area." }
        val count = header.u8(69)
        require(count in 1..255) { "Invalid SACD track count." }
        val all = ByteArray(areaLength * 2048)
        for (i in 0 until areaLength) sector(area.first + i).copyInto(all, i * 2048)
        fun table(id: String): Int = (0 until areaLength).map { it * 2048 }.firstOrNull { all.id(it) == id }
            ?: throw IllegalArgumentException("Missing SACD track table.")
        val offsets = table("SACDTRL1"); val times = table("SACDTRL2")
        val masterText = sector(masterSector + 1)
        val masterCharset = charset(toc.u8(138))
        val album = if (masterText.id(0) == "SACDText") text(masterText, masterText.u16(16), masterCharset) else ""
        val artist = if (masterText.id(0) == "SACDText") text(masterText, masterText.u16(18), masterCharset) else ""
        val textSector = header.u16(128)
        val names = if (textSector == 0) emptyList() else {
            require(textSector < areaLength) { "Invalid SACD text offset." }
            trackText(all.copyOfRange(textSector * 2048, all.size), count, charset(header.u8(90)))
        }
        val tracks = List(count) { i ->
            val start = all.u32(offsets + 8 + i * 4)
            val length = all.u32(offsets + 1028 + i * 4)
            val frame = all.time(times + 8 + i * 4)
            val frames = all.time(times + 1028 + i * 4)
            require(start in first..last && length > 0 && length <= last - start + 1 && frames > 0) { "Invalid SACD track bounds." }
            SacdTrack(i + 1, names.getOrNull(i)?.first.orEmpty().ifBlank { "Track ${i + 1}" },
                names.getOrNull(i)?.second.orEmpty().ifBlank { artist }, start, length, frame, frames)
        }
        require(tracks.zipWithNext().all { (a, b) -> a.firstSector <= b.firstSector && a.firstFrame + a.frames <= b.firstFrame }) { "Overlapping SACD tracks." }
        if (format != 0) {
            val payload = if (format == 2) 2016 else 1764
            require(tracks.last().let { (it.firstFrame + it.frames).toLong() * 9408 } <= (last - first + 1) * payload) { "SACD duration exceeds its audio area." }
        }
        disc = SacdDisc(album, artist, sectorBytes, sectorPrefix, first, last, format, tracks)
        val accessSector = header.u16(132)
        if (format == 0 && accessSector > 0) {
            require(accessSector < areaLength) { "Invalid SACD access table." }
            val at = accessSector * 2048
            require(all.id(at) == "SACD_ACC") { "Missing SACD access table." }
            val entries = all.u16(at + 8)
            accessStep = all.u8(at + 10)
            require(entries in 1..6550 && accessStep > 0 && at + 16 + entries * 5 <= all.size) { "Invalid SACD access entries." }
            access = List(entries) { i ->
                val p = at + 16 + i * 5
                val lsn = (all.u8(p + 2).toLong() shl 16) or (all.u8(p + 3).toLong() shl 8) or all.u8(p + 4).toLong()
                require(lsn in first..last) { "SACD access entry is outside its area." }
                lsn to (all.u16(p) and 32767)
            }
            require(access.zipWithNext().all { (a, b) -> a.first <= b.first }) { "Unordered SACD access entries." }
        }
    }

    fun compressedFrame(frame: Int): ByteArray {
        require(disc.frameFormat == 0) { "This SACD contains uncompressed DSD." }
        val track = requireNotNull(disc.tracks.firstOrNull { frame in it.firstFrame until it.firstFrame + it.frames }) { "SACD frame is outside the track list." }
        val entry = if (accessStep > 0) access.getOrNull(frame / accessStep) else null
        val known = frameSectors.floorEntry(frame)
        var lsn = maxOf(disc.areaStart, entry?.let { it.first - it.second } ?: track.firstSector,
            known?.value ?: disc.areaStart)
        val end = minOf(disc.areaEnd, track.firstSector + track.sectorCount - 1)
        val result = java.io.ByteArrayOutputStream()
        var foundAt = -1L
        var span = 0
        while (lsn <= end) {
            check(!Thread.currentThread().isInterrupted) { "SACD read cancelled." }
            val bytes = sector(lsn)
            val packets = SacdPackets.parse(bytes)
            for (packet in packets) {
                if (packet.frame != null) {
                    if (foundAt >= 0) {
                        require(packet.frame == frame + 1) { "SACD frame sequence is broken." }
                        return result.toByteArray().also { require(it.size in 2..16384) }
                    }
                    if (frameSectors.size < 1_000_000) frameSectors[packet.frame] = lsn
                    require(packet.frame <= frame) { "SACD frame is missing from its index." }
                    if (packet.frame == frame) { foundAt = lsn; span = packet.sectors }
                }
                if (foundAt >= 0 && packet.audio) {
                    require(result.size() <= 16384 - packet.length) { "Oversized SACD DST frame." }
                    result.write(bytes, packet.offset, packet.length)
                }
            }
            if (foundAt >= 0 && lsn - foundAt + 1 == span.toLong())
                return result.toByteArray().also { require(it.size in 2..16384) }
            lsn++
        }
        throw IllegalArgumentException("Truncated SACD DST frame.")
    }

    fun rawFrame(frame: Int): ByteArray {
        require(disc.frameFormat != 0) { "This SACD uses DST frames." }
        val finalFrame = disc.tracks.maxOf { it.firstFrame + it.frames }
        require(frame in 0 until finalFrame)
        val payload = if (disc.frameFormat == 2) 2016 else 1764
        val prefix = 2048 - payload
        var offset = frame.toLong() * 9408
        val result = ByteArray(9408)
        var written = 0
        while (written < result.size) {
            val lsn = disc.areaStart + offset / payload
            require(lsn <= disc.areaEnd) { "Truncated SACD frame." }
            val inside = (offset % payload).toInt()
            val size = minOf(payload - inside, result.size - written)
            val bytes = sector(lsn)
            val packets = SacdPackets.parse(bytes, coded = false).filter { it.audio }
            require(packets.isNotEmpty() && packets.first().offset == prefix && packets.sumOf { it.length } == payload &&
                packets.zipWithNext().all { (a, b) -> a.offset + a.length == b.offset }) { "Invalid SACD DSD layout." }
            bytes.copyInto(result, written, prefix + inside, prefix + inside + size)
            written += size; offset += size
        }
        return result
    }

    fun sector(lsn: Long): ByteArray {
        require(lsn in 0 until sectorCount) { "SACD sector is outside the image." }
        return read(lsn * sectorBytes + sectorPrefix, 2048)
    }

    private val sectorCount get() = input.length / sectorBytes
    private fun read(position: Long, size: Int): ByteArray {
        require(size in 0..8_388_608 && position >= 0 && position <= input.length - size) { "SACD read is outside the image." }
        return input.read(position, size).also { require(it.size == size) { "Truncated SACD image." } }
    }

    private fun trackText(data: ByteArray, count: Int, charset: Charset): List<Pair<String, String>> {
        require(data.id(0) == "SACDTTxt" && 8 + count * 2 <= data.size) { "Invalid SACD text table." }
        return List(count) { track ->
            val offset = data.u16(8 + track * 2)
            if (offset == 0) "" to "" else {
                require(offset >= 8 + count * 2 && offset <= data.size - 4)
                val items = data.u8(offset)
                require(items <= 16)
                var at = offset + 4
                var title = ""; var artist = ""
                repeat(items) {
                    require(at <= data.size - 3)
                    val type = data.u8(at)
                    at += 2
                    val end = textEnd(data, at)
                    val value = String(data, at, end - at, charset).trim()
                    if (type == 1) title = value else if (type == 2) artist = value
                    at = end + 1
                    while (at < data.size && data[at] == 0.toByte()) at++
                }
                title to artist
            }
        }
    }

    private fun charset(code: Int) = Charset.forName(when (code and 7) {
        0, 1 -> "US-ASCII"; 2 -> "ISO-8859-1"; 3 -> "Shift_JIS"; 4 -> "EUC-KR"
        5 -> "GB2312"; 6 -> "Big5"; else -> "ISO-8859-1"
    })
    private fun text(bytes: ByteArray, offset: Int, charset: Charset): String {
        if (offset == 0) return ""
        require(offset >= 64) { "Invalid SACD text position." }
        return String(bytes, offset, textEnd(bytes, offset) - offset, charset).trim()
    }
    private fun textEnd(bytes: ByteArray, offset: Int): Int {
        require(offset in bytes.indices)
        return (offset until minOf(bytes.size, offset + 4096)).firstOrNull { bytes[it] == 0.toByte() }
            ?: throw IllegalArgumentException("Unterminated SACD text.")
    }
    private fun ByteArray.id(at: Int) = String(this, at, 8, Charsets.US_ASCII)
    private fun ByteArray.u8(at: Int) = this[at].toInt() and 255
    private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.u32(at: Int) = ByteBuffer.wrap(this, at, 4).int.toLong() and 0xffffffffL
    private fun ByteArray.time(at: Int): Int {
        val minutes = u8(at); val seconds = u8(at + 1); val frames = u8(at + 2)
        require(seconds < 60 && frames < 75) { "Invalid SACD time." }
        return (minutes * 60 + seconds) * 75 + frames
    }
}
