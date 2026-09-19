package com.aurora.music.data.rules

import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingPresetCodec
import com.aurora.music.data.ProcessingRackCodec
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

data class PresetRuleSession(
    val baseline: ProcessingPreset,
    val applied: ProcessingPreset,
    val ruleId: String,
    val presetId: String,
)

object PresetRuleSessionCodec {
    const val PREFERENCE_KEY = "processing_rule_session_v1"

    fun encode(session: PresetRuleSession): String {
        identifier(session.ruleId); identifier(session.presetId)
        val json = JsonObject().apply {
            addProperty("version", 1)
            addProperty("ruleId", session.ruleId); addProperty("presetId", session.presetId)
            add("baseline", JsonParser.parseString(ProcessingPresetCodec.encode(listOf(session.baseline))))
            add("applied", JsonParser.parseString(ProcessingPresetCodec.encode(listOf(session.applied))))
        }.toString()
        require(json.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Rule recovery state is too large." }
        return json
    }

    fun decode(json: String?): Result<PresetRuleSession?> = runCatching {
        if (json == null) return@runCatching null
        require(json.length <= 2_000_000 && json.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Rule recovery state is too large." }
        val root = ProcessingRackCodec.strictJson(json).asJsonObject
        require(root.keySet() == setOf("version", "ruleId", "presetId", "baseline", "applied")) { "Invalid rule recovery fields." }
        val version = root["version"]
        require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asBigDecimal.intValueExact() == 1) { "Unsupported rule recovery version." }
        fun id(key: String): String {
            val value = root[key]
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid rule recovery identifier." }
            return value.asString.also(::identifier)
        }
        fun snapshot(key: String): ProcessingPreset {
            val library = ProcessingPresetCodec.decode(root[key].toString())
            require(library.error == null && library.presets.size == 1) { library.error ?: "Invalid rule recovery snapshot." }
            return library.presets.single()
        }
        PresetRuleSession(snapshot("baseline"), snapshot("applied"), id("ruleId"), id("presetId"))
    }

    private fun identifier(value: String) = require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Invalid rule recovery identifier." }
}
