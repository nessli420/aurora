package com.aurora.music.data

import com.aurora.music.playback.DspCoeffBuilder
import java.util.Locale

enum class EqDeviceKind(val examples: List<String>) {
    ALL(listOf("Sony WH-1000XM5", "AirPods", "HD 600", "SoundLink")),
    HEADPHONES(listOf("HD 600", "ATH-M50x", "DT 770", "WH-1000XM5")),
    IN_EAR(listOf("AirPods Pro", "Galaxy Buds", "Moondrop", "WF-1000XM5")),
    EARBUDS(listOf("OpenFit", "OpenRun", "AirPods 4", "VE Monk")),
    SPEAKERS(listOf("SoundLink", "Sonos Roam", "JBL 305", "Genelec")),
}

enum class EqProvider { AUTOEQ, SQUIG, SPINORAMA }

data class EqProfile(
    val name: String,
    val source: String,
    val path: String,
    val provider: EqProvider = EqProvider.AUTOEQ,
) {
    // AutoEQ records the actual measurement form factor in the directory, including the rig name.
    val kind: EqDeviceKind get() = when {
        provider == EqProvider.SPINORAMA -> EqDeviceKind.SPEAKERS
        provider == EqProvider.SQUIG -> EqDeviceKind.IN_EAR
        path.split('/').getOrNull(2)?.contains("in-ear") == true -> EqDeviceKind.IN_EAR
        path.split('/').getOrNull(2)?.contains("earbud") == true -> EqDeviceKind.EARBUDS
        else -> EqDeviceKind.HEADPHONES
    }
}

data class ParsedEq(val preampDb: Float, val bands: List<ParamBand>)

/** Equalizer APO parametric text used by both AutoEQ and Spinorama. */
object EqTextParser {
    fun parse(text: String): ParsedEq? {
        var preamp = 0f
        val bands = ArrayList<ParamBand>()
        val number = "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))"
        val filter = Regex("^Filter\\s+\\d+:\\s+ON\\s+(\\S+)\\s+Fc\\s+$number\\s+Hz\\s+Gain\\s+$number\\s+dB\\s+Q\\s+$number(?:\\s.*)?$", RegexOption.IGNORE_CASE)
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith("Preamp", true) -> {
                    preamp = Regex("^Preamp:\\s*$number\\s+dB", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
                    if (!preamp.isFinite() || preamp !in -60f..24f) return null
                }
                t.startsWith("Filter", true) && Regex("\\bON\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> {
                    // Never silently drop unsupported filters or positive gains from a correction.
                    val match = filter.matchEntire(t) ?: return null
                    val type = when (match.groupValues[1].uppercase(Locale.ROOT)) {
                        "LSC", "LS" -> BandType.LOW_SHELF
                        "HSC", "HS" -> BandType.HIGH_SHELF
                        "PK" -> BandType.PEAK
                        else -> return null
                    }
                    val fc = match.groupValues[2].toFloatOrNull() ?: return null
                    val gain = match.groupValues[3].toFloatOrNull() ?: return null
                    val q = match.groupValues[4].toFloatOrNull() ?: return null
                    val band = runCatching { ParamBandCodec.validate(ParamBand(fc, gain, q, type)) }.getOrNull() ?: return null
                    bands.add(band)
                }
            }
        }
        return if (bands.isEmpty() || bands.size > DspCoeffBuilder.MAX_PARAMETRIC) null else ParsedEq(preamp, bands)
    }
}
