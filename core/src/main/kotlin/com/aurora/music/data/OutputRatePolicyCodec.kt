package com.aurora.music.data

import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

object OutputRatePolicyCodec {
    const val PREFERENCE_KEY = "output_rate_policy_v1"
    val RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
    fun validate(policy: OutputRatePolicy): OutputRatePolicy {
        require(policy.fixedRate in RATES && policy.maximumRate in RATES) { "Choose a supported output rate." }
        return policy
    }
    fun encode(policy: OutputRatePolicy): String = Gson().toJson(linkedMapOf(
        "schemaVersion" to 2, "mode" to validate(policy).mode.name, "fixedRate" to policy.fixedRate,
        "preserveFamily" to policy.preserveFamily, "maximumRate" to policy.maximumRate,
        "tpdfDither" to policy.tpdfDither, "noiseShaping" to (policy.noiseShaping == true)))

    fun decode(json: String?): Result<OutputRatePolicy> = runCatching {
        if (json == null) return@runCatching OutputRatePolicy()
        require(json.length <= 2048) { "Output policy is too large." }
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            val values = mutableMapOf<String, Any>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key !in values) { "Duplicate output field." }
                values[key] = when (key) {
                    "schemaVersion", "fixedRate", "maximumRate" -> {
                        require(reader.peek() == JsonToken.NUMBER)
                        reader.nextString().toBigDecimal().intValueExact()
                    }
                    "preserveFamily", "tpdfDither", "noiseShaping" -> { require(reader.peek() == JsonToken.BOOLEAN); reader.nextBoolean() }
                    "mode" -> { require(reader.peek() == JsonToken.STRING); reader.nextString() }
                    else -> error("Unsupported output field.")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            val version = values["schemaVersion"]
            require((version == 1 && values.size == 6 && "noiseShaping" !in values) ||
                (version == 2 && values.size == 7 && "noiseShaping" in values))
            validate(OutputRatePolicy(enumValueOf<OutputRateMode>(values["mode"] as String), values["fixedRate"] as Int,
                values["preserveFamily"] as Boolean, values["maximumRate"] as Int, values["tpdfDither"] as Boolean,
                values["noiseShaping"] as? Boolean ?: false))
        }
    }
}
