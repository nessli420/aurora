package com.aurora.music.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Validated, temporary import files. Never expose their paths in exported metadata. */
class ImportedProcessingPresetBundle internal constructor(
    val preset: ProcessingPreset,
    val impulseResponse: File?,
    private val directory: File,
) : AutoCloseable {
    override fun close() {
        // Only files created by this reader, under its own fresh directory, are removed.
        impulseResponse?.delete()
        File(directory, "bundle.zip").delete()
        directory.delete()
    }
}

/** A deliberately small portable format: a manifest and at most one immutable IR. */
object ProcessingPresetBundle {
    const val VERSION = 2
    const val MAX_IR_BYTES = 64L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 512L * 1024
    private const val MAX_ARCHIVE_BYTES = MAX_IR_BYTES + 1024 * 1024
    private const val FORMAT = "aurora-processing-preset"
    private const val MANIFEST = "manifest.json"
    private const val IR = "ir.wav"
    private val manifestKeys = setOf("format", "version", "preset")
    private val presetKeys = setOf("name", "schemaVersion", "audio", "playback", "activeEqProfile", "irSha256")

    /** The caller owns [output]. A failed export must not be treated as a finished document. */
    fun write(preset: ProcessingPreset, impulseResponse: File?, output: OutputStream) {
        val portable = preset.copy(audio = preset.audio.copy(dspConvIrPath = ""))
        val encoded = JsonParser.parseString(ProcessingPresetCodec.encode(listOf(portable)))
            .asJsonArray[0].asJsonObject
        encoded.remove("id")
        encoded.remove("createdAtMs")
        require((impulseResponse != null) == portable.irSha256.isNotBlank()) {
            "This preset's impulse response is unavailable. Select it again and save a new preset."
        }
        require(!portable.requiresImpulseResponse() || impulseResponse != null) {
            "This preset needs an impulse response before it can be exported."
        }
        if (impulseResponse != null) {
            require(impulseResponse.isFile && impulseResponse.canRead()) { "This preset's impulse response is missing." }
            validateImpulseResponse(impulseResponse)
            require(sha256(impulseResponse) == portable.irSha256) { "This preset's impulse response has changed." }
        }
        val manifest = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            add("preset", encoded)
        }.toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Preset metadata exceeds the supported size." }
        val borrowed = object : FilterOutputStream(output) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, count: Int) = out.write(bytes, offset, count)
        }
        ZipOutputStream(borrowed).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            if (impulseResponse != null) {
                zip.putNextEntry(ZipEntry(IR))
                val digest = MessageDigest.getInstance("SHA-256")
                impulseResponse.inputStream().use { copyBounded(it, zip, MAX_IR_BYTES, digest = digest) }
                require(hex(digest.digest()) == portable.irSha256) { "The impulse response changed during export. Try again." }
                zip.closeEntry()
            }
        }
    }

    /** Spools a bounded archive, validates all entries, and only then returns a complete snapshot. */
    fun read(input: InputStream, temporaryRoot: File): ImportedProcessingPresetBundle {
        check(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) { "Cannot create temporary preset storage." }
        val directory = File(temporaryRoot, "preset-import-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Cannot create temporary preset storage." }
        val archive = File(directory, "bundle.zip")
        val ir = File(directory, IR)
        try {
            archive.outputStream().use { copyBounded(input, it, MAX_ARCHIVE_BYTES) }
            var preset: ProcessingPreset
            var hasIr = false
            ZipFile(archive).use { zip ->
                val entries = mutableMapOf<String, ZipEntry>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    require(entries.size < 2 && entry.name in setOf(MANIFEST, IR) && !entry.isDirectory) {
                        "The preset archive contains unsupported files or paths."
                    }
                    require(entries.put(entry.name, entry) == null) { "The preset archive contains duplicate files." }
                    require(entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED) { "Unsupported archive compression." }
                    val limit = if (entry.name == MANIFEST) MAX_MANIFEST_BYTES else MAX_IR_BYTES
                    require(entry.size in 1..limit && entry.compressedSize in 1..MAX_ARCHIVE_BYTES) {
                        "A preset archive entry exceeds the supported size."
                    }
                }
                val metadata = entries[MANIFEST] ?: error("The archive has no preset manifest.")
                val bytes = java.io.ByteArrayOutputStream()
                readEntry(zip, metadata, bytes, MAX_MANIFEST_BYTES)
                val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                preset = parseManifest(json)
                hasIr = entries.containsKey(IR)
                require(hasIr == preset.irSha256.isNotEmpty()) { "The preset's impulse response dependency is incomplete." }
                require(!preset.requiresImpulseResponse() || hasIr) { "Enabled convolution requires an included impulse response." }
                if (hasIr) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    ir.outputStream().use { readEntry(zip, entries.getValue(IR), it, MAX_IR_BYTES, digest) }
                    require(hex(digest.digest()) == preset.irSha256) { "The preset's impulse response checksum does not match." }
                    validateImpulseResponse(ir)
                }
            }
            archive.delete()
            return ImportedProcessingPresetBundle(preset, ir.takeIf { hasIr }, directory)
        } catch (failure: Throwable) {
            ir.delete()
            archive.delete()
            directory.delete()
            throw failure
        }
    }

    private fun parseManifest(json: String): ProcessingPreset {
        val parsed = JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            val result = readJson(reader, 0, intArrayOf(0))
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after the preset manifest." }
            result
        }
        require(parsed.isJsonObject) { "Invalid preset manifest." }
        val root = parsed.asJsonObject
        require(root.keySet() == manifestKeys) { "Incomplete or unsupported preset manifest." }
        val format = root.get("format")
        require(format.isJsonPrimitive && format.asJsonPrimitive.isString && format.asString == FORMAT) {
            "This is not an Aurora processing preset."
        }
        val version = root.get("version")
        require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asDouble in listOf(1.0, VERSION.toDouble())) {
            "Unsupported preset bundle version."
        }
        require(root.get("preset").isJsonObject) { "Missing preset settings." }
        val p = root.getAsJsonObject("preset")
        require(p.keySet() == if (version.asDouble == 1.0) presetKeys else presetKeys + "rack") { "Incomplete or unsupported preset settings." }
        require(p.get("schemaVersion").isJsonPrimitive && p.get("schemaVersion").asJsonPrimitive.isNumber &&
            p.get("schemaVersion").asDouble == version.asDouble) { "Preset bundle and snapshot versions do not match." }
        p.addProperty("id", UUID.randomUUID().toString())
        p.addProperty("createdAtMs", System.currentTimeMillis())
        val decoded = ProcessingPresetCodec.decode("[$p]")
        require(decoded.error == null) { decoded.error.orEmpty() }
        return decoded.presets.single().also {
            require(it.audio.dspConvIrPath.isEmpty()) { "Portable presets cannot contain local file paths." }
        }
    }

    /** Bounded strict parsing also rejects duplicate fields before any settings are constructed. */
    private fun readJson(reader: JsonReader, depth: Int, count: IntArray): JsonElement {
        require(depth <= 16 && ++count[0] <= 32_768) { "Preset metadata is too complex." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name.length <= 128 && !has(name)) { "Invalid or duplicate preset metadata field." }
                    add(name, readJson(reader, depth + 1, count))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readJson(reader, depth + 1, count))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also {
                require(it.length <= 4096) { "A preset metadata value is too long." }
            })
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString().also {
                require(it.length <= 100) { "A preset metadata number is too long." }
            }))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("Invalid preset manifest JSON.")
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, output: OutputStream, limit: Long, digest: MessageDigest? = null) {
        val crc = CRC32()
        val size = zip.getInputStream(entry).use { copyBounded(it, output, limit, digest, crc) }
        require(size == entry.size && crc.value == entry.crc) { "The preset archive is damaged." }
    }

    /** Header/chunk validation only: malformed frame counts never reach the allocating IR decoder. */
    internal fun validateImpulseResponse(file: File) {
        require(file.length() in 44..MAX_IR_BYTES) { "The impulse response is not a supported WAV file." }
        RandomAccessFile(file, "r").use { wav ->
            fun uint(): Long = Integer.reverseBytes(wav.readInt()).toLong() and 0xffffffffL
            fun ushort(): Int = java.lang.Short.reverseBytes(wav.readShort()).toInt() and 0xffff
            require(wav.readInt() == 0x52494646) { "The impulse response must use RIFF WAV format." }
            val riffEnd = uint() + 8
            require(riffEnd == file.length() && wav.readInt() == 0x57415645) { "The impulse response WAV length is invalid." }
            var format = 0
            var channels = 0
            var sampleRate = 0L
            var byteRate = 0L
            var blockAlign = 0
            var bits = 0
            var dataOffset = 0L
            var dataBytes = 0L
            var chunks = 0
            while (wav.filePointer < riffEnd) {
                require(++chunks <= 1024 && riffEnd - wav.filePointer >= 8) { "The impulse response has invalid WAV chunks." }
                val kind = wav.readInt()
                val length = uint()
                val start = wav.filePointer
                val paddedEnd = start + length + (length and 1L)
                require(paddedEnd <= riffEnd) { "The impulse response contains a truncated WAV chunk." }
                when (kind) {
                    0x666d7420 -> {
                        require(format == 0 && length >= 16 && length % 2 == 0L) { "The impulse response has an invalid format chunk." }
                        format = ushort()
                        channels = ushort()
                        sampleRate = uint()
                        byteRate = uint()
                        blockAlign = ushort()
                        bits = ushort()
                    }
                    0x64617461 -> {
                        require(dataOffset == 0L && length > 0) { "The impulse response has invalid audio data." }
                        dataOffset = start
                        dataBytes = length
                    }
                }
                wav.seek(paddedEnd)
            }
            require(channels in 1..2 && sampleRate in 8_000..384_000 &&
                ((format == 1 && bits in setOf(8, 16, 24, 32)) || (format == 3 && bits == 32))) {
                "Use a mono or stereo PCM WAV (8/16/24/32-bit), or 32-bit float WAV, at 8–384 kHz."
            }
            val frameSize = channels * (bits / 8)
            require(blockAlign == frameSize && byteRate == sampleRate * frameSize && dataOffset > 0 &&
                dataBytes % frameSize == 0L && dataBytes / frameSize <= 8_000_000) {
                "The impulse response has invalid frame sizes or exceeds 8 million frames."
            }
            // Floating point IRs must not inject NaN/Infinity into live convolution.
            if (format == 3) {
                wav.seek(dataOffset)
                val buffer = ByteArray(8192)
                var remaining = dataBytes
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    wav.readFully(buffer, 0, count)
                    val samples = ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                    while (samples.hasRemaining()) {
                        require(samples.float.isFinite()) { "The impulse response contains non-finite samples." }
                    }
                    remaining -= count
                }
            }
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long,
        digest: MessageDigest? = null, crc: CRC32? = null): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= limit) { "The preset archive exceeds the supported size." }
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            crc?.update(buffer, 0, count)
        }
        return total
    }

    fun sha256(file: File): String {
        require(file.length() in 1..MAX_IR_BYTES) { "Impulse response must be between 1 byte and 64 MB." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { copyBounded(it, object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }, MAX_IR_BYTES, digest) }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
