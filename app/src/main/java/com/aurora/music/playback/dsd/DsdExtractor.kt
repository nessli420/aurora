package com.aurora.music.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class DsdExtractor(private val decodeAudio: Boolean = true) : Extractor {
    private enum class State { HEADER, DFF_CHUNKS, DSF_METADATA, AUDIO }
    private var state = State.HEADER
    private lateinit var output: ExtractorOutput
    private var track: TrackOutput? = null
    private var format: DsdFormat? = null
    private var decoder: DsdPcmDecoder? = null
    private var fileEnd = 0L
    private var scanPosition = 0L
    private var metadataOffset = 0L
    private var chunks = 0
    private var versionSeen = false
    private var properties: Pair<Int, Int>? = null
    private var dffDataOffset = -1L
    private var dffDataBytes = 0L
    private val metadata = ArrayList<Metadata.Entry>()
    private val cache = ByteArray(8192)
    private var cacheStart = -1L
    private var cacheFrames = 0
    private var sourceByte = 0L
    private val channelBytes = IntArray(2)
    private val pcm = FloatArray(2048)
    private val pcmBytes = ByteArray(8192)
    private val pcmBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
    private val packet = ParsableByteArray(pcmBytes)

    override fun sniff(input: ExtractorInput): Boolean {
        val bytes = ByteArray(4)
        return try {
            input.peekFully(bytes, 0, 4, true) && (bytes.contentEquals("DSD ".toByteArray()) || bytes.contentEquals("FRM8".toByteArray()))
        } catch (_: EOFException) { false } finally { input.resetPeekPosition() }
    }

    override fun init(output: ExtractorOutput) { this.output = output }
    override fun release() = Unit

    override fun seek(position: Long, timeUs: Long) {
        val f = format ?: return
        val frame = f.seekFrame(timeUs)
        sourceByte = f.prerollByte(frame)
        decoder?.reset(sourceByte, frame)
        cacheStart = -1; cacheFrames = 0
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        when (state) {
            State.HEADER -> readHeader(input)
            State.DFF_CHUNKS -> readDffChunk(input, seekPosition)
            State.DSF_METADATA -> readDsfMetadata(input, seekPosition)
            State.AUDIO -> readAudio(input, seekPosition)
        }
    } catch (failure: IllegalArgumentException) {
        throw ParserException.createForMalformedContainer(failure.message ?: "Invalid DSD file.", failure)
    } catch (failure: EOFException) {
        throw ParserException.createForMalformedContainer("Truncated DSD file.", failure)
    }

    private fun readHeader(input: ExtractorInput): Int {
        require(input.position == 0L) { "Missing DSD header." }
        val first = bytes(input, 12)
        val id = String(first, 0, 4, Charsets.US_ASCII)
        if (id == "DSD ") {
            require(long(first, 4, true) == 28L) { "Invalid DSF header." }
            val header = bytes(input, 16)
            fileEnd = long(header, 0, true)
            metadataOffset = long(header, 8, true)
            checkLength(input)
            val fmt = bytes(input, 12)
            require(String(fmt, 0, 4, Charsets.US_ASCII) == "fmt ") { "Missing DSF format." }
            val size = long(fmt, 4, true)
            require(size in 52..4096) { "Invalid DSF format size." }
            val body = bytes(input, size.toInt() - 12)
            val data = bytes(input, 12)
            require(String(data, 0, 4, Charsets.US_ASCII) == "data") { "Missing DSF data." }
            val dataSize = long(data, 4, true)
            require(dataSize >= 12)
            val end = boundedEnd(input.position, dataSize - 12, fileEnd)
            require(if (metadataOffset == 0L) end == fileEnd else metadataOffset == end && metadataOffset < fileEnd) { "Invalid DSF metadata offset." }
            format = DsdHeaders.dsf(body, input.position, dataSize - 12)
            if (metadataOffset > 0) state = State.DSF_METADATA else startAudio()
        } else {
            require(id == "FRM8") { "Unsupported DSD container." }
            val size = long(first, 4, false)
            require(size >= 4 && size <= Long.MAX_VALUE - 12)
            fileEnd = size + 12
            checkLength(input)
            require(String(bytes(input, 4), Charsets.US_ASCII) == "DSD ") { "Unsupported DFF form." }
            scanPosition = input.position
            state = State.DFF_CHUNKS
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readDffChunk(input: ExtractorInput, seek: PositionHolder): Int {
        if (scanPosition == fileEnd) {
            val p = requireNotNull(properties) { "Missing DFF properties." }
            require(versionSeen && dffDataOffset >= 0 && dffDataBytes > 0 && dffDataBytes % p.second == 0L) { "Missing or misaligned DFF data." }
            require(dffDataBytes / p.second <= Long.MAX_VALUE / 8)
            format = DsdFormat(DsdContainer.DFF, p.first, p.second, dffDataBytes / p.second * 8, dffDataOffset, dffDataBytes)
            startAudio()
            return Extractor.RESULT_CONTINUE
        }
        if (input.position != scanPosition) return reposition(seek, scanPosition)
        require(++chunks <= 4096 && fileEnd - scanPosition >= 12) { "Invalid DFF chunk count or size." }
        val header = bytes(input, 12)
        val id = String(header, 0, 4, Charsets.US_ASCII)
        val size = long(header, 4, false)
        val end = boundedEnd(input.position, size, fileEnd)
        scanPosition = boundedEnd(end, size and 1, fileEnd)
        require(versionSeen || id == "FVER") { "DFF must begin with its version." }
        when (id) {
            "FVER" -> {
                require(!versionSeen && size == 4L) { "Invalid DFF version chunk." }
                val version = ByteBuffer.wrap(bytes(input, 4)).int
                require(version ushr 24 == 1) { "Unsupported DFF version." }
                versionSeen = true
            }
            "PROP" -> {
                require(properties == null && dffDataOffset < 0 && size in 4..65_536) { "Invalid DFF properties." }
                properties = DsdHeaders.dffProperties(bytes(input, size.toInt()))
            }
            "DSD " -> {
                require(properties != null && dffDataOffset < 0) { "Invalid or duplicate DFF sound data." }
                dffDataOffset = input.position; dffDataBytes = size
            }
            "DST " -> throw ParserException.createForUnsupportedContainerFeature("DST-compressed DFF is not supported.")
            "DIIN" -> if (size <= MAX_METADATA_BYTES) readDffText(bytes(input, size.toInt()))
            "ID3 " -> if (size <= MAX_METADATA_BYTES) addId3(bytes(input, size.toInt()))
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readDsfMetadata(input: ExtractorInput, seek: PositionHolder): Int {
        if (input.position != metadataOffset) return reposition(seek, metadataOffset)
        if (fileEnd - metadataOffset >= 10) {
            val header = bytes(input, 10)
            if (String(header, 0, 3, Charsets.US_ASCII) == "ID3" && (6..9).all { header[it].toInt() and 128 == 0 }) {
                var length = 0
                for (i in 6..9) length = (length shl 7) or (header[i].toInt() and 127)
                if (length <= MAX_METADATA_BYTES - 10 && length <= fileEnd - input.position) {
                    val tag = ByteArray(length + 10)
                    header.copyInto(tag); input.readFully(tag, 10, length)
                    addId3(tag)
                }
            }
        }
        startAudio()
        return Extractor.RESULT_CONTINUE
    }

    private fun addId3(bytes: ByteArray) {
        val tag = Id3Decoder().decode(bytes, bytes.size) ?: return
        for (i in 0 until tag.length()) if (metadata.size < 256) metadata.add(tag[i])
    }

    private fun readDffText(bytes: ByteArray) {
        val b = ByteBuffer.wrap(bytes)
        var count = 0
        while (b.hasRemaining()) {
            require(++count <= 1024 && b.remaining() >= 12) { "Invalid DFF metadata." }
            val id = DsdHeaders.id(b)
            val size = b.long
            require(size >= 0 && size <= b.remaining() && size + (size and 1) <= b.remaining()) { "Truncated DFF metadata." }
            val end = b.position() + size.toInt()
            if ((id == "DIAR" || id == "DITI") && size >= 4) {
                val length = b.int
                require(length >= 0 && length <= size - 4) { "Invalid DFF text size." }
                if (length <= 4096 && metadata.size < 256) {
                    val text = ByteArray(length).also(b::get).toString(Charsets.US_ASCII).trimEnd('\u0000')
                    metadata.add(TextInformationFrame(if (id == "DIAR") "TPE1" else "TIT2", null, listOf(text)))
                }
            }
            b.position(end + (size and 1).toInt())
        }
    }

    private fun startAudio() {
        val f = checkNotNull(format)
        decoder = if (decodeAudio) DsdPcmDecoder(f) else null
        sourceByte = 0; cacheStart = -1; cacheFrames = 0
        metadata.add(DsdSourceInfo(f.container.name, f.bitRate, f.channels, f.sampleCount))
        track = output.track(0, C.TRACK_TYPE_AUDIO).also { track ->
            track.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setContainerMimeType(if (f.container == DsdContainer.DSF) "audio/x-dsf" else "audio/x-dff")
                .setCodecs("dsd${f.bitRate / 44_100}").setSampleRate(f.pcmRate).setChannelCount(f.channels)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT).setAverageBitrate(f.bitRate * f.channels)
                .setMaxInputSize(pcmBytes.size).setMetadata(Metadata(metadata)).build())
        }
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = f.durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val frame = f.seekFrame(timeUs)
                return SeekMap.SeekPoints(SeekPoint(frame * 1_000_000L / f.pcmRate, f.position(f.prerollByte(frame))))
            }
        })
        output.endTracks()
        state = State.AUDIO
    }

    private fun readAudio(input: ExtractorInput, seek: PositionHolder): Int {
        if (!decodeAudio) return Extractor.RESULT_END_OF_INPUT
        val f = checkNotNull(format)
        val d = checkNotNull(decoder)
        if (d.isEnded) return Extractor.RESULT_END_OF_INPUT
        val firstFrame = d.outputFrame
        var produced = 0
        var work = 0
        while (produced < 1024 && !d.isEnded && ++work <= 16_384) {
            var validBits = 0
            if (sourceByte < f.bytesPerChannel) {
                if (cacheStart < 0 || sourceByte >= cacheStart + cacheFrames) {
                    val position = f.position(sourceByte)
                    if (input.position != position) {
                        if (produced > 0) break
                        return reposition(seek, position)
                    }
                    cacheStart = sourceByte
                    cacheFrames = if (f.container == DsdContainer.DSF) f.blockBytes
                        else minOf((cache.size / f.channels).toLong(), f.bytesPerChannel - sourceByte).toInt()
                    input.readFully(cache, 0, cacheFrames * f.channels)
                }
                val offset = (sourceByte - cacheStart).toInt()
                for (channel in 0 until f.channels) {
                    val at = if (f.container == DsdContainer.DSF) channel * f.blockBytes + offset else offset * f.channels + channel
                    val value = cache[at].toInt() and 255
                    channelBytes[channel] = if (f.leastSignificantBitFirst) Integer.reverse(value) ushr 24 else value
                }
                validBits = minOf(8L, f.sampleCount - sourceByte * 8).toInt()
            }
            if (d.push(channelBytes, validBits, pcm, produced * f.channels)) produced++
            sourceByte++
        }
        if (produced > 0) {
            pcmBuffer.clear()
            repeat(produced * f.channels) { pcmBuffer.putFloat(pcm[it]) }
            val size = produced * f.channels * 4
            packet.reset(pcmBytes, size)
            checkNotNull(track).sampleData(packet, size)
            checkNotNull(track).sampleMetadata(firstFrame * 1_000_000L / f.pcmRate, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun checkLength(input: ExtractorInput) {
        require(fileEnd >= 16 && (input.length == C.LENGTH_UNSET.toLong() || input.length == fileEnd)) { "DSD container length does not match the file." }
    }
    private fun bytes(input: ExtractorInput, count: Int): ByteArray = ByteArray(count).also { input.readFully(it, 0, count) }
    private fun long(bytes: ByteArray, offset: Int, little: Boolean): Long = ByteBuffer.wrap(bytes).order(
        if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).getLong(offset)
    private fun boundedEnd(position: Long, size: Long, limit: Long): Long {
        require(position in 0..limit && size >= 0 && size <= limit - position) { "DSD chunk exceeds the file." }
        return position + size
    }
    private fun reposition(holder: PositionHolder, position: Long): Int { holder.position = position; return Extractor.RESULT_SEEK }

    companion object { private const val MAX_METADATA_BYTES = 4 * 1024 * 1024 }
}
