package com.aurora.music.data

import com.google.gson.Gson
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

/** Versioned serial graph. Disabled graphs preserve the existing global processing behavior. */
data class ProcessingRack(
    val schemaVersion: Int = 3,
    val enabled: Boolean = false,
    val name: String = "Legacy chain",
    val nodes: List<ProcessingRackNode> = emptyList(),
    val autoHeadroom: Boolean = false,
) {
    companion object {
        /** Match the old engine's effective selection, including its twelve-parametric-band limit. */
        fun legacy(audio: AudioPrefs, mono: Boolean = false): ProcessingRack {
            val custom = audio.dspMode == DspMode.CUSTOM
            val payload = audio.copy(dspConvIrPath = "", dspConvIrName = "",
                dspGraphicBands = audio.dspGraphicBands.take(31), dspParametric = audio.dspParametric.take(12),
                dspWidth = if (custom && mono) 0f else audio.dspWidth)
            val nodes = mutableListOf(node("legacy-dsp", "Legacy DSP", RackNodeKind.LEGACY_DSP, payload, !custom))
            if (!custom && mono) nodes += node("legacy-mono", "Mono", RackNodeKind.STEREO,
                AudioPrefs(dspWidth = 0f, dspLimiterEnabled = false))
            nodes += node("legacy-convolution", "Convolution", RackNodeKind.CONVOLUTION,
                AudioPrefs(dspConvMakeupDb = audio.dspConvMakeupDb), !audio.dspConvEnabled)
            return ProcessingRack(nodes = nodes)
        }

        /** An editable starting point with deliberate output protection after every other effect. */
        fun recommended(audio: AudioPrefs, mono: Boolean = false): ProcessingRack = ProcessingRack(
            name = "Ordered processing", nodes = listOf(
                node("gain", "Input gain", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = audio.dspPreampDb)),
                node("eq", "Equalizer", RackNodeKind.EQ, AudioPrefs(dspGraphicLayout = audio.dspGraphicLayout,
                    dspGraphicBands = audio.dspGraphicBands.take(31), dspParametric = audio.dspParametric.take(64))),
                node("saturation", "Saturation", RackNodeKind.SATURATION, AudioPrefs(dspSaturation = audio.dspSaturation), audio.dspSaturation == 0f),
                node("stereo", "Stereo and trim", RackNodeKind.STEREO, AudioPrefs(dspBalance = audio.dspBalance,
                    dspWidth = if (mono) 0f else audio.dspWidth, dspTrimLeftDb = audio.dspTrimLeftDb, dspTrimRightDb = audio.dspTrimRightDb)),
                node("crossfeed", "Crossfeed", RackNodeKind.CROSSFEED, AudioPrefs(dspCrossfeed = audio.dspCrossfeed), audio.dspCrossfeed == 0f),
                node("compressor", "Compressor", RackNodeKind.COMPRESSOR,
                    AudioPrefs(dspCompThreshDb = audio.dspCompThreshDb, dspCompRatio = audio.dspCompRatio), !audio.dspCompEnabled),
                node("delay", "Channel delay", RackNodeKind.DELAY,
                    AudioPrefs(dspDelayLeftMs = audio.dspDelayLeftMs, dspDelayRightMs = audio.dspDelayRightMs),
                    audio.dspDelayLeftMs == 0f && audio.dspDelayRightMs == 0f),
                node("convolution", "Convolution", RackNodeKind.CONVOLUTION,
                    AudioPrefs(dspConvMakeupDb = audio.dspConvMakeupDb), !audio.dspConvEnabled),
                node("limiter", "Final limiter", RackNodeKind.LIMITER,
                    AudioPrefs(dspLimiterCeilingDb = audio.dspLimiterCeilingDb)),
            ),
        )

        private fun node(key: String, name: String, kind: RackNodeKind, audio: AudioPrefs, bypass: Boolean = false) =
            ProcessingRackNode(UUID.nameUUIDFromBytes("aurora-rack-v1:$key".toByteArray(Charsets.UTF_8)).toString(), name, kind,
                bypass = bypass, audio = audio)
    }
}

enum class RackNodeKind { LEGACY_DSP, GAIN, EQ, SATURATION, STEREO, CROSSFEED, COMPRESSOR, LIMITER, DELAY, CONVOLUTION }

enum class RackEqChannel { BOTH, LEFT, RIGHT }

