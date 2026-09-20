package com.aurora.music.extensions

import com.aurora.music.data.*
import com.google.gson.*
import java.util.UUID

data class ExtensionDescriptor(val component: String, val packageName: String, val uid: Int, val signer: String,
    val name: String, val api: Int, val capabilities: Set<String>, val license: String) {
    val compatible get() = api == 1 && capabilities.isNotEmpty() && capabilities.all { it in ExtensionCodec.CAPABILITIES }
}
data class ExtensionGrant(val component: String, val signer: String, val capabilities: Set<String>,
    val enabled: Boolean, val settings: Map<String, Double> = emptyMap())
data class ExtensionSetting(val id: String, val label: String, val minimum: Double, val maximum: Double, val default: Double)
data class ExtensionManifest(val name: String, val version: String, val license: String, val capabilities: Set<String>,
    val settings: List<ExtensionSetting>)
data class ExtensionEntry(val component: String, val descriptor: ExtensionDescriptor?, val grant: ExtensionGrant?,
    val manifest: ExtensionManifest? = null, val error: String? = null) {
    val name get() = descriptor?.name ?: component.substringBefore('/')
    val enabled get() = descriptor?.compatible == true && grant?.enabled == true &&
        descriptor.signer == grant.signer && descriptor.capabilities == grant.capabilities
}

