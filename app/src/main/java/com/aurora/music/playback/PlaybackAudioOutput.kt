package com.aurora.music.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aurora.music.data.DspMode
import com.aurora.music.data.MonoProcessingLocation
import com.aurora.music.data.PlaybackPathKind
import com.aurora.music.data.SignalFormat
import com.aurora.music.data.SignalPath
import com.aurora.music.data.SignalPathFacts
import com.aurora.music.data.StreamFormat
import com.aurora.music.data.UsbFallbackPolicy
import com.aurora.music.data.UsbOutputMode
import com.aurora.music.data.buildSignalPath
import com.aurora.music.data.monoProcessingLocation
import com.aurora.music.data.usesFloatPcmPath
import com.aurora.music.playback.dsd.DsdSourceInfo
import com.aurora.music.playback.network.endpoint.EndpointRoute
import com.aurora.music.playback.usb.NativeUsbPcmTransport
import com.aurora.music.playback.usb.ProcessedUsbAudioSink
import com.aurora.music.playback.usb.UsbGraphProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@UnstableApi
internal class PlaybackAudioOutput(
    private val context: Context,
    private val initialPrefs: com.aurora.music.data.PlaybackPrefs,
    private val scope: CoroutineScope,
    private val processing: PlaybackProcessing,
    private val fades: PlaybackFades,
    private val live: () -> ExoPlayer,
    private val haptics: MusicHaptics,
    private val visualizer: VisualizerController,
    private val routes: com.aurora.music.data.routes.ProcessingRouteMonitor,
    private val signalPath: MutableStateFlow<SignalPath>,
    private val streamFormat: MutableStateFlow<StreamFormat?>,
    private val preferredDeviceId: StateFlow<Int>,
    private val streamBitrateKbps: (android.net.Uri) -> Int?,
    private val changed: () -> Unit,
) {
    private val player get() = live()
    val bitPerfect = initialPrefs.bitPerfectUsb // exclusive usb uses its native mixer, never a second android output
    val usbMode = initialPrefs.usbOutputMode
    val usbFallback = initialPrefs.usbFallbackPolicy
    val dsdWire = if (bitPerfect && usbMode == UsbOutputMode.DIRECT) when (initialPrefs.usbDsdMode) {
        com.aurora.music.data.UsbDsdMode.DOP -> com.aurora.music.playback.dsd.DsdWireFormat.DOP
        com.aurora.music.data.UsbDsdMode.NATIVE -> com.aurora.music.playback.dsd.DsdWireFormat.NATIVE_MSB32
        else -> null
    } else null
    private val useFloatOut = bitPerfect || initialPrefs.preferHighRes
    private val usePrecisionProcessing = initialPrefs.preferHighRes || bitPerfect
    @Volatile var preferHighResPref: Boolean = false
    @Volatile var gapless: Boolean = initialPrefs.gapless
    private fun gapMs() = if (gapless) 0 else GaplessAudioSink.GAP_MS
    var exclusiveUsbPref = false
    var usbModePref = UsbOutputMode.DIRECT
    var usbFallbackPref = UsbFallbackPolicy.PAUSE
    var usbSink: com.decent.usbaudio.media3.UsbAudioSink? = null
        private set
    var processedUsbSink: ProcessedUsbAudioSink? = null
        private set
    var hardwareVolume: UsbHardwareVolumeController? = null
        private set
    // usb and audio device lookups are binder calls, signal path snapshots run every second
    private val nativeRoutes = mutableMapOf<String, com.aurora.music.data.routes.ProcessingRoute>()
    private var rawDsdSink: com.aurora.music.playback.dsd.RawDsdAudioSink? = null
    @Volatile private var rawDsdFailure: String? = null
    @Volatile private var usbFailure: String? = null
    @Volatile private var grantedBitPerfect: Boolean = false
    @Volatile private var deviceSupportsBitPerfect: Boolean = false
    private class SinkEvidence {
        @Volatile var routedOutput: ConfirmedAudioRoute? = null
        val beforeMeter = PcmLevelMeter()
        val afterMeter = PcmLevelMeter()
        val precisionAfterMeter = PcmLevelMeter()
        var spectrum: com.aurora.music.data.AudioSpectrum? = null
        var spectrumGeneration = -1L
        var spectrumBusy = false
        var spectrumRequestedAt = 0L
        val afterMeterProcessor = LevelMeterAudioProcessor(afterMeter)
        val compatibilityProcessor = PrecisionRackAudioProcessor()
        var precisionProcessor: PrecisionBlockProcessor? = null
        @Volatile var precisionSink: PrecisionAudioSink? = null
        @Volatile var decoded: Format? = null
        @Volatile var downstreamGain: Float? = null
        val tracks = OutputTrackEvidence<AudioSink.AudioTrackConfig> { a, b ->
            a.sampleRate == b.sampleRate && a.encoding == b.encoding && a.channelConfig == b.channelConfig &&
                a.tunneling == b.tunneling && a.offload == b.offload && a.bufferSize == b.bufferSize
        }
        val track: AudioSink.AudioTrackConfig? get() = tracks.configuration
        var decoder: String? = null
        var underruns: Long = 0L
        val sources = mutableMapOf<String, Format>()
        // spectra are only read while the signal path is watched
        fun spectrumTaps(on: Boolean) {
            beforeMeter.spectrumEnabled = on; afterMeter.spectrumEnabled = on; precisionAfterMeter.spectrumEnabled = on
        }
    }
    private val sinkEvidence = java.util.IdentityHashMap<ExoPlayer, SinkEvidence>()
    private var mixerRequestKey: String? = null
    private var grantedMixerFormat: SignalFormat? = null
    private var mixerRequestDetail: String? = null
    private var mixerRequestedDevice: android.media.AudioDeviceInfo? = null
    private val outputCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) { mixerRequestKey = null; nativeRoutes.clear(); changed() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) { mixerRequestKey = null; nativeRoutes.clear(); changed() }
    }
    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.getIntExtra(VOLUME_STREAM_EXTRA, -1) != android.media.AudioManager.STREAM_MUSIC) return
            hardwareVolume?.onMediaVolumeChanged()
            changed()
        }
    }
    private val initialEvidence = SinkEvidence()

    init {
        initialEvidence.compatibilityProcessor.tpdfDither = processing.outputRatePolicy.tpdfDither
        initialEvidence.compatibilityProcessor.noiseShaping = processing.outputRatePolicy.noiseShaping == true
        processing.addCompatibility(initialEvidence.compatibilityProcessor.engine)
        if (usePrecisionProcessing) initialEvidence.precisionProcessor = PrecisionBlockProcessor().also { processing.addPrecision(it) }
    }

    private val usbProcessor = if (bitPerfect && usbMode == UsbOutputMode.PROCESSED)
        PrecisionBlockProcessor().also { processing.addPrecision(it) } else null

    fun renderersFactory(): DefaultRenderersFactory {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(context: Context, extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>) {
                if (dsdWire != null) {
                    val rawSink = com.aurora.music.playback.dsd.RawDsdAudioSink(DefaultAudioSink.Builder(context).build(), dsdWire,
                        { usbSink?.reset(); com.aurora.music.playback.dsd.NativeDsdUsbTransport(context, dsdWire, initialPrefs.usbDsdExperimental == true) },
                        {
                            rawDsdSink?.telemetry?.let {
                                if (it.failure != null) rawDsdFailure = it.failure
                                else if (it.active) rawDsdFailure = null
                            }
                            android.os.Handler(context.mainLooper).post { changed() }
                        })
                    rawDsdSink = rawSink
                    out.add(com.aurora.music.playback.dsd.RawDsdAudioRenderer(eventHandler, eventListener, rawSink))
                }
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    audioSink, eventHandler, eventListener, out)
            }
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val routedOutput = ConfirmedAudioRoute(context) { changed() }
                initialEvidence.routedOutput?.close()
                initialEvidence.routedOutput = routedOutput
                val base = DefaultAudioSink.Builder(context)
                    .setAudioTrackProvider(routedOutput.provider)
                    .setAudioProcessors(arrayOf(initialEvidence.compatibilityProcessor, initialEvidence.afterMeterProcessor))
                    .setEnableFloatOutput(useFloatOut)
                    // float bypasses sonic so use hardware playback params for speed
                    .setEnableAudioTrackPlaybackParams(useFloatOut || enableAudioTrackPlaybackParams)
                    .build()
                val fallback = initialEvidence.precisionProcessor?.let {
                    PrecisionAudioSink(base, it, initialEvidence.precisionAfterMeter,
                        { processing.outputRatePolicy }, { routedOutput.supportedSampleRates() }).also { wrapped -> initialEvidence.precisionSink = wrapped }
                } ?: base
                val sink = when {
                    usbProcessor != null -> ProcessedUsbAudioSink(fallback,
                        UsbGraphProcessor(usbProcessor, { processing.outputRatePolicy }, after = initialEvidence.precisionAfterMeter),
                        { NativeUsbPcmTransport(context) }, usbFallback == UsbFallbackPolicy.ANDROID,
                        { softwareUsbVolume() }, { android.os.Handler(context.mainLooper).post { changed() } })
                        .also { processedUsbSink = it }
                    bitPerfect -> com.decent.usbaudio.media3.UsbAudioSink(fallback, context,
                        com.decent.usbaudio.media3.UsbAudioSinkConfig(
                            nativeFlacEnabled = dsdWire == null,
                            allowAndroidFallback = usbFallback == UsbFallbackPolicy.ANDROID,
                            onUsbFailure = { reason -> usbFailure = reason; android.os.Handler(context.mainLooper).post { changed() } },
                        )).also {
                            usbSink = it
                            val nativeSink = it
                            visualizer.monoSource = object : VisualizerController.MonoSource {
                                override fun read(out: FloatArray) = nativeSink.readNativePcm(out)
                                override fun sampleRate() = nativeSink.nativeEngineSampleRate
                                override fun active() = nativeSink.nativeEngineActive
                            }
                        }
                    else -> fallback
                }
                val exclusiveSink = if (dsdWire == null) sink else object : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
                    override fun configure(inputFormat: androidx.media3.common.Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                        rawDsdSink?.flush()
                        super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    }
                }
                return GaplessAudioSink(TappingAudioSink(exclusiveSink, visualizer, initialEvidence.beforeMeter, haptics,
                    onVolume = { initialEvidence.downstreamGain = it }) { initialEvidence.decoded = it }, ::gapMs,
                    inert = true)
            }
        }
        // prefer ffmpeg decoder so non-flac content decodes to float32
        if (bitPerfect) {
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        return renderersFactory
    }

    fun attachMainDeck(player: ExoPlayer) {
        attachSignalEvidence(player, initialEvidence)
        if (bitPerfect) usbSink?.attachToPlayer(player)
        if (bitPerfect && usbMode == UsbOutputMode.DIRECT) hardwareVolume = UsbHardwareVolumeController(context)

        // usb host permission isnt persisted across process restarts so re-acquire on startup else sink silently falls back to normal output
        if (bitPerfect) {
            runCatching {
                val dev = com.decent.usbaudio.UsbAudioDevice.getInstance(context)
                dev.findUsbAudioDevice()?.let { d -> if (!dev.hasPermission(d)) dev.requestPermission(d) {} }
            }
        }
    }

    fun newFadeDeck(build: (DefaultRenderersFactory) -> ExoPlayer): ExoPlayer {
        val fadeEvidence = SinkEvidence()
        fadeEvidence.spectrumTaps(signalPath.subscriptionCount.value > 0)
        processing.addCompatibility(fadeEvidence.compatibilityProcessor.engine)
        if (usePrecisionProcessing) fadeEvidence.precisionProcessor = PrecisionBlockProcessor().also { processing.addPrecision(it) }
        processing.apply()
        fadeEvidence.precisionProcessor?.setImpulse(processing.currentImpulse, processing.lastAudioPrefs?.dspConvMakeupDb ?: 0f)
        fadeEvidence.compatibilityProcessor.engine.setImpulse(processing.currentImpulse, processing.lastAudioPrefs?.dspConvMakeupDb ?: 0f)
        fadeEvidence.precisionProcessor?.setRackImpulses(processing.rackImpulses)
        fadeEvidence.compatibilityProcessor.engine.setRackImpulses(processing.rackImpulses)
        fadeEvidence.compatibilityProcessor.tpdfDither = processing.outputRatePolicy.tpdfDither
        fadeEvidence.compatibilityProcessor.noiseShaping = processing.outputRatePolicy.noiseShaping == true
        val factory = object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(context: Context, extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    audioSink, eventHandler, eventListener, out)
            }
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val routedOutput = ConfirmedAudioRoute(context) { changed() }
                fadeEvidence.routedOutput?.close()
                fadeEvidence.routedOutput = routedOutput
                val base = DefaultAudioSink.Builder(context)
                    .setAudioTrackProvider(routedOutput.provider)
                    .setAudioProcessors(arrayOf(fadeEvidence.compatibilityProcessor, fadeEvidence.afterMeterProcessor))
                    .setEnableFloatOutput(useFloatOut)
                    .setEnableAudioTrackPlaybackParams(useFloatOut)
                    .build()
                val sink = fadeEvidence.precisionProcessor?.let {
                    PrecisionAudioSink(base, it, fadeEvidence.precisionAfterMeter,
                        { processing.outputRatePolicy }, { routedOutput.supportedSampleRates() }).also { wrapped -> fadeEvidence.precisionSink = wrapped }
                } ?: base
                return GaplessAudioSink(TappingAudioSink(sink, visualizer, fadeEvidence.beforeMeter,
                    onVolume = { fadeEvidence.downstreamGain = it }) { fadeEvidence.decoded = it }, ::gapMs, inert = true)
            }
        }
        return build(factory).also { attachSignalEvidence(it, fadeEvidence) }
    }

    private fun attachSignalEvidence(owner: ExoPlayer, evidence: SinkEvidence) {
        sinkEvidence[owner] = evidence
        owner.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
                bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                evidence.underruns++
                if (owner === player) changed()
            }
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                val id = runCatching { eventTime.timeline.getWindow(eventTime.windowIndex,
                    androidx.media3.common.Timeline.Window()).mediaItem.mediaId }.getOrNull()
                if (id != null) {
                    if (evidence.sources.size > 8) evidence.sources.clear()
                    evidence.sources[id] = format
                }
                if (owner === player) changed()
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                // decoder names are platform component ids, not track or server metadata
                evidence.decoder = decoderName.takeIf { it.matches(DECODER_NAME) }
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                evidence.tracks.initialized(audioTrackConfig)
                if (owner === player) changed()
            }
            override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                evidence.tracks.released(audioTrackConfig)
                if (owner === player) changed()
            }
            override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
                evidence.decoder = null
                // late disabled events can trail a newer sink config, only TappingAudioSink owns the decoded format
            }
        })
    }

    fun listen() {
        (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .registerAudioDeviceCallback(outputCallback, android.os.Handler(context.mainLooper))
        androidx.core.content.ContextCompat.registerReceiver(context, volumeReceiver,
            android.content.IntentFilter(VOLUME_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun release() {
        runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(outputCallback) }
        runCatching { context.unregisterReceiver(volumeReceiver) }
        hardwareVolume?.close()
        requestBitPerfect(null, null, false)
    }

    fun clearEvidence() {
        sinkEvidence.values.forEach { it.routedOutput?.close() }
        sinkEvidence.clear()
    }

    fun applyDither(policy: com.aurora.music.playback.engine.OutputRatePolicy) {
        sinkEvidence.values.forEach {
            it.compatibilityProcessor.tpdfDither = policy.tpdfDither
            it.compatibilityProcessor.noiseShaping = policy.noiseShaping == true
        }
    }

    fun spectrumTaps(on: Boolean) {
        sinkEvidence.values.forEach { it.spectrumTaps(on) }
    }

    fun downstreamGain(): Double? = sinkEvidence[player]?.downstreamGain?.toDouble()

    fun observedSource(mediaId: String?): Format? = sinkEvidence[player]?.sources?.get(mediaId)

    // youtube's webm and m4a streams rarely state a bitrate, so take the one the resolver picked
    private fun resolvedBitrate(item: MediaItem): Int? = item.localConfiguration?.uri
        ?.takeIf { it.scheme == "aurora-yt" }?.let { streamBitrateKbps(it) }?.times(1000)

    private fun requestedOutputDevice(): android.media.AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return null
        val id = preferredDeviceId.value
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { id != 0 && it.id == id }
    }

    fun requestBitPerfect(device: android.media.AudioDeviceInfo?, track: AudioSink.AudioTrackConfig?, on: Boolean,
        exactOnly: Boolean = true) {
        val key = "${device?.id}:${track?.sampleRate}:${track?.encoding}:${track?.channelConfig}:$on:$exactOnly"
        if (key == mixerRequestKey) return
        mixerRequestKey = key
        grantedBitPerfect = false
        deviceSupportsBitPerfect = false
        grantedMixerFormat = null
        mixerRequestDetail = null
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        mixerRequestedDevice?.let { previous -> runCatching { am.clearPreferredMixerAttributes(attrs, previous) } }
        mixerRequestedDevice = null
        if (!on || device == null || track == null) return
        runCatching {
            val supported = am.getSupportedMixerAttributes(device)
                .filter { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            deviceSupportsBitPerfect = supported.isNotEmpty()
            mixerRequestDetail = if (supported.isEmpty()) "Device exposes no bit-perfect mixer attributes"
                else "No bit-perfect mixer attribute matches this AudioTrack format"
            val wanted = MixerFormat(track.sampleRate, track.encoding, track.channelConfig)
            val chosen = BitPerfectMixerChoice.choose(supported.map { MixerFormat(it.format.sampleRate, it.format.encoding, it.format.channelMask) },
                wanted, exactOnly) ?: return@runCatching
            val attribute = supported.first { MixerFormat(it.format.sampleRate, it.format.encoding, it.format.channelMask) == chosen }
            grantedBitPerfect = am.setPreferredMixerAttributes(attrs, device, attribute)
            mixerRequestDetail = when {
                !grantedBitPerfect -> "Matching mixer preference was not accepted"
                chosen == wanted -> "Matching mixer preference accepted; actual route and HAL behavior remain unverified"
                else -> "Bit-perfect mixer preference accepted at the track rate in the device's sample format; Android's conversion is not observed"
            }
            if (grantedBitPerfect) {
                mixerRequestedDevice = device
                grantedMixerFormat = pcmFormat(chosen.sampleRate, chosen.encoding, Integer.bitCount(chosen.channelMask))
            }
        }
    }

    fun reportLocal() {
        val item = player.currentMediaItem
        if (item == null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            routes.publish(com.aurora.music.data.routes.ProcessingRoute())
            requestBitPerfect(null, null, false)
            val failure = rawDsdSink?.telemetry?.failure ?: rawDsdFailure.takeIf { player.playerError != null }
                ?: processedUsbSink?.telemetry?.fallbackReason ?: usbSink?.playbackTelemetry?.failure ?: usbFailure
            signalPath.value = if (bitPerfect && failure != null) SignalPath(note = failure,
                reasons = listOf(failure), output = "USB unavailable") else SignalPath()
            return
        }
        val state = sinkEvidence[player]
        val rawDsd = rawDsdSink?.telemetry
        if (rawDsd?.active == true || rawDsd?.failure != null) {
            requestBitPerfect(null, null, false)
            routes.publish(nativeUsbRoute("Raw DSD USB",
                "PCM processing is bypassed, so device presets have no effect here.", bypassesProcessing = true))
            val info = DsdSourceInfo.from(rawDsd.source)
            val sourceFormat = info?.let { SignalFormat(it.bitRate, 1, it.channels, "DSD") }
            val mode = if (rawDsd.wire == com.aurora.music.playback.dsd.DsdWireFormat.DOP) "DoP" else "Native DSD"
            val status = rawDsd.status
            val good = rawDsd.active && rawDsd.failure == null && status.error == null
            signalPath.value = SignalPath(active = good, codec = "${info?.container ?: "DSD"} / DSD",
                sampleRateHz = info?.bitRate ?: 0, bitDepth = 1, channels = info?.channels ?: 0,
                output = "$mode · USB", bitPerfect = good,
                preservation = if (good) com.aurora.music.data.Preservation.PRESERVED else com.aurora.music.data.Preservation.UNKNOWN,
                note = rawDsd.failure ?: status.error ?: "Raw DSD bypasses PCM processing and software volume.",
                reasons = listOf(rawDsd.failure ?: status.error ?: "Source DSD bits retained through USB packing"),
                source = com.aurora.music.data.SignalStage("Source", "${info?.container} / DSD${(info?.bitRate ?: 0) / 44100}", "Container header", sourceFormat),
                decoder = com.aurora.music.data.SignalStage("Decoder", "Raw DSD", "No PCM conversion", sourceFormat),
                processing = com.aurora.music.data.SignalStage("Processing", "Bypassed", "EQ, gain, ReplayGain, speed, dither and PCM meters bypassed"),
                resampling = com.aurora.music.data.SignalStage("Resampling", "None", "Original DSD bit rate"),
                outputStage = com.aurora.music.data.SignalStage("Output", mode, "USB clock readback: ${status.clockRate ?: 0} Hz",
                    SignalFormat(rawDsd.carrierRate, rawDsd.containerBits, 2, if (mode == "DoP") "DoP carrier" else "raw USB frames")),
                device = com.aurora.music.data.SignalStage("Device", "USB DAC", rawDsd.transportDetail ?: "USB transfers confirmed; DAC decoding is not measured"),
                usbDiagnostics = com.aurora.music.data.UsbDiagnostics(status.completedFrames, status.pendingFrames, status.packetErrors, status.timeouts))
            return
        }
        val native = usbSink?.playbackTelemetry
        val processed = processedUsbSink?.telemetry
        if (native?.usbActive == true || processed?.active == true) {
            usbFailure = null
            rawDsdFailure = null
        }
        val outputFailure = processed?.fallbackReason ?: native?.failure ?: usbFailure
        if (bitPerfect && usbFallback == UsbFallbackPolicy.PAUSE && outputFailure != null) {
            routes.publish(com.aurora.music.data.routes.ProcessingRoute())
            requestBitPerfect(null, null, false)
            signalPath.value = SignalPath(output = "USB unavailable", note = outputFailure, reasons = listOf(outputFailure))
            return
        }
        val processedUsb = processed?.active == true
        val usb = native?.usbActive == true || processedUsb
        val confirmedOutput = state?.routedOutput?.snapshot()
        routes.publish(if (usb)
            if (processedUsb) nativeUsbRoute("Processed USB output",
                "Device presets follow this DAC. Output preset rules are paused for native USB.", bypassesProcessing = false)
            else nativeUsbRoute("Direct USB output", "Aurora processing is bypassed, so device presets have no effect here.", bypassesProcessing = true)
            else confirmedOutput ?: com.aurora.music.data.routes.ProcessingRoute(
                com.aurora.music.data.routes.ProcessingRouteKind.UNKNOWN, label = "Output unconfirmed",
                detail = "Waiting for AudioTrack routing."))
        val nativeFlac = native?.nativeFlac == true
        val raw = state?.decoded ?: native?.decodedFormat
        val decoded = if (nativeFlac) SignalFormat(native!!.sourceRate.takeIf { it > 0 },
            native.sourceDepth.takeIf { it > 0 }, native.sourceChannels.takeIf { it > 0 }, "integer PCM")
            else raw?.let { pcmFormat(it.sampleRate, it.pcmEncoding, it.channelCount) }
        val source = state?.sources?.get(item.mediaId)
        val dsd = DsdSourceInfo.from(source) ?: DsdSourceInfo.from(raw)
        val sourceFormat = if (dsd != null) SignalFormat(dsd.bitRate, 1, dsd.channels, "DSD")
            else if (nativeFlac) decoded else source?.let { pcmFormat(it.sampleRate, it.pcmEncoding, it.channelCount) }
        val codec = if (dsd != null) "${dsd.container} / DSD" else if (nativeFlac) "FLAC" else source?.sampleMimeType?.substringAfter('/')?.uppercase()
            ?.takeIf { it.matches(CODEC_NAME) }.orEmpty()
        val track = state?.track
        val trackFormat = track?.let { pcmFormat(it.sampleRate, it.encoding, Integer.bitCount(it.channelConfig)) }
        // media3 floats only high-res decoded pcm, pcm16 still runs processors; fixed for the service lifetime
        val precise = processedUsb || (!usb && state?.precisionSink?.precisionActive == true)
        val precisionChain = if (processedUsb) processedUsbSink?.processor?.engine else state?.precisionProcessor
        val floatPath = if (state?.precisionSink != null) precise else usesFloatPcmPath(useFloatOut, decoded)
        val processors = !usb && !floatPath && raw != null
        val engine = if (precise) precisionChain else if (processors) state?.compatibilityProcessor?.engine else null
        val rackActive = engine?.rackActive == true
        val nodes = mutableListOf<String>()
        val bypassed = mutableListOf<String>()
        val modifications = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        if (dsd != null) modifications += "DSD converted to 176400 Hz float PCM with a low-pass decimator"
        outputFailure?.let { unknown += "USB: $it" }
        fun node(requested: Boolean, applied: Boolean, name: String) {
            if (applied) { nodes += name; modifications += "$name processes the current samples" }
            else if (requested) bypassed += name
        }
        val ap = processing.lastAudioPrefs
        val customDspActive = !rackActive && ap?.dspMode == DspMode.CUSTOM && engine?.processingActive == true
        val monoLocation = monoProcessingLocation(processing.monoAudioPref, customDspActive,
            !rackActive && processing.monoAudioPref && engine?.processingActive == true)
        node(processing.monoAudioPref, monoLocation == MonoProcessingLocation.CUSTOM_DSP || monoLocation == MonoProcessingLocation.PROCESSOR,
            if (monoLocation == MonoProcessingLocation.CUSTOM_DSP) "Mono downmix (inside Custom DSP, width = 0)" else "Mono downmix")
        node(!rackActive && ap?.dspMode == DspMode.CUSTOM, customDspActive,
            if (precise) "Custom Aurora DSP (binary64 coefficients, arithmetic and state; decoder precision retained)"
            else "Custom Aurora DSP (binary64 coefficients, arithmetic and state; PCM16 input/output)")
        node(!rackActive && ap?.dspConvEnabled == true, !rackActive && engine?.convolutionProcessingActive == true,
            if (precise) "Convolution (binary64 FFT and state; no intermediate PCM16 conversion)"
            else "Convolution (binary64 FFT and state; PCM16 input/output)")
        if (rackActive) {
            val description = engine?.rackDescription.orEmpty()
            nodes += "${if (description.contains("Routed graph")) "Processing graph" else "Serial rack"} (binary64): $description"
            modifications += "The active processing rack processes the current samples"
            engine?.convolutionUnavailableReason?.let { unknown += it }
            if (processing.monoAudioPref) unknown += "The rack owns channel routing; the standard mono setting does not override its nodes"
        } else if (processing.lastRack?.enabled == true && ap?.dspMode == DspMode.CUSTOM) {
            unknown += "Processing rack requested; waiting for a supported stereo stream and prepared schedule"
        }
        node(processing.reverbPrefs.enabled, engine?.reverbActive == true, processing.reverbPrefs.concertPrefs.takeIf { it.enabled }?.let { "Concert venue simulation (${if (it.binaural) "binaural" else "stereo"}${if (engine?.measuredRoomLoaded == true) " in a measured room" else ""}, ${com.aurora.music.data.ConcertVenue.ALL[it.venue].name}, ${com.aurora.music.data.ConcertSound.ALL[it.sound].name} rig at ${it.showDb.toInt()} dB, ${(it.amount * 100).toInt()}% amount)" }
            ?: "Reverb (binary64 feedback delay network, ${(processing.reverbPrefs.mix * 100).toInt()}% mix)")
        if ((processors || precise) && (ap?.dspConvEnabled == true || rackActive)) {
            when (engine?.preparationState) {
                ConvolutionPreparationState.PREPARING -> unknown += "Processing configuration is preparing; the previous ready configuration continues when available"
                ConvolutionPreparationState.FAILED -> unknown += "The requested processing configuration could not be prepared; the previous configuration or dry convolution fallback remains active. " +
                    (engine?.preparationFailure ?: "Impulse response could not be prepared")
                ConvolutionPreparationState.IDLE -> if (!rackActive && ap?.dspConvEnabled == true) unknown += processing.impulseLoadFailure
                    ?: "Convolution was requested but no valid impulse response is loaded"
                else -> Unit
            }
        }
        if (!usb) state?.precisionSink?.fallbackReason?.let { unknown += "High-resolution processing uses compatibility output: $it" }
        if (processedUsb) {
            nodes += "Single ${processed?.validBits}-bit integer conversion after Aurora processing"
            if (processing.outputRatePolicy.tpdfDither && (raw?.pcmEncoding == C.ENCODING_PCM_FLOAT ||
                    (decoded?.bitDepth ?: 0) > (processed?.validBits ?: 32) || engine?.processingChangesSamples == true ||
                    processed?.source?.sampleRate != processed?.output?.sampleRate || processed?.softwareGain != 1.0))
                nodes += "${processing.outputRatePolicy.ditherLabel(processed?.output?.sampleRate ?: 0)} at the final integer conversion"
            if (fades.crossfadeMs > 0) bypassed += "Crossfade (processed USB)"
        } else if (precise) {
            nodes += "Single float32 output conversion after Aurora processing"
            unknown += "Android owns the output clock and may resample or mix the float32 AudioTrack"
            if (decoded?.encoding != "float PCM" && (decoded?.bitDepth ?: 0) > 24)
                unknown += "Float32 output has 24 significant bits; it cannot preserve every PCM32 integer value"
        }
        if (processors && (engine?.processingActive == true || engine?.reverbActive == true))
            nodes += "Single PCM16 output conversion after binary64 global processing"
        if (processors && processing.outputRatePolicy.tpdfDither && engine?.processingChangesSamples == true)
            nodes += "${processing.outputRatePolicy.ditherLabel(raw?.sampleRate ?: 0)} at the final PCM16 conversion"
        if (player.skipSilenceEnabled) {
            if (usb && !processedUsb) bypassed += "Silence trimming (bit-perfect USB)"
            else {
                nodes += "Silence trimming (level-relative threshold, faded cuts)"
                unknown += "Silence trimming removes near-silent passages and fades their edges; removed-sample counts are not instrumented"
            }
        }
        val systemEffects = processing.audioEffects?.activeEffectNames().orEmpty()
        if (usb) {
            bypassed += systemEffects
            if (ap?.dspMode == DspMode.SYSTEM && systemEffects.isEmpty()) bypassed += "System effects"
        } else if (systemEffects.isNotEmpty()) {
            // an enabled effect may not be attached to a reused or changed player session
            unknown += "System effects report enabled (${systemEffects.joinToString()}); attachment and processing on the current AudioTrack are not independently measured"
        }
        val rg = fades.replayGainMultiplier()
        if (usb && !processedUsb) {
            hardwareVolume?.state?.let { hw ->
                nodes += "DAC hardware volume " + (if (hw.muted) "muted" else String.format(java.util.Locale.US, "%.1f dB", hw.current / 256.0)) +
                    " (USB feature unit; samples unchanged)"
            }
            if (rg != 1f) bypassed += "ReplayGain (native output ignores player volume)"
            if (player.volume != 1f) bypassed += "Player volume / fades (native output ignores player volume)"
            if (nativeFlac && kotlin.math.abs(native!!.nativeSpeed - 1.0) > 0.00001) {
                nodes += "Native linear varispeed (${native.nativeSpeed}×)"
                modifications += "Native varispeed resamples the decoded signal"
            } else if (!nativeFlac && player.playbackParameters.speed != 1f) bypassed += "Speed (decoded USB path keeps native rate)"
            if (native?.tailSubmitted == true) unknown += "Native crossfade request submitted; driver does not expose applied/completed state"
        } else {
            if (rg != 1f && player.volume != 1f) nodes += "ReplayGain attenuation ($rg×)"
            if (player.volume != 1f) { nodes += "Player gain (${player.volume}×)"; modifications += "Player volume/fade gain differs from unity" }
            if (fades.sleepFadeActive || fades.wakeFadeActive) { nodes += "Sleep / alarm fade"; modifications += "A playback fade is active" }
            if (fades.xfadeActive) { nodes += "Crossfade"; modifications += "Two player outputs overlap with fade gains" }
            if (player.playbackParameters.speed != 1f || player.playbackParameters.pitch != 1f) {
                if (processedUsb) bypassed += "Speed / pitch (processed USB)"
                else { nodes += "Speed / pitch adjustment"; modifications += "Playback speed or pitch differs from unity" }
            }
            if (processedUsb && processed?.softwareGain != 1.0) {
                nodes += "USB software gain (${processed?.softwareGain}×)"
                modifications += "Software volume attenuates samples before USB output"
            }
            val sourceBits = sourceFormat?.bitDepth
            val decodedBits = decoded?.bitDepth
            if (sourceBits != null && decodedBits != null && sourceBits > decodedBits)
                modifications += "Decoder reduces the source sample depth"
        }
        val device = requestedOutputDevice()
        // automatic output may ask the usb mixer, never report it as the observed route
        val opportunisticMixer = !usb && !bitPerfect && useFloatOut && !usePrecisionProcessing
        // exclusive usb that fell back to android asks for the bit-perfect mixer at the track rate, as 1.x did
        val exclusiveFallback = !usb && bitPerfect
        val automaticMixerCandidate = if (device == null && preferredDeviceId.value == 0 &&
            (opportunisticMixer || exclusiveFallback)) {
            (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isUsb(it.type) }
        } else null
        val mixerDevice = device ?: automaticMixerCandidate
        requestBitPerfect(mixerDevice, track, (opportunisticMixer || exclusiveFallback) && mixerDevice != null && isUsb(mixerDevice.type),
            exactOnly = !exclusiveFallback)
        if (signalPath.subscriptionCount.value > 0 && state != null && (!usb || processedUsb) && raw != null && !state.spectrumBusy &&
            (player.isPlaying || state.spectrum == null || state.spectrumGeneration != state.beforeMeter.spectrum.generation) &&
            System.nanoTime() - state.spectrumRequestedAt > 450_000_000L) {
            state.spectrumBusy = true
            state.spectrumRequestedAt = System.nanoTime()
            val generation = state.beforeMeter.spectrum.generation
            scope.launch {
                try {
                    val spectrum = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        val before = state.beforeMeter.spectrum.windows()
                        val after = if (precise) state.precisionAfterMeter.spectrum.windows() else emptyList()
                        PcmSpectrumAnalyzer.snapshot(before, after)
                    }
                    if (generation == state.beforeMeter.spectrum.generation) {
                        state.spectrum = spectrum
                        state.spectrumGeneration = generation
                    }
                } finally { state.spectrumBusy = false }
            }
        }
        val sourceCopy = when (item.localConfiguration?.uri?.scheme?.lowercase()) {
            "file", "content" -> "Local playable copy"
            "http", "https" -> "Network playable stream"
            "aurora-yt", "aurora-sc" -> "Resolved network playable stream"
            else -> "Playable copy; origin unknown"
        }
        val desiredFloat = exclusiveUsbPref || preferHighResPref
        val measuredAfter = precise || (processors && state?.afterMeterProcessor?.isActive == true)
        val sourceBitrate = source?.averageBitrate?.takeIf { it > 0 } ?: resolvedBitrate(item)
        streamFormat.value = StreamFormat(item.mediaId, codec, sourceFormat?.rateHz ?: 0, sourceFormat?.bitDepth ?: 0,
            (sourceBitrate ?: 0) / 1000)
        signalPath.value = buildSignalPath(SignalPathFacts(
            kind = when { processedUsb -> PlaybackPathKind.PROCESSED_USB; nativeFlac -> PlaybackPathKind.NATIVE_USB; usb -> PlaybackPathKind.DECODED_USB; else -> PlaybackPathKind.ANDROID },
            sourceCopy = sourceCopy, codec = codec, sourceFormat = sourceFormat,
            sourceBitrate = sourceBitrate,
            decoderName = if (dsd != null) "Aurora DSD decimator" else if (nativeFlac) "Native libFLAC" else state?.decoder,
            decodedFormat = decoded,
            processorPath = when {
                processedUsb -> "Decoded PCM → binary64 Aurora processing → integer USB output"
                nativeFlac -> "Native FLAC → USB transport; Android/app processor chain bypassed"
                usb -> "Media3 decoded PCM → USB transport; Android/app processor chain bypassed"
                raw == null -> "Waiting for decoded sink format"
                precise -> "Decoded PCM → binary64 Aurora processing → float32 Android output"
                state?.precisionSink != null -> "Compatibility PCM16 path; gapless trimming and silence skipping remain available"
                floatPath -> "High-resolution float path; app AudioProcessors bypassed"
                else -> "Android PCM processor path (PCM16 transport)"
            }, activeNodes = nodes, bypassedNodes = bypassed, modifications = modifications, unknowns = unknown,
            androidTrackFormat = if (usb) null else trackFormat,
            nativeTransportFormat = if (processedUsb) processed?.output?.let { SignalFormat(it.sampleRate, processed.validBits, it.channelCount, "integer PCM") }
                else if (usb) SignalFormat(native!!.transportRate.takeIf { it > 0 },
                    native.transportValidBits.takeIf { it > 0 }, native.transportChannels.takeIf { it > 0 }, "integer PCM") else null,
            nativeClockAccepted = if (processedUsb) processed?.status?.clockRate == processed?.output?.sampleRate else native?.clockRequestAccepted == true,
            nativeTailSubmitted = native?.tailSubmitted == true,
            nativeContainerBits = if (processedUsb) processed?.containerBits else native?.transportContainerBits?.takeIf { it > 0 },
            nativeClockRate = if (processedUsb) processed?.status?.clockRate else native?.observedClockRate,
            mixerGrant = grantedBitPerfect, mixerGrantMatchesFormat = grantedMixerFormat != null && grantedMixerFormat == trackFormat,
            mixerRequestDetail = if (automaticMixerCandidate != null)
                "Automatic-output USB candidate (route unverified). ${mixerRequestDetail ?: "Request result unknown"}"
                else mixerRequestDetail,
            requestedDevice = device?.let { routeCategory(it.type) }, exclusiveRequested = bitPerfect,
            confirmedDevice = if (usb) null else confirmedOutput?.category,
            restartRequired = exclusiveUsbPref != bitPerfect || desiredFloat != useFloatOut ||
                bitPerfect && (usbModePref != usbMode || usbFallbackPref != usbFallback),
        )).copy(audioTrackUnderruns = if (!usb && raw != null) state?.underruns else null,
            usbDiagnostics = if (processedUsb) processed?.status?.let { com.aurora.music.data.UsbDiagnostics(it.completedFrames, it.pendingFrames, it.packetErrors, it.timeouts) }
                else if (usb && native != null) com.aurora.music.data.UsbDiagnostics(native.completedFrames, native.pendingFrames, native.packetErrors, native.timeouts) else null,
            measurements = if ((!usb || processedUsb) && raw != null && state != null)
            com.aurora.music.data.AudioMeasurements(state.beforeMeter.snapshot(),
                if (precise) state.precisionAfterMeter.snapshot() else if (measuredAfter) state.afterMeter.snapshot() else null,
                player.isPlaying, measuredAfter, fades.xfadeActive,
                state.spectrum?.takeIf { state.spectrumGeneration == state.beforeMeter.spectrum.generation &&
                    (!player.isPlaying || System.nanoTime() - it.measuredAtNanos < 2_000_000_000L) }
                    ?.let { if (precise) it else it.copy(afterDb = null) })
            else null,
            nodeMeters = engine?.rackMeters().orEmpty(),
            latency = com.aurora.music.data.SignalStage("Latency",
                if (engine == null) "Processor delay unavailable on this output"
                else "Rack: ${engine.rackLatencyFrames} frames · SRC: ${if (processedUsb) processedUsbSink?.processor?.resamplingLatencyFrames ?: 0 else state?.precisionSink?.resamplingLatencyFrames ?: 0} frames · Tail: ${engine.rackTailFrames} frames",
                "Compiled processor delay; sink and hardware latency are unknown"),
            resampling = com.aurora.music.data.SignalStage("Resampling",
                if (processedUsb) processedUsbSink?.processor?.let { sink ->
                    sink.rateFallbackReason ?: if (sink.outputFormat.sampleRate == raw?.sampleRate) "Following source rate"
                    else "${raw?.sampleRate} → ${sink.outputFormat.sampleRate} Hz · Bandlimited SRC"
                } ?: "Waiting for USB configuration" else if (precise) state?.precisionSink?.let { sink ->
                    sink.rateFallbackReason ?: if (sink.configuredOutputSampleRate == raw?.sampleRate) "Following source rate"
                    else "${raw?.sampleRate} → ${sink.configuredOutputSampleRate} Hz · Bandlimited SRC"
                } ?: "Waiting for output configuration" else "No Aurora sample-rate conversion observed",
                "Active sink configuration; downstream conversion is unknown"))
    }

    private fun nativeUsbRoute(label: String, detail: String, bypassesProcessing: Boolean) = nativeRoutes.getOrPut(label) {
        ConfirmedAudioRoute.nativeRoute(context, label, detail, bypassesProcessing)
    }

    private fun softwareUsbVolume(): Double {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toDouble() /
            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    fun rendererRoute(): EndpointRoute {
        val path = signalPath.value
        val usb = processedUsbSink?.telemetry?.active == true || usbSink?.playbackTelemetry?.usbActive == true
        val id = if (usb) if (usbMode == UsbOutputMode.PROCESSED) "usb-processed" else "usb-direct" else "local"
        return EndpointRoute(id, path.output.ifBlank { "This device" }, sampleRateHz = path.outputStage.format?.rateHz ?: 0,
            bitDepth = path.outputStage.format?.bitDepth ?: 0, processed = path.preservation == com.aurora.music.data.Preservation.MODIFIED)
    }

    companion object {
        private const val VOLUME_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val VOLUME_STREAM_EXTRA = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private val DECODER_NAME = Regex("[A-Za-z0-9_.-]{1,160}")
        private val CODEC_NAME = Regex("[A-Z0-9.+_-]{1,60}")

        fun isUsb(t: Int) = t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY

        fun routeCategory(type: Int): String = when (type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth output (codec unknown)"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired output"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            else -> if (isUsb(type)) "USB audio device" else "selected Android output"
        }

        fun pcmFormat(rate: Int, encoding: Int, channels: Int): SignalFormat = SignalFormat(
            rateHz = rate.takeIf { it > 0 }, channels = channels.takeIf { it > 0 },
            bitDepth = when (encoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
                else -> null
            }, encoding = when (encoding) {
                C.ENCODING_PCM_FLOAT -> "float PCM"
                C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
                C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "integer PCM"
                else -> null
            },
        )
    }
}
