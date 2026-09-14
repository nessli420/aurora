package com.aurora.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioEffectsController
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.SignalPath
import com.aurora.music.data.SignalFormat
import com.aurora.music.data.SignalPathFacts
import com.aurora.music.data.PlaybackPathKind
import com.aurora.music.data.buildSignalPath
import com.aurora.music.data.usesFloatPcmPath
import com.aurora.music.data.monoProcessingLocation
import com.aurora.music.data.MonoProcessingLocation
import androidx.media3.common.Format
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val container by lazy { (application as AuroraApplication).container }
    private val browseCache = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()
    private val LIBRARY_ROOT = "root"
    private lateinit var player: ExoPlayer
    private var fadePlayer: ExoPlayer? = null
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private var mixPlayer: com.aurora.music.mix.MixPlayer? = null
    @Volatile private var lastIrPath: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioEffects: AudioEffectsController? = null
    @Volatile private var crossfadeMs: Int = 0
    @Volatile private var replayGainMode: Int = 0
    @Volatile private var monoAudioPref: Boolean = false
    @Volatile private var lastAudioPrefs: AudioPrefs? = null
    @Volatile private var useFloatOut: Boolean = false
    private var usePrecisionProcessing = false
    private val precisionChains = mutableListOf<PrecisionBlockProcessor>()
    private val compatibilityChains = mutableListOf<PrecisionBlockProcessor>()
    private var lastRack: com.aurora.music.data.ProcessingRack? = null
    @Volatile private var grantedBitPerfect: Boolean = false
    @Volatile private var deviceSupportsBitPerfect: Boolean = false
    @Volatile private var preferHighResPref: Boolean = false
    @Volatile private var independentOutput: Boolean = false
    private var exclusiveUsbPref = false
    private class SinkEvidence {
        val beforeMeter = PcmLevelMeter()
        val afterMeter = PcmLevelMeter()
        val precisionAfterMeter = PcmLevelMeter()
        val afterMeterProcessor = LevelMeterAudioProcessor(afterMeter)
        val compatibilityProcessor = PrecisionRackAudioProcessor()
        var precisionProcessor: PrecisionBlockProcessor? = null
        @Volatile var precisionSink: PrecisionAudioSink? = null
        @Volatile var decoded: Format? = null
        val tracks = OutputTrackEvidence<AudioSink.AudioTrackConfig> { a, b ->
            a.sampleRate == b.sampleRate && a.encoding == b.encoding && a.channelConfig == b.channelConfig &&
                a.tunneling == b.tunneling && a.offload == b.offload && a.bufferSize == b.bufferSize
        }
        val track: AudioSink.AudioTrackConfig? get() = tracks.configuration
        var decoder: String? = null
        var underruns: Long = 0L
        val sources = mutableMapOf<String, Format>()
    }
    private val sinkEvidence = java.util.IdentityHashMap<ExoPlayer, SinkEvidence>()
    private var mixerRequestKey: String? = null
    private var grantedMixerFormat: SignalFormat? = null
    private var mixerRequestDetail: String? = null
    private var mixerRequestedDevice: android.media.AudioDeviceInfo? = null
    private val outputCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) { mixerRequestKey = null; updateSignalPath() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) { mixerRequestKey = null; updateSignalPath() }
    }

    @Volatile private var sleepFadeActive = false
    private var sleepFadeStartMs = 0L
    private var sleepFadeMs = 0
    @Volatile private var wakeFadeActive = false
    private var wakeFadeStartMs = 0L
    private var wakeFadeMs = 0
    private val nowPlaying by lazy { NowPlayingStore(this) }

    private var xfadeActive = false
    @Volatile private var xfadeBpPending = false   // bit-perfect crossfade fired, awaiting the transition
    private var xfadeStartMs = 0L
    private var xfadeDurationMs = 0L
    private var xfadeElapsedMs = 0L
    private var preparedKey: String? = null
    private var preparedFailedKey: String? = null
    private var nativeEligibilityKey: String? = null
    private var nativeEligible = false
    private var crossfadeCurve = "SMOOTH"
    private var crossfadeHeadroom = true
    private var activeCurve = "SMOOTH"
    private var activeHeadroom = true
    private lateinit var musicSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory
    private var currentImpulse: ImpulseResponse? = null
    private var impulseLoadFailure: String? = null
    private var currentDspParams = DspParams()
    private var xfadeExpectedId: String? = null
    private var xfadeInGain = 1f
    private var xfadeOutGain = 1f
    @Volatile private var bitPerfect = false // exclusive USB uses its native mixer, never a second Android output

    // pre-shuffle queue order for restore on disable null = not shuffled
    private var originalOrder: List<String>? = null
    // items may lag the command so flag and apply neutralize on next timeline change
    private var pendingNeutralize = false
    private var usbSink: com.decent.usbaudio.media3.UsbAudioSink? = null

    override fun onCreate() {
        super.onCreate()

        // The opt-in decoder-side processor retains precision before Media3's PCM16 conversion.
        // Sink selection stays fixed until the service restarts; effect settings remain live.
        val highRes = runBlocking { container.settingsStore.playbackPrefs.first().preferHighRes }
        val bitPerfectUsb = runBlocking { container.settingsStore.playbackPrefs.first().bitPerfectUsb }
        bitPerfect = bitPerfectUsb
        val useFloat = bitPerfectUsb || highRes
        usePrecisionProcessing = highRes && !bitPerfectUsb
        useFloatOut = useFloat
        val initialEvidence = SinkEvidence()
        compatibilityChains += initialEvidence.compatibilityProcessor.engine
        if (usePrecisionProcessing) initialEvidence.precisionProcessor = PrecisionBlockProcessor().also { precisionChains += it }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                if (bitPerfectUsb) {
                    // no dsp processors which would defeat bit-perfect
                    val delegate = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .build()
                    return com.decent.usbaudio.media3.UsbAudioSink(delegate, context).also {
                        usbSink = it
                        it.pcmTap = com.decent.usbaudio.media3.UsbAudioSink.PcmTap { buf, enc, ch, sr ->
                            container.visualizer.pushPcm(buf, enc, ch, sr)
                        }
                        // native libflac decodes in c++ and never reaches handleBuffer
                        val sink = it
                        container.visualizer.monoSource = object : VisualizerController.MonoSource {
                            override fun read(out: FloatArray) = sink.readNativePcm(out)
                            override fun sampleRate() = sink.nativeEngineSampleRate
                            override fun active() = sink.nativeEngineActive
                        }
                    }
                }
                val base = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(initialEvidence.compatibilityProcessor, initialEvidence.afterMeterProcessor))
                    .setEnableFloatOutput(useFloat)
                    // float bypasses sonic so use hardware playback params for speed
                    .setEnableAudioTrackPlaybackParams(useFloat || enableAudioTrackPlaybackParams)
                    .build()
                val sink = initialEvidence.precisionProcessor?.let {
                    PrecisionAudioSink(base, it, initialEvidence.precisionAfterMeter).also { wrapped -> initialEvidence.precisionSink = wrapped }
                } ?: base
                return TappingAudioSink(sink, container.visualizer, initialEvidence.beforeMeter) { initialEvidence.decoded = it }
            }
        }
        // prefer ffmpeg decoder so non-flac content decodes to float32
        if (bitPerfectUsb) {
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // resolve aurora-yt sentinel uris to a real youtube stream just-in-time on the loader thread
        val resolver = container.youtubeResolver
        val ytResolver = androidx.media3.datasource.ResolvingDataSource.Resolver { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "aurora-yt") {
                val real = resolver.resolve(
                    uri.host.orEmpty(),
                    uri.getQueryParameter("q").orEmpty(),
                    uri.getQueryParameter("dur")?.toIntOrNull() ?: 0,
                ) ?: throw java.io.IOException("No stream found for this track")
                dataSpec.withUri(android.net.Uri.parse(real))
            } else dataSpec
        }
        val dataSourceFactory = androidx.media3.datasource.ResolvingDataSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this), ytResolver,
        )
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

        musicSourceFactory = mediaSourceFactory
        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
        if (bitPerfectUsb) {
            // stop exoplayer reading the file while the native flac engine handles decode + usb
            playerBuilder.setLoadControl(
                com.decent.usbaudio.media3.UsbAudioSink.wrapLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl.Builder().build()
                ) { usbSink?.isNativeEngineActive == true }
            )
        }
        player = playerBuilder.build()
        attachSignalEvidence(player, initialEvidence)
        if (bitPerfectUsb) usbSink?.attachToPlayer(player)

        // usb host permission isnt persisted across process restarts so re-acquire on startup else sink silently falls back to normal output
        if (bitPerfectUsb) {
            runCatching {
                val dev = com.decent.usbaudio.UsbAudioDevice.getInstance(this)
                dev.findUsbAudioDevice()?.let { d -> if (!dev.hasPermission(d)) dev.requestPermission(d) {} }
            }
        }

        // share session id so the system dsp effects created on it apply to playback
        if (container.audioSessionId != 0) {
            runCatching { player.setAudioSessionId(container.audioSessionId) }
        }

        player.addListener(serviceListener(player))

        mediaSession = MediaLibrarySession.Builder(this, player, MediaCallback())
            .setCustomLayout(buildCustomLayout())
            .build()

        setupCast()

        startAudioObservers(audioAttributes)
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager)
            .registerAudioDeviceCallback(outputCallback, android.os.Handler(mainLooper))
    }

    private fun attachSignalEvidence(owner: ExoPlayer, evidence: SinkEvidence) {
        sinkEvidence[owner] = evidence
        owner.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
                bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                evidence.underruns++
                if (owner === player) updateSignalPath()
            }
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                val id = runCatching { eventTime.timeline.getWindow(eventTime.windowIndex,
                    androidx.media3.common.Timeline.Window()).mediaItem.mediaId }.getOrNull()
                if (id != null) {
                    if (evidence.sources.size > 8) evidence.sources.clear()
                    evidence.sources[id] = format
                }
                if (owner === player) updateSignalPath()
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                // Decoder names are platform component identifiers, not track/server metadata.
                evidence.decoder = decoderName.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,160}")) }
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                evidence.tracks.initialized(audioTrackConfig)
                if (owner === player) updateSignalPath()
            }
            override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                evidence.tracks.released(audioTrackConfig)
                if (owner === player) updateSignalPath()
            }
            override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
                evidence.decoder = null
                // Decoder analytics arrive on the application thread. An old disabled event
                // may follow a new sink configuration published by the playback thread.
                // Only TappingAudioSink configure/reset owns the decoded PCM format.
            }
        })
    }

    private fun serviceListener(owner: ExoPlayer) = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (p !== player) return
                if (xfadeActive && (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))) {
                    fadePlayer?.playWhenReady = p.isPlaying
                }
                if (!xfadeActive && events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                    clearPrepared()
                    preparedFailedKey = null
                }
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                ) updateCustomLayout()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) maybeNeutralize()
                if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
                ) updateSignalPath()
                // bit-perfect: speed/pitch can't flow through exoplayer's bypassed processors so drive the
                // native usb engine's varispeed directly (reverts to true bit-perfect at 1.0x)
                if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                ) usbSink?.setTimeStretch(p.playbackParameters.speed)
                // the bit-perfect crossfade transition landed — re-arm for the next boundary. also re-arm on
                // any timeline change so the latch can never get stuck if the advance didn't yield a transition.
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED)
                ) xfadeBpPending = false
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) publishNowPlaying()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (owner === player && xfadeActive && reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.mediaItemIndex == player.currentMediaItemIndex) endXfade()
            }
        }

    private fun startAudioObservers(audioAttributes: AudioAttributes) {
        val store = container.settingsStore
        audioEffects = container.audioEffects

        scope.launch {
            container.mixController.commands.collect { command ->
                val mix = mixPlayer ?: return@collect
                when (command) {
                    com.aurora.music.mix.MixCommand.Toggle -> if (mix.playWhenReady) mix.pause() else mix.play()
                    com.aurora.music.mix.MixCommand.Stop -> stopMix()
                    is com.aurora.music.mix.MixCommand.Seek -> mix.seekTimeline(command.seconds)
                    is com.aurora.music.mix.MixCommand.Update -> mix.updateProject(command.project)
                }
            }
        }

        scope.launch {
            // A preset updates both groups in one DataStore transaction. Consume that same
            // snapshot so the engine never receives new channel settings with the old EQ.
            store.processingSettings.collect { snapshot ->
                val prefs = snapshot.playback
                lastAudioPrefs = snapshot.audio
                lastRack = snapshot.rack
                replayGainMode = snapshot.audio.replayGain
                player.skipSilenceEnabled = prefs.skipSilence
                monoAudioPref = prefs.monoAudio
                crossfadeMs = prefs.crossfadeSec * 1000
                crossfadeCurve = prefs.crossfadeCurve
                crossfadeHeadroom = prefs.crossfadeHeadroom
                preferHighResPref = prefs.preferHighRes
                exclusiveUsbPref = prefs.bitPerfectUsb
                // independent output: drop audio focus so other apps keep playing through the speaker while
                // aurora streams to its own device. handleAudioFocus is runtime-switchable via setAudioAttributes.
                if (prefs.independentOutput != independentOutput) {
                    independentOutput = prefs.independentOutput
                    runCatching { player.setAudioAttributes(audioAttributes, !independentOutput) }
                }
                applyAudioEngine()
            }
        }
        scope.launch {
            container.preferredAudioDeviceId.collect { id -> applyPreferredDevice(id) }
        }

        scope.launch {
            while (isActive) {
                // ramp finely while a fade is in flight idle replaygain tracking needs only a coarse tick
                delay(if (xfadeActive || sleepFadeActive || wakeFadeActive) 25L else 100L)
                tickAudio()
            }
        }
        // USB engine and volume/fade changes need snapshots even without a Media3 track event.
        scope.launch { while (isActive) { delay(500); updateSignalPath() } }
    }

    // runtime-switchable via volatile flags no rebuild the two eq engines are mutually exclusive so they never stack
    private fun applyAudioEngine() {
        val ap = lastAudioPrefs ?: return
        val mode = ap.dspMode
        val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(ap.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
        val graphic = FloatArray(layout.freqs.size) { ap.dspGraphicBands.getOrElse(it) { 0f } }
        val params = DspParams(
            graphic = graphic,
            graphicFreqs = layout.freqs,
            graphicQ = layout.q,
            parametric = ap.dspParametric.map { DspBand(it.freqHz, it.gainDb, it.q, it.type) },
            preampDb = ap.dspPreampDb,
            balance = ap.dspBalance,
            width = if (monoAudioPref) 0f else ap.dspWidth,
            crossfeed = ap.dspCrossfeed,
            saturation = ap.dspSaturation,
            delayLeftMs = ap.dspDelayLeftMs,
            delayRightMs = ap.dspDelayRightMs,
            trimLeftDb = ap.dspTrimLeftDb,
            trimRightDb = ap.dspTrimRightDb,
            limiterEnabled = ap.dspLimiterEnabled,
            limiterCeilingDb = ap.dspLimiterCeilingDb,
            compEnabled = ap.dspCompEnabled,
            compThreshDb = ap.dspCompThreshDb,
            compRatio = ap.dspCompRatio,
        )
        currentDspParams = params
        audioEffects?.setMasterEnabled(mode == DspMode.SYSTEM)

        if (ap.dspConvIrPath != lastIrPath) {
            lastIrPath = ap.dspConvIrPath
            scope.launch {
                val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (ap.dspConvIrPath.isBlank()) Result.success<ImpulseResponse?>(null)
                    else ConvolutionProcessor.loadWavResult(java.io.File(ap.dspConvIrPath))
                }
                if (lastIrPath != ap.dspConvIrPath) return@launch
                val ir = loaded.getOrNull()
                // WAV validation errors are fixed technical messages. File-system exceptions
                // can contain local paths, so keep those out of the shareable Signal Path report.
                impulseLoadFailure = loaded.exceptionOrNull()?.let {
                    if (it is IllegalArgumentException) it.message else "The selected impulse response could not be read"
                }
                currentImpulse = ir
                // Loading can outlive a makeup-only settings change. Do not restore its old gain.
                val makeupDb = lastAudioPrefs?.dspConvMakeupDb ?: ap.dspConvMakeupDb
                (precisionChains + compatibilityChains).forEach { it.setImpulse(ir, makeupDb) }
                mixPlayer?.applyAudioConfig(mixAudioConfig())
            }
        }
        (precisionChains + compatibilityChains).forEach { chain ->
            // OFF/System mono still runs in binary64 before the output boundary.
            chain.update(if (mode == DspMode.CUSTOM) params else DspParams(width = if (monoAudioPref) 0f else 1f, limiterEnabled = false))
            chain.enabled = mode == DspMode.CUSTOM || monoAudioPref
            chain.convolutionEnabled = ap.dspConvEnabled
            chain.setMakeup(ap.dspConvMakeupDb)
            chain.updateRack(lastRack?.takeIf { it.enabled && mode == DspMode.CUSTOM })
        }
        mixPlayer?.applyAudioConfig(mixAudioConfig())
        updateSignalPath()
    }

    private fun applyPreferredDevice(id: Int) {
        mixPlayer?.refreshOutputDevice()
        runCatching {
            if (id == 0) {
                player.setPreferredAudioDevice(null)
                fadePlayer?.setPreferredAudioDevice(null)
            } else {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val device = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                player.setPreferredAudioDevice(device)
                fadePlayer?.setPreferredAudioDevice(device)
            }
        }
        updateSignalPath()
    }

    private fun requestedOutputDevice(): android.media.AudioDeviceInfo? {
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return null
        val id = container.preferredAudioDeviceId.value
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { id != 0 && it.id == id }
    }

    private fun isUsb(t: Int) = t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
        t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun routeCategory(device: android.media.AudioDeviceInfo): String = when (device.type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth output (codec unknown)"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired output"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        else -> if (isUsb(device.type)) "USB audio device" else "selected Android output"
    }

    private fun pcmFormat(rate: Int, encoding: Int, channels: Int): SignalFormat = SignalFormat(
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

    private fun requestBitPerfect(device: android.media.AudioDeviceInfo?, track: AudioSink.AudioTrackConfig?, on: Boolean) {
        val key = "${device?.id}:${track?.sampleRate}:${track?.encoding}:${track?.channelConfig}:$on"
        if (key == mixerRequestKey) return
        mixerRequestKey = key
        grantedBitPerfect = false
        deviceSupportsBitPerfect = false
        grantedMixerFormat = null
        mixerRequestDetail = null
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
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
            // A grant for a different rate/depth/channel layout is not useful to this AudioTrack.
            val exact = supported.firstOrNull {
                it.format.sampleRate == track.sampleRate && it.format.encoding == track.encoding &&
                    it.format.channelMask == track.channelConfig
            } ?: return@runCatching
            grantedBitPerfect = am.setPreferredMixerAttributes(attrs, device, exact)
            mixerRequestDetail = if (grantedBitPerfect) "Matching mixer preference accepted; actual route and HAL behavior remain unverified"
                else "Matching mixer preference was not accepted"
            if (grantedBitPerfect) {
                mixerRequestedDevice = device
                grantedMixerFormat = pcmFormat(track.sampleRate, track.encoding, Integer.bitCount(track.channelConfig))
            }
        }
    }

    private fun updateSignalPath() {
        if (!::player.isInitialized) return
        val active = mediaSession?.player ?: player
        if (mixPlayer != null && active === mixPlayer) {
            requestBitPerfect(null, null, false)
            val path = buildSignalPath(SignalPathFacts(kind = if (active.playbackState == Player.STATE_IDLE ||
                active.playbackState == Player.STATE_ENDED) PlaybackPathKind.IDLE else PlaybackPathKind.MIX))
            container.signalPath.value = if (path.active) path.copy(processing = com.aurora.music.data.SignalStage("Processing",
                mixPlayer!!.processingDescription, "Active deck processing state; summed output and downstream hardware are not measured")) else path
            return
        }
        if (castPlayer != null && active === castPlayer) {
            requestBitPerfect(null, null, false)
            container.signalPath.value = buildSignalPath(SignalPathFacts(kind = if (active.currentMediaItem == null ||
                active.playbackState == Player.STATE_IDLE || active.playbackState == Player.STATE_ENDED) PlaybackPathKind.IDLE else PlaybackPathKind.CAST))
            return
        }
        val item = player.currentMediaItem
        if (item == null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            requestBitPerfect(null, null, false)
            container.signalPath.value = SignalPath()
            return
        }
        val state = sinkEvidence[player]
        val native = usbSink?.playbackTelemetry
        val usb = native?.usbActive == true
        val nativeFlac = native?.nativeFlac == true
        val raw = if (bitPerfect) native?.decodedFormat else state?.decoded
        val decoded = if (nativeFlac) SignalFormat(native!!.sourceRate.takeIf { it > 0 },
            native.sourceDepth.takeIf { it > 0 }, native.sourceChannels.takeIf { it > 0 }, "integer PCM")
            else raw?.let { pcmFormat(it.sampleRate, it.pcmEncoding, it.channelCount) }
        val source = state?.sources?.get(item.mediaId)
        val sourceFormat = if (nativeFlac) decoded else source?.let { pcmFormat(it.sampleRate, it.pcmEncoding, it.channelCount) }
        val codec = if (nativeFlac) "FLAC" else source?.sampleMimeType?.substringAfter('/')?.uppercase()
            ?.takeIf { it.matches(Regex("[A-Z0-9.+_-]{1,60}")) }.orEmpty()
        val track = state?.track
        val trackFormat = track?.let { pcmFormat(it.sampleRate, it.encoding, Integer.bitCount(it.channelConfig)) }
        // Media3 uses float only for high-resolution decoded PCM. A PCM16 source still runs processors
        // even when float is enabled in the builder. This setting is fixed for this service lifetime.
        val precise = state?.precisionSink?.precisionActive == true
        val precisionChain = state?.precisionProcessor
        val floatPath = if (state?.precisionSink != null) precise else usesFloatPcmPath(useFloatOut, decoded)
        val processors = !usb && !bitPerfect && !floatPath && raw != null
        val engine = if (precise) precisionChain else if (processors) state?.compatibilityProcessor?.engine else null
        val rackActive = engine?.rackActive == true
        val nodes = mutableListOf<String>()
        val bypassed = mutableListOf<String>()
        val modifications = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        fun node(requested: Boolean, applied: Boolean, name: String) {
            if (applied) { nodes += name; modifications += "$name processes the current samples" }
            else if (requested) bypassed += name
        }
        val ap = lastAudioPrefs
        val customDspActive = !rackActive && ap?.dspMode == DspMode.CUSTOM && engine?.processingActive == true
        val monoLocation = monoProcessingLocation(monoAudioPref, customDspActive,
            !rackActive && monoAudioPref && engine?.processingActive == true)
        node(monoAudioPref, monoLocation == MonoProcessingLocation.CUSTOM_DSP || monoLocation == MonoProcessingLocation.PROCESSOR,
            if (monoLocation == MonoProcessingLocation.CUSTOM_DSP) "Mono downmix (inside Custom DSP, width = 0)" else "Mono downmix")
        node(!rackActive && ap?.dspMode == DspMode.CUSTOM, customDspActive,
            if (precise) "Custom Aurora DSP (binary64 coefficients, arithmetic and state; decoder precision retained)"
            else "Custom Aurora DSP (binary64 coefficients, arithmetic and state; PCM16 input/output)")
        node(!rackActive && ap?.dspConvEnabled == true, !rackActive && engine?.convolutionProcessingActive == true,
            if (precise) "Convolution (binary64 FFT and state; no intermediate PCM16 conversion)"
            else "Convolution (binary64 FFT and state; PCM16 input/output)")
        if (rackActive) {
            nodes += "Serial rack (binary64): ${engine?.rackDescription.orEmpty()}"
            modifications += "The active serial rack processes the current samples"
            engine?.convolutionUnavailableReason?.let { unknown += it }
            if (monoAudioPref) unknown += "The rack owns channel routing; the standard mono setting does not override its nodes"
        } else if (lastRack?.enabled == true && ap?.dspMode == DspMode.CUSTOM) {
            unknown += "Serial rack requested; waiting for a supported stereo stream and prepared schedule"
        }
        if ((processors || precise) && (ap?.dspConvEnabled == true || rackActive)) {
            when (engine?.preparationState) {
                ConvolutionPreparationState.PREPARING -> unknown += "Processing configuration is preparing; the previous ready configuration continues when available"
                ConvolutionPreparationState.FAILED -> unknown += "The requested processing configuration could not be prepared; the previous configuration or dry convolution fallback remains active. " +
                    (engine?.preparationFailure ?: "Impulse response could not be prepared")
                ConvolutionPreparationState.IDLE -> if (!rackActive && ap?.dspConvEnabled == true) unknown += impulseLoadFailure
                    ?: "Convolution was requested but no valid impulse response is loaded"
                else -> Unit
            }
        }
        state?.precisionSink?.fallbackReason?.let { unknown += "High-resolution processing uses compatibility output: $it" }
        if (precise) {
            nodes += "Single float32 output conversion after Aurora processing"
            unknown += "Android owns the output clock and may resample or mix the float32 AudioTrack"
            if (decoded?.encoding != "float PCM" && (decoded?.bitDepth ?: 0) > 24)
                unknown += "Float32 output has 24 significant bits; it cannot preserve every PCM32 integer value"
        }
        if (processors && engine?.processingActive == true)
            nodes += "Single PCM16 output conversion after binary64 global processing"
        if (processors && player.skipSilenceEnabled) {
            nodes += "Silence skipping enabled"
            unknown += "Silence skipping is enabled; removed-sample counts are not instrumented"
        } else if (player.skipSilenceEnabled) bypassed += "Silence skipping"
        val systemEffects = audioEffects?.activeEffectNames().orEmpty()
        if (usb) {
            bypassed += systemEffects
            if (ap?.dspMode == DspMode.SYSTEM && systemEffects.isEmpty()) bypassed += "System effects"
        } else if (systemEffects.isNotEmpty()) {
            // The effect reports enabled, but a reused/changed player session may not be attached.
            unknown += "System effects report enabled (${systemEffects.joinToString()}); attachment and processing on the current AudioTrack are not independently measured"
        }
        val rg = replayGainMultiplier()
        if (usb) {
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
            if (sleepFadeActive || wakeFadeActive) { nodes += "Sleep / alarm fade"; modifications += "A playback fade is active" }
            if (xfadeActive) { nodes += "Crossfade"; modifications += "Two player outputs overlap with fade gains" }
            if (player.playbackParameters.speed != 1f || player.playbackParameters.pitch != 1f) {
                nodes += "Speed / pitch adjustment"; modifications += "Playback speed or pitch differs from unity"
            }
            if (sourceFormat?.bitDepth != null && decoded?.bitDepth != null && sourceFormat.bitDepth > decoded.bitDepth)
                modifications += "Decoder reduces the source sample depth"
        }
        val device = requestedOutputDevice()
        // Preserve the existing opportunistic mixer request for Automatic output. An attached USB
        // device is only a request candidate here; it is never reported as the observed audio route.
        val automaticMixerCandidate = if (device == null && container.preferredAudioDeviceId.value == 0 &&
            !usb && !bitPerfect && useFloatOut && !usePrecisionProcessing) {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager)
                .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isUsb(it.type) }
        } else null
        val mixerDevice = device ?: automaticMixerCandidate
        requestBitPerfect(mixerDevice, track, !usb && !bitPerfect && useFloatOut && !usePrecisionProcessing && mixerDevice != null && isUsb(mixerDevice.type))
        val sourceCopy = when (item.localConfiguration?.uri?.scheme?.lowercase()) {
            "file", "content" -> "Local playable copy"
            "http", "https" -> "Network playable stream"
            "aurora-yt" -> "Resolved network playable stream"
            else -> "Playable copy; origin unknown"
        }
        val desiredFloat = exclusiveUsbPref || preferHighResPref
        val measuredAfter = precise || (processors && state?.afterMeterProcessor?.isActive == true)
        container.signalPath.value = buildSignalPath(SignalPathFacts(
            kind = when { nativeFlac -> PlaybackPathKind.NATIVE_USB; usb -> PlaybackPathKind.DECODED_USB; else -> PlaybackPathKind.ANDROID },
            sourceCopy = sourceCopy, codec = codec, sourceFormat = sourceFormat,
            sourceBitrate = source?.averageBitrate?.takeIf { it > 0 },
            decoderName = if (nativeFlac) "Native libFLAC" else state?.decoder,
            decodedFormat = decoded,
            processorPath = when {
                nativeFlac -> "Native FLAC → USB transport; Android/app processor chain bypassed"
                usb -> "Media3 decoded PCM → USB transport; Android/app processor chain bypassed"
                bitPerfect -> "Android fallback for exclusive USB; this sink has no Aurora processors"
                raw == null -> "Waiting for decoded sink format"
                precise -> "Decoded PCM → binary64 Aurora processing → float32 Android output"
                state?.precisionSink != null -> "Compatibility PCM16 path; gapless trimming and silence skipping remain available"
                floatPath -> "High-resolution float path; app AudioProcessors bypassed"
                else -> "Android PCM processor path (PCM16 transport)"
            }, activeNodes = nodes, bypassedNodes = bypassed, modifications = modifications, unknowns = unknown,
            androidTrackFormat = if (usb) null else trackFormat,
            nativeTransportFormat = if (usb) SignalFormat(native!!.transportRate.takeIf { it > 0 },
                native.transportDepth.takeIf { it > 0 }, native.transportChannels.takeIf { it > 0 }, "integer PCM") else null,
            nativeClockAccepted = native?.clockRequestAccepted == true, nativeTailSubmitted = native?.tailSubmitted == true,
            mixerGrant = grantedBitPerfect, mixerGrantMatchesFormat = grantedMixerFormat != null && grantedMixerFormat == trackFormat,
            mixerRequestDetail = if (automaticMixerCandidate != null)
                "Automatic-output USB candidate (route unverified). ${mixerRequestDetail ?: "Request result unknown"}"
                else mixerRequestDetail,
            requestedDevice = device?.let { routeCategory(it) }, exclusiveRequested = bitPerfect,
            restartRequired = exclusiveUsbPref != bitPerfect || desiredFloat != useFloatOut,
        )).copy(audioTrackUnderruns = if (!usb && !bitPerfect && raw != null) state?.underruns else null,
            measurements = if (!usb && !bitPerfect && raw != null && state != null)
            com.aurora.music.data.AudioMeasurements(state.beforeMeter.snapshot(),
                if (precise) state.precisionAfterMeter.snapshot() else if (measuredAfter) state.afterMeter.snapshot() else null,
                player.isPlaying, measuredAfter, xfadeActive)
            else null)
    }
    private fun tickAudio() {
        if (mediaSession?.player !== player) return
        val now = android.os.SystemClock.elapsedRealtime()
        var master = 1f
        if (sleepFadeActive) {
            val t = ((now - sleepFadeStartMs).toFloat() / sleepFadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            master *= 1f - t
            if (t >= 1f) { sleepFadeActive = false; player.pause(); fadePlayer?.pause() }
        }
        if (wakeFadeActive) {
            val t = ((now - wakeFadeStartMs).toFloat() / wakeFadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            master *= t
            if (t >= 1f) wakeFadeActive = false
        }
        if (xfadeActive) {
            if (player.currentMediaItem?.mediaId != xfadeExpectedId || player.playerError != null) endXfade()
            else driveXfade(master)
        } else {
            player.volume = replayGainMultiplier() * master
            if (!sleepFadeActive && !wakeFadeActive) maybeBeginXfade()
        }
    }

    private fun maybeBeginXfade() {
        val audible = player.isPlaying || (bitPerfect && player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && usbSink?.nativeEngineActive == true)
        if (crossfadeMs <= 0 || !audible || player.isCurrentMediaItemLive) {
            if (crossfadeMs <= 0) clearPrepared()
            return
        }
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        val next = if (repeatOne) player.currentMediaItemIndex else player.nextMediaItemIndex
        if (next == C.INDEX_UNSET) { clearPrepared(); return }
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val remaining = ((duration - player.currentPosition) / speed).toLong()
        val fade = crossfadeMs.toLong().coerceAtMost((duration / speed / 2).toLong())
        val key = "${player.currentMediaItemIndex}:$next:${player.getMediaItemAt(next).localConfiguration?.uri}:${player.mediaItemCount}"
        if (bitPerfect) {
            val sink = usbSink ?: return
            if (repeatOne || !sink.nativeEngineActive || xfadeBpPending) return
            val outgoing = player.currentMediaItem?.localConfiguration?.uri
            val incoming = player.getMediaItemAt(next).localConfiguration?.uri
            if (nativeEligibilityKey != key) {
                nativeEligibilityKey = key; nativeEligible = false
                scope.launch {
                    val eligible = kotlinx.coroutines.withContext(Dispatchers.IO) { sink.canCrossfadeNative(outgoing, incoming) }
                    if (nativeEligibilityKey == key) nativeEligible = eligible
                }
            }
            if (nativeEligible && remaining in 150..fade) {
                val shape = when (crossfadeCurve) { "LINEAR" -> 1; "POWER" -> 2; else -> 0 }
                sink.setPendingTail(outgoing, incoming, player.currentPosition * 1000, (remaining * speed).toLong(), shape, crossfadeHeadroom)
                xfadeBpPending = true
                player.seekToNextMediaItem()
            }
            return
        }
        if (preparedKey != null && preparedKey != key) clearPrepared()
        if (remaining > fade + 15_000 || key == preparedFailedKey) return
        if (preparedKey == null) {
            val incoming = ensureFadePlayer()
            incoming.volume = 0f
            incoming.playWhenReady = false
            incoming.repeatMode = player.repeatMode
            incoming.playbackParameters = player.playbackParameters
            incoming.skipSilenceEnabled = player.skipSilenceEnabled
            incoming.setMediaItems((0 until player.mediaItemCount).map { player.getMediaItemAt(it) }, next, 0)
            incoming.prepare()
            preparedKey = key
        }
        val incoming = fadePlayer ?: return
        if (incoming.playerError != null) { preparedFailedKey = key; clearPrepared(); return }
        if (remaining > fade || remaining < 150 || incoming.playbackState != Player.STATE_READY) return
        // Snapshot duration/shape/gain: editing preferences cannot jump an in-flight fade.
        xfadeDurationMs = remaining.coerceAtLeast(1)
        xfadeElapsedMs = 0
        xfadeStartMs = android.os.SystemClock.elapsedRealtime()
        activeCurve = crossfadeCurve
        activeHeadroom = crossfadeHeadroom
        xfadeOutGain = replayGainMultiplier()
        xfadeInGain = replayGainMultiplier(incoming.currentMediaItem)
        val outgoing = player
        // Aurora already reordered the physical queue. Preserve its shuffle flag while keeping
        // native traversal sequential; originalOrder continues to own later unshuffle restoration.
        incoming.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(incoming.mediaItemCount))
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        // Focus belongs to the incoming/session player. Only one player requests it.
        outgoing.setAudioAttributes(outgoing.audioAttributes, false)
        incoming.setAudioAttributes(incoming.audioAttributes, !independentOutput)
        player = incoming
        fadePlayer = outgoing
        // A cloned queue must not auto-advance the fading deck at its end.
        outgoing.pauseAtEndOfMediaItems = true
        incoming.pauseAtEndOfMediaItems = false
        xfadeExpectedId = incoming.currentMediaItem?.mediaId
        xfadeActive = true
        preparedKey = null
        incoming.volume = 0f
        mediaSession?.player = incoming
        incoming.play()
        android.util.Log.i("AuroraCrossfade", "begin durationMs=$xfadeDurationMs curve=$activeCurve protected=$activeHeadroom")
    }

    private fun driveXfade(master: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        val tail = fadePlayer ?: return endXfade()
        val playing = player.isPlaying
        // Pause, focus loss and incoming buffering freeze BOTH decks and the envelope clock.
        if (playing) {
            tail.playWhenReady = true
            xfadeElapsedMs += (now - xfadeStartMs).coerceIn(0, 100)
        } else tail.pause()
        xfadeStartMs = now
        tail.playbackParameters = player.playbackParameters
        val t = (xfadeElapsedMs.toFloat() / xfadeDurationMs).coerceIn(0f, 1f)
        val gains = com.aurora.music.mix.MixMath.crossfade(t, activeCurve, activeHeadroom)
        player.volume = gains.second * xfadeInGain * master
        tail.volume = gains.first * xfadeOutGain * master
        if (t >= 1f) endXfade(master)
    }

    private fun endXfade(master: Float = 1f) {
        xfadeActive = false
        xfadeExpectedId = null
        clearPrepared()
        player.volume = replayGainMultiplier() * master
    }

    private fun clearPrepared() {
        fadePlayer?.run { volume = 0f; pause(); clearMediaItems() }
        preparedKey = null
    }

    private fun ensureFadePlayer(): ExoPlayer {
        fadePlayer?.let { return it }
        val fadeEvidence = SinkEvidence()
        compatibilityChains += fadeEvidence.compatibilityProcessor.engine
        if (usePrecisionProcessing) fadeEvidence.precisionProcessor = PrecisionBlockProcessor().also { precisionChains += it }
        applyAudioEngine()
        fadeEvidence.precisionProcessor?.setImpulse(currentImpulse, lastAudioPrefs?.dspConvMakeupDb ?: 0f)
        fadeEvidence.compatibilityProcessor.engine.setImpulse(currentImpulse, lastAudioPrefs?.dspConvMakeupDb ?: 0f)
        val factory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val base = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(fadeEvidence.compatibilityProcessor, fadeEvidence.afterMeterProcessor))
                    .setEnableFloatOutput(useFloatOut)
                    .setEnableAudioTrackPlaybackParams(useFloatOut)
                    .build()
                val sink = fadeEvidence.precisionProcessor?.let {
                    PrecisionAudioSink(base, it, fadeEvidence.precisionAfterMeter).also { wrapped -> fadeEvidence.precisionSink = wrapped }
                } ?: base
                return TappingAudioSink(sink, container.visualizer, fadeEvidence.beforeMeter) { fadeEvidence.decoded = it }
            }
        }
        return ExoPlayer.Builder(this, factory)
            .setMediaSourceFactory(musicSourceFactory)
            .setAudioAttributes(player.audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .build().also {
                attachSignalEvidence(it, fadeEvidence)
                it.volume = 0f
                if (container.audioSessionId != 0) it.setAudioSessionId(container.audioSessionId)
                it.addListener(serviceListener(it))
                fadePlayer = it
                applyPreferredDevice(container.preferredAudioDeviceId.value)
            }
    }

    private fun replayGainMultiplier(item: MediaItem? = player.currentMediaItem): Float {
        if (replayGainMode == 0) return 1f
        val extras = item?.mediaMetadata?.extras ?: return 1f
        val db = if (replayGainMode == 2) extras.getFloat("rgAlbum", 0f) else extras.getFloat("rgTrack", 0f)
        return if (db.isFinite()) Math.pow(10.0, db / 20.0).toFloat().coerceIn(0.1f, 1f) else 1f
    }

    private inner class MediaCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // must keep library commands or android auto browser connection is refused
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP_FADE, Bundle.EMPTY))
                .add(SessionCommand(CMD_EXIT_MIX, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_EXIT_MIX -> stopMix()
                // target 1=on 0=off -1=toggle order = pre-shuffle order when caller already shuffled
                CMD_SHUFFLE -> if (mixPlayer != null) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                } else setShuffle(
                    customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"),
                )
                CMD_REPEAT -> mixPlayer?.let { mix ->
                    mix.repeatMode = when (mix.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                } ?: cycleRepeat()
                CMD_SLEEP_FADE -> {
                    val ms = customCommand.customExtras.getInt("fadeMs", 0)
                    mixPlayer?.let { it.sleepFade(ms); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                    if (ms > 0) { sleepFadeMs = ms; sleepFadeStartMs = android.os.SystemClock.elapsedRealtime(); sleepFadeActive = true }
                    else { sleepFadeActive = false; player.volume = replayGainMultiplier() }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browseItem(LIBRARY_ROOT, "Aurora", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, styleExtras(styleList, styleList))
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceFuture {
            LibraryResult.ofItemList(browseChildren(parentId), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceFuture {
            val item = browseCache[mediaId] ?: resolvePlayable(mediaId)
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceFuture {
            mediaItems.map { item ->
                if (item.localConfiguration != null) item else resolvePlayable(item.mediaId) ?: item
            }.toMutableList()
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = serviceFuture {
            val items = runCatching { searchItems(query) }.getOrDefault(emptyList())
            searchCache[query] = items
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceFuture {
            val items = searchCache[query] ?: runCatching { searchItems(query) }.getOrDefault(emptyList()).also { searchCache[query] = it }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val r = container.repository.search(query)
        val songs = r.songs.map { songItem(it) }
        val albums = r.albums.map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
        val artists = r.artists.map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
        return songs + albums + artists
    }

    private fun <T> serviceFuture(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { f.set(block()) }.onFailure { f.setException(it) } }
        return f
    }

    private suspend fun browseChildren(parentId: String): ImmutableList<MediaItem> {
        val repo = container.repository
        val items: List<MediaItem> = runCatching {
            when {
                parentId == LIBRARY_ROOT -> listOf(
                    browseItem("cat_liked", "Liked Songs", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                    browseItem("cat_playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_downloads", "Downloads", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                )
                parentId == "cat_liked" -> repo.starredSongs().map { songItem(it) }
                parentId == "cat_downloads" -> repo.downloadedSongs().map { songItem(it) }
                parentId == "cat_playlists" -> repo.allPlaylists().map { collectionItem("pl_${it.id}", it.title, it.subtitle, it.coverUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
                parentId == "cat_albums" -> repo.allAlbums().map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
                parentId == "cat_artists" -> repo.allArtists().map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
                parentId.startsWith("alb_") -> repo.detail("album", parentId.removePrefix("alb_"))?.tracks?.map { songItem(it) }.orEmpty()
                parentId.startsWith("pl_") -> repo.detail("playlist", parentId.removePrefix("pl_"))?.tracks?.map { songItem(it) }.orEmpty()
                parentId.startsWith("art_") -> repo.detail("artist", parentId.removePrefix("art_"))?.tracks?.map { songItem(it) }.orEmpty()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        return ImmutableList.copyOf(items)
    }

    private suspend fun resolvePlayable(mediaId: String): MediaItem? {
        browseCache[mediaId]?.let { return it }
        val id = mediaId.removePrefix("song_")
        return runCatching { container.repository.songFor(id)?.let { songItem(it) } }.getOrNull()
    }

    private val styleGrid get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val styleList get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    private fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
    }

    private fun browseItem(id: String, title: String, mediaType: Int, childExtras: Bundle? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                .apply { if (childExtras != null) setExtras(childExtras) }.build()
        ).build()

    private fun collectionItem(id: String, title: String, subtitle: String, art: String, mediaType: Int): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                .setExtras(styleExtras(styleList, styleList))
                .apply { if (art.isNotBlank()) setArtworkUri(android.net.Uri.parse(art)) }.build()
        ).build().also { browseCache[id] = it }

    private fun songItem(song: com.aurora.music.model.Song): MediaItem {
        val id = "song_${song.id}"
        val item = MediaItem.Builder().setMediaId(id).setUri(song.streamUrl).setMediaMetadata(
            MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (song.artworkUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(song.artworkUrl)) }.build()
        ).build()
        browseCache[id] = item
        return item
    }

    private fun buildCustomLayout(): List<CommandButton> {
        val shuffleBtn = CommandButton.Builder(
            if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build()
        val repeatIcon = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val repeatBtn = CommandButton.Builder(repeatIcon)
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .build()
        return listOf(shuffleBtn, repeatBtn)
    }

    private fun updateCustomLayout() {
        runCatching { mediaSession?.setCustomLayout(buildCustomLayout()) }
    }

    private fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // physical shuffle reorders the actual items shuffleModeEnabled is just a ui flag with an identity ShuffleOrder so playback follows our order not a second random one
    private fun setShuffle(target: Int, providedOrder: List<String>?) {
        val enable = when (target) {
            1 -> true
            0 -> false
            else -> !player.shuffleModeEnabled
        }
        // a provided order is a fresh shuffle-play always reapply otherwise skip no-ops
        if (providedOrder == null && enable == player.shuffleModeEnabled && enable == (originalOrder != null)) return

        if (enable && providedOrder != null) {
            // caller already shuffled just remember the real order for restore
            originalOrder = providedOrder
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
            return
        }
        if (enable) {
            // keep the current track shuffle everything after it
            if (player.mediaItemCount <= 1) {
                player.shuffleModeEnabled = true
                originalOrder = currentIds()
                return
            }
            val ids = currentIds()
            originalOrder = ids
            val curId = player.currentMediaItem?.mediaId
            val rest = ids.filter { it != curId }.shuffled()
            val desired = (if (curId != null) listOf(curId) else emptyList()) + rest
            applyOrder(desired)
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
        } else {
            val orig = originalOrder
            if (orig != null) {
                val present = currentIds()
                // restore snapshot order keep newly-added items at the end
                val restored = orig.filter { it in present } + present.filter { it !in orig }
                applyOrder(restored)
            }
            player.shuffleModeEnabled = false
            originalOrder = null
        }
    }

    private fun maybeNeutralize() {
        if (!pendingNeutralize) return
        val count = player.mediaItemCount
        if (count <= 0) return
        runCatching { player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(count)) }
        pendingNeutralize = false
    }

    private fun currentIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    // reorder in place via moves so playback isnt interrupted
    private fun applyOrder(target: List<String>) {
        for (i in target.indices) {
            if (i >= player.mediaItemCount) break
            val want = target[i]
            var cur = -1
            var j = i
            while (j < player.mediaItemCount) {
                if (player.getMediaItemAt(j).mediaId == want) { cur = j; break }
                j++
            }
            if (cur in (i + 1) until player.mediaItemCount) player.moveMediaItem(cur, i)
        }
    }

    private fun setupCast() {
        val castContext = runCatching {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
        }.getOrNull() ?: return
        val cp = androidx.media3.cast.CastPlayer(castContext)
        cp.setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchToPlayer(toCast = true)
            override fun onCastSessionUnavailable() = switchToPlayer(toCast = false)
        })
        castPlayer = cp
        cp.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (mediaSession?.player === cp) { updateSignalPath(); publishNowPlaying() }
            }
        })
    }

    // casting hands the receiver a plain url so the dsp chain doesnt travel
    private fun switchToPlayer(toCast: Boolean) {
        if (mixPlayer != null) stopMix()
        if (xfadeActive) endXfade() else clearPrepared()
        val cp = castPlayer ?: return
        val from = mediaSession?.player ?: return
        val to: Player = if (toCast) cp else player
        if (from === to) return
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
            .map { if (toCast) it.buildUpon().setMimeType(guessMime(it)).build() else it }
        val idx = from.currentMediaItemIndex.coerceAtLeast(0)
        val pos = from.currentPosition
        val play = from.playWhenReady
        from.pause()
        if (items.isNotEmpty()) {
            to.setMediaItems(items, idx, pos)
            to.playWhenReady = play
            to.prepare()
        }
        mediaSession?.player = to
        updateSignalPath()
        publishNowPlaying()
    }

    private fun guessMime(item: MediaItem): String {
        val uri = item.localConfiguration?.uri?.toString().orEmpty().lowercase()
        return when {
            uri.contains(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
            uri.contains(".m4a") || uri.contains(".aac") || uri.contains(".mp4") -> androidx.media3.common.MimeTypes.AUDIO_AAC
            uri.contains(".ogg") || uri.contains(".opus") -> androidx.media3.common.MimeTypes.AUDIO_OGG
            uri.contains(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
            else -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        }
    }

    private fun publishNowPlaying() {
        val active = mediaSession?.player ?: player
        val item = active.currentMediaItem
        val md = item?.mediaMetadata
        val np = NowPlaying(
            title = md?.title?.toString().orEmpty(),
            artist = md?.artist?.toString().orEmpty(),
            artUri = md?.artworkUri?.toString().orEmpty(),
            isPlaying = active.isPlaying,
            hasTrack = item != null,
        )
        nowPlaying.save(np)
        NowPlayingBus.state.value = np
        WidgetBridge.refresh(this)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val transport = mediaSession?.player ?: player
        when (intent?.action) {
            ACTION_MIX -> startMix()
            ACTION_PLAY_PAUSE -> { wakeFadeActive = false; if (transport.playWhenReady) transport.pause() else transport.play() }
            ACTION_NEXT -> transport.seekToNextMediaItem()
            ACTION_PREV -> if (transport.currentPosition > 4000) transport.seekTo(0) else transport.seekToPreviousMediaItem()
            ACTION_ALARM -> startAlarmPlayback()
            ACTION_ALARM_DISMISS -> dismissAlarm()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startMix() {
        val request = container.mixController.pendingProject ?: return
        container.mixController.pendingProject = null
        if (bitPerfect || mediaSession?.player === castPlayer) {
            container.mixController.state.value = com.aurora.music.mix.MixPlaybackState(error =
                "Mixes use this device's audio output. Disconnect Cast or turn off exclusive USB output and restart playback first.")
            return
        }
        stopMix()
        if (xfadeActive) endXfade() else clearPrepared()
        player.pause()
        sleepFadeActive = false
        wakeFadeActive = false
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val mix = com.aurora.music.mix.MixPlayer(this, request, musicSourceFactory, container.mixController,
            device = { am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == container.preferredAudioDeviceId.value } },
            startSec = container.mixController.pendingPosition, audioConfig = mixAudioConfig(),
            tracklist = container.mixController.pendingTracklist)
        mixPlayer = mix
        mix.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publishNowPlaying(); updateSignalPath() }
        })
        mediaSession?.player = mix
        mediaSession?.setCustomLayout(emptyList())
        updateSignalPath()
        mix.play()
    }

    private fun stopMix() {
        val mix = mixPlayer ?: return
        mix.pause()
        mediaSession?.player = player
        mix.release()
        mixPlayer = null
        updateCustomLayout()
        updateSignalPath()
        publishNowPlaying()
    }

    private fun mixAudioConfig() = com.aurora.music.mix.MixAudioConfig(
        params = currentDspParams, mode = lastAudioPrefs?.dspMode ?: DspMode.OFF,
        mono = monoAudioPref, impulse = currentImpulse, convolution = lastAudioPrefs?.dspConvEnabled == true,
        convolutionGain = lastAudioPrefs?.dspConvMakeupDb ?: 0f, audioSessionId = container.audioSessionId,
        replayGain = replayGainMode, rack = lastRack?.takeIf { it.enabled && lastAudioPrefs?.dspMode == DspMode.CUSTOM })

    private var alarmLoadJob: kotlinx.coroutines.Job? = null

    private fun dismissAlarm() {
        alarmLoadJob?.cancel()
        alarmLoadJob = null
        wakeFadeActive = false
        runCatching { player.pause(); player.stop(); player.clearMediaItems() }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(ALARM_NOTIF_ID) }
    }

    private fun postAlarmNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("aurora_alarm", "Alarm", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val piFlags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val fsIntent = android.content.Intent(this, AlarmActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val fsPending = android.app.PendingIntent.getActivity(this, 1, fsIntent, piFlags)
        val dismissPending = android.app.PendingIntent.getService(
            this, 2, android.content.Intent(this, PlaybackService::class.java).setAction(ACTION_ALARM_DISMISS), piFlags,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, "aurora_alarm")
            .setSmallIcon(com.aurora.music.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Aurora alarm")
            .setContentText("Tap to dismiss")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(fsPending)
            .setFullScreenIntent(fsPending, true)
            .addAction(0, "Dismiss", dismissPending)
            .build()
        nm.notify(ALARM_NOTIF_ID, notif)
    }

    private fun startAlarmPlayback() {
        alarmLoadJob?.cancel()
        alarmLoadJob = scope.launch {
            val repo = container.repository
            val songs = runCatching { repo.starredSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { repo.downloadedSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@launch
            // Repository fallbacks may catch cancellation; a dismissed or replaced alarm
            // must never publish a late lookup result back into the player.
            if (!isActive) return@launch
            val ordered = songs.shuffled()
            player.setMediaItems(ordered.map { songItem(it) }, 0, 0L)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.volume = 0f
            wakeFadeMs = 30_000
            wakeFadeStartMs = android.os.SystemClock.elapsedRealtime()
            wakeFadeActive = true
            player.play()
            publishNowPlaying()
            postAlarmNotification()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        alarmLoadJob?.cancel()
        alarmLoadJob = null
        stopMix()
        // swipe-away stops playback so music doesnt keep going
        runCatching { fadePlayer?.run { pause(); clearMediaItems() } }
        player.pause()
        player.stop()
        player.clearMediaItems()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(outputCallback) }
        requestBitPerfect(null, null, false)
        mixPlayer?.release()
        mixPlayer = null
        runCatching { fadePlayer?.release() }
        fadePlayer = null
        runCatching { castPlayer?.setSessionAvailabilityListener(null); castPlayer?.release() }
        castPlayer = null
        mediaSession?.release()
        runCatching { player.release() } // sessions player may have been the cast player
        mediaSession = null
        sinkEvidence.clear()
        container.signalPath.value = SignalPath()
        super.onDestroy()
    }

    companion object {
        const val ACTION_MIX = "com.aurora.music.action.MIX"
        const val CMD_EXIT_MIX = "com.aurora.music.EXIT_MIX"
        const val CMD_SHUFFLE = "com.aurora.music.SHUFFLE"
        const val CMD_REPEAT = "com.aurora.music.REPEAT"
        const val CMD_SLEEP_FADE = "com.aurora.music.SLEEP_FADE"
        const val ACTION_PLAY_PAUSE = "com.aurora.music.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.aurora.music.action.NEXT"
        const val ACTION_PREV = "com.aurora.music.action.PREV"
        const val ACTION_ALARM = "com.aurora.music.action.ALARM"
        const val ACTION_ALARM_DISMISS = "com.aurora.music.action.ALARM_DISMISS"
        private const val ALARM_NOTIF_ID = 0xA1A
    }
}
