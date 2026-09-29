package com.aurora.music.desktop.audio

import com.aurora.music.data.AudioMeasurements
import com.aurora.music.data.PcmLevels
import com.aurora.music.data.Preservation
import com.aurora.music.data.SignalFormat
import com.aurora.music.data.SignalPath
import com.aurora.music.data.SignalStage
import com.aurora.music.desktop.audio.decode.SampleKind
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.playback.chain.DspChainReport
import com.aurora.music.playback.engine.RackNodeMeter

fun PlaybackEngine.signalPath(): SignalPath =
    state.value.signalPath(dspReport(), beforeMeter.snapshot(), afterMeter.snapshot(), rackMeters())

fun EngineState.signalPath(report: DspChainReport?, before: PcmLevels?, after: PcmLevels?, nodeMeters: List<RackNodeMeter>): SignalPath {
    if (current == null) return SignalPath()
    val info = source
    val out = output
    if (info == null || out == null) {
        return SignalPath(active = true, note = "Opening the source", reasons = listOf("The decoder or the output is not open yet"))
    }
    val format = info.sampleFormat
    val modified = buildList {
        if (info.channels != 2) add("Channel count changes from ${info.channels} to 2")
        if (processing.dspActive) add("Aurora processing changes samples")
        if (processing.resampling) add("Resampled from ${info.sampleRate} Hz to ${out.sampleRate} Hz")
        if (speed != 1f) add("Playback speed ${speed}x changes tempo and pitch")
        if (processing.gainApplied) add("Volume, ReplayGain or crossfade gain is applied")
        if (!out.encoding.isFloat && (processing.ditherLabel != null || format.kind != SampleKind.INTEGER || format.bits > out.encoding.validBits)) {
            add("Samples are quantized to ${out.encoding.validBits}-bit integers" + (processing.ditherLabel?.let { " with $it" } ?: ""))
        }
    }
    val unknown = listOfNotNull(
        out.fallbackReason,
        processing.rateFallbackReason,
        if (out.exclusive) "Driver and DAC behavior beyond the exclusive WASAPI stream are not verified"
        else "Windows mixes shared-mode audio in 32-bit float; system volume, effects and other streams are not observed",
    )
    val reasons = (modified + unknown).distinct()
    val preserved = modified.isEmpty() && processing.bitPerfect
    val processingDetail = buildList {
        add(if (processing.dspActive) processing.dspDescription.ifBlank { "Custom DSP" } else "No active Aurora sample-processing node")
        if (processing.gainApplied) add("Gain applied after processing")
        if (crossfading) add("Two decks are crossfading")
    }.joinToString(". ")
    return SignalPath(
        active = true, codec = info.codec, sampleRateHz = info.sampleRate, bitDepth = format.bits, channels = info.channels,
        output = if (out.exclusive) "WASAPI exclusive" else "WASAPI shared", bitPerfect = preserved,
        note = reasons.first(), preservation = when {
            modified.isNotEmpty() -> Preservation.MODIFIED
            preserved -> Preservation.PRESERVED
            else -> Preservation.UNKNOWN
        },
        reasons = reasons,
        source = SignalStage("Source", listOfNotNull(info.container, info.codec, info.bitrate.takeIf { it > 0 }?.let { "$it bit/s" })
            .joinToString(" · "), "FFmpeg stream probe; upstream transcoding history may be unknown",
            SignalFormat(info.sampleRate, format.bits.takeIf { it > 0 }, info.channels, when (format.kind) {
                SampleKind.INTEGER -> "integer PCM"
                SampleKind.FLOAT -> "float PCM"
                SampleKind.LOSSY -> "lossy"
            })),
        decoder = SignalStage("Decoder", decoderName ?: "Decoder unknown", "FFmpeg decoder output converted to binary64 stereo",
            SignalFormat(info.sampleRate, 64, 2, "float PCM")),
        processing = SignalStage("Processing", processingDetail, "Current chain configuration and runtime processing state"),
        resampling = SignalStage("Resampling", processing.rateFallbackReason
            ?: if (processing.resampling) "${info.sampleRate} → ${out.sampleRate} Hz · Bandlimited SRC" else "Following source rate",
            "Configured chain rates"),
        outputStage = SignalStage("Output", "${if (out.exclusive) "Exclusive" else "Shared"} WASAPI stream · ${out.encoding.label}",
            "Open WASAPI stream format", SignalFormat(out.sampleRate, out.encoding.validBits, 2, if (out.encoding.isFloat) "float PCM" else "integer PCM")),
        device = SignalStage("Device", if (out.followsDefault) "System default output" else "Selected output",
            "Active WASAPI endpoint; hardware format and downstream processing unknown"),
        latency = if (report == null) SignalStage("Latency", "Processor delay unavailable") else SignalStage("Latency",
            "Rack: ${report.latencyFrames} frames · SRC: ${report.resamplerLookaheadFrames} frames · Tail: ${report.tailFrames} frames",
            "Compiled processor delay; output buffer and hardware latency are not included"),
        measurements = AudioMeasurements(before, after, isPlaying, after != null, crossfading),
        nodeMeters = nodeMeters,
    )
}

private val OutputEncoding.label: String get() = when (this) {
    OutputEncoding.S16 -> "16-bit integer"
    OutputEncoding.S24 -> "24-bit integer"
    OutputEncoding.S24_IN_32 -> "24-bit integer in 32-bit container"
    OutputEncoding.S32 -> "32-bit integer"
    OutputEncoding.F32 -> "32-bit float"
}