object ExtensionCodec {
    const val PREFERENCE_KEY = "extension_grants_v1"
    const val MAX_BYTES = 196_608
    val CAPABILITIES = setOf("audio", "media", "metadata")
    private val gson = Gson()
    fun objectValue(json: String): JsonObject = ProcessingRackCodec.strictJson(json, MAX_BYTES, 16_384).let {
        require(it.isJsonObject) { "Extension data must be an object." }; it.asJsonObject
    }
    fun text(o: JsonObject, key: String, limit: Int = 200, optional: Boolean = false): String {
        val value = o[key]
        if (optional && value == null) return ""
        require(value is JsonPrimitive && value.isString) { "Invalid extension field: $key." }
        return value.asString.also { require(it.length <= limit && it.none { c -> c.isISOControl() }) { "Invalid extension text." } }
    }
    fun number(o: JsonObject, key: String, low: Double, high: Double): Double {
        val value = o[key]
        require(value is JsonPrimitive && value.isNumber) { "Invalid extension number." }
        return value.asDouble.also { require(it.isFinite() && it in low..high) { "Extension number is out of range." } }
    }
    fun capabilities(o: JsonObject): Set<String> {
        val values = o["capabilities"]
        require(values is JsonArray && values.size() in 1..3) { "Invalid extension capabilities." }
        val result = values.map { require(it is JsonPrimitive && it.isString); it.asString }.toSet()
        require(result.size == values.size() && result.all { it in CAPABILITIES }) { "Unsupported extension capability." }
        return result
    }
    fun integer(o: JsonObject, key: String, low: Int, high: Int): Int {
        val value = number(o, key, low.toDouble(), high.toDouble())
        require(value == value.toInt().toDouble()) { "Extension field $key must be an integer." }
        return value.toInt()
    }
    fun decode(json: String?): List<ExtensionGrant> {
        if (json.isNullOrBlank()) return emptyList()
        val root = objectValue(json)
        require(number(root, "version", 1.0, 1.0) == 1.0)
        val grants = root["grants"]
        require(grants is JsonArray && grants.size() <= 32) { "Too many extensions." }
        return grants.map {
            require(it.isJsonObject); val o = it.asJsonObject
            val component = text(o, "component", 300)
            require(component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))) { "Invalid extension component." }
            val signer = text(o, "signer", 64)
            require(signer.matches(Regex("[0-9a-f]{64}"))) { "Invalid extension signer." }
            require(o["enabled"] is JsonPrimitive && o["enabled"].asJsonPrimitive.isBoolean)
            val settings = o["settings"]
            require(settings is JsonObject && settings.size() <= 16)
            val values = settings.entrySet().associate { (key, _) ->
                require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}")))
                key to number(settings, key, -1_000_000.0, 1_000_000.0)
            }
            ExtensionGrant(component, signer, capabilities(o), o["enabled"].asBoolean, values)
        }.also { require(it.map(ExtensionGrant::component).distinct().size == it.size) { "Duplicate extensions." } }
    }
    fun encode(grants: List<ExtensionGrant>): String = gson.toJson(mapOf("version" to 1, "grants" to grants)).also { decode(it) }
    fun manifest(o: JsonObject): ExtensionManifest {
        require(number(o, "api", 1.0, 1.0) == 1.0) { "Unsupported extension API." }
        val settings = o["settings"] ?: JsonArray()
        require(settings is JsonArray && settings.size() <= 16)
        val fields = settings.map {
            require(it.isJsonObject); val field = it.asJsonObject
            val id = text(field, "id", 40)
            require(id.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}")))
            val min = number(field, "minimum", -1_000_000.0, 1_000_000.0)
            val max = number(field, "maximum", min, 1_000_000.0)
            ExtensionSetting(id, text(field, "label", 80), min, max, number(field, "default", min, max))
        }
        require(fields.map { it.id }.distinct().size == fields.size)
        return ExtensionManifest(text(o, "name", 80), text(o, "version", 40), text(o, "license", 500), capabilities(o), fields)
    }
    fun compileAudio(data: JsonElement, name: String): ProcessingRack {
        require(data is JsonArray && data.size() in 1..16) { "Audio extensions support 1–16 stages." }
        val nodes = data.mapIndexed { index, element ->
            require(element.isJsonObject); val o = element.asJsonObject
            val id = UUID.randomUUID().toString()
            when (text(o, "type", 20)) {
                "gain" -> {
                    require(o.keySet() == setOf("type", "gainDb"))
                    ProcessingRackNode(id, "Gain ${index + 1}", RackNodeKind.GAIN,
                        audio = AudioPrefs(dspPreampDb = number(o, "gainDb", -60.0, 12.0).toFloat()))
                }
                "stereo" -> {
                    require(o.keySet() == setOf("type", "width", "balance"))
                    ProcessingRackNode(id, "Stereo ${index + 1}", RackNodeKind.STEREO,
                        audio = AudioPrefs(dspWidth = number(o, "width", 0.0, 2.0).toFloat(),
                            dspBalance = number(o, "balance", -1.0, 1.0).toFloat()))
                }
                "crossfeed" -> {
                    require(o.keySet() == setOf("type", "amount"))
                    ProcessingRackNode(id, "Crossfeed ${index + 1}", RackNodeKind.CROSSFEED,
                        audio = AudioPrefs(dspCrossfeed = number(o, "amount", 0.0, 1.0).toFloat()))
                }
                "equalizer" -> {
                    require(o.keySet() == setOf("type", "bands"))
                    val bands = o["bands"]
                    require(bands is JsonArray && bands.size() in 1..64) { "An equalizer supports 1–64 bands." }
                    val filters = bands.map { element ->
                        require(element.isJsonObject); val band = element.asJsonObject
                        require(band.keySet() == setOf("frequency", "gainDb", "q", "shape"))
                        val type = when (text(band, "shape", 20)) {
                            "peak" -> BandType.PEAK
                            "lowShelf" -> BandType.LOW_SHELF
                            "highShelf" -> BandType.HIGH_SHELF
                            else -> error("Unsupported extension filter.")
                        }
                        ParamBand(number(band, "frequency", 10.0, 24_000.0).toFloat(),
                            number(band, "gainDb", -30.0, 30.0).toFloat(), number(band, "q", 0.01, 100.0).toFloat(), type)
                    }
                    ProcessingRackNode(id, "Equalizer ${index + 1}", RackNodeKind.EQ,
                        audio = AudioPrefs(dspGraphicBands = emptyList(), dspParametric = filters))
                }
                else -> error("Unsupported audio extension stage.")
            }
        }
        return ProcessingRackCodec.validate(ProcessingRack(enabled = true, name = name.take(80), nodes = nodes, autoHeadroom = true))
    }
}
