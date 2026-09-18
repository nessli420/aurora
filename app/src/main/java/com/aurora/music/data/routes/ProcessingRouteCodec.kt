package com.aurora.music.data.routes

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.UUID

object ProcessingRouteCodec {
    const val PREFERENCE_KEY = "processing_routes_v1"
    private val routePattern = Regex("android:[0-9]{1,3}:[0-9a-f]{64}")

    fun validKey(key: String) = routePattern.matches(key)

    fun validate(rules: ProcessingRouteRules): ProcessingRouteRules {
        require(rules.bindings.size <= 128 && rules.headphones.size <= 128 && rules.manual.size <= 128) { "Too many output rules." }
        require(rules.bindings.map { it.routeKey to it.headphones }.distinct().size == rules.bindings.size) { "Duplicate output rule." }
        rules.bindings.forEach {
            require(validKey(it.routeKey)) { "Invalid output identity." }
            text(it.routeLabel, false); text(it.headphones, true)
            require(runCatching { UUID.fromString(it.presetId).toString() == it.presetId }.getOrDefault(false)) { "Invalid preset identifier." }
        }
        rules.headphones.forEach { (key, value) -> require(validKey(key)) { "Invalid output identity." }; text(value, true) }
        require(rules.manual.all(::validKey)) { "Invalid manual output identity." }
        return rules
    }

    fun encode(rules: ProcessingRouteRules): String {
        validate(rules)
        return JsonObject().apply {
            addProperty("version", 1); addProperty("enabled", rules.enabled)
            add("bindings", JsonArray().apply { rules.bindings.forEach { binding -> add(JsonObject().apply {
                addProperty("routeKey", binding.routeKey); addProperty("routeLabel", binding.routeLabel)
                addProperty("headphones", binding.headphones); addProperty("presetId", binding.presetId)
            }) } })
            add("headphones", JsonObject().apply { rules.headphones.forEach { (key, value) -> addProperty(key, value) } })
            add("manual", JsonArray().apply { rules.manual.sorted().forEach(::add) })
        }.toString()
    }

    fun decode(json: String?): Result<ProcessingRouteRules> = runCatching {
        if (json == null) return@runCatching ProcessingRouteRules()
        require(json.length <= 256 * 1024) { "Output rules exceed 256 KiB." }
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            fun scan(depth: Int) {
                require(depth <= 5) { "Output rules are too complex." }
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val names = mutableSetOf<String>()
                        while (reader.hasNext()) { require(names.add(reader.nextName())) { "Duplicate output field." }; scan(depth + 1) }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); var count = 0
                        while (reader.hasNext()) { require(++count <= 128); scan(depth + 1) }; reader.endArray() }
                    JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= 256)
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    else -> error("Invalid output rules.")
                }
            }
            scan(0); require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet() == setOf("version", "enabled", "bindings", "headphones", "manual")) { "Invalid output rule fields." }
        require(root["version"].isJsonPrimitive && root["version"].asJsonPrimitive.isNumber && root["version"].asInt == 1 && root["version"].asDouble == 1.0) { "Unsupported output rule version." }
        require(root["enabled"].isJsonPrimitive && root["enabled"].asJsonPrimitive.isBoolean)
        fun string(o: JsonObject, key: String): String { val value = o[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isString); return value.asString }
        require(root["bindings"].isJsonArray && root["headphones"].isJsonObject && root["manual"].isJsonArray)
        val bindings = root["bindings"].asJsonArray.map { raw ->
            val o = raw.asJsonObject
            require(o.keySet() == setOf("routeKey", "routeLabel", "headphones", "presetId"))
            RoutePresetBinding(string(o, "routeKey"), string(o, "routeLabel"), string(o, "headphones"), string(o, "presetId"))
        }
        val headphones = root["headphones"].asJsonObject.entrySet().associate { (key, value) ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString); key to value.asString
        }
        val manual = root["manual"].asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        require(manual.distinct().size == manual.size) { "Duplicate manual output." }
        validate(ProcessingRouteRules(root["enabled"].asBoolean, bindings, headphones, manual.toSet()))
    }

    private fun text(value: String, empty: Boolean) {
        require(value.length <= 120 && (empty || value.isNotBlank()) && value.none(Char::isISOControl) && value == value.trim()) { "Invalid output label." }
    }
}
