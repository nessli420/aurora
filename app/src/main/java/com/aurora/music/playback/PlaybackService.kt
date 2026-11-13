package com.aurora.music.playback

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DspMode
import com.aurora.music.data.UsbFallbackPolicy
import com.aurora.music.playback.dsd.DsdExtractorsFactory
import com.aurora.music.data.SignalPath
import com.aurora.music.data.SignalPathFacts
import com.aurora.music.data.PlaybackPathKind
import com.aurora.music.data.buildSignalPath
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.aurora.music.playback.network.*
import com.aurora.music.playback.network.endpoint.ConnectCommand
import com.aurora.music.playback.network.endpoint.ConnectSwitch
import com.aurora.music.playback.network.endpoint.ConnectTrack
import com.aurora.music.playback.network.endpoint.PairedRenderer
import com.aurora.music.playback.network.endpoint.resolveConnectTracks

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val container by lazy { (application as AuroraApplication).container }
    private val libraryBrowser by lazy { PlaybackLibraryBrowser(this, container.repository) }
    private lateinit var player: ExoPlayer
    private val shuffleQueue = LocalShuffleQueue { player }
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private var networkPlayer: NetworkQueuePlayer? = null
    private var networkBridge: NetworkPlaybackBridge? = null
    private var remotePlayer: com.aurora.music.playback.network.RemoteSessionPlayer? = null
    private var connectHost: com.aurora.music.playback.network.AndroidConnectHost? = null
    private lateinit var networkMedia: NetworkMediaPreparer
    private var networkSwitchJob: kotlinx.coroutines.Job? = null
    private var networkOriginalStream = false
    private var handoffPlayWhenReady: Boolean? = null
    @Volatile private var networkDisposed = false
    private var mixPlayer: com.aurora.music.mix.MixPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // periodic work only runs while something is audible or fading
    private val engaged = MutableStateFlow(false)
    private val musicHaptics by lazy { MusicHaptics(this) }
    private var lastNowPlaying: NowPlaying? = null
    private val wakeModes = java.util.IdentityHashMap<ExoPlayer, Int>()
    private val processing = PlaybackProcessing(scope, pushMix = { mixPlayer?.applyAudioConfig(mixAudioConfig()) }, refresh = ::updateSignalPath)
    private val fades = PlaybackFades(scope, live = { player }, setLive = { player = it }, attach = { attach(it) },
        isLocal = { mediaSession?.player === player }, bitPerfect = { output.bitPerfect }, usbSink = { output.usbSink },
        independentOutput = { independentOutput }, newDeck = ::buildFadeDeck,
        onNewDeck = { applyPreferredDevice(container.preferredAudioDeviceId.value) })
    private val sessionCallback = PlaybackSessionCallback({ libraryBrowser }, scope,
        { p -> if (p === player) { shuffleQueue.originalOrder = null; player.shuffleModeEnabled = false } }, ::updateSignalPath, ::customCommand)
    private val foreground = PlaybackForeground(this, { mediaSession }, { player }, ::mediaNotification)
    private lateinit var output: PlaybackAudioOutput

    private val listeningHistory by lazy { PlaybackListeningHistory(container.playHistory, container.discord) }
    private val listeningDetails by lazy {
        PlaybackListeningDetails({ play -> container.listeningDetails.save(play) }, { container.settingsStore.processingRoutes.current.route },
            { container.signalPath.value }, { processing.lastAudioPrefs }, { processing.lastRack }, { zone, point -> container.placesStore.sample(zone, point) }, { fades.crossfadeMs.toLong() })
    }
    private val resumeTracker by lazy { ResumeTracker(container.resumePoints, scope, container.settingsStore.playbackPrefs) }
    private val playbackReporting by lazy { PlaybackReportingController(container.repository, container.playbackReports,
        allowed = { container.playbackReportingAllowed.value && !container.offline.value && container.sessionReady.value == true },
        nativePlaying = { active -> active === player && output.usbSink?.isNativeEngineActive == true && player.playWhenReady }) }
    @Volatile private var independentOutput: Boolean = false
    private val nowPlaying by lazy { NowPlayingStore(this) }

    private var youtubeSkips = 0
    private lateinit var musicSourceFactory: YoutubeMediaSourceFactory

    override fun onCreate() {
        super.onCreate()
        val networkFiles = Regex("[0-9a-f-]{36}\\.(source|partial|wav|mp3|flac|ogg|m4a|aac)")
        java.io.File(cacheDir, "network-audio").listFiles()?.filter { it.isFile && networkFiles.matches(it.name) }
            ?.forEach { it.delete() }

        val initialPrefs = runBlocking { container.settingsStore.playbackPrefs.first() }
        processing.outputRatePolicy = initialPrefs.outputRatePolicy
        output = PlaybackAudioOutput(this, initialPrefs, scope, processing, fades, { player }, musicHaptics, container.visualizer,
            container.settingsStore.processingRoutes, container.signalPath, container.streamFormat, container.preferredAudioDeviceId,
            { container.youtubeResolver.streamBitrateKbps(it) }, ::updateSignalPath)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = output.renderersFactory()

        // resolve aurora-yt sentinel uris to a real youtube stream just-in-time on the loader thread
        val resolver = container.youtubeResolver
        val ytResolver = androidx.media3.datasource.ResolvingDataSource.Resolver { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "aurora-yt") {
                val real = resolver.resolveSentinel(uri)
                    ?: throw YoutubeStreamException("This YouTube stream is unavailable. Please try again.")
                dataSpec.withUri(android.net.Uri.parse(real))
            } else if (uri.scheme == "aurora-extension") dataSpec.withUri(container.extensions.resolve(uri))
            else cueSource(uri).let { if (it == uri) dataSpec else dataSpec.withUri(it) }
        }
        val resolvedDataSourceFactory = androidx.media3.datasource.ResolvingDataSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this), ytResolver,
        )
        val dataSourceFactory = com.aurora.music.playback.sacd.SacdDataSource.Factory(this, container.audioCache.factory(resolvedDataSourceFactory))
        networkMedia = NetworkMediaPreparer(this, dataSourceFactory, container.settingsStore.processingSettings,
            { processing.currentImpulse }, { processing.rackImpulses }, { networkDisposed })
        val mediaSourceFactory = YoutubeMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, DsdExtractorsFactory(rawOutput = output.dsdWire != null)), resolver,
            preferred = { PreferredPlayback.resolve(this, container.repository, it, container::localCopy) }, prepare = { container.audioCache.prepare(cueClipped(it)) },
            soundcloud = container::resolveSoundCloud, standIn = container::playOrStandIn)

        musicSourceFactory = mediaSourceFactory
        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // hold cpu and wifi so a backgrounded device does not sleep through the next youtube resolve
            .setWakeMode(C.WAKE_MODE_NETWORK)
        // later media3 defaults: start after 1 s of audio, resume after 2 s (1.5.1 waits 2.5 s and 5 s)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 50_000, 1_000, 2_000).build()
        playerBuilder.setLoadControl(if (output.bitPerfect) {
            // stop exoplayer reading the file while the native flac engine handles decode + usb
            com.decent.usbaudio.media3.UsbAudioSink.wrapLoadControl(loadControl) { output.usbSink?.isNativeEngineActive == true }
        } else loadControl)
        player = playerBuilder.build()
        output.attachMainDeck(player)

        // share session id so the system dsp effects created on it apply to playback
        if (container.audioSessionId != 0) {
            runCatching { player.setAudioSessionId(container.audioSessionId) }
        }

        player.addListener(serviceListener(player))

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setCustomLayout(buildCustomLayout())
            .build()

        djQueue.follow(player)
        container.aiDjQueue = djQueue
        djQueue.watch(scope, ::updateCustomLayout)

        playbackReporting.observe(player)
        resumeTracker.observe(player)

        setupCast()
        connectHost = com.aurora.music.playback.network.AndroidConnectHost(this, { player }, output::rendererRoute,
            { player.shuffleModeEnabled }, { on -> shuffleQueue.setShuffle(if (on) 1 else 0, null) })
        networkBridge = NetworkPlaybackBridge(this, { player }, container.networkOutput,
            ::selectNetworkOutput, output::rendererRoute, foreground::setReceiverForeground, connectHost, ::selectRemote)

        startAudioObservers(audioAttributes)
        output.listen()
    }

    private fun serviceListener(owner: ExoPlayer) = object : Player.Listener {
            // added before the session's listener, so this runs before media3 republishes the queue
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (owner === mediaSession?.player) sessionCallback.gatePlatformQueue(mediaSession, timeline.windowCount)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // idle also covers a restored queue the view model hasn't prepared yet
                if (owner === player && playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 &&
                    (player.playerError == null || output.bitPerfect)) player.prepare()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (owner === player && isYoutubeStreamFailure(error)) {
                    container.report(com.aurora.music.localization.appString(com.aurora.music.R.string.stand_in_skipped,
                        player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()))
                    skipUnplayableYoutube()
                    return
                }
                if (owner === player && error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (owner !== player || !output.bitPerfect) return
                val resume = player.playWhenReady
                if (output.processedUsbSink?.useAndroidAfterFailure() == true) {
                    val position = player.currentPosition
                    player.seekTo(position)
                    player.prepare()
                    player.playWhenReady = resume
                } else if (output.usbFallback == UsbFallbackPolicy.PAUSE) player.pause()
            }
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED) || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                ) applyWakeMode(owner)
                if (p !== player) { refreshEngaged(); return }
                if (fades.xfadeActive && (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))) {
                    fades.followLive(p.isPlaying)
                }
                refreshEngaged()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED)
                ) fades.applyIdleGain()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) fades.onTimelineChanged()
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                ) updateCustomLayout()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) shuffleQueue.maybeNeutralize()
                if (p.playWhenReady && (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))) {
                    val next = p.nextMediaItemIndex
                    if (next != C.INDEX_UNSET) musicSourceFactory.prefetch(p.getMediaItemAt(next))
                }
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && p.playbackState == Player.STATE_READY) youtubeSkips = 0
                if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
                ) updateSignalPath()
                // bit-perfect bypasses exoplayer's processors, so drive the native usb varispeed, 1.0x reverts to bit-perfect
                if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                ) output.usbSink?.setTimeStretch(p.playbackParameters.speed)
                // re-arm the bit-perfect crossfade on a transition or any timeline change so the latch cannot stick
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED)
                ) fades.rearmNative()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) publishNowPlaying()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (owner === player && fades.xfadeActive && reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.mediaItemIndex == player.currentMediaItemIndex) fades.endXfade()
            }
        }

    private fun isYoutubeStreamFailure(error: androidx.media3.common.PlaybackException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.take(8).any { cause ->
            cause is YoutubeStreamException ||
                (cause as? androidx.media3.datasource.HttpDataSource.HttpDataSourceException)
                    ?.dataSpec?.uri?.host?.endsWith(".googlevideo.com") == true
        }

    // skip an unresolvable youtube track, capped so a dead network cannot spin through the queue
    private fun skipUnplayableYoutube() {
        if (!player.hasNextMediaItem() || youtubeSkips >= MAX_YOUTUBE_SKIPS) { youtubeSkips = 0; return }
        youtubeSkips++
        val resume = player.playWhenReady
        android.util.Log.w("AuroraPlayback", "Skipping unplayable YouTube track ($youtubeSkips/$MAX_YOUTUBE_SKIPS)")
        player.seekToNextMediaItem()
        player.prepare()
        player.playWhenReady = resume
    }

    private fun startAudioObservers(audioAttributes: AudioAttributes) {
        val store = container.settingsStore
        processing.audioEffects = container.audioEffects
        scope.launch {
            store.outputRatePolicy.collect { policy ->
                processing.outputRatePolicy = policy
                output.applyDither(policy)
                mixPlayer?.applyAudioConfig(mixAudioConfig())
            }
        }
        scope.launch {
            store.engineImpulseAssets.collectLatest { entries ->
                processing.loadRackImpulses(entries)
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
            // one snapshot per preset so new channel settings never pair with the old eq
            store.processingSettings.collect { snapshot ->
                val prefs = snapshot.playback
                processing.lastAudioPrefs = snapshot.audio
                processing.lastRack = snapshot.rack
                fades.replayGainMode = snapshot.audio.replayGain
                player.skipSilenceEnabled = prefs.skipSilence
                processing.monoAudioPref = prefs.monoAudio
                fades.crossfadeMs = prefs.crossfadeSec * 1000
                fades.crossfadeCurve = prefs.crossfadeCurve
                fades.crossfadeHeadroom = prefs.crossfadeHeadroom
                fades.gapless = prefs.gapless
                output.gapless = prefs.gapless
                output.preferHighResPref = prefs.preferHighRes
                output.exclusiveUsbPref = prefs.bitPerfectUsb
                output.usbModePref = prefs.usbOutputMode
                output.usbFallbackPref = prefs.usbFallbackPolicy
                output.hardwareVolume?.enabled = prefs.usbHardwareVolume != false
                // independent output drops audio focus so other apps keep the speaker, switchable at runtime
                if (prefs.independentOutput != independentOutput) {
                    independentOutput = prefs.independentOutput
                    runCatching { player.setAudioAttributes(audioAttributes, !independentOutput) }
                }
                processing.apply()
                fades.applyIdleGain()
                if (fades.crossfadeMs <= 0 && !fades.xfadeActive) fades.clearPrepared()
            }
        }
        scope.launch {
            container.preferredAudioDeviceId.collect { id -> applyPreferredDevice(id) }
        }
        scope.launch {
            container.settingsStore.musicHapticsPrefs.collect { musicHaptics.setPrefs(it) }
        }
        scope.launch {
            container.settingsStore.reverbPrefs.collect { prefs ->
                processing.setReverb(prefs)
                updateSignalPath()
            }
        }
        scope.launch {
            container.settingsStore.privateSession.collect { privateSession ->
                listeningHistory.allowed = !privateSession
                listeningDetails.privateSession = privateSession
                listeningDetails.update()
            }
        }
        scope.launch {
            container.settingsStore.insightsPrefs.collect { prefs ->
                listeningDetails.prefs = prefs
                listeningDetails.update()
            }
        }
        scope.launch {
            container.settingsStore.activeEqProfile.collect { listeningDetails.autoEqProfile = it }
        }
        scope.launch {
            container.playbackReportingAllowed.collect { playbackReporting.sample() }
        }

        scope.launch {
            while (isActive) {
                engaged.first { it }
                while (refreshEngaged()) {
                    fades.tick()
                    // ramp finely while a fade is in flight idle replaygain tracking needs only a coarse tick
                    delay(if (mediaSession?.player !== player) 1000L else if (fades.xfadeActive || fades.sleepFadeActive || fades.wakeFadeActive) 25L else 100L)
                }
                fades.tick()
            }
        }
        // usb engine and volume or fade changes need snapshots even without a media3 track event
        scope.launch {
            while (isActive) {
                engaged.first { it }
                do {
                    if (container.signalPath.subscriptionCount.value == 0) updateSignalPath()
                    output.hardwareVolume?.poll()
                    delay(1000)
                    trackPlayback()
                } while (refreshEngaged())
                updateSignalPath()
            }
        }
        scope.launch {
            container.signalPath.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                output.spectrumTaps(watched)
                if (watched) while (true) { updateSignalPath(); delay(500) }
            }
        }
    }

    private fun trackPlayback() {
        val active = mediaSession?.player
        playbackReporting.observe(active)
        resumeTracker.observe(active)
        val nativeUsb = active === player && output.usbSink?.isNativeEngineActive == true && player.playWhenReady
        listeningHistory.track(active, nativeUsb)
        listeningDetails.track(active, nativeUsb)
    }

    private fun refreshEngaged(): Boolean = playbackEngaged().also {
        engaged.value = it
        musicHaptics.setEngaged(it && ::player.isInitialized && mediaSession?.player === player && player.playWhenReady)
    }

    private fun playbackEngaged(): Boolean {
        if (!::player.isInitialized) return false
        val local = mediaSession?.player === player
        if (local && (fades.sleepFadeActive || fades.wakeFadeActive)) return true
        if (fades.xfadeActive && (player.playWhenReady || fades.fadePlayer?.playWhenReady == true)) return true
        if (local && player.playWhenReady && output.usbSink?.isNativeEngineActive == true) return true
        val active = mediaSession?.player ?: return false
        return active.playWhenReady && active.playbackState != Player.STATE_IDLE && active.playbackState != Player.STATE_ENDED
    }

    // local-only stretches drop the wifi lock a network neighbour keeps it for the resolve gap
    private fun applyWakeMode(target: ExoPlayer) {
        val current = target.currentMediaItem ?: return
        val next = target.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { target.getMediaItemAt(it) }
        val mode = if (isLocalItem(current) && (next == null || isLocalItem(next))) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK
        if (wakeModes.put(target, mode) != mode) target.setWakeMode(mode)
    }

    private fun isLocalItem(item: MediaItem) = item.localConfiguration?.uri?.scheme?.lowercase().orEmpty() in LOCAL_SCHEMES

    private fun applyPreferredDevice(id: Int) {
        mixPlayer?.refreshOutputDevice()
        runCatching {
            if (id == 0) {
                player.setPreferredAudioDevice(null)
                fades.fadePlayer?.setPreferredAudioDevice(null)
            } else {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val device = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                player.setPreferredAudioDevice(device)
                fades.fadePlayer?.setPreferredAudioDevice(device)
            }
        }
        updateSignalPath()
    }

    private fun publishPresetRuleContext(active: Player) {
        val item = active.currentMediaItem
        val context = PresetContextPublisher.build(item,
            active = item != null && active.playbackState != Player.STATE_IDLE && active.playbackState != Player.STATE_ENDED,
            observedSourceFormat = if (active === player) output.observedSource(item?.mediaId) else null,
            androidAuto = sessionCallback.androidAuto,
            cast = active === networkPlayer || castPlayer != null && active === castPlayer)
        container.settingsStore.presetRuleContext.publish(context)
    }

    private fun updateSignalPath() {
        refreshEngaged()
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val volumeStep = runCatching { audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }
        val maximum = runCatching { audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
        try { updateSignalPathSnapshot(volumeStep, maximum) }
        finally { updateListeningLevels(audio, volumeStep.getOrNull(), maximum.getOrNull()) }
    }

    private fun updateListeningLevels(audio: android.media.AudioManager, volumeStep: Int?, maximum: Int?) {
        val active = if (::player.isInitialized) mediaSession?.player ?: player else null
        val normal = ::player.isInitialized && active === player
        val path = container.signalPath.value
        val measurements = path.measurements
        val muted = runCatching { audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(true)
        val prefs = processing.lastAudioPrefs
        val supported = normal && path.active && measurements?.afterAvailable == true &&
            !measurements.overlappingPlayers && prefs != null && prefs.dspMode != DspMode.SYSTEM &&
            processing.audioEffects?.activeEffectNames().orEmpty().isEmpty() &&
            player.playbackParameters.speed == 1f && player.playbackParameters.pitch == 1f
        container.listeningLevels.observe(com.aurora.music.data.listening.ListeningObservation(
            route = container.settingsStore.processingRoutes.current, playing = active?.isPlaying == true,
            postDsp = measurements?.after, downstreamGain = if (normal) output.downstreamGain() else null,
            volumeIndex = volumeStep, volumeMuted = muted, pathSupported = supported,
            allowHistory = listeningHistory.allowed, volumeMaximum = maximum))
    }

    private fun updateSignalPathSnapshot(volumeStep: Result<Int>, maximum: Result<Int>) {
        if (!::player.isInitialized) return
        val active = mediaSession?.player ?: player
        publishPresetRuleContext(active)
        val volume = volumeStep.getOrThrow().toDouble() / maximum.getOrThrow().coerceAtLeast(1)
        processing.setRelativeVolume(volume)
        mixPlayer?.applyAudioConfig(mixAudioConfig(volume))
        if (mixPlayer != null && active === mixPlayer) {
            val route = mixPlayer!!.confirmedOutput
            container.settingsStore.processingRoutes.publish(route)
            output.requestBitPerfect(null, null, false)
            val path = buildSignalPath(SignalPathFacts(kind = if (active.playbackState == Player.STATE_IDLE ||
                active.playbackState == Player.STATE_ENDED) PlaybackPathKind.IDLE else PlaybackPathKind.MIX,
                confirmedDevice = route.category))
            container.signalPath.value = if (path.active) path.copy(processing = com.aurora.music.data.SignalStage("Processing",
                mixPlayer!!.processingDescription, "Active deck processing state; summed output and downstream hardware are not measured")) else path
            return
        }
        remotePlayer?.takeIf { active === it }?.let { remote ->
            output.requestBitPerfect(null, null, false)
            container.signalPath.value = SignalPath(active = false, output = "Aurora · ${remote.name}",
                note = "Playing on ${remote.name}; that device owns its audio path.")
            return
        }
        if (networkPlayer != null && active === networkPlayer) {
            output.requestBitPerfect(null, null, false)
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
            output.requestBitPerfect(null, null, false)
            container.signalPath.value = buildSignalPath(SignalPathFacts(kind = if (active.currentMediaItem == null ||
                active.playbackState == Player.STATE_IDLE || active.playbackState == Player.STATE_ENDED) PlaybackPathKind.IDLE else PlaybackPathKind.CAST))
            return
        }
        output.reportLocal()
    }

    private fun buildFadeDeck(): ExoPlayer = output.newFadeDeck { factory ->
        ExoPlayer.Builder(this, factory)
            .setMediaSourceFactory(musicSourceFactory)
            .setAudioAttributes(player.audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
            }
    }.also {
        it.volume = 0f
        if (container.audioSessionId != 0) it.setAudioSessionId(container.audioSessionId)
        it.addListener(serviceListener(it))
    }

    private fun attach(next: Player, count: Int = next.mediaItemCount) {
        sessionCallback.gatePlatformQueue(mediaSession, count)
        mediaSession?.player = next
        djQueue.follow(next)
        connectHost?.follow()
    }

    private fun customCommand(session: MediaSession, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            CMD_QUEUE_APPEND -> {
                val active = session.player
                val token = args.getString("token")
                val count = args.getInt("count", -1)
                val bundles = args.getParcelableArrayList<Bundle>("items")
                val items = runCatching { bundles?.map(MediaItem::fromBundle) }.getOrNull()
                val reason = when {
                    mixPlayer != null -> "mix is active"
                    remotePlayer != null -> "controlling another device"
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
            CMD_SHUFFLE -> if (container.aiDjMode.state.value.active && customCommand.customExtras.getInt("target", -1) != 0) Unit
            else if (remotePlayer != null) remotePlayer!!.setShuffle(customCommand.customExtras.getInt("target", -1))
            else if (networkPlayer != null) {
                networkPlayer!!.shuffle(customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"))
            } else if (mixPlayer != null) {
                return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            } else {
                // shuffle with autoplay's picks leave with it, before the queue goes back in order
                customCommand.customExtras.getStringArrayList("drop")?.let(shuffleQueue::dropAhead)
                shuffleQueue.setShuffle(
                    customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"),
                )
            }
            CMD_REPEAT -> if (container.aiDjMode.state.value.active) Unit else remotePlayer?.cycleRepeat() ?: networkPlayer?.let { remote ->
                remote.repeatMode = PlaybackSessionCallback.nextRepeatMode(remote.repeatMode)
            } ?: mixPlayer?.let { mix ->
                mix.repeatMode = PlaybackSessionCallback.nextRepeatMode(mix.repeatMode)
            } ?: cycleRepeat()
            CMD_SLEEP_FADE -> {
                if (networkPlayer != null || remotePlayer != null) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                val ms = customCommand.customExtras.getInt("fadeMs", 0)
                mixPlayer?.let { it.sleepFade(ms); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                fades.sleepFade(ms)
                refreshEngaged()
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun buildCustomLayout(): List<CommandButton> =
        if (container.aiDjMode.state.value.active) emptyList() else PlaybackSessionCallback.customLayout(mediaSession?.player ?: player)

    private fun updateCustomLayout() {
        runCatching { mediaSession?.setCustomLayout(buildCustomLayout()) }
    }

    private val djQueue by lazy { AiDjPlaybackQueue({ mediaSession?.player ?: player }, container.aiDjMode, container.repository) }

    private fun cycleRepeat() {
        player.repeatMode = PlaybackSessionCallback.nextRepeatMode(player.repeatMode)
    }

    private fun setupCast() {
        val task = runCatching {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext, Dispatchers.IO.asExecutor())
        }.getOrNull() ?: return
        task.addOnFailureListener { android.util.Log.w("AuroraCast", "Cast is unavailable", it) }
        task.addOnSuccessListener { castContext ->
            if (networkDisposed) return@addOnSuccessListener
            val cp = androidx.media3.cast.CastPlayer(castContext)
            val availability = object : androidx.media3.cast.SessionAvailabilityListener {
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
            }
            cp.setSessionAvailabilityListener(availability)
            castPlayer = cp
            cp.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (mediaSession?.player === cp) { updateSignalPath(); publishNowPlaying() }
                }
            })
            // a session resumed before this listener existed
            if (cp.isCastSessionAvailable) availability.onCastSessionAvailable()
        }
    }

    private fun selectRemote(peer: PairedRenderer?) {
        val old = remotePlayer
        val behaviour = kotlinx.coroutines.runBlocking { container.settingsStore.connectSwitch.first() }
        if (peer == null) {
            if (old != null) leaveRemote(old, behaviour == ConnectSwitch.MOVE)
            return
        }
        if (networkPlayer != null) {
            container.networkOutput.update { it.copy(error = "Disconnect network output before controlling another device.") }
            return
        }
        if (mixPlayer != null) stopMix()
        old?.release()
        val session = container.networkOutput.openSession(peer)
        val remote = com.aurora.music.playback.network.RemoteSessionPlayer(this, session)
        val play = if (player.mediaItemCount == 0) null else {
            val tracks = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }.map { item ->
                ConnectTrack(item.mediaId, item.mediaMetadata.title?.toString().orEmpty().take(512),
                    item.mediaMetadata.artist?.toString().orEmpty().take(512), item.mediaMetadata.albumTitle?.toString().orEmpty().take(512),
                    item.mediaMetadata.extras?.getInt("aurora.durationSec") ?: 0)
            }
            val index = player.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
            val from = (index - 100).coerceIn(0, (tracks.size - 500).coerceAtLeast(0))
            ConnectCommand.Play(tracks.subList(from, minOf(tracks.size, from + 500)),
                index - from, player.currentPosition.coerceAtLeast(0), player.playWhenReady)
        }
        val resume = player.playWhenReady
        if (behaviour != ConnectSwitch.KEEP) player.pause()
        remotePlayer = remote
        attach(remote)
        remote.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (remotePlayer !== remote) return
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) || events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) updateCustomLayout()
                updateSignalPath(); publishNowPlaying()
            }
        })
        // a queue the other device can't play comes straight back here instead of leaving both silent
        scope.launch {
            if (session.handOff(behaviour, play) || remotePlayer !== remote) return@launch
            val message = session.problem() ?: com.aurora.music.localization.appString(com.aurora.music.R.string.connect_error_lost, session.name)
            leaveRemote(remote, moveBack = false)
            if (resume) player.play()
            container.networkOutput.update { it.copy(error = message) }
            android.widget.Toast.makeText(this@PlaybackService, message, android.widget.Toast.LENGTH_LONG).show()
        }
        updateCustomLayout(); updateSignalPath(); publishNowPlaying()
    }

    private fun leaveRemote(old: com.aurora.music.playback.network.RemoteSessionPlayer, moveBack: Boolean) {
        remotePlayer = null
        attach(player)
        container.networkOutput.release(old.session)
        val remote = old.session.status.value
        val position = old.session.positionMs()
        scope.launch {
            if (moveBack && remote != null && remote.queue.isNotEmpty()) {
                val songs = container.repository.resolveConnectTracks(remote.queue)
                val start = songs.indexOfFirst { it.id == remote.current?.id }.coerceAtLeast(0)
                if (songs.isNotEmpty() && remotePlayer == null) {
                    player.setMediaItems(songs.map { songMediaItem(it, container.repository) }, start,
                        if (songs[start].id == remote.current?.id) position else 0)
                    player.prepare()
                    player.playWhenReady = remote.playing
                    old.session.send(ConnectCommand.Playing(false))
                }
            }
            old.release()
            old.session.close()
        }
        updateCustomLayout(); updateSignalPath(); publishNowPlaying()
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
        remotePlayer?.takeIf { target != null }?.let { remote ->
            container.networkOutput.update { it.copy(error = "Stop controlling ${remote.name} first.") }
            return
        }
        if (target != null && container.networkOutput.state.value.receiverEnabled) {
            container.networkOutput.update { it.copy(error = "Turn off receiver mode before sending audio.") }
            return
        }
        if (mixPlayer != null) stopMix()
        fades.cancel()
        val from = mediaSession?.player ?: return
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        val index = from.currentMediaItemIndex.coerceAtLeast(0)
        val position = from.currentPosition.coerceAtLeast(0)
        val wanted = requestedPlay ?: from.playWhenReady
        val old = networkPlayer
        val repeat = from.repeatMode
        val shuffleOrder = if (old != null) old.shuffleRestoreIds else shuffleQueue.originalOrder
        from.pause()
        if (target == null) {
            networkPlayer = null
            attach(player, items.size)
            shuffleQueue.originalOrder = shuffleOrder
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
        val remote = NetworkQueuePlayer(receiver, render = { item -> networkMedia.prepare(item, receiver, processed) }) { preparing, detail, error ->
            if (networkPlayer?.receiver === receiver) container.networkOutput.update {
                it.copy(preparing = preparing, detail = detail, error = error)
            }
        }
        networkPlayer = remote
        attach(remote, items.size)
        container.networkOutput.update { it.copy(receiverName = receiver.name, receiverKind = receiver.kind, error = null) }
        remote.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (networkPlayer === remote) {
                    shuffleQueue.originalOrder = remote.shuffleRestoreIds
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

    private fun publishNowPlaying() {
        val active = mediaSession?.player ?: player
        val item = active.currentMediaItem
        val md = item?.mediaMetadata
        val np = NowPlaying(
            title = md?.title?.toString().orEmpty(),
            artist = md?.artist?.toString().orEmpty(),
            artUri = md?.artworkUri?.toString().orEmpty(),
            // play intent, so buffering does not flip the widget icon
            isPlaying = active.playWhenReady && active.playbackState != Player.STATE_IDLE && active.playbackState != Player.STATE_ENDED,
            hasTrack = item != null,
        )
        if (np == lastNowPlaying) return
        lastNowPlaying = np
        nowPlaying.save(np)
        WidgetBridge.refresh(this)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val transport = mediaSession?.player ?: player
        when (intent?.action) {
            ACTION_RECEIVER_START -> foreground.setReceiverForeground(true)
            ACTION_RECEIVER_STOP -> container.networkOutput.enableReceiver(false)
            ACTION_MIX -> startMix()
            ACTION_PLAY_PAUSE -> { fades.stopWakeFade(); if (transport.playWhenReady) transport.pause() else transport.play() }
            ACTION_NEXT -> transport.seekToNextMediaItem()
            ACTION_PREV -> if (transport.currentPosition > 4000) transport.seekTo(0) else transport.seekToPreviousMediaItem()
            ACTION_ALARM -> startAlarmPlayback()
            ACTION_ALARM_DISMISS -> dismissAlarm()
        }
        super.onStartCommand(intent, flags, startId)
        // queue restore lives in the ui, so a sticky restart would only idle with an empty player
        return START_NOT_STICKY
    }

    private fun startMix() {
        val request = container.mixController.pendingProject ?: return
        container.mixController.pendingProject = null
        if (output.bitPerfect || mediaSession?.player === castPlayer || networkPlayer != null) {
            container.mixController.state.value = com.aurora.music.mix.MixPlaybackState(error =
                "Mixes use this device's audio output. Disconnect Cast or turn off exclusive USB output and restart playback first.")
            return
        }
        stopMix()
        fades.cancel()
        player.pause()
        fades.stopFades()
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val mix = com.aurora.music.mix.MixPlayer(this, request, musicSourceFactory, container.mixController,
            device = { am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == container.preferredAudioDeviceId.value } },
            startSec = container.mixController.pendingPosition, audioConfig = mixAudioConfig(),
            tracklist = container.mixController.pendingTracklist)
        mixPlayer = mix
        mix.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publishNowPlaying(); updateSignalPath() }
        })
        attach(mix)
        mediaSession?.setCustomLayout(emptyList())
        updateSignalPath()
        mix.play()
    }

    private fun stopMix() {
        val mix = mixPlayer ?: return
        mix.pause()
        attach(player)
        mix.release()
        mixPlayer = null
        updateCustomLayout()
        updateSignalPath()
        publishNowPlaying()
    }

    private fun mixAudioConfig(volume: Double = 1.0): com.aurora.music.mix.MixAudioConfig = processing.mixAudioConfig(volume, fades.replayGainMode, container.audioSessionId)

    private var alarmLoadJob: kotlinx.coroutines.Job? = null

    private fun dismissAlarm() {
        alarmLoadJob?.cancel()
        alarmLoadJob = null
        fades.stopWakeFade()
        runCatching { player.pause(); player.stop(); player.clearMediaItems() }
        foreground.cancelAlarmNotification()
        foreground.releaseAlarmForeground()
    }

    private fun startAlarmPlayback() {
        alarmLoadJob?.cancel()
        foreground.holdAlarmForeground()
        alarmLoadJob = scope.launch {
            val repo = container.repository
            val songs = runCatching { kotlinx.coroutines.withTimeoutOrNull(ALARM_LIKES_TIMEOUT_MS) { repo.starredSongs() } }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { repo.downloadedSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
            // ignore dismissed or replaced alarms
            if (!isActive) return@launch
            if (songs == null) { foreground.releaseAlarmForeground(); return@launch }
            val ordered = withContext(Dispatchers.Default) { songs.shuffled().map { libraryBrowser.songItem(it) } }
            networkSwitchJob?.cancel()
            networkPlayer?.let { remote ->
                withContext(kotlinx.coroutines.NonCancellable) {
                    remote.shutdown()
                    if (!networkDisposed && networkPlayer === remote) {
                        completeNetworkOutputSwitch(null)
                        handoffPlayWhenReady = null
                    }
                }
                if (!isActive || networkDisposed) return@launch
                if (networkPlayer != null) { foreground.releaseAlarmForeground(); return@launch }
                if (!remote.stopConfirmed) container.networkOutput.update {
                    it.copy(error = "The receiver did not confirm stop. The alarm is playing on this device.")
                }
            }
            if (mixPlayer != null) stopMix()
            fades.cancel()
            shuffleQueue.originalOrder = null
            player.shuffleModeEnabled = false
            player.setMediaItems(ordered, 0, 0L)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.volume = 0f
            fades.startWakeFade(30_000)
            player.play()
            refreshEngaged()
            publishNowPlaying()
            foreground.postAlarmNotification()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (foreground.receiverForeground) foreground.setReceiverForeground(true) else super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun mediaNotification(session: MediaSession, startInForegroundRequired: Boolean) =
        super.onUpdateNotification(session, startInForegroundRequired)

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (foreground.receiverForeground) return
        container.aiDjMode.stop()
        alarmLoadJob?.cancel()
        alarmLoadJob = null
        stopMix()
        // swipe-away stops playback so music doesnt keep going
        runCatching { fades.fadePlayer?.run { pause(); clearMediaItems() } }
        player.pause()
        player.stop()
        player.clearMediaItems()
        stopSelf()
    }

    override fun onDestroy() {
        container.aiDjMode.stop()
        container.aiDjQueue = null
        djQueue.close()
        musicHaptics.close()
        playbackReporting.close()
        resumeTracker.close()
        listeningHistory.finish()
        listeningDetails.finish()
        networkDisposed = true
        networkBridge?.close(); networkBridge = null
        connectHost?.close(); connectHost = null
        remotePlayer?.release(); remotePlayer = null
        container.networkOutput.closeSession()
        networkPlayer?.release(); networkPlayer = null
        networkMedia.close()
        scope.cancel()
        output.release()
        mixPlayer?.release()
        mixPlayer = null
        fades.release()
        runCatching { castPlayer?.setSessionAvailabilityListener(null); castPlayer?.release() }
        castPlayer = null
        mediaSession?.release()
        runCatching { player.release() } // sessions player may have been the cast player
        mediaSession = null
        output.clearEvidence()
        container.settingsStore.processingRoutes.publish(com.aurora.music.data.routes.ProcessingRoute())
        container.settingsStore.presetRuleContext.publish(com.aurora.music.data.rules.PresetPlaybackContext())
        sessionCallback.clearControllers()
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
        private const val MAX_YOUTUBE_SKIPS = 5
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
        private const val ALARM_LIKES_TIMEOUT_MS = 15_000L
        private val LOCAL_SCHEMES = setOf("file", "content", "android.resource", "aurora-sacd", "aurora-cache")
    }
}