data class ProcessingRackNode(
    val id: String,
    val name: String,
    val kind: RackNodeKind,
    val bypass: Boolean = false,
    val wet: Float = 1f,
    val audio: AudioPrefs = AudioPrefs(),
    val eqChannel: RackEqChannel = RackEqChannel.BOTH,
)

private data class ProcessingRackDto(
    val autoHeadroom: Boolean? = null,
    val schemaVersion: Int? = null, val enabled: Boolean? = null, val name: String? = null, val nodes: JsonArray? = null,
)
private data class ProcessingRackNodeDto(
    val id: String? = null, val name: String? = null, val kind: String? = null,
    val bypass: Boolean? = null, val wet: Float? = null, val audio: JsonObject? = null,
    val eqChannel: String? = null,
)

object ProcessingRackCodec {
    const val PREFERENCE_KEY = "processing_rack_v1"
    const val MAX_NODES = 16
    const val MAX_PARAMETRIC_BANDS = 64
    const val MAX_TOTAL_PARAMETRIC_BANDS = 256
    const val MAX_BIQUAD_SECTIONS = 512
    const val MAX_LEGACY_PARAMETRIC_BANDS = 12
    private val gson = Gson()
    private val legacyRackKeys = setOf("schemaVersion", "enabled", "name", "nodes")
    private val rackKeys = legacyRackKeys + "autoHeadroom"
    private val legacyNodeKeys = setOf("id", "name", "kind", "bypass", "wet", "audio")
    private val nodeKeys = legacyNodeKeys + "eqChannel"

    fun validate(rack: ProcessingRack): ProcessingRack {
        require(rack.schemaVersion == 3) { "Unsupported processing rack version." }
        val title = ProcessingPresetCodec.name(rack.name)
        require(rack.nodes.size <= MAX_NODES) { "A rack supports up to $MAX_NODES nodes." }
        require(rack.nodes.map { it.id }.distinct().size == rack.nodes.size) { "Duplicate rack node identifiers." }
        require(rack.nodes.count { it.kind == RackNodeKind.CONVOLUTION } <= 1) { "A rack supports one convolution node." }
        var bands = 0
        var sections = 0
        val nodes = rack.nodes.map { node ->
            require(runCatching { UUID.fromString(node.id).toString() == node.id }.getOrDefault(false)) { "Invalid rack node identifier." }
            require(node.wet.isFinite() && node.wet in 0f..1f) { "Node wet mix must be between 0 and 1." }
            require(node.kind == RackNodeKind.EQ || node.eqChannel == RackEqChannel.BOTH) {
                "Channel selection is supported by dedicated Equalizer nodes only."
            }
            val audio = ProcessingPresetCodec.validateAudio(node.audio)
            require(audio.dspConvIrPath.isEmpty()) { "Rack nodes use the shared impulse response; local paths are not allowed." }
            if (node.kind == RackNodeKind.EQ || node.kind == RackNodeKind.LEGACY_DSP) {
                require(audio.dspGraphicBands.size <= 31) { "A rack equalizer supports up to 31 graphic bands." }
                if (node.kind == RackNodeKind.LEGACY_DSP) require(audio.dspParametric.size <= MAX_LEGACY_PARAMETRIC_BANDS) {
                    "Legacy DSP supports up to $MAX_LEGACY_PARAMETRIC_BANDS parametric bands; use an Equalizer node for more."
                }
                require(audio.dspParametric.size <= MAX_PARAMETRIC_BANDS) { "An Equalizer supports up to $MAX_PARAMETRIC_BANDS parametric bands." }
                bands += audio.dspParametric.size
                sections += sectionCount(node.copy(audio = audio))
            }
            node.copy(name = ProcessingPresetCodec.name(node.name), audio = audio.copy(eqBands = audio.eqBands.toList(),
                dspGraphicBands = audio.dspGraphicBands.toList(), dspParametric = audio.dspParametric.toList()))
        }
        require(bands <= MAX_TOTAL_PARAMETRIC_BANDS) { "A rack supports up to $MAX_TOTAL_PARAMETRIC_BANDS parametric bands in total." }
        require(sections <= MAX_BIQUAD_SECTIONS) { "This rack exceeds the $MAX_BIQUAD_SECTIONS-section processing budget." }
        return rack.copy(name = title, nodes = nodes)
    }

