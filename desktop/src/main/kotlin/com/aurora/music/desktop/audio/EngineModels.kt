package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.StreamInfo
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.model.Song
import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.engine.OutputRatePolicy

enum class RepeatMode { OFF, ALL, ONE }

enum class EnginePhase { IDLE, BUFFERING, READY, ENDED }

enum class TransitionReason { AUTO, REPEAT, SEEK, QUEUE_CHANGED }

enum class ShuffleTarget { ON, OFF, TOGGLE }

data class QueueEntry(val uid: Long, val song: Song)

data class PlaybackFailure(val kind: Kind, val message: String) {
    enum class Kind { UNSUPPORTED_SOURCE, SOURCE_UNAVAILABLE, DECODE, OUTPUT }
}

data class OutputInfo(
    val deviceId: String?,
    val deviceName: String?,
    val exclusive: Boolean,
    val sampleRate: Int,
    val encoding: OutputEncoding,
    val followsDefault: Boolean,
    val fallbackReason: String? = null,
)

data class ProcessingFacts(
    val dspActive: Boolean = false,
    val dspDescription: String = "",
    val preparation: ConvolutionPreparationState = ConvolutionPreparationState.IDLE,
    val resampling: Boolean = false,
    val gainApplied: Boolean = false,
    val ditherLabel: String? = null,
    val bitPerfect: Boolean = false,
    val rateFallbackReason: String? = null,
)

data class EngineState(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = -1,
    val playWhenReady: Boolean = false,
    val phase: EnginePhase = EnginePhase.IDLE,
    val live: Boolean = false,
    val shuffle: Boolean = false,
    val shuffleRestoreIds: List<String>? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val crossfading: Boolean = false,
    val source: StreamInfo? = null,
    val decoderName: String? = null,
    val output: OutputInfo? = null,
    val processing: ProcessingFacts = ProcessingFacts(),
    val error: PlaybackFailure? = null,
) {
    val current: QueueEntry? get() = entries.getOrNull(index)
    val isPlaying: Boolean get() = playWhenReady && phase == EnginePhase.READY
    val effectivelyPlaying: Boolean get() = isPlaying || playWhenReady && phase == EnginePhase.BUFFERING
}

sealed interface EngineEvent {
    data class Transition(val from: QueueEntry?, val to: QueueEntry?, val reason: TransitionReason, val endPositionMs: Long?) : EngineEvent
    data class Discontinuity(val entry: QueueEntry, val fromMs: Long, val toMs: Long) : EngineEvent
    data object Ended : EngineEvent
    data class Failed(val entry: QueueEntry?, val failure: PlaybackFailure) : EngineEvent
    data class OutputFallback(val reason: String) : EngineEvent
}

data class EngineConfig(
    val crossfadeMs: Int = 0,
    val crossfadeCurve: String = "SMOOTH",
    val crossfadeHeadroom: Boolean = true,
    val replayGain: Int = 0,
    val outputRatePolicy: OutputRatePolicy = OutputRatePolicy(),
    val bufferMs: Int = 250,
    val maxConsecutiveFailures: Int = 5,
)
