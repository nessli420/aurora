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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val monoProcessor = MonoAudioProcessor()
    private val auroraDsp = AuroraDspProcessor()
    private val convolver = ConvolutionProcessor()
    @Volatile private var lastIrPath: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioEffects: AudioEffectsController? = null
    @Volatile private var crossfadeMs: Int = 0
    @Volatile private var replayGainMode: Int = 0
    @Volatile private var monoAudioPref: Boolean = false
    @Volatile private var lastAudioPrefs: AudioPrefs? = null
    @Volatile private var useFloatOut: Boolean = false
    @Volatile private var grantedBitPerfect: Boolean = false
    @Volatile private var deviceSupportsBitPerfect: Boolean = false
    @Volatile private var preferHighResPref: Boolean = false

    @Volatile private var sleepFadeActive = false
    private var sleepFadeStartMs = 0L
    private var sleepFadeMs = 0
    @Volatile private var wakeFadeActive = false
    private var wakeFadeStartMs = 0L
    private var wakeFadeMs = 0
    private val nowPlaying by lazy { NowPlayingStore(this) }

    private var xfadeActive = false
    private var xfadeStartMs = 0L
    private var xfadeExpectedId: String? = null
    private var xfadeInGain = 1f
    private var xfadeOutGain = 1f
    @Volatile private var bitPerfect = false     // crossfade disabled in bit-perfect usb mode

    // pre-shuffle queue order for restore on disable null = not shuffled
    private var originalOrder: List<String>? = null
    // items may lag the command so flag and apply neutralize on next timeline change
    private var pendingNeutralize = false
    private var usbSink: com.decent.usbaudio.media3.UsbAudioSink? = null

    override fun onCreate() {
        super.onCreate()

        // float pipeline bypasses all app processors (sonic mono silence-skip custom dsp) so custom dsp needs 16-bit and keeps float off when active decided once here so engine switch takes effect next playback start
        val highRes = runBlocking { container.settingsStore.playbackPrefs.first().preferHighRes }
        val startupDspMode = runBlocking { container.settingsStore.audioPrefs.first().dspMode }
        val bitPerfectUsb = runBlocking { container.settingsStore.playbackPrefs.first().bitPerfectUsb }
        bitPerfect = bitPerfectUsb
        val useFloat = bitPerfectUsb || (highRes && startupDspMode != DspMode.CUSTOM)
        useFloatOut = useFloat

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
                    .setAudioProcessors(arrayOf(monoProcessor, auroraDsp, convolver))
                    .setEnableFloatOutput(useFloat)
                    // float bypasses sonic so use hardware playback params for speed
                    .setEnableAudioTrackPlaybackParams(useFloat || enableAudioTrackPlaybackParams)
                    .build()
                return TappingAudioSink(base, container.visualizer)
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

        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                ) updateCustomLayout()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) maybeNeutralize()
                if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
                ) updateSignalPath()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) publishNowPlaying()
            }
        })

        mediaSession = MediaLibrarySession.Builder(this, player, MediaCallback())
            .setCustomLayout(buildCustomLayout())
            .build()

        setupCast()

        val store = container.settingsStore
        audioEffects = container.audioEffects

        scope.launch {
            store.playbackPrefs.collect { prefs ->
                player.skipSilenceEnabled = prefs.skipSilence
                monoAudioPref = prefs.monoAudio
                crossfadeMs = prefs.crossfadeSec * 1000
                preferHighResPref = prefs.preferHighRes
                applyAudioEngine()
            }
        }
        scope.launch {
            store.audioPrefs.collect {
                replayGainMode = it.replayGain
                lastAudioPrefs = it
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
        auroraDsp.update(params)
        auroraDsp.enabled = mode == DspMode.CUSTOM
        audioEffects?.setMasterEnabled(mode == DspMode.SYSTEM)

        convolver.enabled = ap.dspConvEnabled
        convolver.setMakeup(ap.dspConvMakeupDb)
        if (ap.dspConvIrPath != lastIrPath) {
            lastIrPath = ap.dspConvIrPath
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ir = ap.dspConvIrPath.takeIf { it.isNotBlank() }
                    ?.let { runCatching { ConvolutionProcessor.loadWav(java.io.File(it)) }.getOrNull() }
                convolver.setImpulse(ir, ap.dspConvMakeupDb)
            }
        }
        // mono in system/off runs in monoprocessor in custom its width=0 above
        monoProcessor.enabled = monoAudioPref && mode != DspMode.CUSTOM
        updateSignalPath()
    }

    private fun applyPreferredDevice(id: Int) {
        runCatching {
            if (id == 0) {
                player.setPreferredAudioDevice(null)
            } else {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val device = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                player.setPreferredAudioDevice(device)
            }
        }
        updateSignalPath()
    }

    private fun currentOutputDevice(): android.media.AudioDeviceInfo? {
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return null
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        fun rank(t: Int) = when (t) {
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> 0
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 1
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
            else -> 9
        }
        return outs.minByOrNull { rank(it.type) }
    }

    private fun isUsb(t: Int) = t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
        t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun requestBitPerfect(device: android.media.AudioDeviceInfo?, on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 34 || device == null) { grantedBitPerfect = false; deviceSupportsBitPerfect = false; return }
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val supported = am.getSupportedMixerAttributes(device)
            android.util.Log.d("BitPerfect", "device=${device.productName}(type=${device.type}) supportedMixerAttrs=${supported.size} behaviors=${supported.map { it.mixerBehavior }}")
            val bp = supported.firstOrNull { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            deviceSupportsBitPerfect = bp != null
            grantedBitPerfect = if (on && bp != null) {
                val ok = am.setPreferredMixerAttributes(attrs, device, bp)
                android.util.Log.d("BitPerfect", "setPreferredMixerAttributes granted=$ok")
                ok
            } else {
                if (bp != null) runCatching { am.clearPreferredMixerAttributes(attrs, device) }
                false
            }
        }.onFailure { grantedBitPerfect = false; deviceSupportsBitPerfect = false }
    }

    private fun updateSignalPath() {
        val container = (application as AuroraApplication).container
        val fmt = runCatching { player.audioFormat }.getOrNull()
        val ap = lastAudioPrefs
        val device = currentOutputDevice()
        val outName = device?.productName?.toString()?.trim()?.ifBlank { null }
            ?: when {
                device == null -> "Speaker"
                isUsb(device.type) -> "USB DAC"
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                else -> "Output"
            }

        val wantBitPerfect = useFloatOut && device != null && isUsb(device.type)
        requestBitPerfect(device, wantBitPerfect)

        if (fmt == null) { container.signalPath.value = SignalPath(active = false); return }
        val codec = fmt.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
        val depth = when (fmt.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32; C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        // anything that alters samples breaks bit-perfect
        val modifying = (ap?.dspMode == DspMode.CUSTOM) || (ap?.dspMode == DspMode.SYSTEM) ||
            monoAudioPref || (ap?.replayGain ?: 0) != 0 || (ap?.dspConvEnabled == true) ||
            kotlin.math.abs(player.playbackParameters.speed - 1f) > 0.001f
        val isBt = device != null && (
            device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)
        // truly bit-perfect needs an exclusive mixer grant float passthrough alone may still get sample-rate-converted by the mixer bluetooth is always re-encoded
        val (bitPerfect, note) = when {
            isBt -> false to "Bluetooth — re-encoded by the system codec (LDAC/aptX/AAC/SBC)"
            modifying -> false to "DSP / effects active — not bit-perfect"
            useFloatOut && grantedBitPerfect -> true to "Exclusive bit-perfect to DAC"
            useFloatOut && deviceSupportsBitPerfect -> false to "Hi-res float — exclusive not granted (try replug / restart)"
            useFloatOut -> false to "Hi-res float passthrough — this device has no exclusive bit-perfect path"
            preferHighResPref -> false to "Restart playback to engage hi-res float output"
            else -> false to "Through Android mixer — enable Hi-res output for bit-perfect"
        }
        container.signalPath.value = SignalPath(
            active = true, codec = codec, sampleRateHz = fmt.sampleRate.takeIf { it > 0 } ?: 0,
            bitDepth = depth, channels = fmt.channelCount, output = outName,
            bitPerfect = bitPerfect, note = note,
        )
    }

    private fun tickAudio() {
        if (sleepFadeActive) { driveSleepFade(); return }
        if (wakeFadeActive) { driveWakeFade(); return }
        if (xfadeActive) {
            // cancel if user navigated away from the track we crossfaded into
            if (player.currentMediaItem?.mediaId != xfadeExpectedId) { endXfade() } else { driveXfade() }
            return
        }
        val base = replayGainMultiplier()
        if (kotlin.math.abs(player.volume - base) > 0.01f) player.volume = base
        maybeBeginXfade()
    }

    private fun driveSleepFade() {
        val ms = sleepFadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - sleepFadeStartMs).toFloat() / ms).coerceIn(0f, 1f)
        player.volume = ((1f - t) * replayGainMultiplier()).coerceIn(0f, 1f)
        if (t >= 1f) {
            sleepFadeActive = false
            player.pause()
            player.volume = replayGainMultiplier()
        }
    }

    private fun driveWakeFade() {
        val ms = wakeFadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - wakeFadeStartMs).toFloat() / ms).coerceIn(0f, 1f)
        player.volume = (t * replayGainMultiplier()).coerceIn(0f, 1f)
        if (t >= 1f) { wakeFadeActive = false; player.volume = replayGainMultiplier() }
    }

    private fun maybeBeginXfade() {
        val fade = crossfadeMs
        // bit-perfect routes main player to the usb dac the 2nd fade player would come out the speaker so dont crossfade
        if (fade <= 0 || bitPerfect || !player.isPlaying) return
        // repeat-one navigation acts like repeat-off so loop back to position 0 of the same item to crossfade
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        if (!repeatOne && !player.hasNextMediaItem()) return
        val duration = player.duration
        if (duration == C.TIME_UNSET) return
        val remaining = duration - player.currentPosition
        if (remaining in 0..fade.toLong()) beginXfade(repeatOne)
    }

    private fun beginXfade(repeatOne: Boolean) {
        val outgoing = player.currentMediaItem ?: return
        val uri = outgoing.localConfiguration?.uri ?: return
        val pos = player.currentPosition
        val tail = ensureFadePlayer()
        runCatching {
            tail.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            tail.prepare()
            tail.seekTo(pos)
            tail.volume = player.volume.coerceIn(0f, 1f)
            tail.playWhenReady = true
        }
        xfadeOutGain = player.volume.coerceIn(0f, 1f)
        if (repeatOne) player.seekTo(0) else player.seekToNextMediaItem()
        xfadeInGain = replayGainMultiplier()
        player.volume = 0f
        xfadeExpectedId = player.currentMediaItem?.mediaId
        xfadeStartMs = android.os.SystemClock.elapsedRealtime()
        xfadeActive = true
    }

    private fun driveXfade() {
        val fade = crossfadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - xfadeStartMs).toFloat() / fade).coerceIn(0f, 1f)
        // equal-power curves avoid the ~3db dip two linear ramps produce
        val half = Math.PI.toFloat() / 2f
        val inG = kotlin.math.sin(t * half)
        val outG = kotlin.math.cos(t * half)
        player.volume = (inG * xfadeInGain).coerceIn(0f, 1f)
        fadePlayer?.volume = (outG * xfadeOutGain).coerceIn(0f, 1f)
        if (t >= 1f) endXfade()
    }

    private fun endXfade() {
        xfadeActive = false
        xfadeExpectedId = null
        runCatching { fadePlayer?.run { pause(); clearMediaItems() } }
        player.volume = replayGainMultiplier()
    }

    private fun ensureFadePlayer(): ExoPlayer {
        fadePlayer?.let { return it }
        val attrs = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        return ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build().also { fadePlayer = it }
    }

    private fun replayGainMultiplier(): Float {
        if (replayGainMode == 0) return 1f
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return 1f
        val gainDb = if (replayGainMode == 2) extras.getFloat("rgAlbum", Float.NaN) else extras.getFloat("rgTrack", Float.NaN)
        if (gainDb.isNaN() || gainDb == 0f) return 1f
        // attenuate-only to avoid inter-sample clipping when boosting quiet tracks
        return Math.pow(10.0, gainDb / 20.0).toFloat().coerceIn(0.1f, 1f)
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
                // target 1=on 0=off -1=toggle order = pre-shuffle order when caller already shuffled
                CMD_SHUFFLE -> setShuffle(
                    customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"),
                )
                CMD_REPEAT -> cycleRepeat()
                CMD_SLEEP_FADE -> {
                    val ms = customCommand.customExtras.getInt("fadeMs", 0)
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
    }

    // casting hands the receiver a plain url so the dsp chain doesnt travel
    private fun switchToPlayer(toCast: Boolean) {
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
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> { wakeFadeActive = false; if (player.isPlaying) player.pause() else player.play() }
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_PREV -> if (player.currentPosition > 4000) player.seekTo(0) else player.seekToPreviousMediaItem()
            ACTION_ALARM -> startAlarmPlayback()
            ACTION_ALARM_DISMISS -> dismissAlarm()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun dismissAlarm() {
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
        scope.launch {
            val repo = container.repository
            val songs = runCatching { repo.starredSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { repo.downloadedSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@launch
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
        // swipe-away stops playback so music doesnt keep going
        runCatching { fadePlayer?.run { pause(); clearMediaItems() } }
        player.pause()
        player.stop()
        player.clearMediaItems()
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { fadePlayer?.release() }
        fadePlayer = null
        runCatching { castPlayer?.setSessionAvailabilityListener(null); castPlayer?.release() }
        castPlayer = null
        mediaSession?.release()
        runCatching { player.release() } // sessions player may have been the cast player
        mediaSession = null
        super.onDestroy()
    }

    companion object {
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
