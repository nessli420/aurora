package com.aurora.music.data.tuning

import com.aurora.music.localization.appString
import com.aurora.music.R

import java.util.UUID

enum class TuningCurveFormat(@androidx.annotation.StringRes private val labelRes: Int) {
    TEXT(R.string.text_hz_db_text_rew_d34904),
    SQUIG(R.string.text_squig_measurement_text_1ab632),
    AUTOEQ_RAW(R.string.text_autoeq_json_raw_measurement_984669),
    AUTOEQ_TARGET(R.string.text_autoeq_json_target_de676c),
    AUTOEQ_CSV(R.string.text_autoeq_target_csv_231f90),
    WAVELET(R.string.text_wavelet_graphiceq_correction_230cc2);
    val label: String get() = appString(labelRes)
}

object TuningCurveAdapters {
    fun parse(text: String, name: String, format: TuningCurveFormat, phaseColumn: Boolean = false,
        provenance: MeasurementProvenance = MeasurementProvenance(), importedAtMs: Long = 0): Result<MeasurementCurve> = runCatching {
        val curve = MeasurementCurve(UUID.randomUUID().toString(), name, parsePoints(text, format, phaseColumn), text,
            importedAtMs, provenance, format)
        TuningProjectCodec.validateCurve(curve, checkSource = false)
    }

    internal fun parsePoints(text: String, format: TuningCurveFormat, phase: Boolean): List<FrequencyResponsePoint> {
        require(text.toByteArray(Charsets.UTF_8).size <= MeasurementTextImporter.MAX_TEXT_BYTES) { "Curve exceeds 512 KiB." }
        return when (format) {
            TuningCurveFormat.TEXT, TuningCurveFormat.SQUIG -> MeasurementTextImporter.parsePoints(text, phase)
            TuningCurveFormat.AUTOEQ_CSV -> {
                val lines = text.removePrefix("\uFEFF").lineSequence().toList()
                require(lines.firstOrNull()?.trim() == "frequency,raw") { "Expected frequency,raw CSV columns." }
                MeasurementTextImporter.parsePoints("Frequency (Hz),Magnitude (dB)\n" + lines.drop(1).joinToString("\n"), false)
            }
            TuningCurveFormat.AUTOEQ_RAW, TuningCurveFormat.AUTOEQ_TARGET -> {
                val root = TuningProjectCodec.strictJson(text, MeasurementTextImporter.MAX_TEXT_BYTES)
                require(root.isJsonObject) { "Expected an AutoEq frequency-response object." }
                val objectValue = root.asJsonObject
                val known = setOf("frequency", "raw", "smoothed", "error", "error_smoothed", "equalization", "parametric_eq", "fixed_band_eq", "equalized_raw", "equalized_smoothed", "target")
                require((objectValue.keySet() - known).isEmpty()) { "Import the AutoEq frequency-response object, without API or filter settings." }
                val key = if (format == TuningCurveFormat.AUTOEQ_RAW) "raw" else "target"
                val frequencies = objectValue["frequency"]
                val magnitudes = objectValue[key]
                require(frequencies?.isJsonArray == true && magnitudes?.isJsonArray == true) { "The AutoEq object needs frequency and $key arrays." }
                val f = frequencies.asJsonArray; val m = magnitudes.asJsonArray
                require(f.size() in 2..MeasurementTextImporter.MAX_POINTS && m.size() == f.size()) { "AutoEq arrays must have matching lengths." }
                objectValue.entrySet().forEach { (_, values) ->
                    require(values.isJsonArray && values.asJsonArray.size() == f.size()) { "AutoEq arrays must share one frequency grid." }
                    values.asJsonArray.forEach {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()) { "AutoEq values must be finite numbers." }
                        require(it.asDouble != 0.0 || it.asBigDecimal.compareTo(java.math.BigDecimal.ZERO) == 0) { "AutoEq value is too small to represent." }
                    }
                }
                val numeric = (0 until f.size()).joinToString("\n") { "${f[it].asDouble} ${m[it].asDouble}" }
                MeasurementTextImporter.parsePoints(numeric, false)
            }
            TuningCurveFormat.WAVELET -> {
                val line = text.trim()
                require(line.startsWith("GraphicEQ:") && !line.contains('\n') && !line.contains('\r')) { "Expected one GraphicEQ line." }
                val numeric = line.removePrefix("GraphicEQ:").split(';').joinToString("\n") { it.trim() }
                val points = MeasurementTextImporter.parsePoints(numeric, false)
                require(points.map { it.frequencyHz.toInt() } == waveletFrequencies && points.all { it.frequencyHz % 1.0 == 0.0 }) { "Wavelet requires its standard 127-frequency grid." }
                require(points.all { it.magnitudeDb in -60.0..24.0 }) { "GraphicEQ gain must be between −60 and 24 dB." }
                points
            }
        }
    }

    fun correctionProject(curve: MeasurementCurve, nowMs: Long = 0): TuningProject {
        require(curve.format == TuningCurveFormat.WAVELET) { "Select a correction curve." }
        val flat = MeasurementTextImporter.parse("${curve.points.first().frequencyHz} 0\n${curve.points.last().frequencyHz} 0", "Flat reference").getOrThrow()
        return TuningProjectCodec.create(curve.name, nowMs).copy(measurementLeft = flat, measurementRight = flat.copy(id = UUID.randomUUID().toString()), target = curve,
            notes = "Parametric fit of a GraphicEQ correction curve.", config = TuningFitConfig(channelMode = TuningChannelMode.LINKED_AVERAGE,
                normalization = TuningNormalization.NONE, smoothingOctaves = 0.0, maxBoostDb = 24.0, maxCutDb = 30.0))
    }

    private val waveletFrequencies = "20 21 22 23 24 26 27 29 30 32 34 36 38 40 43 45 48 50 53 56 59 63 66 70 74 78 83 87 92 97 103 109 115 121 128 136 143 151 160 169 178 188 199 210 222 235 248 262 277 292 309 326 345 364 385 406 429 453 479 506 534 565 596 630 665 703 743 784 829 875 924 977 1032 1090 1151 1216 1284 1357 1433 1514 1599 1689 1784 1885 1991 2103 2221 2347 2479 2618 2766 2921 3086 3260 3443 3637 3842 4058 4287 4528 4783 5052 5337 5637 5955 6290 6644 7018 7414 7831 8272 8738 9230 9749 10298 10878 11490 12137 12821 13543 14305 15110 15961 16860 17809 18812 19871".split(' ').map(String::toInt)
}