    fun encode(rack: ProcessingRack): String = gson.toJson(validate(rack))
    fun decode(json: String): Result<ProcessingRack> = runCatching { read(strictJson(json, 256 * 1024, 32_768)) }

    fun sectionCount(node: ProcessingRackNode): Int = when (node.kind) {
        RackNodeKind.LEGACY_DSP -> 79
        RackNodeKind.EQ -> node.audio.dspGraphicBands.size + node.audio.dspParametric.sumOf { it.filterType.sectionCount(it.filterOrder) }
        else -> 0
    }

    internal fun read(element: JsonElement): ProcessingRack {
        require(element.isJsonObject) { "Processing rack must be an object." }
        val root = element.asJsonObject
        val version = number(root, "schemaVersion")
        require(root.keySet() == if (version < 3.0) legacyRackKeys else rackKeys) { "Processing rack fields are incomplete or unsupported." }
        require(version in setOf(1.0, 2.0, 3.0)) { "Unsupported processing rack version." }
        boolean(root, "enabled"); string(root, "name")
        if (version >= 3.0) boolean(root, "autoHeadroom")
        require(root.get("nodes").isJsonArray && root.getAsJsonArray("nodes").size() <= MAX_NODES) { "Invalid rack nodes." }
        val dto = gson.fromJson(root, ProcessingRackDto::class.java)
        val nodes = requireNotNull(dto.nodes).map { elementNode ->
            require(elementNode.isJsonObject) { "Rack node must be an object." }
            val o = elementNode.asJsonObject
            require(o.keySet() == if (version == 1.0) legacyNodeKeys else nodeKeys) { "Rack node fields are incomplete or unsupported." }
            string(o, "id"); string(o, "name"); val kindName = string(o, "kind")
            val kind = RackNodeKind.entries.firstOrNull { it.name == kindName } ?: error("Unsupported rack node kind.")
            val channel = if (version == 1.0) RackEqChannel.BOTH else {
                val channelName = string(o, "eqChannel")
                RackEqChannel.entries.firstOrNull { it.name == channelName } ?: error("Unsupported EQ channel.")
            }
            boolean(o, "bypass"); val wet = number(o, "wet")
            require(wet in 0.0..1.0) { "Invalid node wet mix." }
            val n = gson.fromJson(o, ProcessingRackNodeDto::class.java)
            ProcessingRackNode(requireNotNull(n.id), requireNotNull(n.name), kind, requireNotNull(n.bypass),
                requireNotNull(n.wet), ProcessingPresetCodec.readAudio(requireNotNull(n.audio)), channel)
        }
        return validate(ProcessingRack(3, requireNotNull(dto.enabled), requireNotNull(dto.name), nodes, dto.autoHeadroom ?: false))
    }

    /** Used by preset and backup decoders too: duplicate fields must not silently overwrite data. */
    internal fun strictJson(json: String, maxChars: Int = 2_000_000, maxValues: Int = 300_000): JsonElement {
        require(json.length <= maxChars) { "Processing metadata exceeds the supported size." }
        return JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            val count = intArrayOf(0)
            fun read(depth: Int): JsonElement {
                require(depth <= 20 && ++count[0] <= maxValues) { "Processing metadata is too complex." }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(key.length <= 128 && !has(key)) { "Invalid or duplicate processing metadata field." }
                            add(key, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                        reader.beginArray(); while (reader.hasNext()) add(read(depth + 1)); reader.endArray()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 4096) { "Processing metadata value is too long." } })
                    JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString().also { require(it.length <= 100) { "Processing number is too long." } }))
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> error("Invalid processing JSON.")
                }
            }
            val result = read(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after processing metadata." }
            result
        }
    }

    private fun string(o: JsonObject, key: String): String {
        val value = o.get(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid rack $key." }
        return value.asString
    }
    private fun number(o: JsonObject, key: String): Double {
        val value = o.get(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.isFinite()) { "Invalid rack $key." }
        return value.asDouble
    }
    private fun boolean(o: JsonObject, key: String) {
        val value = o.get(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid rack $key." }
    }
}

/** Only a live, nonzero convolution node requires the rack's shared asset. */
fun ProcessingRack.requiresImpulseResponse(): Boolean = enabled && nodes.any {
    it.kind == RackNodeKind.CONVOLUTION && !it.bypass && it.wet > 0f
}
