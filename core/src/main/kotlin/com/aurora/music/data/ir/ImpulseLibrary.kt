package com.aurora.music.data.ir

import com.aurora.music.playback.engine.SamplePrecision

data class ImpulseMetadata(
    val sampleRate: Int,
    val channels: Int,
    val frames: Int,
    val precision: SamplePrecision,
    val validBits: Int,
    val peak: Double
)

enum class ImpulseNormalization { NONE, PEAK_MINUS_1_DB }

data class ImpulsePreparation(
    val startFrame: Int,
    val endFrameExclusive: Int,
    val normalization: ImpulseNormalization = ImpulseNormalization.NONE,
    val minimumPhase: Boolean = false,
    val delayFrames: Int = 0,
)

data class ImpulsePreparedAsset(
    val path: String,
    val sha256: String,
    val metadata: ImpulseMetadata,
    val preparation: ImpulsePreparation
)

data class ImpulseLibraryEntry(
    val id: String,
    val name: String,
    val sourceName: String,
    val sourcePath: String,
    val sourceSha256: String,
    val sourceMetadata: ImpulseMetadata,
    val createdAtMs: Long,
    val prepared: ImpulsePreparedAsset? = null
)

data class ImpulseWaveformBin(val min: Double, val max: Double)

data class ImpulsePreview(
    val metadata: ImpulseMetadata,
    val left: List<ImpulseWaveformBin>,
    val right: List<ImpulseWaveformBin>,
    val leftToRight: List<ImpulseWaveformBin> = emptyList(),
    val rightToLeft: List<ImpulseWaveformBin> = emptyList(),
)

data class ImpulseResourceEstimate(
    val targetSampleRate: Int,
    val targetFrames: Long,
    val supported: Boolean,
    val decodedBytes: Long,
    val partitionCount: Int,
    val partitionBytes: Long,
    val resamplingDelayFrames: Int = 0,
)
