package com.aurora.music.data.tuning

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

data class TuningTarget(val id: String, val name: String, val curve: MeasurementCurve)

data class PublishedTuningTarget(val file: String, val rig: String, val sha256: String, val format: TuningCurveFormat) {
    val name: String get() = file.removeSuffix(".csv")
    val url: String get() = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/${TuningTargetCatalog.SOURCE_REVISION}/targets/" +
        java.net.URLEncoder.encode(file, "UTF-8").replace("+", "%20")
}

object TuningTargetCatalog {
    const val PREFERENCE_KEY = "tuning_targets_v1"
    const val SOURCE_REVISION = "7ae0f56d53074872b028649617a22bbb4232feb7"
    const val MAX_TARGETS = 32
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    val published = listOf(
        PublishedTuningTarget("Harman in-ear 2019.csv", "711 / IEC 60318-4", "9f7acab72e1f7507889a13d4278390089d495ccedcfd3aa48df939a6c7491139", TuningCurveFormat.AUTOEQ_CSV),
        PublishedTuningTarget("Diffuse field 5128.csv", "B&K 5128", "f906951f4ef7229fee579ca0bb574eec4aeea93f69e1e224ca4f94461836d361", TuningCurveFormat.AUTOEQ_CSV),
        PublishedTuningTarget("JM-1 with Harman filters.csv", "B&K 5128", "9120be5921ca9730a257fdfd02ba53f27523831a56169627746c0e6c0671e93f", TuningCurveFormat.TEXT),
        PublishedTuningTarget("Harman loudspeaker in-room flat 2013.csv", "In-room loudspeaker", "171b5568adf8c43d9805b51fb7acc42ac4ad4e7bab4c4f00a009741bf301d118", TuningCurveFormat.AUTOEQ_CSV),
    )

    fun fromCurve(curve: MeasurementCurve): TuningTarget = validate(TuningTarget(UUID.randomUUID().toString(), curve.name, curve))

    fun validate(target: TuningTarget): TuningTarget {
        require(UUID.fromString(target.id).toString() == target.id) { "Invalid target identifier." }
        require(target.name.isNotBlank() && target.name.length <= 80 && target.name.none(Char::isISOControl)) { "Use a target name of 1–80 characters." }
        require(target.curve.format != TuningCurveFormat.WAVELET) { "Correction curves cannot be saved as acoustic targets." }
        return target.copy(curve = TuningProjectCodec.validateCurve(target.curve))
    }

    fun encodeLibrary(targets: List<TuningTarget>): String {
        require(targets.size <= MAX_TARGETS && targets.map { it.id }.distinct().size == targets.size) { "Save up to 32 distinct targets." }
        val root = JsonObject().apply { addProperty("schemaVersion", 1); add("targets", gson.toJsonTree(targets.map(::validate))) }
        return gson.toJson(root).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Target library exceeds 8 MiB." } }
    }

    fun decodeLibrary(json: String?): Result<List<TuningTarget>> = runCatching {
        if (json == null) return@runCatching emptyList()
        val root = TuningProjectCodec.strictJson(json, MAX_BYTES).asJsonObject
        require(root.keySet() == setOf("schemaVersion", "targets") && root["schemaVersion"].isJsonPrimitive &&
            root["schemaVersion"].asJsonPrimitive.isNumber && root["schemaVersion"].asBigDecimal == java.math.BigDecimal.ONE) { "Unsupported target library." }
        val values = root["targets"].asJsonArray
        require(values.size() <= MAX_TARGETS) { "Too many saved targets." }
        val result = values.map {
            val item = it.asJsonObject
            require(item.keySet() == setOf("id", "name", "curve")) { "Unsupported target fields." }
            require(listOf("id", "name").all { key -> item[key].isJsonPrimitive && item[key].asJsonPrimitive.isString }) { "Invalid target text." }
            validate(TuningTarget(item["id"].asString, item["name"].asString, TuningProjectCodec.readCurve(item["curve"])))
        }
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate target identifiers." }
        result
    }

    fun upsert(targets: List<TuningTarget>, target: TuningTarget): Result<List<TuningTarget>> = runCatching {
        val next = validate(target)
        val result = if (targets.any { it.id == next.id }) targets.map { if (it.id == next.id) next else it } else targets + next
        encodeLibrary(result)
        result
    }

    fun delete(targets: List<TuningTarget>, id: String): Result<List<TuningTarget>> = runCatching {
        targets.filterNot { it.id == id }.also(::encodeLibrary)
    }

    fun decodePublished(source: PublishedTuningTarget, bytes: ByteArray, nowMs: Long = 0): MeasurementCurve {
        require(source in published && bytes.size <= MeasurementTextImporter.MAX_TEXT_BYTES) { "Unsupported published target." }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == source.sha256) { "Target content changed. Import a reviewed file instead." }
        return TuningCurveAdapters.parse(bytes.toString(Charsets.UTF_8), source.name, source.format,
            provenance = MeasurementProvenance(source.rig, source.url,
                "AutoEq target snapshot $SOURCE_REVISION. Repository license: MIT. Verify measurement rig compatibility."), importedAtMs = nowMs).getOrThrow()
    }

    suspend fun download(source: PublishedTuningTarget): MeasurementCurve = withContext(Dispatchers.IO) {
        require(source in published) { "Unsupported target source." }
        val connection = URI(source.url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Could not download the target." }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MeasurementTextImporter.MAX_TEXT_BYTES) { "Target exceeds 512 KiB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            decodePublished(source, bytes, System.currentTimeMillis())
        } finally { connection.disconnect() }
    }
}
