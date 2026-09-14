package com.aurora.music.data

/** Runtime evidence only. This is deliberately not persisted with preferences. */
enum class Preservation { PRESERVED, MODIFIED, UNKNOWN }

data class SignalFormat(
    val rateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val encoding: String? = null,
) {
    fun describe(): String = listOfNotNull(
        rateHz?.let { "$it Hz" }, bitDepth?.let { "$it-bit" },
        channels?.let { "$it channels" }, encoding,
    ).joinToString(" · ").ifBlank { "Unknown format" }
}

data class SignalStage(
    val title: String,
    val detail: String = "Unknown",
    val evidence: String = "Not observed",
    val format: SignalFormat? = null,
)

data class SignalPath(
    val active: Boolean = false,
    // These compatibility fields describe the selected source, never the hardware output.
    val codec: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val output: String = "",
    val bitPerfect: Boolean = false,
    val note: String = "Start playback to inspect its signal path.",
    val preservation: Preservation = Preservation.UNKNOWN,
    val reasons: List<String> = listOf("No active playback path"),
    val source: SignalStage = SignalStage("Source"),
    val decoder: SignalStage = SignalStage("Decoder"),
    val processing: SignalStage = SignalStage("Processing"),
    val resampling: SignalStage = SignalStage("Resampling"),
    val outputStage: SignalStage = SignalStage("Output"),
    val device: SignalStage = SignalStage("Device"),
    val latency: SignalStage = SignalStage("Latency", "Processor, sink and downstream latency are not measured"),
    val measurements: AudioMeasurements? = null,
    /** Media3 AudioTrack underrun notifications for the primary player's lifetime; null elsewhere. */
    val audioTrackUnderruns: Long? = null,
) {
    val stages: List<SignalStage> get() = listOf(source, decoder, processing, resampling, outputStage, device, latency)

    /** Only allowlisted technical facts enter this report; no media URLs, account/device IDs or titles. */
    fun toDiagnosticReport(): String = buildString {
        appendLine("Aurora Signal Path · diagnostic v1")
        appendLine("State: ${if (active) "Active" else "Idle"}")
        appendLine("Sample preservation: ${preservation.name.lowercase().replaceFirstChar { it.uppercase() }}")
        reasons.forEach { appendLine("- $it") }
        audioTrackUnderruns?.let { appendLine("Primary player AudioTrack underruns since creation: $it") }
        stages.forEach {
            appendLine()
            appendLine("${it.title}: ${it.detail}")
            it.format?.let { format -> appendLine("Format: ${format.describe()}") }
            appendLine("Evidence: ${it.evidence}")
        }
        measurements?.let { m ->
            appendLine()
            appendLine("Digital sample levels: independent 100 ms windows, not aligned to audible output")
            fun appendLevels(label: String, level: PcmLevels?) {
                if (level == null) { appendLine("$label: unavailable"); return }
                appendLine("$label: ${level.sampleRate} Hz, ${level.channels} channels, ${level.windowFrames} frames")
                appendLine("Peak L/R (linear full scale): ${level.leftPeak} / ${level.rightPeak}")
                appendLine("RMS L/R (linear full scale): ${level.leftRms} / ${level.rightRms}")
                appendLine("Full-scale / invalid samples since reset: ${level.fullScaleSamples} / ${level.invalidSamples}")
            }
            appendLevels("Before app processing", m.before)
            appendLevels("After app processing, before player/system gain", m.after)
            if (m.overlappingPlayers) appendLine("Primary player only; crossfade sum unmeasured")
        }
        appendLine()
        appendLine("Hardware behavior beyond the reported output boundary is not verified.")
    }
}

enum class PlaybackPathKind { IDLE, ANDROID, NATIVE_USB, DECODED_USB, CAST, MIX }

/** Media3's float option only selects its bypass chain for high-resolution decoded PCM. */
fun usesFloatPcmPath(floatEnabled: Boolean, decoded: SignalFormat?): Boolean =
    floatEnabled && decoded != null && (decoded.encoding == "float PCM" || (decoded.bitDepth ?: 0) > 16)

