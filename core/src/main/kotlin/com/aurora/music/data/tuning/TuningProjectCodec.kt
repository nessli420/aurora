package com.aurora.music.data.tuning

import com.aurora.music.data.ParamBand
import com.aurora.music.data.ParamBandCodec
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
import java.security.MessageDigest
import java.util.UUID

// settings storage owns the atomic write.
object TuningProjectCodec {
    const val PREFERENCE_KEY = "tuning_projects_v1"
    const val MAX_PROJECTS = 32
    const val MAX_PROJECT_BYTES = 4 * 1024 * 1024
    const val MAX_LIBRARY_BYTES = 8 * 1024 * 1024
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val projectKeys = setOf("id", "name", "measurementLeft", "measurementRight", "target", "notes", "config", "generatedFit", "schemaVersion", "createdAtMs", "updatedAtMs")
    private val configKeys = setOf("sampleRate", "bandBudget", "minFrequencyHz", "maxFrequencyHz", "maxBoostDb", "maxCutDb", "minQ", "maxQ", "channelMode", "normalization", "smoothingOctaves")
    private val extendedConfigKeys = setOf("bassDb", "bassFrequencyHz", "tiltDbPerOctave", "earGainDb", "earGainFrequencyHz", "trebleStartHz", "trebleMaxBoostDb", "trebleMaxCutDb", "trebleMaxQ", "leftLimits", "rightLimits", "leftTrimDb", "rightTrimDb", "leftDelayMs", "rightDelayMs")
    private val curveKeys = setOf("id", "name", "points", "sourceText", "importedAtMs", "provenance")
    private val fitKeys = setOf("algorithmVersion", "inputFingerprint", "config", "frequenciesHz", "channels", "minFrequencyHz", "maxFrequencyHz", "measuredNormalizationDb", "targetNormalizationDb", "preampDb", "peakBoostDb", "peakCutDb", "errorBeforeDb", "errorAfterDb", "notes")
    private val channelKeys = setOf("channel", "bands", "measuredDb", "targetDb", "correctionDb", "fittedDb", "predictedDb", "errorBeforeDb", "errorAfterDb")

    fun create(name: String, nowMs: Long = 0): TuningProject = validate(TuningProject(UUID.randomUUID().toString(), name,
        createdAtMs = nowMs, updatedAtMs = nowMs))

    fun validate(project: TuningProject): TuningProject {
        require(project.schemaVersion == 1) { "Unsupported tuning project version." }
        identifier(project.id)
        require(project.createdAtMs >= 0 && project.updatedAtMs >= 0) { "Invalid project date." }
        note(project.notes, 4096, "Project notes")
        val detached = project.copy(name = name(project.name), measurementLeft = project.measurementLeft?.let { validateCurve(it) },
            measurementRight = project.measurementRight?.let { validateCurve(it) }, target = project.target?.let { validateCurve(it) },
            config = validateConfig(project.config), generatedFit = null)
        val result = detached.copy(generatedFit = project.generatedFit?.let { validateFit(it, detached) })
        bounded(gson.toJson(result), MAX_PROJECT_BYTES, "Tuning project")
        return result
    }

