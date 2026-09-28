package com.aurora.music.data

/** Digital sample measurements, independent of decorative visualizer gain and smoothing. */
data class PcmLevels(
    val sampleRate: Int,
    val channels: Int,
    val windowFrames: Int,
    val framesSinceReset: Long,
    val leftPeak: Double,
    val rightPeak: Double,
    val leftRms: Double,
    val rightRms: Double,
    val fullScaleSamples: Long,
    val invalidSamples: Long,
    val presentationEndUs: Long?,
    val measuredAtNanos: Long,
)

data class AudioMeasurements(
    val before: PcmLevels?,
    val after: PcmLevels?,
    val playing: Boolean,
    val afterAvailable: Boolean,
    val overlappingPlayers: Boolean,
    val spectrum: AudioSpectrum? = null,
)

data class AudioSpectrum(val sampleRate: Int, val presentationStartUs: Long, val measuredAtNanos: Long,
    val beforeDb: List<Float>, val afterDb: List<Float>? = null)
