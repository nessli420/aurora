package com.aurora.music.data.tuning

// raw imported values stay unchanged.
data class FrequencyResponsePoint(val frequencyHz: Double, val magnitudeDb: Double, val phaseDegrees: Double? = null)

data class MeasurementProvenance(val rig: String = "", val source: String = "", val notes: String = "")

data class MeasurementCurve(
    val id: String,
    val name: String,
    val points: List<FrequencyResponsePoint>,
    val sourceText: String,
    val importedAtMs: Long = 0,
    val provenance: MeasurementProvenance = MeasurementProvenance(),
    val format: TuningCurveFormat = TuningCurveFormat.TEXT,
)

enum class TuningChannelMode { LEFT, RIGHT, INDEPENDENT, LINKED_AVERAGE }
enum class TuningNormalization { NONE, MATCH_MEAN_200_2000 }


data class TuningChannelLimits(
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 12.0,
    val minQ: Double = .3,
    val maxQ: Double = 8.0,
)

data class TuningFitConfig(
    val sampleRate: Int = 48_000,
    val bandBudget: Int = 12,
    val minFrequencyHz: Double = 20.0,
    val maxFrequencyHz: Double = 20_000.0,
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 12.0,
    val minQ: Double = .3,
    val maxQ: Double = 8.0,
    val channelMode: TuningChannelMode = TuningChannelMode.INDEPENDENT,
    val normalization: TuningNormalization = TuningNormalization.MATCH_MEAN_200_2000,
    val smoothingOctaves: Double = 1.0 / 6.0,
    val bassDb: Double = 0.0,
    val bassFrequencyHz: Double = 105.0,
    val tiltDbPerOctave: Double = 0.0,
    val earGainDb: Double = 0.0,
    val earGainFrequencyHz: Double = 2800.0,
    val trebleStartHz: Double = 6000.0,
    val trebleMaxBoostDb: Double? = null,
    val trebleMaxCutDb: Double? = null,
    val trebleMaxQ: Double? = null,
    val leftLimits: TuningChannelLimits? = null,
    val rightLimits: TuningChannelLimits? = null,
    val leftTrimDb: Double = 0.0,
    val rightTrimDb: Double = 0.0,
    val leftDelayMs: Double = 0.0,
    val rightDelayMs: Double = 0.0,
)

internal fun TuningFitConfig.forChannel(channel: TuningFitChannel): TuningFitConfig {
    val limits = when (channel) {
        TuningFitChannel.LEFT -> leftLimits
        TuningFitChannel.RIGHT -> rightLimits
        TuningFitChannel.LINKED_AVERAGE -> null
    } ?: return this
    return copy(maxBoostDb = limits.maxBoostDb, maxCutDb = limits.maxCutDb, minQ = limits.minQ, maxQ = limits.maxQ)
}

// null selects a flat relative target.
data class TuningProject(
    val id: String,
    val name: String,
    val measurementLeft: MeasurementCurve? = null,
    val measurementRight: MeasurementCurve? = null,
    val target: MeasurementCurve? = null,
    val notes: String = "",
    val config: TuningFitConfig = TuningFitConfig(),
    val generatedFit: TuningFitResult? = null,
    val schemaVersion: Int = 1,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
)

internal val TuningFitConfig.hasChannelAlignment: Boolean
    get() = leftTrimDb != 0.0 || rightTrimDb != 0.0 || leftDelayMs != 0.0 || rightDelayMs != 0.0

internal val TuningFitConfig.alignmentNodeCount: Int
    get() = (if (leftTrimDb != 0.0 || rightTrimDb != 0.0) 1 else 0) +
        (if (leftDelayMs != 0.0 || rightDelayMs != 0.0) 1 else 0)
