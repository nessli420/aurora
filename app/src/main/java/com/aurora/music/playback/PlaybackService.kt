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

/**
 * Media3 MediaSessionService hosting the ExoPlayer. Playback prefs (skip-silence, crossfade)
 * are observed live from SettingsStore and applied to the engine.
 */
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

    // Sleep-timer fade-out: when armed, the audio tick ramps volume to zero over [sleepFadeMs]
    // then pauses. Lives here so it cooperates with the ReplayGain volume tick.
    @Volatile private var sleepFadeActive = false
    private var sleepFadeStartMs = 0L
    private var sleepFadeMs = 0
    // Wake-to-music alarm fade-in: ramp volume up from zero over [wakeFadeMs] after an alarm start.
    @Volatile private var wakeFadeActive = false
    private var wakeFadeStartMs = 0L
    private var wakeFadeMs = 0
    private val nowPlaying by lazy { NowPlayingStore(this) }

    private var xfadeActive = false
    private var xfadeStartMs = 0L
    private var xfadeExpectedId: String? = null

    // Physical-shuffle bookkeeping: queue order (by mediaId) before shuffle was turned on, so
    // disabling can restore the real album/playlist order. Null = not currently shuffled.
    private var originalOrder: List<String>? = null
    // Playback should follow the physical timeline order, not a second random ExoPlayer order.
    // Neutralising needs the items present, which may lag the command, so flag it and apply on the
    // next timeline change if they weren't ready yet.
    private var pendingNeutralize = false
    // Experimental USB bit-perfect sink (decent-player), non-null only when the setting is on.
    private var usbSink: com.decent.usbaudio.media3.UsbAudioSink? = null

    override fun onCreate() {
        super.onCreate()

        // Float output is bit-perfect for hi-res but Media3 runs the float pipeline WITHOUT any app
        // audio processors: Sonic (speed/pitch), mono downmix, silence-skip AND our custom DSP are
        // all bypassed. So Custom DSP can only run in the 16-bit pipeline and takes priority over
        // bit-perfect: when Custom is the persisted engine we keep float OFF so the DSP engages.
        // Hi-res passthrough therefore means bit-perfect XOR Custom DSP. Decided once here (the sink
        // can't be rebuilt cheaply mid-session), so switching engines while hi-res is on takes
        // effect on the next playback start. Speed still works in float mode via hardware varispeed
        // (matched pitch); independent pitch isn't available there but match-pitch is the default.
        val highRes = runBlocking { container.settingsStore.playbackPrefs.first().preferHighRes }
        val startupDspMode = runBlocking { container.settingsStore.audioPrefs.first().dspMode }
        // Experimental: route PCM straight to a USB DAC via the decent-player driver (bit-perfect,
        // bypasses the whole Android audio stack AND our DSP). Falls back to speaker when no DAC.
        val bitPerfectUsb = runBlocking { container.settingsStore.playbackPrefs.first().bitPerfectUsb }
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
                    // Plain float DefaultAudioSink (no DSP processors, which would defeat bit-perfect)
                    // wrapped by the USB driver sink. With no DAC it just plays normally.
                    val delegate = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .build()
                    return com.decent.usbaudio.media3.UsbAudioSink(delegate, context).also { usbSink = it }
                }
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(monoProcessor, auroraDsp, convolver))
                    .setEnableFloatOutput(useFloat)
                    // Float mode bypasses Sonic, so use hardware playback params for speed; otherwise
                    // keep Sonic (which also gives independent pitch).
                    .setEnableAudioTrackPlaybackParams(useFloat || enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        // Prefer the FFmpeg decoder so non-local/non-FLAC content decodes to float32 (the wrapper
        // converts to the DAC's exact bit depth). Local FLAC still uses the native engine.
        if (bitPerfectUsb) {
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // Spotify songs carry sentinel `aurora-yt://<id>?q=...&dur=...` URIs; resolve them to a real
        // YouTube audio stream just-in-time on the loader thread. Other backends' URIs pass through.
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
            // Stop ExoPlayer reading the file while the native FLAC engine handles decode + USB.
            playerBuilder.setLoadControl(
                com.decent.usbaudio.media3.UsbAudioSink.wrapLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl.Builder().build()
                ) { usbSink?.isNativeEngineActive == true }
            )
        }
        player = playerBuilder.build()
        // Connect the USB sink to the player (track-path extraction, engine lifecycle, EOF -> next).
        if (bitPerfectUsb) usbSink?.attachToPlayer(player)

        // Route audio to the shared session id so the DSP effects (EQ/bass/virtualizer/loudness)
        // created on it actually apply to playback.
        if (container.audioSessionId != 0) {
            runCatching { player.setAudioSessionId(container.audioSessionId) }
        }

        // Reflect shuffle/repeat state in the notification via custom buttons that fall through to
        // our own physical-shuffle / repeat-cycle handling (see onCustomCommand).
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                ) updateCustomLayout()
                // Items may have only just arrived after a shuffle-play command; neutralise now.
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
        // Route to the user's chosen output device (USB DAC, BT, wired, speaker).
        scope.launch {
            container.preferredAudioDeviceId.collect { id -> applyPreferredDevice(id) }
        }

        // Audio tick: drive ReplayGain volume + a real overlapping crossfade.
        scope.launch {
            while (isActive) {
                delay(100)
                tickAudio()
            }
        }
    }

    /**
     * Reconcile the active tone-shaping engine from the latest prefs. Runtime-switchable via
     * volatile flags (no pipeline rebuild): Custom DSP toggles [auroraDsp], System toggles the
     * AudioEffect chain, mono downmix folds into the DSP as width=0 when Custom is active else
     * runs through [monoProcessor]. The two EQ engines are mutually exclusive so they never stack.
     */
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

        // Convolution / impulse response (independent of the EQ engine).
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
        // Mono in System/Off runs in the 16-bit MonoAudioProcessor; in Custom it's width=0 above.
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

    /** The output device currently most likely in use (prefers external: USB → BT → wired). */
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

    /** On Android 14+, request exclusive bit-perfect mixer output to [device] (best-effort). */
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

    /** Compute the current signal path + bit-perfect verdict and publish it for the UI. */
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

        // Request/clear bit-perfect when float passthrough is engaged to a USB DAC.
        val wantBitPerfect = useFloatOut && device != null && isUsb(device.type)
        requestBitPerfect(device, wantBitPerfect)

        if (fmt == null) { container.signalPath.value = SignalPath(active = false); return }
        val codec = fmt.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
        val depth = when (fmt.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32; C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        // Anything that alters samples breaks bit-perfect.
        val modifying = (ap?.dspMode == DspMode.CUSTOM) || (ap?.dspMode == DspMode.SYSTEM) ||
            monoAudioPref || (ap?.replayGain ?: 0) != 0 || (ap?.dspConvEnabled == true) ||
            kotlin.math.abs(player.playbackParameters.speed - 1f) > 0.001f
        val isBt = device != null && (
            device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)
        // Truly bit-perfect means an exclusive mixer grant (no resampling at all). Float passthrough
        // alone skips Aurora's 16-bit stage but Android's mixer may still sample-rate-convert.
        // Bluetooth is always re-encoded by the system codec (LDAC/aptX/AAC/SBC), never bit-perfect.
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
        // Sleep fade-out owns the volume while active (ramp to silence, then pause).
        if (sleepFadeActive) { driveSleepFade(); return }
        // Wake alarm fade-in: ramp from silence up to the normal (ReplayGain) volume.
        if (wakeFadeActive) { driveWakeFade(); return }
        if (xfadeActive) {
            // Cancel if the user navigated away from the track we crossfaded into.
            if (player.currentMediaItem?.mediaId != xfadeExpectedId) { endXfade() } else { driveXfade() }
            return
        }
        // Base volume = ReplayGain (no fade in normal playback).
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
        if (fade <= 0 || !player.isPlaying) return
        // In REPEAT_MODE_ONE ExoPlayer's navigation treats repeat-one as repeat-off, so
        // hasNextMediaItem()/seekToNextMediaItem() do nothing; the "next" track is the current one
        // restarted. Crossfade by looping back to position 0 of the same item so the tail fades out
        // while the fresh start fades in. (Crossfade off: ExoPlayer loops natively.)
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        if (!repeatOne && !player.hasNextMediaItem()) return
        val duration = player.duration
        if (duration == C.TIME_UNSET) return
        val remaining = duration - player.currentPosition
        if (remaining in 0..fade.toLong()) beginXfade(repeatOne)
    }

    /** Start the next track (fading in) on the main player while the outgoing tail fades out on a 2nd player. */
    private fun beginXfade(repeatOne: Boolean) {
        val outgoing = player.currentMediaItem ?: return
        val uri = outgoing.localConfiguration?.uri ?: return
        val pos = player.currentPosition
        val tail = ensureFadePlayer()
        runCatching {
            tail.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            tail.prepare()
            tail.seekTo(pos)
            tail.volume = player.volume.coerceIn(0.06f, 1f)
            tail.playWhenReady = true
        }
        // Repeat-one: restart the same item (next == current). Otherwise advance to the next item.
        if (repeatOne) player.seekTo(0) else player.seekToNextMediaItem()
        player.volume = 0f
        xfadeExpectedId = player.currentMediaItem?.mediaId
        xfadeStartMs = android.os.SystemClock.elapsedRealtime()
        xfadeActive = true
    }

    private fun driveXfade() {
        val fade = crossfadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - xfadeStartMs).toFloat() / fade).coerceIn(0f, 1f)
        player.volume = (t * replayGainMultiplier()).coerceIn(0f, 1f)
        fadePlayer?.volume = (1f - t).coerceIn(0f, 1f)
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
        // Attenuate-only, to avoid inter-sample clipping when boosting quiet tracks.
        return Math.pow(10.0, gainDb / 20.0).toFloat().coerceIn(0.1f, 1f)
    }

    // Notification shuffle/repeat + physical shuffle

    private inner class MediaCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Must start from DEFAULT_SESSION_AND_LIBRARY_COMMANDS: a MediaLibrarySession browser
            // (Android Auto) needs the library browse commands (getLibraryRoot/getChildren/...).
            // DEFAULT_SESSION_COMMANDS strips them, so Auto's browser connection is refused.
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
                // target: 1 = force on, 0 = force off, -1 (default) = toggle.
                // "order" (optional) = the real pre-shuffle order, when the caller already shuffled
                // the queue itself (shuffle-from-start); then we don't reorder, just remember it.
                CMD_SHUFFLE -> setShuffle(
                    customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"),
                )
                CMD_REPEAT -> cycleRepeat()
                // fadeMs > 0 arms a fade-out-then-pause; <= 0 cancels it and restores volume.
                CMD_SLEEP_FADE -> {
                    val ms = customCommand.customExtras.getInt("fadeMs", 0)
                    if (ms > 0) { sleepFadeMs = ms; sleepFadeStartMs = android.os.SystemClock.elapsedRealtime(); sleepFadeActive = true }
                    else { sleepFadeActive = false; player.volume = replayGainMultiplier() }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // Browsable library tree (Android Auto / Wear / system surfaces)

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

        /** Resolve mediaId-only items (browsed-then-played) into playable items with a real URI. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceFuture {
            mediaItems.map { item ->
                if (item.localConfiguration != null) item else resolvePlayable(item.mediaId) ?: item
            }.toMutableList()
        }

        // Voice / text search (Android Auto "search", Assistant)

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

    /** Resolve a search query into playable songs + browsable albums/artists for the browse surface. */
    private suspend fun searchItems(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val r = container.repository.search(query)
        val songs = r.songs.map { songItem(it) }
        val albums = r.albums.map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
        val artists = r.artists.map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
        // Songs first (directly playable), then collections to drill into.
        return songs + albums + artists
    }

    /** Run a suspend block on the service scope and surface it as a ListenableFuture. */
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

    // Android Auto content-style: how a node's children should be laid out (grid vs list).
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
                // Collection children are tracks → show them as a list.
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

    /**
     * Physical shuffle: reorder the actual media items rather than using ExoPlayer's internal
     * shuffle order (which leaves the visible queue untouched). On enable, snapshot the real order
     * then shuffle everything after the current track; on disable, restore the snapshot.
     * [shuffleModeEnabled] is kept in sync purely as a state flag for the UI/notification, with an
     * identity ShuffleOrder so playback follows our physical order, not a second random one.
     */
    private fun setShuffle(target: Int, providedOrder: List<String>?) {
        val enable = when (target) {
            1 -> true
            0 -> false
            else -> !player.shuffleModeEnabled
        }
        // A provided order means a fresh shuffle-play: always (re)apply it. Otherwise skip no-ops.
        if (providedOrder == null && enable == player.shuffleModeEnabled && enable == (originalOrder != null)) return

        if (enable && providedOrder != null) {
            // Caller already shuffled the queue itself; just remember the real order for restore.
            originalOrder = providedOrder
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
            return
        }
        if (enable) {
            // In-place toggle mid-playback: keep the current track, shuffle everything after it.
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
                // Restore snapshot order for items still queued; keep any newly-added items at the end.
                val restored = orig.filter { it in present } + present.filter { it !in orig }
                applyOrder(restored)
            }
            player.shuffleModeEnabled = false
            originalOrder = null
        }
    }

    /** Force the play order to follow the (physical) timeline order once items are present. */
    private fun maybeNeutralize() {
        if (!pendingNeutralize) return
        val count = player.mediaItemCount
        if (count <= 0) return
        runCatching { player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(count)) }
        pendingNeutralize = false
    }

    private fun currentIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** Reorder the queue in place (via moves, so playback isn't interrupted) to match [target] ids. */
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

    /** Set up Cast: when a Cast session connects, hand playback to the receiver; restore on disconnect. */
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

    /**
     * Transfer the queue + position between the local ExoPlayer and the CastPlayer and swap which one
     * the session drives. Casting hands the receiver a plain URL, so the DSP chain doesn't travel:
     * this is the "convenience mode" the UI labels.
     */
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

    /** Best-effort MIME for the Cast receiver, guessed from the stream URL extension. */
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

    /** Snapshot the current track for the home-screen widget + Quick Settings tile (and persist it). */
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

    /** Handle widget / Quick Settings tile / alarm actions delivered as service intents. */
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

    /** Stop the wake-to-music playback and clear the alarm notification. */
    private fun dismissAlarm() {
        wakeFadeActive = false
        runCatching { player.pause(); player.stop(); player.clearMediaItems() }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(ALARM_NOTIF_ID) }
    }

    /** Post a full-screen alarm notification with a Dismiss action (launches [AlarmActivity]). */
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

    /** Wake-to-music: shuffle the user's liked (or downloaded) library and fade it in from silence. */
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
        // User swiped the app away: stop playback and tear the service down so music doesn't keep going.
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
        runCatching { player.release() } // local ExoPlayer (the session's player may have been the cast player)
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
