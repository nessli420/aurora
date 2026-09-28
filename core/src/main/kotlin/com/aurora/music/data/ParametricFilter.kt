package com.aurora.music.data

import com.google.gson.JsonObject

object BandType { const val PEAK = 0; const val LOW_SHELF = 1; const val HIGH_SHELF = 2 }

enum class FilterType(val code: Int, val id: String, val label: String, val hasGain: Boolean = false, val hasQ: Boolean = true) {
    PEAK(0, "peak.v1", "Peak", true),
    LOW_SHELF(1, "low_shelf.v1", "Low shelf", true),
    HIGH_SHELF(2, "high_shelf.v1", "High shelf", true),
    LOW_PASS(3, "low_pass.v1", "Low pass"),
    HIGH_PASS(4, "high_pass.v1", "High pass"),
    BAND_PASS(5, "band_pass.v1", "Band pass"),
    NOTCH(6, "notch.v1", "Notch"),
    ALL_PASS(7, "all_pass.v1", "All pass"),
    TILT(8, "tilt.v1", "Tilt", true),
    BUTTERWORTH_LOW_PASS(9, "butterworth_low_pass.v1", "Butterworth low pass", hasQ = false),
    BUTTERWORTH_HIGH_PASS(10, "butterworth_high_pass.v1", "Butterworth high pass", hasQ = false),
    LINKWITZ_RILEY_LOW_PASS(11, "linkwitz_riley_low_pass.v1", "Linkwitz-Riley low pass", hasQ = false),
    LINKWITZ_RILEY_HIGH_PASS(12, "linkwitz_riley_high_pass.v1", "Linkwitz-Riley high pass", hasQ = false),
    CUSTOM_BIQUAD(13, "custom_biquad.v1", "Custom biquad", hasQ = false);

    val orders: List<Int> get() = when (this) {
        BUTTERWORTH_LOW_PASS, BUTTERWORTH_HIGH_PASS -> listOf(2, 4, 6, 8)
        LINKWITZ_RILEY_LOW_PASS, LINKWITZ_RILEY_HIGH_PASS -> listOf(4, 8)
        else -> listOf(2)
    }
    fun sectionCount(order: Int = 2): Int = when {
        this == TILT -> 2
        !hasQ -> order / 2
        else -> 1
    }
    companion object {
        fun fromLegacy(code: Int): FilterType = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unsupported filter type.")
        fun fromId(id: String): FilterType = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported filter identifier.")
    }
}

data class ParamBand(
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
    val type: Int = BandType.PEAK,
    val filterId: String? = FilterType.fromLegacy(type).id,
    val enabled: Boolean? = true,
    val order: Int? = 2,
    val coefficients: List<Double>? = null,
) {
    val filterType: FilterType get() = filterId?.let(FilterType::fromId) ?: FilterType.fromLegacy(type)
    val isEnabled: Boolean get() = enabled ?: true
    val filterOrder: Int get() = order ?: 2
}

object ParamBandCodec {
    private val legacyKeys = setOf("freqHz", "gainDb", "q", "type")
    private val keys = legacyKeys + setOf("filterId", "enabled", "order")

    fun validate(band: ParamBand): ParamBand {
        require(band.freqHz.isFinite() && band.freqHz in 10f..24000f &&
            band.gainDb.isFinite() && band.gainDb in -30f..30f &&
            band.q.isFinite() && band.q in .01f..100f) { "Invalid equalizer band." }
        val type = band.filterType
        require(type.code == band.type) { "Filter identifier and legacy type disagree." }
        require(band.filterOrder in type.orders) { "Unsupported filter order." }
        require(type.hasGain || band.gainDb == 0f) { "This filter does not use gain." }
        if (type == FilterType.CUSTOM_BIQUAD) validateCoefficients(requireNotNull(band.coefficients) { "Enter five biquad coefficients." })
        else require(band.coefficients == null) { "Only custom biquads accept coefficients." }
        return band.copy(filterId = type.id, enabled = band.isEnabled, order = band.filterOrder, coefficients = band.coefficients?.toList())
    }

