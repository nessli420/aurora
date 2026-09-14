package com.aurora.music.data

import java.util.Locale
import java.util.UUID

/**
 * Strict, bounded Equalizer APO subset for rack interchange, independent of the legacy AutoEQ parser.
 * Supports one Preamp and PK/LSC/HSC filters with explicit Q. OFF rows are rejected because ParsedEq
 * cannot retain their disabled state. Channel selection, includes, expressions, bandwidth and shelf
 * slope/corner variants are also rejected rather than changing their meaning.
 * Format reference: https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/
 */
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
            if (tokens.firstOrNull().equals("OFF", true))
                fail(line, "OFF filters cannot be preserved; remove or comment out disabled rows before importing.")
            if (tokens.size != 10 || !tokens[0].equals("ON", true) || !tokens[2].equals("Fc", true) ||
                !tokens[4].equals("Hz", true) || !tokens[5].equals("Gain", true) ||
                !tokens[7].equals("dB", true) || !tokens[8].equals("Q", true))
                fail(line, "Expected Filter [number]: ON <PK|LSC|HSC> Fc <frequency> Hz Gain <gain> dB Q <Q>.")
            val type = when (tokens[1].uppercase(Locale.ROOT)) {
                "PK" -> BandType.PEAK
                "LSC" -> BandType.LOW_SHELF
                "HSC" -> BandType.HIGH_SHELF
                else -> fail(line, "Unsupported filter type '${tokens[1]}'; use PK, LSC or HSC with explicit Q.")
            }
            val frequency = number(tokens[3], line, "Frequency", 10f, 24000f)
            val gain = number(tokens[6], line, "Gain", -30f, 30f)
            val q = number(tokens[9], line, "Q", 0.1f, 100f)
            if (bands.size >= ProcessingRackCodec.MAX_PARAMETRIC_BANDS)
                fail(line, "A profile supports up to ${ProcessingRackCodec.MAX_PARAMETRIC_BANDS} active filters.")
            bands += ParamBand(frequency, gain, q, type)
        }
        if (!sawCommand) fail(1, "No Preamp or active filters found.")
        ParsedEq(preamp, bands.toList())
    }

    /** Float.toString is locale-independent and round-trips every accepted Float without rounding it. */
    fun encode(profile: ParsedEq): String {
        val valid = validate(profile)
        return buildString {
            append("Preamp: ").append(valid.preampDb.toString()).append(" dB\n")
            valid.bands.forEachIndexed { index, band ->
                val type = when (band.type) {
                    BandType.PEAK -> "PK"
                    BandType.LOW_SHELF -> "LSC"
                    else -> "HSC" // validate has already rejected unknown types.
                }
                append("Filter ").append(index + 1).append(": ON ").append(type)
                    .append(" Fc ").append(band.freqHz.toString()).append(" Hz Gain ")
                    .append(band.gainDb.toString()).append(" dB Q ").append(band.q.toString()).append('\n')
            }
        }
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
        require(existingBands + valid.bands.size <= ProcessingRackCodec.MAX_PARAMETRIC_BANDS) {
            "This import exceeds the rack's ${ProcessingRackCodec.MAX_PARAMETRIC_BANDS}-band parametric limit."
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
        ProcessingPresetCodec.validateAudio(AudioPrefs(dspPreampDb = profile.preampDb, dspParametric = profile.bands))
        require(profile.bands.all { it.q >= 0.1f }) {
            "Q below 0.1 would be clamped during playback; use Q values from 0.1 to 100."
        }
        return profile.copy(bands = profile.bands.toList())
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
