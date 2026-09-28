package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.Locale
import java.util.UUID

object RackEqTextCodec {
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_LINES = 2048
    const val MAX_LINE_CHARS = 4096
    private const val MAX_NUMBER_CHARS = 64
    private val whitespace = Regex("[ \\t]+")
    private val preampHeader = Regex("^Preamp[ \\t]*:[ \\t]*(.*)$", RegexOption.IGNORE_CASE)
    private val filterHeader = Regex("^Filter(?:[ \\t]+[0-9]+)?[ \\t]*:[ \\t]*(.*)$", RegexOption.IGNORE_CASE)
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    fun parse(text: String): Result<ParsedEq> = runCatching {
        if (text.length > MAX_TEXT_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES)
            fail(1, "EQ text exceeds 256 KiB.")
        if (text.removePrefix("\uFEFF").trimStart().startsWith("{")) return@runCatching parseJson(text.removePrefix("\uFEFF"))
        val bands = ArrayList<ParamBand>()
        var preamp = 0f
        var sawPreamp = false
        var sawCommand = false
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            if (line > MAX_LINES) fail(line, "EQ text exceeds $MAX_LINES lines.")
            if (raw.length > MAX_LINE_CHARS) fail(line, "Line exceeds $MAX_LINE_CHARS characters.")
            if (raw.any { it.isISOControl() && it != '\t' }) fail(line, "Unsupported control character.")
            val command = raw.substringBefore('#').trim()
            if (command.isEmpty()) return@forEachIndexed
            sawCommand = true
            val preampMatch = preampHeader.matchEntire(command)
            if (preampMatch != null) {
                if (sawPreamp) fail(line, "Multiple Preamp commands are not supported; supply one combined value.")
                val tokens = preampMatch.groupValues[1].trim().split(whitespace)
                if (tokens.size != 2 || !tokens[1].equals("dB", true)) fail(line, "Expected Preamp: <gain> dB.")
                preamp = number(tokens[0], line, "Preamp", -60f, 24f)
                sawPreamp = true
                return@forEachIndexed
            }
            val filterMatch = filterHeader.matchEntire(command)
                ?: fail(line, "Unsupported or malformed command; use Preamp or Filter with PK, LSC or HSC and Q.")
            val tokens = filterMatch.groupValues[1].trim().split(whitespace)
            if (tokens.size < 5 || !tokens[0].uppercase(Locale.ROOT).let { it == "ON" || it == "OFF" } ||
                !tokens[2].equals("Fc", true) || !tokens[4].equals("Hz", true))
                fail(line, "Expected Filter: ON|OFF <type> Fc <frequency> Hz and filter parameters.")
            val type = when (tokens[1].uppercase(Locale.ROOT)) {
                "PK" -> FilterType.PEAK
                "LSC" -> FilterType.LOW_SHELF
                "HSC" -> FilterType.HIGH_SHELF
                "LPQ" -> FilterType.LOW_PASS
                "HPQ" -> FilterType.HIGH_PASS
                "BP" -> FilterType.BAND_PASS
                "NO" -> FilterType.NOTCH
                "AP" -> FilterType.ALL_PASS
                else -> fail(line, "Unsupported filter type '${tokens[1]}'. Use PK, LSC, HSC, LPQ, HPQ, BP, NO or AP with Q.")
            }
            val frequency = number(tokens[3], line, "Frequency", 10f, 24000f)
            val gain: Float
            val q: Float
            if (type.hasGain) {
                if (tokens.size != 10 || !tokens[5].equals("Gain", true) ||
                    !tokens[7].equals("dB", true) || !tokens[8].equals("Q", true))
                    fail(line, "This filter requires Gain <dB> dB Q <Q>.")
                gain = number(tokens[6], line, "Gain", -30f, 30f)
                q = number(tokens[9], line, "Q", .1f, 100f)
            } else {
                if (tokens.size != 7 || !tokens[5].equals("Q", true)) fail(line, "This filter requires Q <Q> and no gain.")
                gain = 0f
                q = number(tokens[6], line, "Q", .1f, 100f)
            }
            if (bands.size >= ProcessingRackCodec.MAX_PARAMETRIC_BANDS)
                fail(line, "A profile supports up to ${ProcessingRackCodec.MAX_PARAMETRIC_BANDS} filters.")
            bands += ParamBand(frequency, gain, q, type.code, enabled = tokens[0].equals("ON", true))
        }
        if (!sawCommand) fail(1, "No Preamp or active filters found.")
        ParsedEq(preamp, bands.toList())
    }

    fun encode(profile: ParsedEq): String {
        val valid = validate(profile)
        if (valid.bands.any { it.type >= FilterType.TILT.code }) return Gson().toJson(JsonObject().apply {
            addProperty("format", "aurora.parametric.v1")
            addProperty("preampDb", valid.preampDb)
            add("bands", Gson().toJsonTree(valid.bands))
        })
        return buildString {
            append("Preamp: ").append(valid.preampDb.toString()).append(" dB\n")
            valid.bands.forEachIndexed { index, band ->
                val type = listOf("PK", "LSC", "HSC", "LPQ", "HPQ", "BP", "NO", "AP")[band.type]
                append("Filter ").append(index + 1).append(if (band.isEnabled) ": ON " else ": OFF ").append(type)
                    .append(" Fc ").append(band.freqHz.toString()).append(" Hz")
                if (band.filterType.hasGain) append(" Gain ").append(band.gainDb.toString()).append(" dB")
                append(" Q ").append(band.q.toString()).append('\n')
            }
        }
    }

    private fun parseJson(text: String): ParsedEq {
        val root = ProcessingRackCodec.strictJson(text, MAX_TEXT_BYTES, 4096)
        require(root.isJsonObject) { "EQ JSON must be an object." }
        val o = root.asJsonObject
        fun number(objectValue: JsonObject, key: String): Float {
            val v = objectValue.get(key)
            require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.asDouble.isFinite()) { "Invalid EQ $key." }
            val value = v.asFloat
            require(value.isFinite() && (value != 0f || v.asBigDecimal.signum() == 0)) { "EQ $key cannot be represented." }
            return value
        }
        if (o.keySet() == setOf("format", "preampDb", "bands")) {
            require(o.get("format").isJsonPrimitive && o.get("format").asJsonPrimitive.isString &&
                o.get("format").asString == "aurora.parametric.v1") { "Unsupported EQ JSON format." }
            require(o.get("bands").isJsonArray) { "EQ bands must be a list." }
            return validate(ParsedEq(number(o, "preampDb"), o.getAsJsonArray("bands").map {
                require(it.isJsonObject) { "Invalid EQ band." }; ParamBandCodec.read(it.asJsonObject)
            }))
        }
        require(o.keySet() == setOf("fs", "filters")) { "Use Aurora EQ JSON or AutoEq PEQ JSON with fs and filters." }
        val sampleRate = number(o, "fs")
        require(sampleRate in 8000f..768000f && sampleRate % 1f == 0f) { "Invalid AutoEq sample rate." }
        require(o.get("filters").isJsonArray) { "AutoEq filters must be a list." }
        return validate(ParsedEq(0f, o.getAsJsonArray("filters").map { element ->
            require(element.isJsonObject) { "Invalid AutoEq filter." }
            val filter = element.asJsonObject
            require(filter.keySet() == setOf("type", "fc", "q", "gain")) { "Unsupported AutoEq filter fields." }
            require(filter.get("type").isJsonPrimitive && filter.get("type").asJsonPrimitive.isString) { "Invalid AutoEq filter type." }
            val type = when (filter.get("type").asString) {
                "PEAKING" -> FilterType.PEAK
                "LOW_SHELF" -> FilterType.LOW_SHELF
                "HIGH_SHELF" -> FilterType.HIGH_SHELF
                else -> error("Unsupported AutoEq filter type.")
            }
            val frequency = number(filter, "fc")
            require(frequency < sampleRate / 2f) { "AutoEq filter is above its design Nyquist frequency." }
            ParamBand(frequency, number(filter, "gain"), number(filter, "q"), type.code)
        }))
    }

    /** Build one complete graph snapshot; the caller persists it once, after preview/confirmation. */
    fun appendToRack(rack: ProcessingRack, profile: ParsedEq, name: String): Result<ProcessingRack> = runCatching {
        val existing = ProcessingRackCodec.validate(rack)
        val valid = validate(profile)
        val title = ProcessingPresetCodec.name(name)
        val extraNodes = if (valid.preampDb != 0f) 2 else 1
        require(existing.nodes.size + extraNodes <= ProcessingRackCodec.MAX_NODES) {
            "This import needs $extraNodes free rack node(s); a rack supports ${ProcessingRackCodec.MAX_NODES}."
        }
        val existingBands = existing.nodes.filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }
            .sumOf { it.audio.dspParametric.size }
        require(existingBands + valid.bands.size <= ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS) {
            "This import exceeds the rack's ${ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS}-band parametric limit."
        }
        val added = ArrayList<ProcessingRackNode>(extraNodes)
        if (valid.preampDb != 0f) added += ProcessingRackNode(UUID.randomUUID().toString(),
            title.take(73) + " · Gain", RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = valid.preampDb))
        added += ProcessingRackNode(UUID.randomUUID().toString(), title, RackNodeKind.EQ,
            audio = AudioPrefs(dspGraphicBands = emptyList(), dspParametric = valid.bands))
        ProcessingRackCodec.validate(existing.copy(nodes = existing.nodes + added))
    }

    private fun validate(profile: ParsedEq): ParsedEq {
        require(profile.bands.size <= ProcessingRackCodec.MAX_PARAMETRIC_BANDS) {
            "A profile supports up to ${ProcessingRackCodec.MAX_PARAMETRIC_BANDS} active filters."
        }
        val audio = ProcessingPresetCodec.validateAudio(AudioPrefs(dspPreampDb = profile.preampDb, dspParametric = profile.bands))
        require(profile.bands.all { it.q >= 0.1f }) {
            "Q below 0.1 would be clamped during playback; use Q values from 0.1 to 100."
        }
        return profile.copy(bands = audio.dspParametric.toList())
    }

    private fun number(token: String, line: Int, label: String, low: Float, high: Float): Float {
        if (token.length > MAX_NUMBER_CHARS || !decimal.matches(token)) fail(line, "$label must be a decimal number.")
        val value = token.toFloatOrNull()
        if (label == "Q" && value != null && value.isFinite() && value < low)
            fail(line, "Q below 0.1 would be clamped during playback; use Q values from 0.1 to 100.")
        if (value == null || !value.isFinite() || value !in low..high)
            fail(line, "$label must be between $low and $high.")
        if (value == 0f && token.takeWhile { it != 'e' && it != 'E' }.any { it in '1'..'9' })
            fail(line, "$label is too small to represent without becoming zero.")
        return value
    }

    private fun fail(line: Int, message: String): Nothing = throw IllegalArgumentException("Line $line: $message")
}
