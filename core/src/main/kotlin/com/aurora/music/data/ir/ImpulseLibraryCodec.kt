package com.aurora.music.data.ir

import com.aurora.music.playback.engine.SamplePrecision
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.UUID

object ImpulseLibraryCodec {
    const val PREFERENCE_KEY = "impulse_library_v1"
    const val MAX_ENTRIES = 32
    const val MAX_LIBRARY_BYTES = 1024 * 1024
    const val MAX_WAV_BYTES = 64L * 1024 * 1024
    const val MAX_SOURCE_FRAMES = 1_048_576
    const val MAX_PREPARED_FRAMES = 262_144
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val entryKeys = setOf("id", "name", "sourceName", "sourcePath", "sourceSha256", "sourceMetadata", "createdAtMs", "prepared")
    private val metadataKeys = setOf("sampleRate", "channels", "frames", "precision", "validBits", "peak")

    fun validate(entry: ImpulseLibraryEntry): ImpulseLibraryEntry {
        identifier(entry.id)
        require(entry.createdAtMs >= 0) { "Invalid import date." }
        val name = name(entry.name)
        require(entry.sourceName.isNotBlank() && entry.sourceName.length <= 255 && entry.sourceName.none(Char::isISOControl)) {
            "Invalid source name."
        }
        path(entry.sourcePath)
        hash(entry.sourceSha256)
        validateMetadata(entry.sourceMetadata)
        entry.prepared?.let { prepared ->
            path(prepared.path)
            require(prepared.path != entry.sourcePath || prepared.sha256 == entry.sourceSha256 && prepared.metadata == entry.sourceMetadata) {
                "Shared impulse files must have identical content."
            }
            hash(prepared.sha256)
            validateMetadata(prepared.metadata)
            validatePreparation(prepared.preparation, entry.sourceMetadata)
            require(prepared.metadata.sampleRate == entry.sourceMetadata.sampleRate &&
                prepared.metadata.channels == entry.sourceMetadata.channels &&
                prepared.metadata.frames == prepared.preparation.endFrameExclusive - prepared.preparation.startFrame + prepared.preparation.delayFrames &&
                prepared.metadata.precision == SamplePrecision.FLOAT_32 && prepared.metadata.validBits == 24) {
                "Prepared metadata does not match its trim."
            }
            if (prepared.preparation.normalization == ImpulseNormalization.PEAK_MINUS_1_DB) {
                require(kotlin.math.abs(prepared.metadata.peak - ImpulseLibraryFiles.NORMALIZED_PEAK) <= 1e-7) {
                    "Prepared peak does not match normalization."
                }
            }
        }
        return entry.copy(name = name)
    }

    fun validateMetadata(metadata: ImpulseMetadata): ImpulseMetadata {
        require(metadata.sampleRate in 8_000..384_000) { "Invalid sample rate." }
        require(metadata.channels in listOf(1, 2, 4)) { "Use a mono, stereo or LL/LR/RL/RR impulse response." }
        require(metadata.frames in 1..MAX_SOURCE_FRAMES) { "Invalid impulse length." }
        require(metadata.precision != SamplePrecision.FLOAT_64 && metadata.validBits in 1..metadata.precision.significandBits &&
            (metadata.precision != SamplePrecision.FLOAT_32 || metadata.validBits == 24)) { "Invalid sample precision." }
        require(metadata.peak.isFinite() && metadata.peak in 0.0..Float.MAX_VALUE.toDouble() &&
            (metadata.precision == SamplePrecision.FLOAT_32 || metadata.peak <= 1.0)) { "Invalid impulse peak." }
        return metadata
    }

    fun validatePreparation(preparation: ImpulsePreparation, source: ImpulseMetadata): ImpulsePreparation {
        validateMetadata(source)
        require(preparation.startFrame >= 0 && preparation.endFrameExclusive <= source.frames &&
            preparation.startFrame < preparation.endFrameExclusive) { "Choose a nonempty trim within the source." }
        require(preparation.delayFrames in 0..source.sampleRate) { "Delay must be between 0 and 1,000 ms." }
        require(preparation.endFrameExclusive - preparation.startFrame + preparation.delayFrames <= MAX_PREPARED_FRAMES) {
            "Trim the copy to 262,144 frames or fewer."
        }
        return preparation
    }

    fun encodeLibrary(entries: List<ImpulseLibraryEntry>): String {
        val validated = validateLibrary(entries)
        val root = JsonObject().apply {
            addProperty("schemaVersion", 2)
            add("entries", gson.toJsonTree(validated))
        }
        return bounded(gson.toJson(root))
    }

    fun decodeLibrary(json: String?): Result<List<ImpulseLibraryEntry>> = runCatching {
        if (json == null) return@runCatching emptyList()
        val root = objectWith(strictJson(json), setOf("schemaVersion", "entries"))
        require(integer(root, "schemaVersion") in 1L..2L) { "Unsupported impulse library version." }
        val entries = root["entries"]
        require(entries.isJsonArray && entries.asJsonArray.size() <= MAX_ENTRIES) { "Invalid impulse library size." }
        validateLibrary(entries.asJsonArray.map(::readEntry))
    }

