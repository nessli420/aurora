package com.aurora.music.mix

import android.content.*
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.MediaSource
import com.aurora.music.playback.*
import com.aurora.music.data.DspMode
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlin.math.*

data class MixAudioConfig(val params: DspParams = DspParams(), val mode: Int = DspMode.OFF,
    val mono: Boolean = false, val impulse: ImpulseResponse? = null, val convolution: Boolean = false,
    val convolutionGain: Float = 0f, val audioSessionId: Int = 0, val replayGain: Int = 0,
    val rack: com.aurora.music.data.ProcessingRack? = null,
    val rackImpulses: Map<String, ImpulseResponse> = emptyMap(), val tpdfDither: Boolean = false,
    val relativeVolume: Double = 1.0)

/** One session transport controls every deck, including lock-screen pause, seek and audio-focus loss. */
@UnstableApi
class MixPlayer(
    private val context: Context,
    initial: MixProject,
    private val sources: MediaSource.Factory,
    private val bus: MixController,
    private val device: () -> android.media.AudioDeviceInfo?,
    startSec: Float = 0f,
    audioConfig: MixAudioConfig = MixAudioConfig(),
    private val tracklist: Boolean = false,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private data class Deck(val player: ExoPlayer, val dsp: AuroraDspProcessor, var clip: MixClip,
        val globalProcessor: PrecisionRackAudioProcessor, val routedOutput: ConfirmedAudioRoute,
        var started: Boolean = false, var bassCut: Float = 0f)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var project = initial.normalized()
    private val decks = mutableListOf<Deck>()
    private var wanted = false
    private var loading = true
    private var ended = false
    private var error: PlaybackException? = null
    private var position = startSec.coerceIn(0f, project.durationSec)
    private var lastTick = SystemClock.elapsedRealtime()
    private var resumeAfterFocus = false
    private var sleepEndMs = 0L
    private var sleepLengthMs = 0
    private var focusError: String? = null
    private var globalConfig = audioConfig
    private var publishedTrack = -1
    private var repeat = Player.REPEAT_MODE_OFF
    private var transportParams = PlaybackParameters.DEFAULT
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                if (resumeAfterFocus) { wanted = true; resumeAfterFocus = false; lastTick = SystemClock.elapsedRealtime(); invalidateState() }
            } else {
                resumeAfterFocus = wanted && change != AudioManager.AUDIOFOCUS_LOSS
                wanted = false
                decks.forEach { it.player.pause() }
                invalidateState()
            }
        }.build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    init {
        require(project.clips.isNotEmpty()) { "Add tracks to the mix first" }
        bus.activeProject = project
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        require(AutoMixPlanner.maxLayers(project) <= MixProject.MAX_LAYERS) { "At most eight tracks can overlap at once." }
        seekInternal(position)
        scope.launch { while (isActive) { tick(); delay(16) } }
    }

    private fun createDeck(clip: MixClip) {
            val dsp = AuroraDspProcessor().apply { enabled = true }
            val globalProcessor = PrecisionRackAudioProcessor()
            val routedOutput = ConfirmedAudioRoute(context)
            val factory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                        .setAudioTrackProvider(routedOutput.provider)
                        .setAudioProcessors(arrayOf(MixStereoProcessor(), dsp, globalProcessor)).build()
            }
            val p = ExoPlayer.Builder(context, factory).setMediaSourceFactory(sources)
                .setAudioAttributes(AudioAttributes.DEFAULT, false)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 20000, 500, 1000).build())
                .build()
            p.volume = 0f
            if (globalConfig.audioSessionId != 0) p.setAudioSessionId(globalConfig.audioSessionId)
            p.setPreferredAudioDevice(device())
            p.setMediaItem(MediaItem.Builder().setMediaId(clip.id).setUri(if (clip.stem == StemMode.FULL) clip.song.streamUrl else clip.stemUri).build())
            p.seekTo((clip.cueInSec * 1000).toLong())
            val deck = Deck(p, dsp, clip, globalProcessor, routedOutput)
            decks.add(deck)
            configure(deck)
            configureGlobal(deck, globalConfig, true)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = PlaybackException("Could not play ${clip.song.title}. Check the source and retry.", e, e.errorCode)
                    wanted = false
                    decks.forEach { it.player.pause() }
                    invalidateState()
                }
            })
            p.prepare()
    }

    /** Keep a bounded set of active/upcoming decoders, regardless of collection length. */
    private fun syncDecks() {
        val near = project.clips.filter { it.endSec > position && it.startSec <= position + 15f }
            .sortedBy { it.startSec }.take(MixProject.MAX_LAYERS)
        val ids = near.map { it.id }.toSet()
        val obsolete = decks.filter { it.clip.id !in ids }
        obsolete.forEach { it.player.volume = 0f; it.player.release(); it.routedOutput.close() }
        decks.removeAll(obsolete.toSet())
        for (clip in near) if (decks.none { it.clip.id == clip.id }) {
            createDeck(clip)
            val p = decks.last().player
            val offset = (position - clip.startSec).coerceIn(0f, clip.durationSec)
            p.seekTo(((clip.cueInSec + offset * clip.speed) * 1000).toLong())
        }
    }

    private fun configure(deck: Deck) {
        val c = deck.clip
        val boost = max(0f, max(c.bassDb, max(c.midDb, c.trebleDb)))
        deck.dsp.update(DspParams(
            parametric = listOf(DspBand(150f, c.bassDb + deck.bassCut, 0.7f, 1), DspBand(1200f, c.midDb, 0.7f, 0), DspBand(6000f, c.trebleDb, 0.7f, 2)),
            preampDb = -boost, balance = c.pan, limiterEnabled = true, limiterCeilingDb = -0.5f))
        deck.player.playbackParameters = PlaybackParameters(c.speed * transportParams.speed,
            2.0.pow(c.pitchSemitones / 12.0).toFloat() * transportParams.pitch)
    }

    fun applyAudioConfig(config: MixAudioConfig) {
        val changedImpulse = config.impulse !== globalConfig.impulse
        globalConfig = config
        decks.forEach { configureGlobal(it, config, changedImpulse) }
    }

    private fun configureGlobal(deck: Deck, config: MixAudioConfig, changedImpulse: Boolean) {
        val engine = deck.globalProcessor.engine
        engine.update(if (config.mode == DspMode.CUSTOM) config.params.copy(width = if (config.mono) 0f else config.params.width)
            else DspParams(width = if (config.mono) 0f else 1f, limiterEnabled = false))
        engine.enabled = config.mode == DspMode.CUSTOM || config.mono
        engine.convolutionEnabled = config.convolution
        engine.setMakeup(config.convolutionGain)
        engine.setRackImpulses(config.rackImpulses)
        engine.relativeVolume = config.relativeVolume
        deck.globalProcessor.tpdfDither = config.tpdfDither
        if (changedImpulse) engine.setImpulse(config.impulse, config.convolutionGain)
        engine.updateRack(config.rack?.takeIf { it.enabled && config.mode == DspMode.CUSTOM })
    }

    val processingDescription: String get() {
        val active = decks.filter { it.player.isPlaying }
        val racks = active.map { it.globalProcessor.engine }.filter { it.rackActive }
        return if (racks.isNotEmpty()) "Per-clip DSP → PCM16 boundary → binary64 serial rack (${racks.first().rackDescription}) → PCM16 output → timeline gains. ${racks.size} playing deck(s) report rack processing."
        else "Per-clip DSP → PCM16 boundary → binary64 global effects/convolution → PCM16 output → timeline gains. Per-deck output levels and the summed output are not measured."
    }

    val confirmedOutput: com.aurora.music.data.routes.ProcessingRoute get() {
        val routes = decks.filter { it.player.isPlaying }.map { it.routedOutput.snapshot() }
        if (routes.isNotEmpty() && routes.all { it.key != null } && routes.map { it.key }.distinct().size == 1) {
            return routes.first().copy(detail = "Confirmed by active deck AudioTracks.")
        }
        return com.aurora.music.data.routes.ProcessingRoute(com.aurora.music.data.routes.ProcessingRouteKind.MIX,
            label = "Mix output unconfirmed", detail = "Output rules wait for all active deck routes to agree.",
            category = routes.mapNotNull { it.category }.distinct().singleOrNull()
                ?.takeIf { routes.isNotEmpty() && routes.all { route -> route.category != null } })
    }

    fun updateProject(updated: MixProject) {
        if (updated.clips.map { it.id } != project.clips.map { it.id }) return
        val next = updated.normalized()
        var reposition = false
        if (AutoMixPlanner.maxLayers(next) > MixProject.MAX_LAYERS) return
        decks.forEach { deck ->
            val c = next.clips.first { it.id == deck.clip.id }
            if (deck.clip != c) {
                reposition = reposition || deck.clip.startSec != c.startSec || deck.clip.cueInSec != c.cueInSec || deck.clip.speed != c.speed
                if (c.stem != deck.clip.stem || c.stemUri != deck.clip.stemUri) {
                    deck.player.pause()
                    deck.player.setMediaItem(MediaItem.fromUri(if (c.stem == StemMode.FULL) c.song.streamUrl else c.stemUri))
                    deck.player.prepare(); reposition = true
                }
                deck.clip = c; configure(deck)
            }
        }
        project = next
        bus.activeProject = project
        if (reposition) seekInternal(position)
        invalidateState()
    }

    fun sleepFade(milliseconds: Int) {
        sleepLengthMs = milliseconds.coerceAtLeast(0)
        sleepEndMs = SystemClock.elapsedRealtime() + sleepLengthMs
    }

    fun refreshOutputDevice() { decks.forEach { it.player.setPreferredAudioDevice(device()) } }

    private fun tick() {
        val beforeTrack = if (tracklist) currentTrack() else 0
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastTick).coerceIn(0, 100) / 1000f
        lastTick = now
        syncDecks()
        val active = decks.filter { position >= it.clip.startSec && position < it.clip.endSec }
        val buffering = active.any { it.player.playbackState != Player.STATE_READY && it.player.playbackState != Player.STATE_ENDED }
        if (loading != buffering) { loading = buffering; invalidateState() }
        if (wanted && !loading && error == null && !ended) {
            // AudioTrack can take time to resume after a seek (especially over Bluetooth).
            // Follow the actual source clock while a deck is active; a wall clock would run ahead.
            val clockDeck = active.firstOrNull { it.started }
            if (clockDeck != null) {
                val audible = clockDeck.clip.startSec +
                    (clockDeck.player.currentPosition / 1000f - clockDeck.clip.cueInSec) / clockDeck.clip.speed
                position = max(position, audible)
                if (clockDeck.player.playbackState == Player.STATE_ENDED) position = max(position, clockDeck.clip.endSec)
            } else if (active.isEmpty()) position += elapsed
        }
        if (tracklist && repeat == Player.REPEAT_MODE_ONE && wanted) {
            val clip = project.clips[beforeTrack]
            val boundary = project.clips.getOrNull(beforeTrack + 1)?.startSec ?: clip.endSec
            if (position >= boundary) seekInternal(clip.startSec + clip.fadeInSec / 2)
        }
        if (position >= project.durationSec) {
            if ((project.loop || repeat == Player.REPEAT_MODE_ALL) && wanted) seekInternal(0f)
            else { position = project.durationSec; ended = true; wanted = false; invalidateState() }
        }
        var master = if (sleepLengthMs > 0) ((sleepEndMs - now).toFloat() / sleepLengthMs).coerceIn(0f, 1f) else 1f
        if (sleepLengthMs > 0 && now >= sleepEndMs) { wanted = false; sleepLengthMs = 0; master = 0f; invalidateState() }
        val levels = MixMath.gains(project, position).mapIndexed { index, gain ->
            val song = project.clips[index].song
            val db = when (globalConfig.replayGain) { 1 -> song.replayGainTrack; 2 -> song.replayGainAlbum; else -> 0f }
            gain * master * if (db.isFinite()) MixMath.amplitude(db).coerceIn(0.1f, 1f) else 1f
        }
        val levelById = project.clips.mapIndexed { i, c -> c.id to levels[i] }.toMap()
        decks.forEach { deck ->
            if (deck.clip.bassSwap) {
                val c = deck.clip
                val entrance = if (c.fadeInSec > 0) 1 - MixMath.rise(2 * (position - c.startSec) / c.fadeInSec, "SMOOTH") else 0f
                val exit = if (c.fadeOutSec > 0) MixMath.rise(1 - 2 * (c.endSec - position) / c.fadeOutSec, "SMOOTH") else 0f
                val cut = -18f * max(entrance, exit)
                if (abs(cut - deck.bassCut) > .25f) { deck.bassCut = cut; configure(deck) }
            } else if (deck.bassCut != 0f) { deck.bassCut = 0f; configure(deck) }
            val inClip = position >= deck.clip.startSec && position < deck.clip.endSec
            val play = wanted && !loading && inClip && !ended && error == null
            if (play && !deck.started) {
                val sourceMs = ((deck.clip.cueInSec + (position - deck.clip.startSec) * deck.clip.speed) * 1000).toLong()
                if (abs(deck.player.currentPosition - sourceMs) > 80) deck.player.seekTo(sourceMs)
                deck.started = true
            }
            deck.player.volume = levelById[deck.clip.id] ?: 0f
            if (deck.player.playWhenReady != play) deck.player.playWhenReady = play
        }
        bus.state.value = MixPlaybackState(project.id, wanted, loading, position, error?.message ?: focusError, levels)
        if (tracklist && publishedTrack != currentTrack()) {
            publishedTrack = currentTrack()
            invalidateState()
        }
    }

    // Studio uses the continuous timeline; MediaSession controls use source-song time.
    fun seekTimeline(seconds: Float) = seekInternal(seconds)

    private fun currentTrack(): Int = project.clips.indexOfLast {
        position >= it.startSec + it.fadeInSec / 2
    }.coerceAtLeast(0)

    private fun sourcePositionMs(): Long {
        val clip = project.clips[currentTrack()]
        return ((clip.cueInSec + (position - clip.startSec).coerceIn(0f, clip.durationSec) * clip.speed) * 1000).toLong()
    }

    private fun seekInternal(seconds: Float) {
        position = seconds.coerceIn(0f, project.durationSec)
        ended = false
        syncDecks()
        lastTick = SystemClock.elapsedRealtime()
        decks.forEach { deck ->
            deck.player.pause()
            val offset = (position - deck.clip.startSec).coerceIn(0f, deck.clip.durationSec)
            deck.player.seekTo(((deck.clip.cueInSec + offset * deck.clip.speed) * 1000).toLong())
            deck.started = false
        }
        invalidateState()
    }

    override fun getState(): State {
        val item = MediaItem.Builder().setMediaId("aurora-mix:${project.id}").setMediaMetadata(MediaMetadata.Builder()
            .setTitle(project.name).setArtist("Aurora Mix · ${project.clips.size} tracks")
            .setArtworkUri(project.clips.firstOrNull()?.song?.artworkUrl?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
            .setIsPlayable(true).build()).build()
        val playlist = if (tracklist) project.clips.map { clip ->
            val song = clip.song
            val media = MediaItem.Builder().setMediaId(song.id).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setArtworkUri(song.artworkUrl.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
                .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
            MediaItemData.Builder(clip.id).setMediaItem(media)
                .setDurationUs(song.durationSec * 1_000_000L).setIsSeekable(true).build()
        } else listOf(MediaItemData.Builder(project.id).setMediaItem(item)
            .setDurationUs((project.durationSec * 1_000_000).toLong()).setIsSeekable(true).build())
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE,
                Player.COMMAND_STOP, Player.COMMAND_RELEASE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA)
        if (tracklist) commands.addAll(Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SPEED_AND_PITCH)
        return State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (tracklist) currentTrack() else 0)
            .setRepeatMode(repeat)
            .setPlaybackParameters(PlaybackParameters(
                (if (tracklist) project.clips[currentTrack()].speed else 1f) * transportParams.speed, transportParams.pitch))
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (error != null) Player.STATE_IDLE else if (ended) Player.STATE_ENDED else if (loading) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayerError(error)
            .setContentPositionMs { if (tracklist) sourcePositionMs() else (position * 1000).toLong() }
            .setContentBufferedPositionMs { if (tracklist) sourcePositionMs() else (position * 1000).toLong() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        resumeAfterFocus = false
        wanted = playWhenReady && manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusError = if (playWhenReady && !wanted) "Audio focus is unavailable. Try again when the other audio finishes." else null
        if (playWhenReady) sleepLengthMs = 0
        if (wanted && ended) seekInternal(0f)
        if (!wanted) decks.forEach { it.player.pause() }
        lastTick = SystemClock.elapsedRealtime()
        return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val seconds = if (positionMs == C.TIME_UNSET) 0f else positionMs / 1000f
        if (tracklist) {
            val clip = project.clips[mediaItemIndex.coerceIn(project.clips.indices)]
            // Skip enters after the overlap midpoint so the requested track owns the session.
            val offset = max(clip.fadeInSec / 2, (seconds - clip.cueInSec) / clip.speed)
            seekInternal(clip.startSec + offset.coerceIn(0f, clip.durationSec))
        } else seekInternal(seconds)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        transportParams = playbackParameters
        decks.forEach { configure(it) }
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        wanted = false; decks.forEach { it.player.pause() }; manager.abandonAudioFocusRequest(focus)
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel(); decks.forEach { it.player.release(); it.routedOutput.close() }; decks.clear()
        manager.abandonAudioFocusRequest(focus)
        runCatching { context.unregisterReceiver(noisy) }
        bus.state.value = MixPlaybackState()
        bus.activeProject = null
        return Futures.immediateVoidFuture()
    }
}