    fun validateCoefficients(values: List<Double>): List<Double> {
        require(values.size == 5 && values.all { it.isFinite() && kotlin.math.abs(it) <= 1e6 }) { "Enter five finite coefficients between -1,000,000 and 1,000,000." }
        val a1 = values[3]; val a2 = values[4]
        require(kotlin.math.abs(a2) < 1.0 && 1.0 + a1 + a2 > 0.0 && 1.0 - a1 + a2 > 0.0) { "Biquad poles must lie inside the unit circle." }
        val f1 = a1.toFloat().toDouble(); val f2 = a2.toFloat().toDouble()
        require(kotlin.math.abs(f2) < 1.0 && 1.0 + f1 + f2 > 0.0 && 1.0 - f1 + f2 > 0.0) { "Biquad must remain stable at float precision." }
        return values
    }

    fun read(o: JsonObject): ParamBand {
        require(o.keySet() == legacyKeys || o.keySet() == keys || o.keySet() == keys + "coefficients") { "Equalizer band fields are incomplete or unsupported." }
        fun number(key: String): Double {
            val v = o.get(key)
            require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.asDouble.isFinite()) { "Invalid band $key." }
            require(v.asFloat.isFinite() && (v.asFloat != 0f || v.asBigDecimal.signum() == 0)) { "Band $key cannot be represented." }
            return v.asDouble
        }
        val code = number("type").also { require(it == it.toInt().toDouble()) { "Invalid filter type." } }.toInt()
        val type = FilterType.fromLegacy(code)
        val extended = o.has("filterId")
        require(extended || code in 0..2) { "New filters require a stable identifier." }
        if (extended) {
            require(o.get("filterId").isJsonPrimitive && o.get("filterId").asJsonPrimitive.isString) { "Invalid filter identifier." }
            require(o.get("enabled").isJsonPrimitive && o.get("enabled").asJsonPrimitive.isBoolean) { "Invalid filter bypass." }
        }
        val order = if (extended) number("order").also { require(it == it.toInt().toDouble()) { "Invalid filter order." } }.toInt() else 2
        val coefficients = if (o.has("coefficients") && !o.get("coefficients").isJsonNull) {
            require(o.get("coefficients").isJsonArray) { "Invalid biquad coefficients." }
            o.getAsJsonArray("coefficients").map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite() &&
                    (it.asDouble != 0.0 || it.asBigDecimal.signum() == 0)) { "Invalid biquad coefficient." }
                it.asDouble
            }
        } else null
        return validate(ParamBand(number("freqHz").toFloat(), number("gainDb").toFloat(), number("q").toFloat(), code,
            if (extended) o.get("filterId").asString else type.id, if (extended) o.get("enabled").asBoolean else true, order, coefficients))
    }

    fun encodePreference(bands: List<ParamBand>): String = bands.joinToString(";") {
        val b = validate(it)
        "${b.freqHz}:${b.gainDb}:${b.q}:${b.filterId}:${b.isEnabled}:${b.filterOrder}" +
            (b.coefficients?.joinToString(",")?.let { values -> ":$values" } ?: "")
    }

    fun decodePreference(value: String?): List<ParamBand> = value.orEmpty().split(';').filter { it.isNotBlank() }.mapNotNull { row ->
        runCatching {
            val parts = row.split(':')
            require(parts.size in setOf(3, 4, 6, 7))
            val type = if (parts.size >= 6) FilterType.fromId(parts[3]) else FilterType.fromLegacy(parts.getOrNull(3)?.toInt() ?: 0)
            require(parts.size >= 6 || type.code in 0..2)
            validate(ParamBand(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), type.code, type.id,
                if (parts.size >= 6) parts[4].toBooleanStrict() else true, if (parts.size >= 6) parts[5].toInt() else 2,
                if (parts.size == 7) parts[6].split(',').map(String::toDouble) else null))
        }.getOrNull()
    }
}
