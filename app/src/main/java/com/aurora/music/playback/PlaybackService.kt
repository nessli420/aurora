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
import com.aurora.music.data.UsbOutputMode
import com.aurora.music.data.UsbFallbackPolicy
import com.aurora.music.playback.usb.ProcessedUsbAudioSink
import com.aurora.music.playback.usb.UsbGraphProcessor
import com.aurora.music.playback.usb.NativeUsbPcmTransport
import com.aurora.music.playback.dsd.DsdExtractorsFactory
import com.aurora.music.playback.dsd.DsdSourceInfo
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.aurora.music.playback.network.*
import com.aurora.music.playback.network.audio.ProcessedNetworkRenderer
import com.aurora.music.playback.network.endpoint.EndpointRoute

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
    private var networkPlayer: NetworkQueuePlayer? = null
    private var networkBridge: NetworkPlaybackBridge? = null
    private var networkServer: ScopedMediaServer? = null
    private lateinit var networkDataSource: androidx.media3.datasource.DataSource.Factory
    private var receiverForeground = false
    private var networkSwitchJob: kotlinx.coroutines.Job? = null
    private var networkOriginalStream = false
    private var handoffPlayWhenReady: Boolean? = null
    @Volatile private var networkDisposed = false
    private var mixPlayer: com.aurora.music.mix.MixPlayer? = null
    @Volatile private var lastIrPath: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioEffects: AudioEffectsController? = null
    @Volatile private var crossfadeMs: Int = 0
    @Volatile private var replayGainMode: Int = 0
    @Volatile private var monoAudioPref: Boolean = false
    @Volatile private var lastAudioPrefs: AudioPrefs? = null
    private var listeningHistoryAllowed = false
    private var historyTick = 0L
    private var historySong: com.aurora.music.model.Song? = null
    private var historyPosition = 0L
    private var historyWasPlaying = false
    private var historyPendingMs = 0L
    private var historyDay = java.time.LocalDate.now()

    private fun flushListeningHistory() {
        val previous = historySong
        if (previous != null && historyPendingMs > 0) {
            container.playHistory.recordListening(previous, historyPendingMs,
                if (historyDay == java.time.LocalDate.now()) System.currentTimeMillis()
                else historyDay.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1)
        }
        historyPendingMs = 0
    }

    private fun trackListeningHistory() {
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = (now - historyTick).coerceIn(0L, 2000L)
        historyTick = now
        val active = mediaSession?.player ?: return
        val item = active.currentMediaItem
        val id = item?.mediaId.orEmpty()
        val playing = active.isPlaying || (active === player && usbSink?.isNativeEngineActive == true && active.playWhenReady)
        val position = active.currentPosition
        val day = java.time.LocalDate.now()
        val newSession = id != historySong?.id || (position < historyPosition && position < 1500 && historyPosition > 5000) || day != historyDay
        if (newSession || !playing || !listeningHistoryAllowed) flushListeningHistory()
        if (newSession || !listeningHistoryAllowed) container.playHistory.endListeningSession()
        val m = item?.mediaMetadata
        val song = com.aurora.music.model.Song(id, m?.title?.toString().orEmpty(), m?.artist?.toString().orEmpty(),
            m?.albumTitle?.toString().orEmpty(), m?.artworkUri?.toString().orEmpty(),
            m?.extras?.getInt("aurora.durationSec")?.takeIf { it > 0 } ?: (active.duration.coerceAtLeast(0) / 1000).toInt(),
            albumId = m?.extras?.getString("aurora.albumId").orEmpty(), artistId = m?.extras?.getString("aurora.artistId").orEmpty())
        container.discord.update(song, playing && id.isNotBlank(), position / 1000f)
        if (!newSession && historyWasPlaying && playing && listeningHistoryAllowed && id.isNotBlank() &&
            !id.startsWith("radio:") && !id.startsWith("podcast:") && !id.startsWith("aurora-mix:")) {
            historyPendingMs += elapsed
            if (historyPendingMs >= 5000) flushListeningHistory()
        }
        historySong = song; historyPosition = position; historyWasPlaying = playing; historyDay = day
    }
    @Volatile private var useFloatOut: Boolean = false
    private var usePrecisionProcessing = false
    private val precisionChains = mutableListOf<PrecisionBlockProcessor>()
    private val compatibilityChains = mutableListOf<PrecisionBlockProcessor>()
    private var lastRack: com.aurora.music.data.ProcessingRack? = null
    @Volatile private var outputRatePolicy = com.aurora.music.playback.engine.OutputRatePolicy()
    private var rackImpulses: Map<String, ImpulseResponse> = emptyMap()
    private val androidAutoControllers = mutableSetOf<MediaSession.ControllerInfo>()
    @Volatile private var grantedBitPerfect: Boolean = false
    @Volatile private var deviceSupportsBitPerfect: Boolean = false
    @Volatile private var preferHighResPref: Boolean = false
    @Volatile private var independentOutput: Boolean = false
    private var exclusiveUsbPref = false
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
    private var processedUsbSink: ProcessedUsbAudioSink? = null
    private var rawDsdSink: com.aurora.music.playback.dsd.RawDsdAudioSink? = null
    @Volatile private var rawDsdFailure: String? = null
    private var usbMode = UsbOutputMode.DIRECT
    private var usbFallback = UsbFallbackPolicy.PAUSE
    private var usbModePref = UsbOutputMode.DIRECT
    private var usbFallbackPref = UsbFallbackPolicy.PAUSE
    @Volatile private var usbFailure: String? = null

    override fun onCreate() {
        super.onCreate()
        val networkFiles = Regex("[0-9a-f-]{36}\\.(source|partial|wav|mp3|flac|ogg|m4a|aac)")
        java.io.File(cacheDir, "network-audio").listFiles()?.filter { it.isFile && networkFiles.matches(it.name) }
            ?.forEach { it.delete() }

        val initialPrefs = runBlocking { container.settingsStore.playbackPrefs.first() }
        val highRes = initialPrefs.preferHighRes
        outputRatePolicy = runBlocking { container.settingsStore.outputRatePolicy.first() }
        val bitPerfectUsb = initialPrefs.bitPerfectUsb
        usbMode = initialPrefs.usbOutputMode
        usbFallback = initialPrefs.usbFallbackPolicy
        val dsdWire = if (bitPerfectUsb && usbMode == UsbOutputMode.DIRECT) when (initialPrefs.usbDsdMode) {
            com.aurora.music.data.UsbDsdMode.DOP -> com.aurora.music.playback.dsd.DsdWireFormat.DOP
            com.aurora.music.data.UsbDsdMode.NATIVE -> com.aurora.music.playback.dsd.DsdWireFormat.NATIVE_MSB32
            else -> null
        } else null
        bitPerfect = bitPerfectUsb
        val useFloat = bitPerfectUsb || highRes
        usePrecisionProcessing = highRes || bitPerfectUsb
        useFloatOut = useFloat
        val initialEvidence = SinkEvidence()
        initialEvidence.compatibilityProcessor.tpdfDither = outputRatePolicy.tpdfDither
        initialEvidence.compatibilityProcessor.noiseShaping = outputRatePolicy.noiseShaping == true
        compatibilityChains += initialEvidence.compatibilityProcessor.engine
        if (usePrecisionProcessing) initialEvidence.precisionProcessor = PrecisionBlockProcessor().also { precisionChains += it }
        val usbProcessor = if (bitPerfectUsb && usbMode == UsbOutputMode.PROCESSED)
            PrecisionBlockProcessor().also { precisionChains += it } else null

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
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
                            android.os.Handler(mainLooper).post { updateSignalPath() }
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
                val routedOutput = ConfirmedAudioRoute(context) { updateSignalPath() }
                initialEvidence.routedOutput?.close()
                initialEvidence.routedOutput = routedOutput
                val base = DefaultAudioSink.Builder(context)
                    .setAudioTrackProvider(routedOutput.provider)
                    .setAudioProcessors(arrayOf(initialEvidence.compatibilityProcessor, initialEvidence.afterMeterProcessor))
                    .setEnableFloatOutput(useFloat)
                    // float bypasses sonic so use hardware playback params for speed
                    .setEnableAudioTrackPlaybackParams(useFloat || enableAudioTrackPlaybackParams)
                    .build()
                val fallback = initialEvidence.precisionProcessor?.let {
                    PrecisionAudioSink(base, it, initialEvidence.precisionAfterMeter,
                        { outputRatePolicy }, { routedOutput.supportedSampleRates() }).also { wrapped -> initialEvidence.precisionSink = wrapped }
                } ?: base
                val sink = when {
                    usbProcessor != null -> ProcessedUsbAudioSink(fallback,
                        UsbGraphProcessor(usbProcessor, { outputRatePolicy }, after = initialEvidence.precisionAfterMeter),
                        { NativeUsbPcmTransport(context) }, usbFallback == UsbFallbackPolicy.ANDROID,
                        { softwareUsbVolume() }, { android.os.Handler(mainLooper).post { updateSignalPath() } })
                        .also { processedUsbSink = it }
                    bitPerfectUsb -> com.decent.usbaudio.media3.UsbAudioSink(fallback, context,
                        com.decent.usbaudio.media3.UsbAudioSinkConfig(
                            nativeFlacEnabled = dsdWire == null,
                            allowAndroidFallback = usbFallback == UsbFallbackPolicy.ANDROID,
                            onUsbFailure = { reason -> usbFailure = reason; android.os.Handler(mainLooper).post { updateSignalPath() } },
                        )).also {
                            usbSink = it
                            val nativeSink = it
                            container.visualizer.monoSource = object : VisualizerController.MonoSource {
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
                return TappingAudioSink(exclusiveSink, container.visualizer, initialEvidence.beforeMeter,
                    onVolume = { initialEvidence.downstreamGain = it }) { initialEvidence.decoded = it }
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
                val real = resolver.resolveSentinel(uri)
                    ?: throw java.io.IOException("This YouTube stream is unavailable. Please try again.")
                dataSpec.withUri(android.net.Uri.parse(real))
            } else if (uri.scheme == "aurora-extension") dataSpec.withUri(container.extensions.resolve(uri))
            else dataSpec
        }
        val resolvedDataSourceFactory = androidx.media3.datasource.ResolvingDataSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this), ytResolver,
        )
        val dataSourceFactory = com.aurora.music.playback.sacd.SacdDataSource.Factory(this, container.audioCache.factory(resolvedDataSourceFactory))
        networkDataSource = dataSourceFactory
        val mediaSourceFactory = YoutubeMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, DsdExtractorsFactory(rawOutput = dsdWire != null)), resolver,
            preferred = { PreferredPlayback.resolve(this, container.repository, it) }, prepare = container.audioCache::prepare)

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
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
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
        networkBridge = NetworkPlaybackBridge(this, player, container.networkOutput,
            ::selectNetworkOutput, ::rendererRoute, ::setReceiverForeground)

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
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (owner === player && bitPerfect && playWhenReady && player.playerError != null) player.prepare()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (owner === player && error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (owner !== player || !bitPerfect) return
                val resume = player.playWhenReady
                if (processedUsbSink?.useAndroidAfterFailure() == true) {
                    val position = player.currentPosition
                    player.seekTo(position)
                    player.prepare()
                    player.playWhenReady = resume
                } else if (usbFallback == UsbFallbackPolicy.PAUSE) player.pause()
            }
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
            store.outputRatePolicy.collect { policy ->
                outputRatePolicy = policy
                sinkEvidence.values.forEach {
                    it.compatibilityProcessor.tpdfDither = policy.tpdfDither
                    it.compatibilityProcessor.noiseShaping = policy.noiseShaping == true
                }
                mixPlayer?.applyAudioConfig(mixAudioConfig())
            }
        }
        scope.launch {
            store.processingRackAssets.collectLatest { entries ->
                val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    entries.mapNotNull { entry ->
                        val file = com.aurora.music.data.ir.ImpulseLibraryFiles.validateAsset(entry, entry.prepared != null).getOrNull()
                        file?.let { ConvolutionProcessor.loadWavResult(it).getOrNull() }?.let { entry.id to it }
                    }.toMap()
                }
                rackImpulses = loaded
                (precisionChains + compatibilityChains).forEach { it.setRackImpulses(loaded) }
                mixPlayer?.applyAudioConfig(mixAudioConfig())
            }
        }

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
                usbModePref = prefs.usbOutputMode
                usbFallbackPref = prefs.usbFallbackPolicy
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
            container.settingsStore.privateSession.collect { privateSession -> listeningHistoryAllowed = !privateSession }
        }

        scope.launch {
            while (isActive) {
                // ramp finely while a fade is in flight idle replaygain tracking needs only a coarse tick
                delay(if (xfadeActive || sleepFadeActive || wakeFadeActive) 25L else 100L)
                tickAudio()
            }
        }
        scope.launch { while (isActive) { delay(1000); trackListeningHistory() } }
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
            parametric = ap.dspParametric.map(DspBand::from),
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

    private fun publishPresetRuleContext(active: Player) {
        val item = active.currentMediaItem
        val context = PresetContextPublisher.build(item,
            active = item != null && active.playbackState != Player.STATE_IDLE && active.playbackState != Player.STATE_ENDED,
            observedSourceFormat = if (active === player) sinkEvidence[player]?.sources?.get(item?.mediaId) else null,
            androidAuto = androidAutoControllers.isNotEmpty(),
            cast = active === networkPlayer || castPlayer != null && active === castPlayer)
        container.settingsStore.presetRuleContext.publish(context)
    }

    private fun updateSignalPath() {
        try { updateSignalPathSnapshot() }
        finally { updateListeningLevels() }
    }

    private fun updateListeningLevels() {
        val active = if (::player.isInitialized) mediaSession?.player ?: player else null
        val normal = ::player.isInitialized && active === player
        val path = container.signalPath.value
        val measurements = path.measurements
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val volumeStep = runCatching { audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrNull()
        val maximum = runCatching { audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrNull()
        val muted = runCatching { audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(true)
        val prefs = lastAudioPrefs
        val supported = normal && path.active && measurements?.afterAvailable == true &&
            !measurements.overlappingPlayers && prefs != null && prefs.dspMode != DspMode.SYSTEM &&
            audioEffects?.activeEffectNames().orEmpty().isEmpty() &&
            player.playbackParameters.speed == 1f && player.playbackParameters.pitch == 1f
        container.listeningLevels.observe(com.aurora.music.data.listening.ListeningObservation(
            route = container.settingsStore.processingRoutes.current, playing = active?.isPlaying == true,
            postDsp = measurements?.after, downstreamGain = if (normal) sinkEvidence[player]?.downstreamGain?.toDouble() else null,
            volumeIndex = volumeStep, volumeMuted = muted, pathSupported = supported,
            allowHistory = listeningHistoryAllowed, volumeMaximum = maximum))
    }

    private fun updateSignalPathSnapshot() {
        if (!::player.isInitialized) return
        val active = mediaSession?.player ?: player
        publishPresetRuleContext(active)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toDouble() /
            audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        (precisionChains + compatibilityChains).forEach { it.relativeVolume = volume }
        mixPlayer?.applyAudioConfig(mixAudioConfig(volume))
        if (mixPlayer != null && active === mixPlayer) {
            val route = mixPlayer!!.confirmedOutput
            container.settingsStore.processingRoutes.publish(route)
            requestBitPerfect(null, null, false)
            val path = buildSignalPath(SignalPathFacts(kind = if (active.playbackState == Player.STATE_IDLE ||
                active.playbackState == Player.STATE_ENDED) PlaybackPathKind.IDLE else PlaybackPathKind.MIX,
                confirmedDevice = route.category))
            container.signalPath.value = if (path.active) path.copy(processing = com.aurora.music.data.SignalStage("Processing",
                mixPlayer!!.processingDescription, "Active deck processing state; summed output and downstream hardware are not measured")) else path
            return
        }
        if (networkPlayer != null && active === networkPlayer) {
            requestBitPerfect(null, null, false)
            container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute(
                com.aurora.music.data.routes.ProcessingRouteKind.CAST, label = networkPlayer!!.receiver.name,
                detail = "Network output uses a processing snapshot per track."))
            val remote = networkPlayer!!
            val sending = remote.receiver.kind != "Aurora"
            val original = networkOriginalStream
            container.signalPath.value = SignalPath(active = active.currentMediaItem != null,
                output = "${remote.receiver.kind} · ${remote.receiver.name}", preservation = if (original) com.aurora.music.data.Preservation.UNKNOWN else com.aurora.music.data.Preservation.MODIFIED,
                note = container.networkOutput.state.value.error ?: if (!sending) "Receiver owns DSP and audio output." else if (original) "Direct Cast; Aurora DSP is bypassed." else "Processed audio; receiver output is unmeasured.",
                reasons = listOf(if (original) "The original audio file is shared without sample processing." else "Network audio is converted to 48 kHz stereo PCM16.", "Receiver decoding, volume and hardware output are not measured by the sender."),
                source = com.aurora.music.data.SignalStage("Source", if (original) "Private copy of the original audio file" else "Private source decoded on this device"),
                decoder = com.aurora.music.data.SignalStage("Decoder", if (original) "${remote.receiver.kind} receiver; decoder is remote" else "Local PCM reader or FFmpeg decoder"),
                processing = com.aurora.music.data.SignalStage("Processing", remote.processingDescription,
                    "Track preparation snapshot; later edits apply to the next prepared track"),
                resampling = com.aurora.music.data.SignalStage("Resampling", if (original) "Bypassed on sender" else "Bandlimited conversion to 48000 Hz when required"),
                outputStage = com.aurora.music.data.SignalStage("Output", if (original) "Original container; receiver format unmeasured" else "WAV · 48000 Hz · 16-bit · stereo", "Scoped HTTP media stream",
                    if (original) null else com.aurora.music.data.SignalFormat(48000, 16, 2, "PCM")),
                device = com.aurora.music.data.SignalStage("Device", remote.receiver.name, "Remote transport status"),
                latency = com.aurora.music.data.SignalStage("Latency", "Full-track preparation plus receiver buffering; gapless playback is not guaranteed"))
            return
        }
        if (castPlayer != null && active === castPlayer) {
            container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute(
                com.aurora.music.data.routes.ProcessingRouteKind.CAST, label = "Cast receiver",
                detail = "Direct Cast bypasses local processing. Output rules are paused."))
            requestBitPerfect(null, null, false)
            container.signalPath.value = buildSignalPath(SignalPathFacts(kind = if (active.currentMediaItem == null ||
                active.playbackState == Player.STATE_IDLE || active.playbackState == Player.STATE_ENDED) PlaybackPathKind.IDLE else PlaybackPathKind.CAST))
            return
        }
        val item = player.currentMediaItem
        if (item == null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute())
            requestBitPerfect(null, null, false)
            val failure = rawDsdSink?.telemetry?.failure ?: rawDsdFailure.takeIf { player.playerError != null }
                ?: processedUsbSink?.telemetry?.fallbackReason ?: usbSink?.playbackTelemetry?.failure ?: usbFailure
            container.signalPath.value = if (bitPerfect && failure != null) SignalPath(note = failure,
                reasons = listOf(failure), output = "USB unavailable") else SignalPath()
            return
        }
        val state = sinkEvidence[player]
        val rawDsd = rawDsdSink?.telemetry
        if (rawDsd?.active == true || rawDsd?.failure != null) {
            requestBitPerfect(null, null, false)
            container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute(
                com.aurora.music.data.routes.ProcessingRouteKind.NATIVE_USB, label = "Raw DSD USB",
                detail = "PCM processing is bypassed."))
            val info = DsdSourceInfo.from(rawDsd.source)
            val sourceFormat = info?.let { SignalFormat(it.bitRate, 1, it.channels, "DSD") }
            val mode = if (rawDsd.wire == com.aurora.music.playback.dsd.DsdWireFormat.DOP) "DoP" else "Native DSD"
            val status = rawDsd.status
            val good = rawDsd.active && rawDsd.failure == null && status.error == null
            container.signalPath.value = SignalPath(active = good, codec = "${info?.container ?: "DSD"} / DSD",
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
            container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute())
            requestBitPerfect(null, null, false)
            container.signalPath.value = SignalPath(output = "USB unavailable", note = outputFailure, reasons = listOf(outputFailure))
            return
        }
        val processedUsb = processed?.active == true
        val usb = native?.usbActive == true || processedUsb
        val confirmedOutput = state?.routedOutput?.snapshot()
        container.settingsStore.processingRoutes.publish(if (usb)
            com.aurora.music.data.routes.ProcessingRoute(com.aurora.music.data.routes.ProcessingRouteKind.NATIVE_USB,
                label = if (processedUsb) "Processed USB output" else "Direct USB output",
                detail = "Automatic output rules are paused for native USB.")
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
            ?.takeIf { it.matches(Regex("[A-Z0-9.+_-]{1,60}")) }.orEmpty()
        val track = state?.track
        val trackFormat = track?.let { pcmFormat(it.sampleRate, it.encoding, Integer.bitCount(it.channelConfig)) }
        // Media3 uses float only for high-resolution decoded PCM. A PCM16 source still runs processors
        // even when float is enabled in the builder. This setting is fixed for this service lifetime.
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
            val description = engine?.rackDescription.orEmpty()
            nodes += "${if (description.contains("Routed graph")) "Processing graph" else "Serial rack"} (binary64): $description"
            modifications += "The active processing rack processes the current samples"
            engine?.convolutionUnavailableReason?.let { unknown += it }
            if (monoAudioPref) unknown += "The rack owns channel routing; the standard mono setting does not override its nodes"
        } else if (lastRack?.enabled == true && ap?.dspMode == DspMode.CUSTOM) {
            unknown += "Processing rack requested; waiting for a supported stereo stream and prepared schedule"
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
        if (!usb) state?.precisionSink?.fallbackReason?.let { unknown += "High-resolution processing uses compatibility output: $it" }
        if (processedUsb) {
            nodes += "Single ${processed?.validBits}-bit integer conversion after Aurora processing"
            if (outputRatePolicy.tpdfDither && (raw?.pcmEncoding == C.ENCODING_PCM_FLOAT ||
                    (decoded?.bitDepth ?: 0) > (processed?.validBits ?: 32) || engine?.processingChangesSamples == true ||
                    processed?.source?.sampleRate != processed?.output?.sampleRate || processed?.softwareGain != 1.0))
                nodes += "${outputRatePolicy.ditherLabel(processed?.output?.sampleRate ?: 0)} at the final integer conversion"
            if (crossfadeMs > 0) bypassed += "Crossfade (processed USB)"
        } else if (precise) {
            nodes += "Single float32 output conversion after Aurora processing"
            unknown += "Android owns the output clock and may resample or mix the float32 AudioTrack"
            if (decoded?.encoding != "float PCM" && (decoded?.bitDepth ?: 0) > 24)
                unknown += "Float32 output has 24 significant bits; it cannot preserve every PCM32 integer value"
        }
        if (processors && engine?.processingActive == true)
            nodes += "Single PCM16 output conversion after binary64 global processing"
        if (processors && outputRatePolicy.tpdfDither && engine?.processingChangesSamples == true)
            nodes += "${outputRatePolicy.ditherLabel(raw?.sampleRate ?: 0)} at the final PCM16 conversion"
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
        if (usb && !processedUsb) {
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
                if (processedUsb) bypassed += "Speed / pitch (processed USB)"
                else { nodes += "Speed / pitch adjustment"; modifications += "Playback speed or pitch differs from unity" }
            }
            if (processedUsb && processed?.softwareGain != 1.0) {
                nodes += "USB software gain (${processed?.softwareGain}×)"
                modifications += "Software volume attenuates samples before USB output"
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
        if (state != null && (!usb || processedUsb) && raw != null && !state.spectrumBusy &&
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
            "aurora-yt" -> "Resolved network playable stream"
            else -> "Playable copy; origin unknown"
        }
        val desiredFloat = exclusiveUsbPref || preferHighResPref
        val measuredAfter = precise || (processors && state?.afterMeterProcessor?.isActive == true)
        container.signalPath.value = buildSignalPath(SignalPathFacts(
            kind = when { processedUsb -> PlaybackPathKind.PROCESSED_USB; nativeFlac -> PlaybackPathKind.NATIVE_USB; usb -> PlaybackPathKind.DECODED_USB; else -> PlaybackPathKind.ANDROID },
            sourceCopy = sourceCopy, codec = codec, sourceFormat = sourceFormat,
            sourceBitrate = source?.averageBitrate?.takeIf { it > 0 },
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
            requestedDevice = device?.let { routeCategory(it) }, exclusiveRequested = bitPerfect,
            confirmedDevice = if (usb) null else confirmedOutput?.category,
            restartRequired = exclusiveUsbPref != bitPerfect || desiredFloat != useFloatOut ||
                bitPerfect && (usbModePref != usbMode || usbFallbackPref != usbFallback),
        )).copy(audioTrackUnderruns = if (!usb && raw != null) state?.underruns else null,
            usbDiagnostics = if (processedUsb) processed?.status?.let { com.aurora.music.data.UsbDiagnostics(it.completedFrames, it.pendingFrames, it.packetErrors, it.timeouts) }
                else if (usb && native != null) com.aurora.music.data.UsbDiagnostics(native.completedFrames, native.pendingFrames, native.packetErrors, native.timeouts) else null,
            measurements = if ((!usb || processedUsb) && raw != null && state != null)
            com.aurora.music.data.AudioMeasurements(state.beforeMeter.snapshot(),
                if (precise) state.precisionAfterMeter.snapshot() else if (measuredAfter) state.afterMeter.snapshot() else null,
                player.isPlaying, measuredAfter, xfadeActive,
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
    private fun softwareUsbVolume(): Double {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toDouble() /
            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
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
        fadeEvidence.precisionProcessor?.setRackImpulses(rackImpulses)
        fadeEvidence.compatibilityProcessor.engine.setRackImpulses(rackImpulses)
        fadeEvidence.compatibilityProcessor.tpdfDither = outputRatePolicy.tpdfDither
        fadeEvidence.compatibilityProcessor.noiseShaping = outputRatePolicy.noiseShaping == true
        val factory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val routedOutput = ConfirmedAudioRoute(context) { updateSignalPath() }
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
                        { outputRatePolicy }, { routedOutput.supportedSampleRates() }).also { wrapped -> fadeEvidence.precisionSink = wrapped }
                } ?: base
                return TappingAudioSink(sink, container.visualizer, fadeEvidence.beforeMeter,
                    onVolume = { fadeEvidence.downstreamGain = it }) { fadeEvidence.decoded = it }
            }
        }
        return ExoPlayer.Builder(this, factory)
            .setMediaSourceFactory(musicSourceFactory)
            .setAudioAttributes(player.audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .build().also {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
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
            if (controller.packageName == "com.google.android.projection.gearhead") androidAutoControllers += controller
            // must keep library commands or android auto browser connection is refused
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP_FADE, Bundle.EMPTY))
                .add(SessionCommand(CMD_EXIT_MIX, Bundle.EMPTY))
                .add(SessionCommand(CMD_QUEUE_APPEND, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            androidAutoControllers -= controller
            updateSignalPath()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_QUEUE_APPEND -> {
                    val active = session.player
                    val token = args.getString("token")
                    val count = args.getInt("count", -1)
                    val bundles = args.getParcelableArrayList<Bundle>("items")
                    val items = runCatching { bundles?.map(MediaItem::fromBundle) }.getOrNull()
                    val reason = when {
                        mixPlayer != null -> "mix is active"
                        token.isNullOrBlank() || count <= 0 -> "invalid queue identity"
                        items.isNullOrEmpty() || items.size > 120 -> "invalid batch size"
                        items.any { it.localConfiguration == null } -> "missing playback URI"
                        items.any { it.mediaMetadata.extras?.getString(QUEUE_TOKEN) != token } -> "invalid batch identity"
                        active.mediaItemCount != count || (0 until count).any {
                            active.getMediaItemAt(it).mediaMetadata.extras?.getString(QUEUE_TOKEN) != token
                        } -> "queue changed"
                        else -> null
                    }
                    if (reason != null) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE,
                        Bundle().apply { putString("reason", reason); putInt("count", active.mediaItemCount) }))
                    active.addMediaItems(if (args.getBoolean("prepend")) 0 else count, requireNotNull(items))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_EXIT_MIX -> stopMix()
                // target 1=on 0=off -1=toggle order = pre-shuffle order when caller already shuffled
                CMD_SHUFFLE -> if (networkPlayer != null) {
                    networkPlayer!!.shuffle(customCommand.customExtras.getInt("target", -1),
                        customCommand.customExtras.getStringArrayList("order"))
                } else if (mixPlayer != null) {
                    return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                } else setShuffle(
                    customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"),
                )
                CMD_REPEAT -> networkPlayer?.let { remote ->
                    remote.repeatMode = when (remote.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                } ?: mixPlayer?.let { mix ->
                    mix.repeatMode = when (mix.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                } ?: cycleRepeat()
                CMD_SLEEP_FADE -> {
                    if (networkPlayer != null) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
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
            else LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) return Futures.immediateFuture(mediaItems)
            return serviceFuture {
                mediaItems.map { item ->
                    if (item.localConfiguration != null) item else resolvePlayable(item.mediaId) ?: item
                }.toMutableList()
            }
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
                .setExtras(PresetContextPublisher.extras(song, container.repository.playbackSourceIdentity(song)))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (song.artworkUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(song.artworkUrl)) }.build()
        ).build()
        browseCache[id] = item
        return item
    }

    private fun buildCustomLayout(): List<CommandButton> {
        val active = mediaSession?.player ?: player
        val shuffleBtn = CommandButton.Builder(
            if (active.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build()
        val repeatIcon = when (active.repeatMode) {
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
            override fun onCastSessionAvailable() {
                if (container.networkOutput.state.value.receiverEnabled) {
                    container.networkOutput.update { it.copy(error = "Turn off receiver mode before casting.") }
                    return
                }
                selectNetworkOutput(NetworkTarget.Cast)
            }
            override fun onCastSessionUnavailable() {
                if (networkPlayer?.receiver?.kind == "Cast") {
                    selectNetworkOutput(null)
                    container.networkOutput.update { it.copy(error = "Cast disconnected. Playback is paused.") }
                }
            }
        })
        castPlayer = cp
        cp.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (mediaSession?.player === cp) { updateSignalPath(); publishNowPlaying() }
            }
        })
    }

    private fun selectNetworkOutput(target: NetworkTarget?) {
        networkSwitchJob?.cancel()
        val remote = networkPlayer
        if (remote == null) { completeNetworkOutputSwitch(target); return }
        val resume = handoffPlayWhenReady ?: remote.playWhenReady
        handoffPlayWhenReady = resume
        networkSwitchJob = scope.launch {
            remote.shutdown()
            if (isActive) {
                completeNetworkOutputSwitch(target, resume && remote.stopConfirmed)
                handoffPlayWhenReady = null
                if (!remote.stopConfirmed) container.networkOutput.update { it.copy(error = "The previous receiver did not confirm stop. Playback is paused.") }
            }
        }
    }

    private fun completeNetworkOutputSwitch(target: NetworkTarget?, requestedPlay: Boolean? = null) {
        if (target != null && container.networkOutput.state.value.receiverEnabled) {
            container.networkOutput.update { it.copy(error = "Turn off receiver mode before sending audio.") }
            return
        }
        if (mixPlayer != null) stopMix()
        if (xfadeActive) endXfade() else clearPrepared()
        val from = mediaSession?.player ?: return
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        val index = from.currentMediaItemIndex.coerceAtLeast(0)
        val position = from.currentPosition.coerceAtLeast(0)
        val wanted = requestedPlay ?: from.playWhenReady
        val old = networkPlayer
        val repeat = from.repeatMode
        val shuffleOrder = if (old != null) old.shuffleRestoreIds else originalOrder
        from.pause()
        if (target == null) {
            networkPlayer = null
            mediaSession?.player = player
            originalOrder = shuffleOrder
            player.repeatMode = repeat
            if (items.isNotEmpty()) { player.setMediaItems(items, index.coerceAtMost(items.lastIndex), position); player.prepare() }
            else player.clearMediaItems()
            player.playWhenReady = false
            container.networkOutput.update { it.copy(receiverName = null, receiverKind = null, preparing = false, detail = "", error = null) }
            old?.release()
            updateSignalPath(); publishNowPlaying()
            return
        }
        val receiver = when (target) {
            is NetworkTarget.Dlna -> DlnaNetworkReceiver(target.renderer)
            is NetworkTarget.Aurora -> AuroraNetworkReceiver(target.renderer)
            NetworkTarget.Cast -> {
                val cp = castPlayer ?: return
                val castSession = runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
                    .sessionManager.currentCastSession }.getOrNull()
                val host = castSession?.castDevice?.inetAddress?.hostAddress
                if (host.isNullOrBlank()) {
                    container.networkOutput.update { it.copy(error = "The Cast receiver address is unavailable.") }; return
                }
                CastNetworkReceiver(cp, host, castSession)
            }
        }
        player.stop()
        val processed = target != NetworkTarget.Cast || container.networkOutput.state.value.processedCast
        networkOriginalStream = !processed || receiver.kind == "Aurora"
        val remote = NetworkQueuePlayer(receiver, render = { item -> prepareNetworkMedia(item, receiver, processed) }) { preparing, detail, error ->
            if (networkPlayer?.receiver === receiver) container.networkOutput.update {
                it.copy(preparing = preparing, detail = detail, error = error)
            }
        }
        networkPlayer = remote
        mediaSession?.player = remote
        container.networkOutput.update { it.copy(receiverName = receiver.name, receiverKind = receiver.kind, error = null) }
        remote.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (networkPlayer === remote) {
                    originalOrder = remote.shuffleRestoreIds
                    if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                        events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) updateCustomLayout()
                    updateSignalPath(); publishNowPlaying()
                }
            }
        })
        old?.release()
        if (items.isNotEmpty()) { remote.setMediaItems(items, index.coerceAtMost(items.lastIndex), position); remote.playWhenReady = wanted; remote.prepare() }
        remote.repeatMode = repeat
        remote.adoptShuffle(shuffleOrder)
        updateCustomLayout()
        updateSignalPath(); publishNowPlaying()
    }

    private suspend fun prepareNetworkMedia(item: MediaItem, receiver: NetworkReceiver, processed: Boolean): NetworkMedia {
        if (!processed || receiver.kind == "Aurora") {
            val direct = try { com.aurora.music.playback.network.audio.DirectNetworkPreparer.prepare(this, item, networkDataSource) }
            catch (e: com.aurora.music.playback.network.audio.NetworkRenderingException) { throw NetworkOutputException(e.message ?: "Audio preparation failed.") }
            return grantNetworkMedia(receiver, direct.file, direct.mimeType, direct.durationMs,
                if (receiver.kind == "Aurora") "Original audio file; the Aurora receiver applies its own settings" else direct.summary)
        }
        val settings = container.settingsStore.processingSettings.first()
        val snapshot = com.aurora.music.data.ProcessingSnapshot(
            settings.audio, com.aurora.music.data.ProcessingPlaybackPrefs.from(settings.playback), rack = settings.rack)
        val ir = currentImpulse
        val impulses = rackImpulses.toMap()
        val extras = item.mediaMetadata.extras
        val db = if (settings.audio.replayGain == 2) extras?.getFloat("rgAlbum", 0f) ?: 0f else extras?.getFloat("rgTrack", 0f) ?: 0f
        val gain = if (settings.audio.replayGain == 0) 1.0 else Math.pow(10.0, db.coerceAtMost(0f) / 20.0)
        val relativeVolume = try { receiver.status().volume?.toDouble() ?: 1.0 }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { 1.0 }
        val rendered = try { ProcessedNetworkRenderer.render(this, item, networkDataSource, snapshot,
            impulses = impulses, legacyImpulse = ir, outputGain = gain, relativeVolume = relativeVolume) }
        catch (e: com.aurora.music.playback.network.audio.NetworkRenderingException) { throw NetworkOutputException(e.message ?: "Audio preparation failed.") }
        return grantNetworkMedia(receiver, rendered.file, rendered.mimeType, rendered.durationMs, rendered.processingSummary)
    }

    private suspend fun grantNetworkMedia(receiver: NetworkReceiver, file: java.io.File, mime: String, duration: Long, summary: String): NetworkMedia {
        var release: (() -> Unit)? = null
        try {
            val negotiatedMime = receiver.contentType(mime)
            return withContext(Dispatchers.IO) {
                val address = java.net.InetAddress.getByName(receiver.host)
                val localAddress = java.net.DatagramSocket().use { socket -> socket.connect(address, 9); socket.localAddress.hostAddress!! }
                synchronized(this@PlaybackService) {
                    check(!networkDisposed)
                    val server = networkServer ?: ScopedMediaServer().also { it.start(); networkServer = it }
                    val grant = server.grant(file, negotiatedMime, 4 * 60 * 60 * 1000L, allowedClient = address)
                    val cleanup = { server.revoke(grant.token); file.delete(); Unit }
                    release = cleanup
                    NetworkMedia(grant.url(localAddress, server.port), negotiatedMime, duration, summary,
                        sizeBytes = file.length(), release = cleanup)
                }
            }
        } catch (e: Exception) { release?.invoke(); file.delete(); throw e }
    }

    private fun rendererRoute(): EndpointRoute {
        val path = container.signalPath.value
        val usb = processedUsbSink?.telemetry?.active == true || usbSink?.playbackTelemetry?.usbActive == true
        val id = if (usb) if (usbMode == UsbOutputMode.PROCESSED) "usb-processed" else "usb-direct" else "local"
        return EndpointRoute(id, path.output.ifBlank { "This device" }, sampleRateHz = path.outputStage.format?.rateHz ?: 0,
            bitDepth = path.outputStage.format?.bitDepth ?: 0, processed = path.preservation == com.aurora.music.data.Preservation.MODIFIED)
    }

    private fun setReceiverForeground(enabled: Boolean) {
        receiverForeground = enabled
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (!enabled) {
            notificationManager.cancel(NETWORK_NOTIFICATION_ID)
            if (!(mediaSession?.player?.isPlaying ?: false)) stopForeground(STOP_FOREGROUND_REMOVE)
            mediaSession?.let { super.onUpdateNotification(it, it.player.isPlaying) }
            return
        }
        notificationManager.createNotificationChannel(NotificationChannel("aurora_receiver", "Aurora receiver", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 91, android.content.Intent(this, com.aurora.music.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val stop = android.app.PendingIntent.getService(this, 92, android.content.Intent(this, PlaybackService::class.java).setAction(ACTION_RECEIVER_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val transport = android.app.PendingIntent.getService(this, 93, android.content.Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = androidx.core.app.NotificationCompat.Builder(this, "aurora_receiver")
            .setSmallIcon(com.aurora.music.R.drawable.ic_aurora_logo).setContentTitle("Aurora receiver")
            .setContentText(if (player.isPlaying) player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Playing" else "Ready for a paired device")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Turn off", stop)
        if (player.currentMediaItem != null) {
            builder.setContentTitle(player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Aurora receiver")
                .setContentText(player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty())
                .addAction(if (player.playWhenReady) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (player.playWhenReady) "Pause" else "Play", transport)
            mediaSession?.let { builder.setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(1)) }
        }
        val notification = builder.build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NETWORK_NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (player.isPlaying) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        else startForeground(NETWORK_NOTIFICATION_ID, notification)
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
            ACTION_RECEIVER_START -> setReceiverForeground(true)
            ACTION_RECEIVER_STOP -> networkBridge?.enableReceiver(false)
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
        if (bitPerfect || mediaSession?.player === castPlayer || networkPlayer != null) {
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

    private fun mixAudioConfig(volume: Double = 1.0) = com.aurora.music.mix.MixAudioConfig(
        params = currentDspParams, mode = lastAudioPrefs?.dspMode ?: DspMode.OFF,
        mono = monoAudioPref, impulse = currentImpulse, convolution = lastAudioPrefs?.dspConvEnabled == true,
        convolutionGain = lastAudioPrefs?.dspConvMakeupDb ?: 0f, audioSessionId = container.audioSessionId,
        replayGain = replayGainMode, rack = lastRack?.takeIf { it.enabled && lastAudioPrefs?.dspMode == DspMode.CUSTOM },
        rackImpulses = rackImpulses, tpdfDither = outputRatePolicy.tpdfDither, relativeVolume = volume,
        noiseShaping = outputRatePolicy.noiseShaping == true)

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
            // ignore dismissed or replaced alarms
            if (!isActive) return@launch
            networkSwitchJob?.cancel()
            networkPlayer?.let { remote ->
                withContext(kotlinx.coroutines.NonCancellable) {
                    remote.shutdown()
                    if (!networkDisposed && networkPlayer === remote) {
                        completeNetworkOutputSwitch(null)
                        handoffPlayWhenReady = null
                    }
                }
                if (!isActive || networkDisposed || networkPlayer != null) return@launch
                if (!remote.stopConfirmed) container.networkOutput.update {
                    it.copy(error = "The receiver did not confirm stop. The alarm is playing on this device.")
                }
            }
            if (mixPlayer != null) stopMix()
            if (xfadeActive) endXfade() else clearPrepared()
            originalOrder = null
            player.shuffleModeEnabled = false
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

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (receiverForeground) setReceiverForeground(true) else super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (receiverForeground) return
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
        flushListeningHistory()
        historySong?.let { container.discord.update(it, false, 0f) }
        container.playHistory.endListeningSession()
        networkDisposed = true
        networkBridge?.close(); networkBridge = null
        networkPlayer?.release(); networkPlayer = null
        networkServer?.close(); networkServer = null
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
        sinkEvidence.values.forEach { it.routedOutput?.close() }
        sinkEvidence.clear()
        container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute())
        container.settingsStore.presetRuleContext.publish(com.aurora.music.data.rules.PresetPlaybackContext())
        androidAutoControllers.clear()
        container.signalPath.value = SignalPath()
        container.listeningLevels.observe(com.aurora.music.data.listening.ListeningObservation(
            route = container.settingsStore.processingRoutes.current, playing = false, postDsp = null,
            downstreamGain = null, volumeIndex = null, volumeMuted = true, pathSupported = false,
            allowHistory = false))
        super.onDestroy()
    }

    companion object {
        const val ACTION_MIX = "com.aurora.music.action.MIX"
        const val ACTION_RECEIVER_START = "com.aurora.music.action.RECEIVER_START"
        const val ACTION_RECEIVER_STOP = "com.aurora.music.action.RECEIVER_STOP"
        private const val NETWORK_NOTIFICATION_ID = 0xA15
        const val CMD_EXIT_MIX = "com.aurora.music.EXIT_MIX"
        const val CMD_QUEUE_APPEND = "com.aurora.music.QUEUE_APPEND"
        const val QUEUE_TOKEN = "aurora_queue_token"
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