enum class MonoProcessingLocation { OFF, PROCESSOR, CUSTOM_DSP, BYPASSED }

fun monoProcessingLocation(requested: Boolean, customDspActive: Boolean, monoProcessorActive: Boolean): MonoProcessingLocation = when {
    !requested -> MonoProcessingLocation.OFF
    customDspActive -> MonoProcessingLocation.CUSTOM_DSP
    monoProcessorActive -> MonoProcessingLocation.PROCESSOR
    else -> MonoProcessingLocation.BYPASSED
}

/** Input to the pure decision function: observations and requests remain separate. */
data class SignalPathFacts(
    val kind: PlaybackPathKind = PlaybackPathKind.IDLE,
    val sourceCopy: String = "Unknown playable copy",
    val codec: String = "",
    val sourceFormat: SignalFormat? = null,
    val sourceBitrate: Int? = null,
    val decoderName: String? = null,
    val decodedFormat: SignalFormat? = null,
    val processorPath: String = "Waiting for sink configuration",
    val activeNodes: List<String> = emptyList(),
    val bypassedNodes: List<String> = emptyList(),
    val modifications: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val androidTrackFormat: SignalFormat? = null,
    val nativeTransportFormat: SignalFormat? = null,
    val nativeClockAccepted: Boolean = false,
    val nativeTailSubmitted: Boolean = false,
    val mixerGrant: Boolean = false,
    val mixerGrantMatchesFormat: Boolean = false,
    val mixerRequestDetail: String? = null,
    val requestedDevice: String? = null,
    val exclusiveRequested: Boolean = false,
    val restartRequired: Boolean = false,
)