    fun upsert(entries: List<ImpulseLibraryEntry>, entry: ImpulseLibraryEntry): Result<List<ImpulseLibraryEntry>> = runCatching {
        val current = validateLibrary(entries)
        val next = validate(entry)
        val result = if (current.any { it.id == next.id }) current.map { if (it.id == next.id) next else it } else current + next
        encodeLibrary(result)
        result
    }

    fun delete(entries: List<ImpulseLibraryEntry>, id: String): Result<List<ImpulseLibraryEntry>> = runCatching {
        identifier(id)
        validateLibrary(entries).filterNot { it.id == id }.also(::encodeLibrary)
    }

    private fun validateLibrary(entries: List<ImpulseLibraryEntry>): List<ImpulseLibraryEntry> {
        require(entries.size <= MAX_ENTRIES) { "Save up to 32 impulse responses." }
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate impulse identifier." }
        return entries.map(::validate)
    }

    private fun readEntry(value: JsonElement): ImpulseLibraryEntry {
        val o = objectWith(value, entryKeys)
        return ImpulseLibraryEntry(string(o, "id"), string(o, "name"), string(o, "sourceName"), string(o, "sourcePath"),
            string(o, "sourceSha256"), readMetadata(o["sourceMetadata"]), integer(o, "createdAtMs"),
            o["prepared"].takeUnless { it.isJsonNull }?.let(::readPrepared))
    }

    private fun readMetadata(value: JsonElement): ImpulseMetadata {
        val o = objectWith(value, metadataKeys)
        return ImpulseMetadata(int(o, "sampleRate"), int(o, "channels"), int(o, "frames"),
            enumValueOf<SamplePrecision>(string(o, "precision")), int(o, "validBits"), number(o, "peak"))
    }

    private fun readPrepared(value: JsonElement): ImpulsePreparedAsset {
        val o = objectWith(value, setOf("path", "sha256", "metadata", "preparation"))
        val p = o["preparation"].asJsonObject
        require(p.keySet().containsAll(setOf("startFrame", "endFrameExclusive", "normalization")) &&
            p.keySet().all { it in setOf("startFrame", "endFrameExclusive", "normalization", "minimumPhase", "delayFrames") }) {
            "Unsupported preparation fields."
        }
        val minimum = p["minimumPhase"]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Invalid phase option." }; it.asBoolean
        } ?: false
        return ImpulsePreparedAsset(string(o, "path"), string(o, "sha256"), readMetadata(o["metadata"]),
            ImpulsePreparation(int(p, "startFrame"), int(p, "endFrameExclusive"),
                enumValueOf<ImpulseNormalization>(string(p, "normalization")), minimum,
                if (p.has("delayFrames")) int(p, "delayFrames") else 0))
    }

    private fun strictJson(json: String): JsonElement {
        bounded(json)
        return JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            var count = 0
            fun read(depth: Int): JsonElement {
                require(depth <= 8 && ++count <= 4096) { "Impulse JSON is too complex." }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(key.length <= 64 && !has(key)) { "Duplicate or invalid impulse field." }
                            add(key, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                        reader.beginArray()
                        while (reader.hasNext()) add(read(depth + 1))
                        reader.endArray()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 4096) { "Impulse field is too long." } })
                    JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString().also { require(it.length <= 100) { "Invalid impulse number." } }))
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    else -> error("Invalid impulse JSON.")
                }
            }
            read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after impulse JSON." } }
        }
    }

    private fun objectWith(value: JsonElement?, keys: Set<String>): JsonObject {
        require(value != null && value.isJsonObject && value.asJsonObject.keySet() == keys) { "Impulse fields are incomplete or unsupported." }
        return value.asJsonObject
    }

    private fun string(o: JsonObject, key: String): String = o[key].let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid $key." }; it.asString
    }

    private fun integer(o: JsonObject, key: String): Long = o[key].let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Invalid $key." }; it.asBigDecimal.longValueExact()
    }

    private fun int(o: JsonObject, key: String): Int = integer(o, key).let { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid $key." }; it.toInt() }

    private fun number(o: JsonObject, key: String): Double = o[key].let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()) { "Invalid $key." }; it.asDouble
    }

    private fun identifier(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid impulse identifier." } }
    fun name(value: String) = value.trim().also { require(it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl)) { "Use a name between 1 and 80 characters." } }
    private fun hash(value: String) { require(hashPattern.matches(value)) { "Invalid impulse checksum." } }
    private fun path(value: String) {
        if (Regex("aurora-ir:[0-9a-f]{64}").matches(value)) return
        require(value.length in 1..4096 && value.none(Char::isISOControl) &&
            (value.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)) &&
            value.split('/', '\\').none { it == "." || it == ".." }) { "Invalid impulse path." }
    }
    private fun bounded(value: String) = value.also {
        require(it.length <= MAX_LIBRARY_BYTES && it.toByteArray(Charsets.UTF_8).size <= MAX_LIBRARY_BYTES) { "Impulse library exceeds 1 MiB." }
    }
}
