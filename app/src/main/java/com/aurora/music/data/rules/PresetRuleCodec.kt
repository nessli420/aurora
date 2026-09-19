package com.aurora.music.data.rules

import com.aurora.music.data.routes.ProcessingRouteCodec
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.UUID

object PresetRuleCodec {
    const val PREFERENCE_KEY = "processing_rules_v1"
    const val MAX_RULES = 64
    const val MAX_CONDITIONS = 16
    private const val MAX_BYTES = 256 * 1024

    fun validate(set: PresetRuleSet): PresetRuleSet {
        require(set.rules.size <= MAX_RULES) { "Up to 64 preset rules are supported." }
        require(set.rules.map { it.id }.distinct().size == set.rules.size) { "Duplicate rule identifier." }
        set.rules.forEach { rule ->
            identifier(rule.id); identifier(rule.presetId); text(rule.name, 80)
            require(rule.priority in -1000..1000) { "Priority must be between -1000 and 1000." }
            require(rule.conditions.size in 1..MAX_CONDITIONS) { "Add between 1 and 16 conditions." }
            require(rule.conditions.distinct().size == rule.conditions.size) { "Duplicate rule condition." }
            rule.conditions.forEach { condition ->
                if (condition.field == RuleField.SAMPLE_RATE) {
                    require(condition.values.isEmpty() && (condition.minRateHz != null || condition.maxRateHz != null)) { "Set a sample-rate range." }
                    listOfNotNull(condition.minRateHz, condition.maxRateHz).forEach {
                        require(it in 1..1_536_000) { "Sample rate must be between 1 and 1536000 Hz." }
                    }
                    require(condition.minRateHz == null || condition.maxRateHz == null || condition.minRateHz <= condition.maxRateHz) { "Minimum rate exceeds maximum." }
                } else {
                    require(condition.minRateHz == null && condition.maxRateHz == null) { "Unexpected sample-rate range." }
                    require(condition.values.size in 1..16 && condition.values.distinct().size == condition.values.size) { "Set between 1 and 16 distinct values." }
                    condition.values.forEach { value ->
                        text(value, 512, condition.field == RuleField.HEADPHONES)
                        when (condition.field) {
                            RuleField.ROUTE -> require(ProcessingRouteCodec.validKey(value)) { "Select a confirmed output." }
                            RuleField.SOURCE -> require(RuleSource.entries.any { it.name == value }) { "Invalid source condition." }
                            RuleField.CONTEXT -> require(RulePlaybackMode.entries.any { it.name == value }) { "Invalid playback context." }
                            else -> Unit
                        }
                    }
                }
            }
        }
        return set
    }

    fun encode(set: PresetRuleSet): String {
        validate(set)
        val json = JsonObject().apply {
            addProperty("version", 1); addProperty("enabled", set.enabled); addProperty("manualHold", set.manualHold)
            add("rules", JsonArray().apply { set.rules.forEach { rule -> add(JsonObject().apply {
                addProperty("id", rule.id); addProperty("name", rule.name); addProperty("presetId", rule.presetId)
                addProperty("enabled", rule.enabled); addProperty("priority", rule.priority); addProperty("match", rule.match.name)
                add("conditions", JsonArray().apply { rule.conditions.forEach { condition -> add(JsonObject().apply {
                    addProperty("field", condition.field.name)
                    if (condition.field == RuleField.SAMPLE_RATE) {
                        condition.minRateHz?.let { addProperty("minRateHz", it) }
                        condition.maxRateHz?.let { addProperty("maxRateHz", it) }
                    } else add("values", JsonArray().apply { condition.values.forEach { add(it) } })
                }) } })
            }) } })
        }.toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preset rules exceed 256 KiB." }
        return json
    }

    fun decode(json: String?): Result<PresetRuleSet> = runCatching {
        if (json == null) return@runCatching PresetRuleSet()
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preset rules exceed 256 KiB." }
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            fun scan(depth: Int) {
                require(depth <= 7) { "Preset rules are too complex." }
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val names = mutableSetOf<String>()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(key.length <= 32 && names.size < 16 && names.add(key)) { "Invalid or duplicate rule field." }
                            scan(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray(); var count = 0
                        while (reader.hasNext()) { require(++count <= MAX_RULES) { "Too many rule values." }; scan(depth + 1) }
                        reader.endArray()
                    }
                    JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= 512) { "Rule value is too long." }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    else -> error("Invalid rule value.")
                }
            }
            scan(0); require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected rule data." }
        }
        val root = JsonParser.parseString(json).asJsonObject
        fields(root, setOf("version", "enabled", "manualHold", "rules"))
        require(integer(root, "version") == 1) { "Unsupported preset rule version." }
        require(root["rules"].isJsonArray) { "Invalid preset rule list." }
        val rules = root["rules"].asJsonArray.map { raw ->
            val o = raw.asJsonObject
            fields(o, setOf("id", "name", "presetId", "enabled", "priority", "match", "conditions"))
            require(o["conditions"].isJsonArray) { "Invalid rule conditions." }
            val conditions = o["conditions"].asJsonArray.map { value ->
                val c = value.asJsonObject
                val field = RuleField.valueOf(string(c, "field"))
                if (field == RuleField.SAMPLE_RATE) {
                    require(c.keySet().all { it in setOf("field", "minRateHz", "maxRateHz") }) { "Invalid rate condition." }
                    PresetRuleCondition(field, minRateHz = c.takeIf { it.has("minRateHz") }?.let { integer(it, "minRateHz") },
                        maxRateHz = c.takeIf { it.has("maxRateHz") }?.let { integer(it, "maxRateHz") })
                } else {
                    fields(c, setOf("field", "values")); require(c["values"].isJsonArray) { "Invalid condition values." }
                    PresetRuleCondition(field, c["values"].asJsonArray.map { entry ->
                        require(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) { "Invalid condition text." }; entry.asString
                    })
                }
            }
            PresetRule(string(o, "id"), string(o, "name"), string(o, "presetId"), boolean(o, "enabled"), integer(o, "priority"),
                RuleMatch.valueOf(string(o, "match")), conditions)
        }
        validate(PresetRuleSet(boolean(root, "enabled"), boolean(root, "manualHold"), rules))
    }

    private fun fields(o: JsonObject, expected: Set<String>) = require(o.keySet() == expected) { "Invalid rule fields." }
    private fun string(o: JsonObject, key: String): String {
        val value = o[key]; require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid rule text." }
        return value.asString
    }
    private fun boolean(o: JsonObject, key: String): Boolean {
        val value = o[key]; require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid rule flag." }
        return value.asBoolean
    }
    private fun integer(o: JsonObject, key: String): Int {
        val value = o[key]; require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid rule number." }
        return value.asBigDecimal.intValueExact()
    }
    private fun identifier(value: String) = require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Invalid rule identifier." }
    private fun text(value: String, maximum: Int, empty: Boolean = false) = require(value.length <= maximum &&
        (empty || value.isNotBlank()) && value.none(Char::isISOControl) && value == value.trim()) { "Invalid rule text." }
}