    fun validateConfig(config: TuningFitConfig): TuningFitConfig {
        require(config.sampleRate in 8_000..768_000) { "Sample rate must be between 8,000 and 768,000 Hz." }
        require(config.bandBudget in 1..128) { "Band budget must be between 1 and 128 in total." }
        range(config.minFrequencyHz, 10.0, 24_000.0, "Minimum frequency")
        range(config.maxFrequencyHz, 10.0, 24_000.0, "Maximum frequency")
        require(config.minFrequencyHz < config.maxFrequencyHz) { "Minimum frequency must be below maximum frequency." }
        range(config.maxBoostDb, 0.0, 24.0, "Maximum boost")
        range(config.maxCutDb, 0.0, 30.0, "Maximum cut")
        range(config.minQ, .1, 100.0, "Minimum Q"); range(config.maxQ, .1, 100.0, "Maximum Q")
        require(config.minQ <= config.maxQ) { "Minimum Q must not exceed maximum Q." }
        range(config.smoothingOctaves, 0.0, 1.0, "Smoothing")
        range(config.bassDb, -12.0, 12.0, "Bass")
        range(config.bassFrequencyHz, 20.0, 500.0, "Bass frequency")
        range(config.tiltDbPerOctave, -3.0, 3.0, "Tilt")
        range(config.earGainDb, -12.0, 12.0, "Ear gain")
        range(config.earGainFrequencyHz, 1000.0, 5000.0, "Ear gain frequency")
        range(config.trebleStartHz, 2000.0, 16000.0, "Treble start")
        config.trebleMaxBoostDb?.let { range(it, 0.0, 24.0, "Treble boost") }
        config.trebleMaxCutDb?.let { range(it, 0.0, 30.0, "Treble cut") }
        config.trebleMaxQ?.let { range(it, .1, 100.0, "Treble Q") }
        listOfNotNull(config.leftLimits, config.rightLimits).forEach {
            range(it.maxBoostDb, 0.0, 24.0, "Channel boost")
            range(it.maxCutDb, 0.0, 30.0, "Channel cut")
            range(it.minQ, .1, 100.0, "Channel minimum Q")
            range(it.maxQ, it.minQ, 100.0, "Channel maximum Q")
        }
        val minimumQ = maxOf(config.minQ, config.leftLimits?.minQ ?: config.minQ, config.rightLimits?.minQ ?: config.minQ)
        require(config.trebleMaxQ == null || config.trebleMaxQ >= minimumQ) { "Treble Q must not be below minimum Q." }
        range(config.leftTrimDb, -12.0, 0.0, "Left trim")
        range(config.rightTrimDb, -12.0, 0.0, "Right trim")
        range(config.leftDelayMs, 0.0, 20.0, "Left delay")
        range(config.rightDelayMs, 0.0, 20.0, "Right delay")
        return config
    }

    internal fun validateCurve(curve: MeasurementCurve, checkSource: Boolean = true): MeasurementCurve {
        identifier(curve.id); require(curve.importedAtMs >= 0) { "Invalid measurement date." }
        note(curve.provenance.rig, 1024, "Measurement rig")
        note(curve.provenance.source, 1024, "Measurement source")
        note(curve.provenance.notes, 4096, "Measurement notes")
        require(curve.points.size in 2..MeasurementTextImporter.MAX_POINTS) { "Invalid measurement point count." }
        bounded(curve.sourceText, MeasurementTextImporter.MAX_TEXT_BYTES, "Measurement source")
        val points = curve.points.toList()
        val phase = points.first().phaseDegrees != null
        points.forEachIndexed { index, point ->
            require(point.frequencyHz.isFinite() && point.frequencyHz > 0 && point.frequencyHz <= 1_000_000) { "Invalid measurement frequency." }
            range(point.magnitudeDb, -1000.0, 1000.0, "Measurement magnitude")
            require((point.phaseDegrees != null) == phase) { "Measurement phase must be present on every row or none." }
            point.phaseDegrees?.let { range(it, -1e9, 1e9, "Measurement phase") }
            require(index == 0 || point.frequencyHz > points[index - 1].frequencyHz) { "Measurement frequencies must increase strictly." }
        }
        if (checkSource) require(TuningCurveAdapters.parsePoints(curve.sourceText, curve.format, phase) == points) {
            "Measurement points do not match their preserved source text."
        }
        return curve.copy(name = name(curve.name), points = points)
    }

    fun encodeProject(project: TuningProject): String = gson.toJson(validate(project))
    fun decodeProject(json: String): Result<TuningProject> = runCatching { validate(readProject(strictJson(json, MAX_PROJECT_BYTES))) }

