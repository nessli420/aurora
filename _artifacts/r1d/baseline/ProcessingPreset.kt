package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/** Local legacy-engine snapshots. This schema does not claim a new DSP graph or portable assets. */
data class ProcessingPlaybackPrefs(
    val skipSilence: Boolean = false,
    val crossfadeSec: Int = 0,
    val crossfadeCurve: String = "SMOOTH",
    val crossfadeHeadroom: Boolean = true,
    val gapless: Boolean = true,
    val defaultSpeed: Float = 1f,
    val monoAudio: Boolean = false,
    val preferHighRes: Boolean = false,
    val bitPerfectUsb: Boolean = false,
    val independentOutput: Boolean = false,
) {
    companion object {
        fun from(p: PlaybackPrefs) = ProcessingPlaybackPrefs(p.skipSilence, p.crossfadeSec,
            p.crossfadeCurve, p.crossfadeHeadroom, p.gapless, p.defaultSpeed, p.monoAudio,
            p.preferHighRes, p.bitPerfectUsb, p.independentOutput)
    }
}

data class ProcessingSnapshot(
    val audio: AudioPrefs,
    val playback: ProcessingPlaybackPrefs,
    val activeEqProfile: String = "",
)

data class ProcessingSettings(val audio: AudioPrefs, val playback: PlaybackPrefs)

data class ProcessingPreset(
    val id: String,
    val name: String,
    val schemaVersion: Int = ProcessingPresetCodec.SCHEMA_VERSION,
    val createdAtMs: Long,
    val audio: AudioPrefs,
    val playback: ProcessingPlaybackPrefs,
    val activeEqProfile: String = "",
    val irSha256: String = "",
)

data class ProcessingPresetLibrary(val presets: List<ProcessingPreset> = emptyList(), val error: String? = null)
data class ProcessingPresetApplyResult(val presetName: String, val restartRequired: Boolean)

// Only this nullable envelope is deserialized from storage. Nested objects are type-checked in full
// before Gson may construct their non-null Kotlin domain classes; missing values never become zeroes.
private data class ProcessingPresetDto(
    val id: String? = null,
    val name: String? = null,
    val schemaVersion: Int? = null,
    val createdAtMs: Long? = null,
    val audio: JsonObject? = null,
    val playback: JsonObject? = null,
    val activeEqProfile: String? = null,
    val irSha256: String? = null,
)

object ProcessingPresetCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_PRESETS = 100
    private const val MAX_JSON_CHARS = 2_000_000
    private val gson = Gson()
    private val audioShape = gson.toJsonTree(AudioPrefs()).asJsonObject
    private val playbackShape = gson.toJsonTree(ProcessingPlaybackPrefs()).asJsonObject
    private val bandShape = gson.toJsonTree(ParamBand(1000f, 0f, 1f)).asJsonObject

    fun name(value: String): String = value.trim().also {
        require(it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl)) {
            "Use a preset name between 1 and 80 characters."
        }
    }

    fun decode(json: String?): ProcessingPresetLibrary {
        if (json.isNullOrBlank()) return ProcessingPresetLibrary()
        return try {
            require(json.length <= MAX_JSON_CHARS) { "Saved presets exceed the supported size." }
            val root = JsonParser.parseString(json)
            require(root.isJsonArray) { "Saved presets are not a list." }
            require(root.asJsonArray.size() <= MAX_PRESETS) { "Too many saved presets." }
            val presets = root.asJsonArray.map { read(it) }
            require(presets.map { it.id }.distinct().size == presets.size) { "Duplicate preset identifiers." }
            ProcessingPresetLibrary(presets)
        } catch (e: Exception) {
            ProcessingPresetLibrary(error = "Saved presets could not be read: ${e.message ?: "invalid data"}")
        }
    }

    fun encode(presets: List<ProcessingPreset>): String {
        require(presets.size <= MAX_PRESETS) { "You can save up to $MAX_PRESETS presets." }
        val json = gson.toJson(presets)
        val decoded = decode(json)
        require(decoded.error == null) { decoded.error.orEmpty() }
        return json
    }

    private fun read(element: JsonElement): ProcessingPreset {
        require(element.isJsonObject) { "A saved preset is not an object." }
        val o = element.asJsonObject
        require(number(o, "schemaVersion").toInt() == SCHEMA_VERSION &&
            number(o, "schemaVersion") == SCHEMA_VERSION.toDouble()) { "Unsupported preset schema version." }
        string(o, "id"); string(o, "name"); number(o, "createdAtMs")
        string(o, "activeEqProfile"); string(o, "irSha256")
        val dto = gson.fromJson(o, ProcessingPresetDto::class.java)
        val id = requireNotNull(dto.id)
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid preset identifier." }
        val title = name(requireNotNull(dto.name))
        val created = requireNotNull(dto.createdAtMs)
        require(created >= 0 && number(o, "createdAtMs") == created.toDouble()) { "Invalid preset creation time." }
        val audio = requireNotNull(dto.audio) { "Missing audio settings." }
        val playback = requireNotNull(dto.playback) { "Missing playback settings." }
        validateShape(audio, audioShape)
        validateShape(playback, playbackShape)
        val a = gson.fromJson(audio, AudioPrefs::class.java)
        val p = gson.fromJson(playback, ProcessingPlaybackPrefs::class.java)
        validate(a, p)
        val hash = requireNotNull(dto.irSha256)
        require(hash.isEmpty() || hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid impulse response checksum." }
        require(dto.activeEqProfile.orEmpty().length <= 500) { "Profile label is too long." }
        return ProcessingPreset(id, title, SCHEMA_VERSION, created, a, p,
            dto.activeEqProfile.orEmpty(), hash)
    }

    private fun validateShape(actual: JsonObject, expected: JsonObject) {
        require(actual.keySet() == expected.keySet()) { "Preset settings are incomplete or unsupported." }
        expected.entrySet().forEach { (key, template) ->
            val v = actual.get(key)
            when {
                template.isJsonArray -> {
                    require(v.isJsonArray && v.asJsonArray.size() <= 128) { "Invalid $key bands." }
                    v.asJsonArray.forEach { band ->
                        if (key == "dspParametric") {
                            require(band.isJsonObject) { "Invalid parametric band." }
                            validateShape(band.asJsonObject, bandShape)
                        } else {
                            require(band.isJsonPrimitive && band.asJsonPrimitive.isNumber && band.asDouble.isFinite()) { "Invalid $key gain." }
                            if (key == "eqBands") require(band.asDouble == band.asInt.toDouble()) { "Invalid system EQ gain." }
                        }
                    }
                }
                template.asJsonPrimitive.isBoolean -> require(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) { "Invalid $key switch." }
                template.asJsonPrimitive.isString -> string(actual, key)
                else -> {
                    val n = number(actual, key)
                    if (!template.toString().contains('.')) require(n == v.asInt.toDouble()) { "Invalid $key value." }
                }
            }
        }
    }

    private fun string(o: JsonObject, key: String): String {
        val v = o.get(key)
        require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) { "Missing or invalid $key." }
        return v.asString.also { require(it.length <= 4096) { "$key is too long." } }
    }

    private fun number(o: JsonObject, key: String): Double {
        val v = o.get(key)
        require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) { "Missing or invalid $key." }
        return v.asDouble.also { require(it.isFinite()) { "Invalid $key number." } }
    }

    private fun validate(a: AudioPrefs, p: ProcessingPlaybackPrefs) {
        fun range(v: Float, low: Float, high: Float) = v.isFinite() && v in low..high
        require(a.dspMode in 0..2 && a.replayGain in 0..2 && a.dspGraphicLayout in 0..2) { "Unsupported processing mode." }
        require(a.eqPreset >= -1 && a.eqBands.all { it in -2400..2400 } && a.bassBoost in 0..1000 &&
            a.virtualizer in 0..1000 && a.loudnessGain in 0..3000) { "Invalid system effect value." }
        require(a.dspGraphicBands.all { range(it, -24f, 24f) } && a.dspParametric.all {
            range(it.freqHz, 10f, 24000f) && range(it.gainDb, -30f, 30f) && range(it.q, 0.01f, 100f) && it.type in 0..2
        }) { "Invalid equalizer band." }
        require(range(a.dspPreampDb, -60f, 24f) && range(a.dspBalance, -1f, 1f) &&
            range(a.dspWidth, 0f, 2f) && range(a.dspCrossfeed, 0f, 1f) && range(a.dspSaturation, 0f, 1f)) { "Invalid processing gain." }
        require(range(a.dspLimiterCeilingDb, -60f, 0f) && range(a.dspCompThreshDb, -100f, 0f) &&
            range(a.dspCompRatio, 1f, 100f) && range(a.dspConvMakeupDb, -60f, 24f)) { "Invalid dynamics value." }
        require(range(a.dspDelayLeftMs, 0f, 100f) && range(a.dspDelayRightMs, 0f, 100f) &&
            range(a.dspTrimLeftDb, -60f, 24f) && range(a.dspTrimRightDb, -60f, 24f)) { "Invalid channel adjustment." }
        require(p.crossfadeSec in 0..30 && p.crossfadeCurve in setOf("LINEAR", "SMOOTH", "POWER") &&
            range(p.defaultSpeed, 0.25f, 4f)) { "Invalid playback processing value." }
    }
}
