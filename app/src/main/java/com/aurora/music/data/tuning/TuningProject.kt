package com.aurora.music.data.tuning

/** Imported values are never normalized, resampled, averaged or phase-wrapped in storage. */
data class FrequencyResponsePoint(val frequencyHz: Double, val magnitudeDb: Double, val phaseDegrees: Double? = null)

data class MeasurementProvenance(val rig: String = "", val source: String = "", val notes: String = "")

data class MeasurementCurve(
    val id: String,
    val name: String,
    val points: List<FrequencyResponsePoint>,
    val sourceText: String,
    val importedAtMs: Long = 0,
    val provenance: MeasurementProvenance = MeasurementProvenance(),
)

enum class TuningChannelMode { LEFT, RIGHT, INDEPENDENT, LINKED_AVERAGE }
enum class TuningNormalization { NONE, MATCH_MEAN_200_2000 }

/** One reproducible fitting request; raw curves remain unchanged when these controls change. */
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
)

/** A null target is the explicit flat relative target; L/R inputs always remain independent. */
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