    fun encodeLibrary(projects: List<TuningProject>): String {
        val validated = validateLibrary(projects)
        val envelope = JsonObject().apply { addProperty("schemaVersion", 1); add("projects", gson.toJsonTree(validated)) }
        return bounded(gson.toJson(envelope), MAX_LIBRARY_BYTES, "Tuning library")
    }

    fun decodeLibrary(json: String?): Result<List<TuningProject>> = runCatching {
        if (json == null) return@runCatching emptyList()
        val root = objectWith(strictJson(json, MAX_LIBRARY_BYTES), setOf("schemaVersion", "projects"), "Tuning library")
        require(integer(root, "schemaVersion") == 1L) { "Unsupported tuning library version." }
        validateLibrary(array(root, "projects", MAX_PROJECTS).map(::readProject))
    }

    fun upsert(projects: List<TuningProject>, project: TuningProject): Result<List<TuningProject>> = runCatching {
        val existing = validateLibrary(projects)
        val next = validate(project)
        val result = if (existing.any { it.id == next.id }) existing.map { if (it.id == next.id) next else it } else existing + next
        encodeLibrary(result) // validate before writing.
        result
    }

    fun delete(projects: List<TuningProject>, id: String): Result<List<TuningProject>> = runCatching {
        identifier(id)
        validateLibrary(projects).filterNot { it.id == id }.also { encodeLibrary(it) }
    }