/** No preference alone, connected device or mixer grant is proof of preserved samples. */
fun buildSignalPath(f: SignalPathFacts): SignalPath {
    if (f.kind == PlaybackPathKind.IDLE) return SignalPath()
    if (f.kind == PlaybackPathKind.CAST) return SignalPath(
        active = true, output = "Cast receiver", note = "Receiver processing and output are unknown",
        reasons = listOf("Cast receiver decoding, volume and downstream output are not observed"),
        source = SignalStage("Source", "Media handed to Cast receiver", "Remote playback session"),
        decoder = SignalStage("Decoder", "Receiver decoder unknown"),
        processing = SignalStage("Processing", "Aurora's local DSP does not travel with direct Cast", "Direct media handoff"),
        outputStage = SignalStage("Output", "Cast receiver; format unknown", "Active Cast session"),
        device = SignalStage("Device", "Cast receiver; hardware route unknown", "Active Cast session"),
    )
    if (f.kind == PlaybackPathKind.MIX) return SignalPath(
        active = true, output = "Android audio · Mix", preservation = Preservation.MODIFIED,
        note = "Mix playback applies per-deck processing and gains",
        reasons = listOf("Mix uses independent decoded decks, per-clip processing and timeline gains"),
        source = SignalStage("Source", "Multiple mix clips; no single source format", "Active Mix player"),
        decoder = SignalStage("Decoder", "Independent Media3 decoders; per-deck formats not instrumented", "Active Mix player"),
        processing = SignalStage("Processing", "Per-deck stereo conversion → clip DSP → global processing → timeline gains",
            "Mix processing schedule; Custom DSP and convolution use binary64 arithmetic/state, with PCM16 between processors. Per-deck activation and timeline gain are not measured here"),
        outputStage = SignalStage("Output", "Android audio; per-deck AudioTrack and hardware formats unknown", "Active Mix player"),
        device = SignalStage("Device", "Active Android route unknown", "A preferred device is a request, not a confirmed route"),
    )
    val usb = f.kind == PlaybackPathKind.NATIVE_USB || f.kind == PlaybackPathKind.DECODED_USB
    val modified = f.modifications.toMutableList()
    val unknown = f.unknowns.toMutableList()
    val input = f.decodedFormat
    val transport = if (usb) f.nativeTransportFormat else f.androidTrackFormat
    if (input?.rateHz != null && transport?.rateHz != null && input.rateHz != transport.rateHz) {
        modified += "Observed input and output transport sample rates differ"
    }
    if (input?.channels != null && transport?.channels != null && input.channels != transport.channels) {
        modified += "Channel count changes before output"
    }
    if (input?.bitDepth != null && transport?.bitDepth != null && input.bitDepth > transport.bitDepth) {
        modified += "Output transport reduces sample precision"
    }
    if (f.nativeTailSubmitted) unknown += "Native crossfade was submitted; completion is not reported by the driver"
    if (usb) {
        if (!f.nativeClockAccepted) unknown += "USB clock or alternate-setting request was not confirmed"
        unknown += "USB transport conversion and downstream DAC behavior are not independently verified"
    } else {
        if (f.mixerGrant && !f.mixerGrantMatchesFormat) unknown += "Mixer grant does not match the current AudioTrack format"
        unknown += if (f.mixerGrant && f.mixerGrantMatchesFormat)
            "Matching mixer preference accepted; actual route and HAL behavior are not observed"
        else "Android mixer, active hardware format and downstream processing are not observed"
    }
    val reasons = (modified + unknown).distinct()
    val verdict = if (modified.isNotEmpty()) Preservation.MODIFIED else Preservation.UNKNOWN
    val processing = buildList {
        add(f.processorPath)
        if (f.activeNodes.isNotEmpty()) add("Active: " + f.activeNodes.joinToString(" → "))
        else add("No active Aurora sample-processing node observed")
        if (f.bypassedNodes.isNotEmpty()) add("Bypassed / unapplied: " + f.bypassedNodes.joinToString(", "))
        if (f.restartRequired) add("Output mode preference changed; stop the playback service or restart the app to rebuild its sink")
    }.joinToString(". ")
    val outputName = if (usb) "Direct USB transport" else "Android audio"
    return SignalPath(
        active = true, codec = f.codec, sampleRateHz = f.sourceFormat?.rateHz ?: 0,
        bitDepth = f.sourceFormat?.bitDepth ?: 0, channels = f.sourceFormat?.channels ?: 0,
        output = outputName, note = reasons.firstOrNull() ?: "Unknown", preservation = verdict, reasons = reasons,
        source = SignalStage("Source", listOfNotNull(f.sourceCopy, f.codec.takeIf { it.isNotBlank() },
            f.sourceBitrate?.let { "$it bit/s" }).joinToString(" · "),
            "Selected playable copy and current decoder metadata; upstream original format and transcoding history may be unknown", f.sourceFormat),
        decoder = SignalStage("Decoder", f.decoderName ?: "Decoder implementation unknown",
            if (f.kind == PlaybackPathKind.NATIVE_USB) "Native FLAC engine metadata" else "Media3 decoder event and sink input configuration", f.decodedFormat),
        processing = SignalStage("Processing", processing, "Current sink configuration and runtime processing state"),
        resampling = SignalStage("Resampling", if (input?.rateHz != null && transport?.rateHz != null)
            "${input.rateHz} Hz input → ${transport.rateHz} Hz transport; downstream conversion unknown"
            else "Input/output rate comparison unavailable; downstream conversion unknown", "Configured transport boundary, not a hardware measurement"),
        outputStage = SignalStage("Output", if (usb) "Direct USB transport configuration; hardware format readback unavailable"
            else "Android AudioTrack configuration; downstream hardware format unknown" +
                if (f.exclusiveRequested) "; exclusive USB requested but native transport is unavailable" else "",
            if (usb) "Native USB stream started; clock request ${if (f.nativeClockAccepted) "accepted" else "not confirmed"}"
            else f.mixerRequestDetail ?: if (f.mixerGrant) "AudioTrack event; mixer preference accepted (route unverified)"
                else "Media3 AudioTrack initialization event", transport),
        device = SignalStage("Device", if (usb) "USB audio device claimed by the native driver; hardware gain unknown"
            else "Active route unknown" + (f.requestedDevice?.let { "; requested $it" } ?: ""),
            if (usb) "Live native USB stream; identifying device details omitted" else "Preferred-device selection is not proof of the routed device"),
    )
}
