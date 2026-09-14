package com.aurora.music.data.tuning

import java.util.Locale
import java.util.UUID

/**
 * Strict offline Hz/dB/optional-degree text import. REW numeric export reference:
 * https://www.roomeqwizard.com/help/help_en-GB/html/file.html
 * Non-numeric metadata must be commented. A third unlabeled column requires explicit confirmation.
 */
object MeasurementTextImporter {
    const val MAX_TEXT_BYTES = 512 * 1024
    const val MAX_POINTS = 16_384
    const val MAX_LINES = 20_000
    const val MAX_LINE_CHARS = 4096
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val whitespace = Regex("[ \\t]+")
    private val header = Regex("^(?:freq|frequency)(?:hz)?(?:spl(?:db)?|magnitude(?:db)|amplitude(?:db)|level(?:db)|db)(?:phase(degrees|degree|deg|°)?)?$")

    fun parse(text: String, name: String = "Imported measurement", phaseColumn: Boolean = false,
        provenance: MeasurementProvenance = MeasurementProvenance(), importedAtMs: Long = 0): Result<MeasurementCurve> = runCatching {
        val curve = MeasurementCurve(UUID.randomUUID().toString(), name, parsePoints(text, phaseColumn), text, importedAtMs, provenance)
        TuningProjectCodec.validateCurve(curve, checkSource = false)
    }

    internal fun parsePoints(text: String, phaseColumn: Boolean): List<FrequencyResponsePoint> {
        if (text.length > MAX_TEXT_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES)
            fail(1, "Measurement text exceeds 512 KiB.")
        val points = ArrayList<FrequencyResponsePoint>()
        var expectedColumns: Int? = null
        var headerColumns: Int? = null
        var headerPhaseDegrees = false
        var sawHeader = false
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            if (line > MAX_LINES) fail(line, "Measurement text exceeds $MAX_LINES lines.")
            if (raw.length > MAX_LINE_CHARS) fail(line, "Measurement line exceeds $MAX_LINE_CHARS characters.")
            if (raw.any { it.isISOControl() && it != '\t' }) fail(line, "Unsupported control character.")
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val commented = trimmed.startsWith('#') || trimmed.startsWith('*') || trimmed.startsWith("//")
            val candidate = if (commented) trimmed.removePrefix("//").trimStart('#', '*').trim() else trimmed
            val normalized = candidate.lowercase(Locale.ROOT).replace(Regex("[ \\t,;\\[\\]()\"]"), "")
            val headerMatch = header.matchEntire(normalized)
            if (headerMatch != null) {
                if (points.isNotEmpty() || sawHeader) fail(line, "Repeated or misplaced column header; import one measurement at a time.")
                headerColumns = if (normalized.contains("phase")) 3 else 2
                headerPhaseDegrees = headerMatch.groupValues[1].isNotEmpty()
                if (headerColumns == 3 && !headerPhaseDegrees && !phaseColumn)
                    fail(line, "Confirm that the phase column is in degrees, or label it Phase (degrees).")
                sawHeader = true
                return@forEachIndexed
            }
            if (commented) {
                if (Regex("^(?:freq|frequency)\\s*(?:[\\[(,;]|k?Hz\\b)", RegexOption.IGNORE_CASE).containsMatchIn(candidate.replace("\"", "")))
                    fail(line, "Unsupported column units or labels; use Hz, dB and optional phase in degrees.")
                return@forEachIndexed
            }
            if (candidate.contains(',') && candidate.contains(';')) fail(line, "Mixed delimiters; use decimal points and one column delimiter.")
            val tokens = when {
                candidate.contains(',') -> candidate.split(',')
                candidate.contains(';') -> candidate.split(';')
                else -> candidate.split(whitespace)
            }.map { token ->
                val value = token.trim()
                if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) value.substring(1, value.length - 1) else value
            }
            if (tokens.size !in 2..3 || tokens.any { it.isEmpty() }) fail(line, "Expected frequency in Hz, magnitude in dB and optional phase in degrees.")
            if (tokens.size == 3 && !phaseColumn && !headerPhaseDegrees)
                fail(line, "The third column is ambiguous. Confirm phase in degrees before importing.")
            if (expectedColumns != null && expectedColumns != tokens.size || headerColumns != null && headerColumns != tokens.size)
                fail(line, "Inconsistent column count; phase must be present on every row or none.")
            expectedColumns = tokens.size
            val frequency = number(tokens[0], line, "Frequency")
            val magnitude = number(tokens[1], line, "Magnitude")
            val phase = tokens.getOrNull(2)?.let { number(it, line, "Phase") }
            if (frequency <= 0.0 || frequency > 1_000_000.0) fail(line, "Frequency must be greater than zero and at most 1,000,000 Hz.")
            if (magnitude !in -1000.0..1000.0) fail(line, "Magnitude must be between -1000 and 1000 dB.")
            if (phase != null && phase !in -1e9..1e9) fail(line, "Phase exceeds the supported degree range.")
            if (points.isNotEmpty() && frequency <= points.last().frequencyHz)
                fail(line, "Frequencies must increase strictly; duplicate or reversed rows are not sorted or averaged.")
            if (points.size >= MAX_POINTS) fail(line, "Measurement exceeds $MAX_POINTS points.")
            points += FrequencyResponsePoint(frequency, magnitude, phase)
        }
        if (points.size < 2) fail(1, "A measurement needs at least two numeric rows.")
        return points.toList()
    }

    private fun number(token: String, line: Int, label: String): Double {
        if (token.length > 64 || !decimal.matches(token)) fail(line, "$label must be a decimal number using a dot.")
        val value = token.toDoubleOrNull()
        if (value == null || !value.isFinite()) fail(line, "$label must be finite.")
        if (value == 0.0 && token.takeWhile { it != 'e' && it != 'E' }.any { it in '1'..'9' })
            fail(line, "$label is too small to represent without becoming zero.")
        return value
    }

    private fun fail(line: Int, message: String): Nothing = throw IllegalArgumentException("Line $line: $message")
}