    // metadata changes do not invalidate a fit.
    fun inputFingerprint(project: TuningProject): String {
        val config = gson.toJsonTree(project.config).asJsonObject
        val defaults = gson.toJsonTree(TuningFitConfig()).asJsonObject
        extendedConfigKeys.forEach { if (config[it] == defaults[it]) config.remove(it) }
        val input = listOf(project.measurementLeft?.points, project.measurementRight?.points, project.target?.points, config)
        return MessageDigest.getInstance("SHA-256").digest(gson.toJson(input).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun validateLibrary(projects: List<TuningProject>): List<TuningProject> {
        require(projects.size <= MAX_PROJECTS) { "Save up to $MAX_PROJECTS tuning projects." }
        require(projects.map { it.id }.distinct().size == projects.size) { "Duplicate tuning project identifiers." }
        return projects.map(::validate)
    }

    private fun validateFit(fit: TuningFitResult, project: TuningProject): TuningFitResult {
        require(fit.algorithmVersion == 1) { "Unsupported tuning fit version." }
        require(fit.config == project.config && fit.inputFingerprint == inputFingerprint(project)) { "The saved fit is stale; generate it again for these measurements and controls." }
        val expected = when (project.config.channelMode) {
            TuningChannelMode.LEFT -> { require(project.measurementLeft != null); listOf(TuningFitChannel.LEFT) }
            TuningChannelMode.RIGHT -> { require(project.measurementRight != null); listOf(TuningFitChannel.RIGHT) }
            TuningChannelMode.INDEPENDENT -> listOfNotNull(project.measurementLeft?.let { TuningFitChannel.LEFT }, project.measurementRight?.let { TuningFitChannel.RIGHT })
            TuningChannelMode.LINKED_AVERAGE -> { require(project.measurementLeft != null && project.measurementRight != null); listOf(TuningFitChannel.LINKED_AVERAGE) }
        }
        require(expected.isNotEmpty() && fit.channels.map { it.channel } == expected) { "Saved fit channels do not match the selected measurements." }
        val frequencies = fit.frequenciesHz.toList()
        require(frequencies.size in 2..4096 && frequencies.all { it.isFinite() && it >= project.config.minFrequencyHz &&
            it <= project.config.maxFrequencyHz && it < project.config.sampleRate / 2.0 }) { "Invalid fitted frequency grid." }
        require(frequencies.zipWithNext().all { (left, right) -> right > left }) { "Fitted frequencies must increase strictly." }
        require(fit.minFrequencyHz == frequencies.first() && fit.maxFrequencyHz == frequencies.last()) { "Saved fit frequency bounds do not match the grid." }
        range(fit.measuredNormalizationDb, -2000.0, 2000.0, "Measurement normalization")
        range(fit.targetNormalizationDb, -2000.0, 2000.0, "Target normalization")
        range(fit.preampDb.toDouble(), -60.0, 0.0, "Fitted preamp")
        range(fit.peakBoostDb, 0.0, 1000.0, "Peak boost"); range(fit.peakCutDb, 0.0, 1000.0, "Peak cut")
        require(fit.peakBoostDb <= maxOf(project.config.maxBoostDb, project.config.leftLimits?.maxBoostDb ?: 0.0, project.config.rightLimits?.maxBoostDb ?: 0.0) + .001 && fit.peakCutDb <= maxOf(project.config.maxCutDb, project.config.leftLimits?.maxCutDb ?: 0.0, project.config.rightLimits?.maxCutDb ?: 0.0) + .001) { "Fit exceeds its boost/cut constraints." }
        range(fit.errorBeforeDb, 0.0, 10_000.0, "Error before"); range(fit.errorAfterDb, 0.0, 10_000.0, "Error after")
        require(fit.notes.size <= 32); fit.notes.forEach { note(it, 4096, "Fit note") }
        require(fit.channels.sumOf { it.bands.size } <= project.config.bandBudget) { "Fit exceeds its total band budget." }
        val channels = fit.channels.map { channel ->
            val config = project.config.forChannel(channel.channel)
            range(channel.errorBeforeDb, 0.0, 10_000.0, "Channel error before"); range(channel.errorAfterDb, 0.0, 10_000.0, "Channel error after")
            channel.bands.forEach { b ->
                ParamBandCodec.validate(b)
                require(b.type == 0 && b.isEnabled && b.filterOrder == 2) { "This fit version supports peak filters only." }
                range(b.freqHz.toDouble(), 10.0, 24_000.0, "Fit band frequency")
                range(b.gainDb.toDouble(), -30.0, 24.0, "Fit band gain"); range(b.q.toDouble(), .1f.toDouble(), 100.0, "Fit band Q")
                require(b.q >= config.minQ - 1e-5 && b.q <= config.maxQ + 1e-5) { "Fit band exceeds its Q constraints." }
            }
            fun curve(values: List<Double>): List<Double> {
                require(values.size == frequencies.size && values.all { it.isFinite() && it in -10_000.0..10_000.0 }) { "Invalid fitted response array." }
                return values.toList()
            }
            require(channel.fittedDb.all { it >= -config.maxCutDb - .001 && it <= config.maxBoostDb + .001 }) { "Fitted curve exceeds its boost/cut constraints." }
            channel.copy(bands = channel.bands.toList(), measuredDb = curve(channel.measuredDb), targetDb = curve(channel.targetDb),
                correctionDb = curve(channel.correctionDb), fittedDb = curve(channel.fittedDb), predictedDb = curve(channel.predictedDb))
        }
        return fit.copy(frequenciesHz = frequencies, channels = channels, notes = fit.notes.toList()).also {
            // verify cached metrics against the filters.
            TuningFitter.verifyGeneratedFit(project, it)
        }
    }

    private fun readProject(element: JsonElement): TuningProject {
        val o = objectWith(element, projectKeys, "Tuning project")
        fun curve(key: String) = nullable(o, key)?.let(::readCurve)
        return TuningProject(string(o, "id"), string(o, "name"), curve("measurementLeft"), curve("measurementRight"), curve("target"),
            string(o, "notes"), readConfig(o["config"]), nullable(o, "generatedFit")?.let(::readFit), int(o, "schemaVersion"),
            integer(o, "createdAtMs"), integer(o, "updatedAtMs"))
    }

    internal fun readCurve(element: JsonElement): MeasurementCurve {
        require(element.isJsonObject) { "Invalid measurement." }
        val o = element.asJsonObject
        require(o.keySet() == curveKeys || o.keySet() == curveKeys + "format") { "Measurement fields are incomplete or unsupported." }
        val p = objectWith(o["provenance"], setOf("rig", "source", "notes"), "Measurement provenance")
        val points = array(o, "points", MeasurementTextImporter.MAX_POINTS).map { value ->
            val point = objectWith(value, setOf("frequencyHz", "magnitudeDb", "phaseDegrees"), "Measurement point")
            FrequencyResponsePoint(number(point, "frequencyHz"), number(point, "magnitudeDb"), nullable(point, "phaseDegrees")?.let(::number))
        }
        return MeasurementCurve(string(o, "id"), string(o, "name"), points, string(o, "sourceText"), integer(o, "importedAtMs"),
            MeasurementProvenance(string(p, "rig"), string(p, "source"), string(p, "notes")),
            if (o.has("format")) enumValueOf<TuningCurveFormat>(string(o, "format")) else TuningCurveFormat.TEXT)
    }

    private fun readConfig(element: JsonElement): TuningFitConfig {
        require(element.isJsonObject) { "Invalid fitting controls." }
        val o = element.asJsonObject
        require(o.keySet().containsAll(configKeys) && (o.keySet() - configKeys - extendedConfigKeys).isEmpty()) { "Fitting fields are incomplete or unsupported." }
        fun optional(key: String, default: Double): Double = if (o.has(key)) number(o, key) else default
        fun optionalNull(key: String): Double? = o[key]?.takeUnless { it.isJsonNull }?.let(::number)
        fun limits(key: String): TuningChannelLimits? = o[key]?.takeUnless { it.isJsonNull }?.let {
            val v = objectWith(it, setOf("maxBoostDb", "maxCutDb", "minQ", "maxQ"), "Channel limits")
            TuningChannelLimits(number(v, "maxBoostDb"), number(v, "maxCutDb"), number(v, "minQ"), number(v, "maxQ"))
        }
        return validateConfig(TuningFitConfig(int(o, "sampleRate"), int(o, "bandBudget"), number(o, "minFrequencyHz"), number(o, "maxFrequencyHz"),
            number(o, "maxBoostDb"), number(o, "maxCutDb"), number(o, "minQ"), number(o, "maxQ"),
            enumValueOf<TuningChannelMode>(string(o, "channelMode")), enumValueOf<TuningNormalization>(string(o, "normalization")), number(o, "smoothingOctaves"),
            optional("bassDb", 0.0), optional("bassFrequencyHz", 105.0), optional("tiltDbPerOctave", 0.0),
            optional("earGainDb", 0.0), optional("earGainFrequencyHz", 2800.0), optional("trebleStartHz", 6000.0),
            optionalNull("trebleMaxBoostDb"), optionalNull("trebleMaxCutDb"), optionalNull("trebleMaxQ"), limits("leftLimits"), limits("rightLimits"),
            optional("leftTrimDb", 0.0), optional("rightTrimDb", 0.0), optional("leftDelayMs", 0.0), optional("rightDelayMs", 0.0)))
    }

    private fun readFit(element: JsonElement): TuningFitResult {
        val o = objectWith(element, fitKeys, "Generated fit")
        fun values(key: String) = array(o, key, 4096).map(::number)
        val channels = array(o, "channels", 2).map { value ->
            val c = objectWith(value, channelKeys, "Fitted channel")
            fun curve(key: String) = array(c, key, 4096).map(::number)
            val bands = array(c, "bands", 64).map { b ->
                require(b.isJsonObject) { "Invalid fitted band." }
                ParamBandCodec.read(b.asJsonObject)
            }
            TuningChannelFit(enumValueOf<TuningFitChannel>(string(c, "channel")), bands, curve("measuredDb"), curve("targetDb"),
                curve("correctionDb"), curve("fittedDb"), curve("predictedDb"), number(c, "errorBeforeDb"), number(c, "errorAfterDb"))
        }
        val notes = array(o, "notes", 32).map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        return TuningFitResult(int(o, "algorithmVersion"), string(o, "inputFingerprint"), readConfig(o["config"]), values("frequenciesHz"), channels,
            number(o, "minFrequencyHz"), number(o, "maxFrequencyHz"), number(o, "measuredNormalizationDb"), number(o, "targetNormalizationDb"),
            number(o, "preampDb").toFloat(), number(o, "peakBoostDb"), number(o, "peakCutDb"), number(o, "errorBeforeDb"), number(o, "errorAfterDb"), notes)
    }

    internal fun strictJson(json: String, limit: Int): JsonElement {
        bounded(json, limit, "Tuning JSON")
        return JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            var count = 0
            fun read(depth: Int): JsonElement {
                require(depth <= 20 && ++count <= 1_500_000) { "Tuning JSON is too complex." }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) { val key = reader.nextName(); require(key.length <= 128 && !has(key)) { "Duplicate or invalid tuning field." }; add(key, read(depth + 1)) }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) add(read(depth + 1)); reader.endArray() }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= MeasurementTextImporter.MAX_TEXT_BYTES) { "Tuning text field is too long." } })
                    JsonToken.NUMBER -> JsonPrimitive(ExactJsonNumber(reader.nextString().also { require(it.length <= 100) { "Tuning number is too long." }; BigDecimal(it) }))
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> error("Invalid tuning JSON.")
                }
            }
            read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after tuning JSON." } }
        }
    }

    private class ExactJsonNumber(private val token: String) : Number() {
        override fun toByte() = toInt().toByte()
        override fun toShort() = toInt().toShort()
        override fun toInt() = BigDecimal(token).toInt()
        override fun toLong() = BigDecimal(token).toLong()
        override fun toFloat() = token.toFloat()
        override fun toDouble() = token.toDouble()
        override fun toString() = token
    }

    private fun objectWith(element: JsonElement?, keys: Set<String>, label: String): JsonObject {
        require(element != null && element.isJsonObject && element.asJsonObject.keySet() == keys) { "$label fields are incomplete or unsupported." }
        return element.asJsonObject
    }
    private fun nullable(o: JsonObject, key: String): JsonElement? = o[key].takeUnless { it.isJsonNull }
    private fun string(o: JsonObject, key: String): String = o[key].let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid $key." }; it.asString }
    private fun number(o: JsonObject, key: String): Double = number(o[key])
    private fun number(value: JsonElement): Double {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.isFinite()) { "Invalid tuning number." }
        return value.asDouble
    }
    private fun integer(o: JsonObject, key: String): Long = o[key].let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Invalid $key." }
        it.asBigDecimal.longValueExact()
    }
    private fun int(o: JsonObject, key: String): Int = integer(o, key).let { require(it in Int.MIN_VALUE..Int.MAX_VALUE); it.toInt() }
    private fun array(o: JsonObject, key: String, max: Int): JsonArray = o[key].let { require(it.isJsonArray && it.asJsonArray.size() <= max) { "Invalid or oversized $key." }; it.asJsonArray }
    private fun identifier(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid tuning identifier." } }
    private fun name(value: String): String = value.trim().also { require(it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl)) { "Use a name between 1 and 80 characters." } }
    private fun note(value: String, limit: Int, label: String) { require(value.length <= limit && value.none { it.isISOControl() && it !in "\n\r\t" }) { "$label is too long or contains invalid characters." } }
    private fun range(value: Double, min: Double, max: Double, label: String) { require(value.isFinite() && value in min..max) { "$label must be between $min and $max." } }
    private fun bounded(value: String, limit: Int, label: String): String = value.also {
        require(it.length <= limit && it.toByteArray(Charsets.UTF_8).size <= limit) { "$label exceeds ${limit / 1024} KiB." }
    }
}
