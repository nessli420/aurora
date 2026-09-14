package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable app backup. Only the exact manifest and content-addressed WAV entries are accepted. */
object BackupArchive {
    const val MAX_METADATA_BYTES = 16L * 1024 * 1024
    const val MAX_ASSET_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    // DEFLATE can expand incompressible input, and ZIP headers/central records also take space.
    private const val ZIP_OVERHEAD_BYTES = 1024L * 1024
    const val PRESETS_KEY = "processing_presets_v1"
    const val IR_PATH_KEY = "dsp_conv_path"
    private const val PREFIX = "aurora-ir:"
    private val gson = Gson()
    private val hashPattern = Regex("[0-9a-f]{64}")

    class Imported internal constructor(val backup: AuroraBackup, val assets: Map<String, File>,
        private val directory: File) : AutoCloseable {
        override fun close() {
            assets.values.forEach { it.delete() }
            File(directory, "backup.zip").delete()
            directory.delete()
        }
    }

    fun write(backup: AuroraBackup, output: OutputStream) {
        val assets = linkedMapOf<String, File>()
        val portable = remap(backup.copy(version = 2)) { path, expected ->
            val file = File(path)
            require(file.isFile && file.canRead()) { "An impulse response is missing. Select it again before exporting." }
            ProcessingPresetBundle.validateImpulseResponse(file)
            val hash = sha256(file)
            require(expected.isEmpty() || expected == hash) { "A saved impulse response has changed." }
            assets[hash] = file
            "$PREFIX$hash" to hash
        }
        require(assets.size <= 101 && assets.values.sumOf { it.length() } <= MAX_ARCHIVE_BYTES - MAX_METADATA_BYTES - ZIP_OVERHEAD_BYTES) {
            "Impulse responses exceed the 495 MiB backup asset limit."
        }
        val bytes = gson.toJson(portable).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA_BYTES) { "Backup metadata exceeds 16 MiB." }
        val borrowed = object : FilterOutputStream(output) {
            private var written = 0L
            override fun close() = flush()
            override fun write(b: Int) {
                require(written < MAX_ARCHIVE_BYTES) { "Backup archive exceeds 512 MiB." }
                out.write(b); written++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                require(len >= 0 && written + len <= MAX_ARCHIVE_BYTES) { "Backup archive exceeds 512 MiB." }
                out.write(b, off, len); written += len
            }
        }
        ZipOutputStream(borrowed).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json")); zip.write(bytes); zip.closeEntry()
            assets.forEach { (hash, file) ->
                zip.putNextEntry(ZipEntry("assets/$hash.wav"))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { copyBounded(it, zip, MAX_ASSET_BYTES, digest) }
                require(hex(digest.digest()) == hash) { "An impulse response changed during export. Try again." }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, temporaryRoot: File): Imported {
        val directory = File(temporaryRoot, "backup-import-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Cannot create backup staging storage." }
        val archive = File(directory, "backup.zip")
        val assets = linkedMapOf<String, File>()
        try {
            archive.outputStream().use { copyBounded(input, it, MAX_ARCHIVE_BYTES) }
            val backup = ZipFile(archive).use { zip ->
                val entries = linkedMapOf<String, ZipEntry>()
                var expanded = 0L
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    val validName = entry.name == "backup.json" || entry.name.matches(Regex("assets/[0-9a-f]{64}\\.wav"))
                    require(validName && !entry.isDirectory && entries.size < 102 && entries.put(entry.name, entry) == null) {
                        "Backup contains duplicate or unsupported files or paths."
                    }
                    val cap = if (entry.name == "backup.json") MAX_METADATA_BYTES else MAX_ASSET_BYTES
                    require(entry.size in 1..cap && entry.compressedSize in 1..MAX_ARCHIVE_BYTES &&
                        entry.method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) { "Unsupported or oversized backup entry." }
                    expanded += entry.size
                    require(expanded <= MAX_ARCHIVE_BYTES) { "Expanded backup exceeds 512 MiB." }
                }
                val manifest = entries["backup.json"] ?: error("Backup has no manifest.")
                val bytes = ByteArrayOutputStream()
                readEntry(zip, manifest, bytes, MAX_METADATA_BYTES)
                val parsed = decodeJson(utf8(bytes.toByteArray()))
                require(parsed.version == 2) { "Unsupported portable backup version." }
                val needed = linkedSetOf<String>()
                remap(parsed) { path, expected ->
                    val hash = path.removePrefix(PREFIX)
                    require(path.startsWith(PREFIX) && hashPattern.matches(hash) && (expected.isEmpty() || expected == hash)) {
                        "Portable backup has an invalid impulse-response reference."
                    }
                    needed += hash
                    path to hash
                }
                require(entries.keys == setOf("backup.json") + needed.map { "assets/$it.wav" }) {
                    "Backup impulse-response dependencies are missing or unreferenced."
                }
                needed.forEach { hash ->
                    val file = File(directory, "$hash.wav")
                    assets[hash] = file
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.outputStream().use { readEntry(zip, entries.getValue("assets/$hash.wav"), it, MAX_ASSET_BYTES, digest) }
                    require(hex(digest.digest()) == hash) { "Backup impulse-response checksum does not match." }
                    ProcessingPresetBundle.validateImpulseResponse(file)
                }
                parsed
            }
            return Imported(backup, assets, directory)
        } catch (failure: Throwable) {
            assets.values.forEach { it.delete() }; archive.delete(); directory.delete()
            throw failure
        }
    }

    /** Full validation precedes Gson construction; missing legacy fields receive explicit defaults. */
    fun decodeJson(json: String): AuroraBackup {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "Backup metadata exceeds 16 MiB." }
        val root = parseStrictJson(json)
        require(root.isJsonObject) { "Backup is not an object." }
        val o = root.asJsonObject
        require(o.keySet().all { it in setOf("version", "createdAt", "prefs", "localStore", "playHistory") }) { "Unsupported backup fields." }
        val version = number(o, "version").toInt()
        require(number(o, "version") == version.toDouble() && version in 1..2) { "Unsupported backup version." }
        val created = o.get("createdAt")?.let { number(o, "createdAt") } ?: 0.0
        require(created >= 0 && created == created.toLong().toDouble()) { "Invalid backup date." }
        val prefs = o.get("prefs")
        require(prefs != null && prefs.isJsonObject) { "Backup settings are missing." }
        val shape = gson.toJsonTree(PrefsBackup()).asJsonObject
        require(prefs.asJsonObject.keySet().all { it in shape.keySet() }) { "Unsupported settings group." }
        val allKeys = mutableSetOf<String>()
        shape.keySet().forEach { kind ->
            val map = prefs.asJsonObject.get(kind) ?: JsonObject().also { prefs.asJsonObject.add(kind, it) }
            require(map.isJsonObject && map.asJsonObject.size() <= 10_000) { "Invalid settings map." }
            map.asJsonObject.entrySet().forEach { (key, value) ->
                require(key.isNotEmpty() && key.length <= 200 && allKeys.add(key)) { "Duplicate or invalid setting key." }
                when (kind) {
                    "strings" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                    "booleans" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                    "stringSets" -> require(value.isJsonArray && value.asJsonArray.size() <= 100_000 &&
                        value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                    else -> {
                        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.isFinite())
                        if (kind == "ints") require(value.asDouble == value.asInt.toDouble())
                        if (kind == "longs") require(value.asDouble == value.asLong.toDouble())
                        if (kind == "floats") require(value.asFloat.isFinite())
                    }
                }
            }
        }
        val local = o.get("localStore")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString } ?: ""
        validateLocal(local)
        val events = o.get("playHistory") ?: com.google.gson.JsonArray().also { o.add("playHistory", it) }
        require(events.isJsonArray && events.asJsonArray.size() <= 100_000) { "Invalid listening history." }
        val historyStrings = setOf("songId", "title", "artist", "album", "albumId", "artistId", "artworkUrl")
        events.asJsonArray.forEach { event ->
            require(event.isJsonObject)
            val e = event.asJsonObject
            require(e.keySet() == historyStrings + setOf("durationSec", "timestamp")) { "Incomplete history entry." }
            historyStrings.forEach { key -> require(e[key].isJsonPrimitive && e[key].asJsonPrimitive.isString) }
            val duration = number(e, "durationSec"); val timestamp = number(e, "timestamp")
            require(duration >= 0 && duration == duration.toInt().toDouble() && timestamp >= 0 && timestamp == timestamp.toLong().toDouble())
        }
        o.addProperty("createdAt", created.toLong()); o.addProperty("localStore", local)
        val result = gson.fromJson(o, AuroraBackup::class.java)
        val library = ProcessingPresetCodec.decode(result.prefs.strings[PRESETS_KEY])
        require(library.error == null) { library.error.orEmpty() }
        result.prefs.strings[ProcessingRackCodec.PREFERENCE_KEY]?.let { ProcessingRackCodec.decode(it).getOrThrow() }
        com.aurora.music.data.tuning.TuningProjectCodec.decodeLibrary(
            result.prefs.strings[com.aurora.music.data.tuning.TuningProjectCodec.PREFERENCE_KEY]).getOrThrow()
        return result
    }

    fun remap(backup: AuroraBackup, asset: (String, String) -> Pair<String, String>): AuroraBackup {
        val strings = backup.prefs.strings.toMutableMap()
        val rack = strings[ProcessingRackCodec.PREFERENCE_KEY]?.let { ProcessingRackCodec.decode(it).getOrThrow() }
        val needsGlobalImpulse = if (rack?.enabled == true) rack.requiresImpulseResponse()
            else backup.prefs.booleans["dsp_conv_enabled"] == true
        require(!needsGlobalImpulse || strings[IR_PATH_KEY].orEmpty().isNotBlank()) {
            "Enabled convolution has no shared impulse-response reference."
        }
        strings[IR_PATH_KEY]?.takeIf { it.isNotEmpty() }?.let { strings[IR_PATH_KEY] = asset(it, "").first }
        val library = ProcessingPresetCodec.decode(strings[PRESETS_KEY])
        require(library.error == null) { library.error.orEmpty() }
        if (strings.containsKey(PRESETS_KEY)) strings[PRESETS_KEY] = ProcessingPresetCodec.encode(library.presets.map { preset ->
            require(!(preset.requiresImpulseResponse() || preset.irSha256.isNotEmpty()) || preset.audio.dspConvIrPath.isNotBlank()) {
                "A saved preset has an incomplete impulse-response reference."
            }
            require(!preset.requiresImpulseResponse() || preset.irSha256.isNotEmpty()) {
                "A saved preset's required impulse response has no checksum."
            }
            if (preset.audio.dspConvIrPath.isEmpty()) preset else {
                val (path, hash) = asset(preset.audio.dspConvIrPath, preset.irSha256)
                preset.copy(audio = preset.audio.copy(dspConvIrPath = path), irSha256 = hash)
            }
        })
        return backup.copy(prefs = backup.prefs.copy(strings = strings))
    }

    fun assetHash(reference: String): String = reference.removePrefix(PREFIX).also {
        require(reference.startsWith(PREFIX) && hashPattern.matches(it)) { "Invalid backup asset reference." }
    }

    fun readJsonStream(input: InputStream): AuroraBackup {
        val bytes = ByteArrayOutputStream(); copyBounded(input, bytes, MAX_METADATA_BYTES)
        return decodeJson(utf8(bytes.toByteArray()))
    }

    private fun validateLocal(json: String) {
        if (json.isEmpty()) return
        val value = parseStrictJson(json)
        require(value.isJsonObject) { "Invalid local library." }
        val o = value.asJsonObject
        require(o.keySet().all { it in setOf("playlists", "likedIds") })
        o.get("likedIds")?.takeUnless { it.isJsonNull }?.let { list ->
            require(list.isJsonArray && list.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
        }
        o.get("playlists")?.takeUnless { it.isJsonNull }?.let { list ->
            require(list.isJsonArray && list.asJsonArray.size() <= 10_000)
            val identifiers = mutableSetOf<String>()
            list.asJsonArray.forEach { p ->
                require(p.isJsonObject)
                require(p.asJsonObject.keySet().all { it in setOf("id", "title", "subtitle", "trackIds") })
                val id = p.asJsonObject.get("id")
                require(id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString && id.asString.isNotBlank())
                require(identifiers.add(id.asString)) { "Duplicate local playlist identifier." }
                listOf("title", "subtitle").forEach { key -> p.asJsonObject.get(key)?.takeUnless { it.isJsonNull }?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                } }
                p.asJsonObject.get("trackIds")?.takeUnless { it.isJsonNull }?.let { tracks ->
                    require(tracks.isJsonArray && tracks.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                }
            }
        }
    }

    private fun parseStrictJson(json: String): JsonElement = JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        strictValue(reader, 0, intArrayOf(0)).also {
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after backup JSON." }
        }
    }

    private fun strictValue(reader: JsonReader, depth: Int, count: IntArray): JsonElement {
        require(depth <= 20 && ++count[0] <= 1_500_000) { "Backup is too complex." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(key.length <= 200 && !has(key)) { "Duplicate or oversized backup field." }
                    add(key, strictValue(reader, depth + 1, count))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> com.google.gson.JsonArray().apply {
                reader.beginArray(); while (reader.hasNext()) add(strictValue(reader, depth + 1, count)); reader.endArray()
            }
            JsonToken.STRING -> com.google.gson.JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> com.google.gson.JsonPrimitive(java.math.BigDecimal(reader.nextString().also {
                require(it.length <= 100) { "Backup number is too long." }
            }))
            JsonToken.BOOLEAN -> com.google.gson.JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); com.google.gson.JsonNull.INSTANCE }
            else -> error("Invalid backup JSON.")
        }
    }

    private fun number(o: JsonObject, key: String): Double {
        val value = o.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.isFinite()) { "Invalid $key." }
        return value.asDouble
    }
    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { copyBounded(it, object : OutputStream() { override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit }, MAX_ASSET_BYTES, digest) }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, output: OutputStream, limit: Long, digest: MessageDigest? = null) {
        val crc = CRC32()
        val size = zip.getInputStream(entry).use { copyBounded(it, output, minOf(limit, entry.size), digest, crc) }
        require(size == entry.size && crc.value == entry.crc) { "Backup archive entry is damaged." }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null, crc: CRC32? = null): Long {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            total += count; require(total <= limit) { "Backup entry exceeds the supported size." }
            output.write(buffer, 0, count); digest?.update(buffer, 0, count); crc?.update(buffer, 0, count)
        }
        return total
    }
}
