package com.aurora.music.desktop.player

import com.aurora.music.R
import com.aurora.music.data.Autoplay
import com.aurora.music.data.LastfmScrobbler
import com.aurora.music.data.ListenBrainzScrobbler
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.data.PlaybackReportDispatcher
import com.aurora.music.data.QueueStore
import com.aurora.music.data.SavedQueue
import com.aurora.music.data.SavedTrack
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.SignalPath
import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.data.toSavedTrack
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.audio.EngineEvent
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.PlaybackEngine
import com.aurora.music.desktop.audio.QueueEntry
import com.aurora.music.desktop.audio.ShuffleTarget
import com.aurora.music.desktop.audio.TransitionReason
import com.aurora.music.desktop.audio.bindSettings
import com.aurora.music.desktop.audio.signalPath
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.platform.DesktopSettings
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.playback.VisualizerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import com.aurora.music.desktop.audio.RepeatMode as EngineRepeat

data class PlayerDependencies(
    val scope: CoroutineScope,
    val repository: MusicRepository,
    val queueStore: QueueStore,
    val settingsStore: SettingsStore,
    val desktopSettings: DesktopSettings,
    val lastfm: LastfmScrobbler,
    val listenBrainz: ListenBrainzScrobbler,
    val playHistory: PlayHistoryStore,
    val playbackReports: PlaybackReportDispatcher,
    val reportingAllowed: () -> Boolean,
    val sessionReady: StateFlow<Boolean?>,
    val accountEpoch: StateFlow<Int>,
    val accountKey: () -> String,
    val offline: () -> Boolean,
    val visualizer: VisualizerController?,
    val artwork: suspend (String) -> ByteArray?,
    val listeningDetails: com.aurora.music.data.DetailedPlaySink = com.aurora.music.data.DetailedPlaySink { },
    val platform: String = "desktop",
    val nowPlaying: (Song, Boolean, Float) -> Unit = { _, _, _ -> },
    val location: (Boolean, Boolean) -> Pair<String?, com.aurora.music.desktop.platform.LocationFix?> = { _, _ -> null to null },
    val sonic: com.aurora.music.data.SonicEngine? = null,
    val listeningLevels: com.aurora.music.data.listening.ListeningLevelStore? = null,
    val companion: com.aurora.music.desktop.video.CompanionVideo? = null,
    val resumePoints: com.aurora.music.data.ResumePoints? = null,
    val aiDj: com.aurora.music.ai.DjEngine? = null,
    val aiDjVoice: com.aurora.music.ai.DjVoice? = null,
    val aiDjEars: com.aurora.music.ai.DjEars? = null,
)

fun DesktopContainer.playerDependencies(): PlayerDependencies = PlayerDependencies(
    scope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.Main.immediate),
    repository = repository,
    queueStore = queueStore,
    settingsStore = settingsStore,
    desktopSettings = desktopSettings,
    lastfm = lastfm,
    listenBrainz = listenBrainz,
    playHistory = playHistory,
    playbackReports = playbackReports,
    reportingAllowed = { playbackReportingAllowed.value },
    sessionReady = sessionReady,
    accountEpoch = accountEpoch,
    accountKey = ::currentAccountKey,
    offline = { offline.value },
    visualizer = visualizer,
    artwork = { url -> artworkBytes(url, http, artworkRepository) },
    listeningDetails = { play -> listeningDetails.save(play) },
    platform = com.aurora.music.desktop.platform.HostPlatform.name.lowercase(java.util.Locale.ROOT),
    nowPlaying = { song, playing, position -> discord.update(song, playing, position) },
    location = placesStore::sample,
    sonic = sonicEngine,
    listeningLevels = listeningLevels,
    companion = companionVideo,
    resumePoints = resumePoints,
    aiDj = aiDj,
    aiDjVoice = aiDjVoice,
    aiDjEars = aiDjEars,
)

class DesktopPlayer(private val engine: PlaybackEngine, private val deps: PlayerDependencies) : PlayerController, AutoCloseable {
    private val scope = CoroutineScope(deps.scope.coroutineContext + SupervisorJob(deps.scope.coroutineContext.job))

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    override val outputs: StateFlow<List<AudioDevice>> = engine.outputs
    private val _preferredOutput = MutableStateFlow<String?>(null)
    override val preferredOutput: StateFlow<String?> = _preferredOutput.asStateFlow()
    private val _exclusiveOutput = MutableStateFlow(false)
    override val exclusiveOutput: StateFlow<Boolean> = _exclusiveOutput.asStateFlow()
    override val exclusiveAvailable: Boolean get() = engine.exclusiveAvailable
    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages: Flow<String> = _messages.asSharedFlow()
    override val signalPath: StateFlow<SignalPath> = flow {
        while (true) {
            emit(engine.signalPath())
            delay(500)
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(1_000), SignalPath())
    val mediaControls = MediaControls(this, scope, deps.artwork)

    private val reporting = PlaybackReporting(deps.repository::playbackReportTarget, deps.playbackReports::submit) {
        deps.reportingAllowed() && !deps.offline() && deps.sessionReady.value == true
    }
    private val history = ListeningHistory(deps.playHistory)
    private val rules = RulePublisher(deps.settingsStore)
    private val details = DesktopListeningDetails(deps.listeningDetails, deps.platform, deps.location)

    private var sleepJob: Job? = null
    private var queueFillJob: Job? = null
    private var flushFill: (() -> Unit)? = null
    private var volumeSave: Job? = null
    private var unmuteVolume = DesktopSettings.DEFAULT_UNMUTE_VOLUME
    private var queueGeneration = 0
    // states still showing the queue that a replace or stop is about to discard
    private var retiredEntries: List<QueueEntry>? = null
    private var syncedEntries: List<QueueEntry>? = null
    private var syncedQueue: List<Song> = emptyList()
    private var savedEntries: List<QueueEntry>? = null
    private var savedTracks: List<SavedTrack> = emptyList()
    private var persisted: EngineState? = null
    private var lastPersistMs = 0L

    private var autoplayEnabled = true
    // songs autoplay queued: they sit after the user's own, and turning autoplay off takes them back out
    private var autoplayIds: Set<String> = emptySet()
    private var autoplayJob: Job? = null
    // the queue entry a top-up last ran for, so a seed with no radio isn't asked again on every tick
    private var autoplayTriedFor = -1L
    // shuffle's second tap: autoplay picks mixed in among your shuffled songs; the next tap takes them back out
    private var smartShuffle = false
    private var smartIds: Set<String> = emptySet()
    private var smartJob: Job? = null
    // a new queue under shuffle with autoplay still wants its picks
    private var smartPending = false
    // bumped with every new queue, so picks fetched for an old one are dropped
    private var smartContext = 0L
    private var syncedShuffle = false
    private var playbackPrefs = com.aurora.music.data.PlaybackPrefs()
    private var resumeId = ""
    private var resumePositionMs = 0L
    private var resumeDurationSec = 0
    private var resumeSavedAt = 0L
    private var privateSession = false
    private val likes = com.aurora.music.data.Likes(scope, deps.repository, deps.settingsStore::setPlaylistLiked)
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // account the live queue belongs to so it persists/restores under the right key
    private var playingAccountKey = ""
    private var openRestoreAttempted = false
    private var playStartMs = 0L
    private var loadingRadio = false

    // sync reads these, and the scope runs it straight away inside init, so they're set up first
    @Volatile private var mergeFeatures = com.aurora.music.data.MergeFeatures()
    private var lastKeyInfoId: String? = null
    private var lastKeyInfo: com.aurora.music.data.SonicEngine.TrackKey? = null

    init {
        engine.visualizer = deps.visualizer
        engine.bindSettings(deps.settingsStore, scope)
        scope.launch { deps.settingsStore.mergeFeatures.collect { mergeFeatures = it } }
        deps.listeningLevels?.let { levels -> scope.launch(Dispatchers.Default) { observeLevels(levels) } }
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            while (true) {
                delay(1_000)
                val current = engine.state.value
                reporting.sample(current, uptime())
                history.track(current, uptime())
                details.track(current, uptime())
            }
        }
        scope.launch {
            var applied: Pair<String?, Boolean>? = null
            deps.desktopSettings.output.collect { prefs ->
                _preferredOutput.value = prefs.deviceId
                val exclusive = prefs.exclusive && engine.exclusiveAvailable
                _exclusiveOutput.value = exclusive
                val target = prefs.deviceId to exclusive
                if (target != applied) engine.setOutput(prefs.deviceId, exclusive)
                applied = target
                if (engine.config.bufferMs != prefs.bufferMs) engine.configure(engine.config.copy(bufferMs = prefs.bufferMs))
            }
        }
        scope.launch {
            val saved = deps.desktopSettings.volume.first()
            val audible = deps.desktopSettings.unmuteVolume.first()
            if (volumeSave == null) {
                _volume.value = saved
                unmuteVolume = audible
                engine.setVolume(volumeGain(saved))
            }
        }
        scope.launch {
            deps.sessionReady.collect { ready ->
                if (ready != true) return@collect
                refreshLikes()
                if (openRestoreAttempted) return@collect
                openRestoreAttempted = true
                val key = deps.accountKey()
                playingAccountKey = key
                val saved = kotlinx.coroutines.withContext(Dispatchers.IO) { savedQueue(key)?.let { it to it.restorableSongs() } }
                // an account switch during the read must not leave this queue under the new key
                if (playingAccountKey == key && queueSize() == 0 && saved != null) restoreQueue(saved.first, saved.second)
            }
        }
        // subsonic can't star playlists so locally-liked ones merge into the same set
        scope.launch { deps.settingsStore.likedPlaylists.collect(likes::setLikedPlaylists) }
        scope.launch { likes.likedIds.collect { ids -> _state.update { it.copy(likedIds = ids) } } }
        scope.launch { likes.failures.collect { _messages.tryEmit(appString(R.string.like_failed)) } }
        // the playing song's heart counts likes on every account, found by recording
        scope.launch {
            _state.map { it.current }.distinctUntilChanged { a, b -> a.id == b.id && a.title == b.title && a.artist == b.artist }
                .collect { if (it.id.isNotEmpty()) likes.check(listOf(it)) }
        }
        scope.launch {
            deps.settingsStore.privateSession.collect {
                privateSession = it
                history.allowed = !it
                details.privateSession = it
                details.update()
            }
        }
        scope.launch {
            deps.settingsStore.insightsPrefs.collect {
                details.prefs = it
                details.update()
            }
        }
        scope.launch { deps.settingsStore.activeEqProfile.collect { details.autoEqProfile = it } }
        scope.launch {
            deps.settingsStore.processingSettings.collect {
                details.audio = it.audio
                details.rack = it.rack
            }
        }
        scope.launch {
            deps.settingsStore.playbackPrefs.collect { p ->
                details.crossfadeMs = p.crossfadeSec * 1000L
                playbackPrefs = p
                _state.update { it.copy(longFormSkipButtons = p.longFormSkipButtons) }
                if (autoplayEnabled && !p.autoplayRadio) dropAutoplay()
                autoplayEnabled = p.autoplayRadio
                if (_state.value.speed == 1.0f && p.defaultSpeed != 1.0f && _state.value.current.id.isEmpty()) {
                    _state.update { it.copy(speed = p.defaultSpeed) }
                }
            }
        }
        // account change saves the outgoing queue then restores the incoming one
        scope.launch {
            deps.accountEpoch.drop(1).collect {
                likes.reset()
                persistQueue(engine.state.value)
                deps.queueStore.requestFlush()
                stopPlayback()
                val key = deps.accountKey()
                playingAccountKey = key
                val saved = savedQueue(key)
                if (saved != null) restoreQueue(saved)
            }
        }
    }

    private fun uptime(): Long = System.nanoTime() / 1_000_000

    private fun sync(s: EngineState) {
        reporting.sample(s, uptime())
        if (s.entries === retiredEntries) return
        retiredEntries = null
        if (s.entries !== syncedEntries) {
            syncedEntries = s.entries
            syncedQueue = s.entries.map { it.song }
        }
        val cur = s.current?.song ?: EMPTY_SONG
        if (s.entries !== djEntries || s.index != djIndex) { djEntries = s.entries; djIndex = s.index; aiDj?.onQueueChanged(djQueue) }
        rules.publish(s)
        deps.companion?.let { video ->
            video.clearIfSongChanged(cur.id)
            video.sync(s.positionMs, s.effectivelyPlaying, s.speed)
        }
        if (cur.id != lastKeyInfoId) {
            val id = cur.id
            lastKeyInfoId = id
            lastKeyInfo = null
            // the sonic store can block on its first parse
            deps.sonic?.let { sonic ->
                scope.launch {
                    val info = withContext(Dispatchers.IO) { runCatching { sonic.keyInfo(id) }.getOrNull() } ?: return@launch
                    if (lastKeyInfoId != id) return@launch
                    lastKeyInfo = info
                    _state.update { it.copy(bpm = info.bpm, camelot = info.camelot, keyName = info.name) }
                }
            }
        }
        val key = lastKeyInfo
        val video = deps.companion?.state?.value
        val sourceType = runCatching { deps.repository.sourceType(cur) }.getOrNull()
        _state.update {
            it.copy(
                hasVideo = video?.ready == true,
                videoLoading = video?.loading == true,
                videoQualityHeight = video?.qualityHeight?.takeIf { video.ready && !video.canvas },
                videoIsCanvas = video?.canvas == true && video.ready,
                supportsCanvas = deps.companion != null && runCatching { deps.repository.supportsCanvas(cur) }.getOrDefault(false) && mergeFeatures.canvasFor(sourceType),
                supportsMusicVideo = deps.companion != null && mergeFeatures.videosFor(sourceType),
                bpm = key?.bpm ?: 0,
                camelot = key?.camelot ?: "",
                keyName = key?.name ?: "",
                current = cur,
                timelineDurationSec = (s.durationMs.coerceAtLeast(0) / 1000).toInt(),
                isLive = s.live || (cur.isRadio() && !cur.id.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX)),
                isPlaying = s.effectivelyPlaying,
                shuffle = s.shuffle,
                repeat = RepeatMode.valueOf(s.repeat.name),
                positionSec = (s.positionMs / 1000f).coerceAtLeast(0f),
                queue = syncedQueue,
                currentIndex = s.index.coerceAtLeast(0),
                autoplayFrom = autoplayFrom(s),
                smartShuffle = smartShuffle && s.shuffle,
                smartPicks = smartIds,
            )
        }
        // shuffle went off some other way (a new queue in order, say), so its picks go too
        if (smartShuffle && !s.shuffle) { smartShuffle = false; dropSmart() }
        val shuffledNow = s.shuffle && !syncedShuffle
        syncedShuffle = s.shuffle
        // shuffle mixes only your songs; autoplay's picks stay after them
        // regrouping syncs again with the new order, which is the one to fill
        if (!(shuffledNow && autoplayIds.isNotEmpty() && regroupAutoplay(s))) {
            maybeSmart(s)
            maybeAutoplay(s)
        }
        deps.nowPlaying(cur, s.effectivelyPlaying && cur.id.isNotBlank() && !privateSession, _state.value.positionSec)
        if (s.effectivelyPlaying) {
            maybeNowPlaying()
            recordIfPlayed(_state.value.positionSec)
        }
        maybePersist(s)
        trackResume(s)
    }

    // episodes and audiobooks pick up where they were left, whichever way they come back into play
    private fun trackResume(s: EngineState) {
        val points = deps.resumePoints ?: return
        val song = s.current?.song ?: return
        val duration = maxOf(song.durationSec, (s.durationMs.coerceAtLeast(0) / 1000).toInt())
        if (song.id != resumeId) {
            saveResume(points)
            resumeId = song.id
            resumeSavedAt = uptime()
            if (s.positionMs <= RESUME_START_MS && com.aurora.music.data.LongForm.resumes(song.id, duration, playbackPrefs))
                points.positionSec(song.id)?.let { engine.seekTo(it * 1000L); return }
        }
        resumePositionMs = s.positionMs
        resumeDurationSec = duration
        if (!s.effectivelyPlaying || uptime() - resumeSavedAt >= RESUME_SAVE_MS) {
            resumeSavedAt = uptime()
            saveResume(points)
        }
    }

    private fun saveResume(points: com.aurora.music.data.ResumePoints) {
        if (resumeId.isNotEmpty() && com.aurora.music.data.LongForm.resumes(resumeId, resumeDurationSec, playbackPrefs))
            points.record(resumeId, (resumePositionMs / 1000).toInt(), resumeDurationSec)
    }

    // the meter reads the final output after aurora's own volume, so only an exclusive stream leaves no unmeasured gain
    // before the dac; desktop has one fixed volume step and a hardware volume change re-confirms the route instead
    private suspend fun observeLevels(levels: com.aurora.music.data.listening.ListeningLevelStore) {
        while (true) {
            val state = engine.state.value
            val output = state.output
            // the signal path only measures once a source and an output are open
            val after = if (state.current == null || state.source == null || output == null) null else engine.afterMeter.snapshot()
            levels.observe(com.aurora.music.data.listening.ListeningObservation(
                route = deps.settingsStore.processingRoutes.current, playing = state.isPlaying, postDsp = after, downstreamGain = 1.0,
                volumeIndex = 0, volumeMuted = state.volume <= 0f,
                pathSupported = output?.exclusive == true && after != null && state.speed == 1f,
                allowHistory = !privateSession, volumeMaximum = 1))
            delay(500)
        }
    }

    override val video: StateFlow<com.aurora.music.desktop.video.VideoPlayer?> =
        (deps.companion?.state ?: kotlinx.coroutines.flow.MutableStateFlow(com.aurora.music.desktop.video.CompanionVideoState()))
            .map { it.player?.takeIf { _ -> it.ready } }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun requestMusicVideo(canvas: Boolean, onResult: (String) -> Unit) {
        if (canvas && !_state.value.supportsCanvas) { onResult(appString(R.string.canvas_not_found)); return }
        deps.companion?.request(_state.value.current, canvas) {
            onResult(appString(if (canvas) R.string.canvas_not_found else R.string.video_match_not_found))
        }
    }

    override fun setVideoVisible(visible: Boolean) { deps.companion?.setVisible(visible) }

    override fun setVideoQuality(height: Int?) { deps.companion?.setQuality(height) }

    override suspend fun <T> releasingFile(path: String, block: suspend () -> T): T {
        val state = engine.state.value
        val current = state.current?.song
        val target = runCatching { java.io.File(path).canonicalFile }.getOrNull()
        val holds = current != null && target != null &&
            com.aurora.music.ui.components.CoverKey.localPath(current).takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).canonicalFile }.getOrNull() } == target
        if (!holds) return block()
        val index = state.index
        val position = state.positionMs
        val resume = state.playWhenReady
        engine.stop()
        withTimeoutOrNull(2_000) { engine.state.first { it.phase == com.aurora.music.desktop.audio.EnginePhase.IDLE } }
        try {
            return block()
        } finally {
            engine.seekTo(index, position)
            if (resume) engine.play()
        }
    }

    private fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Transition -> {
                reporting.transition(event, engine.state.value, uptime())
                if (_state.value.sleepEndOfTrack && (event.reason == TransitionReason.AUTO || event.reason == TransitionReason.REPEAT)) sleepNow()
            }
            is EngineEvent.Discontinuity -> reporting.discontinuity(event, engine.state.value, uptime())
            EngineEvent.Ended -> if (_state.value.sleepEndOfTrack) sleepNow() else maybeAutoplay(engine.state.value)
            is EngineEvent.Failed -> _messages.tryEmit(event.failure.message)
            is EngineEvent.OutputFallback -> _messages.tryEmit(event.reason)
            is EngineEvent.HardwareVolumeChanged -> showVolume(volumePosition(event.gain))
        }
    }

    private fun sleepNow() {
        engine.pause()
        _state.update { it.copy(sleepEndOfTrack = false) }
    }

    private fun maybeNowPlaying() {
        val cur = _state.value.current
        if (cur.id.isEmpty() || cur.id == lastNowPlayingId) return
        lastNowPlayingId = cur.id
        // radio/podcasts aren't library tracks never scrobble them
        if (cur.isRadio() || cur.isPodcast() || cur.id.startsWith("aurora-mix:")) return
        playStartMs = System.currentTimeMillis()
        if (!privateSession) { deps.lastfm.nowPlaying(cur); deps.listenBrainz.nowPlaying(cur) }
    }

    private fun recordIfPlayed(posSec: Float) {
        val cur = _state.value.current
        if (cur.id.isEmpty() || posSec < 30f || cur.id == lastRecordedId) return
        lastRecordedId = cur.id
        if (cur.isRadio() || cur.isPodcast() || cur.id.startsWith("aurora-mix:")) return
        if (privateSession) return
        deps.lastfm.scrobble(cur, playStartMs)
        deps.listenBrainz.scrobble(cur, playStartMs)
    }

    // where the songs autoplay queued start in what's still to come, or -1
    private fun autoplayFrom(s: EngineState): Int {
        if (autoplayIds.isEmpty()) return -1
        return (s.index + 1 until s.entries.size).firstOrNull { s.entries[it].song.id in autoplayIds } ?: -1
    }

    // once the last song in the queue starts, more like what has been playing follows it, so the music never stops
    private fun maybeAutoplay(s: EngineState) {
        val current = s.current ?: return
        if (!autoplayEnabled || s.repeat != EngineRepeat.OFF || s.index != s.entries.lastIndex || s.live) return
        if (current.uid == autoplayTriedFor || autoplayJob?.isActive == true || queueFillJob?.isActive == true) return
        if (loadingRadio || _state.value.isMix || aiDj?.state?.value?.active == true || !Autoplay.follows(current.song)) return
        autoplayTriedFor = current.uid
        val generation = queueGeneration
        val played = s.entries.take(s.index + 1).map { it.song }
        val seeds = Autoplay.seeds(played).ifEmpty { return }
        autoplayJob = scope.launch {
            val more = Autoplay.next(seeds, s.entries.map { it.song },
                radio = { deps.repository.radio(it.id) },
                // the sonic list leads with the seed itself
                fallback = { seed -> withContext(Dispatchers.IO) { deps.sonic?.buildRadio(seed).orEmpty().drop(1) } })
            val now = engine.state.value
            // a new queue or an edit meanwhile means these were picked for a queue that is gone
            if (more.isEmpty() || generation != queueGeneration || now.entries.lastOrNull()?.uid != current.uid) return@launch
            autoplayIds = autoplayIds + more.map { it.id }
            queueGeneration++
            val ended = now.phase == com.aurora.music.desktop.audio.EnginePhase.ENDED
            engine.append(more)
            // the last song finished before the picks arrived, so carry on into them
            if (ended) { engine.next(); engine.play() }
        }
    }

    // shuffle with autoplay: picks like the queue's songs go in after every few of yours, ahead of time
    private fun maybeSmart(s: EngineState) {
        if (!smartPending || !smartShuffle || !s.shuffle || smartJob?.isActive == true) return
        val current = s.current ?: return
        // a queue still paging in gets its picks once it's whole, so they spread over all of it
        if (queueFillJob?.isActive == true || _state.value.isMix || aiDj?.state?.value?.active == true) return
        smartPending = false
        val context = smartContext
        val upcoming = s.entries.drop(s.index + 1).map { it.song }
        val count = Autoplay.smartCount(upcoming.size).takeIf { it > 0 } ?: return
        // the playing song first, then what's coming, one per artist
        val seeds = Autoplay.seeds((listOf(current.song) + upcoming).asReversed(), Autoplay.SMART_SEEDS).ifEmpty { return }
        smartJob = scope.launch {
            val picks = Autoplay.next(seeds, s.entries.map { it.song },
                radio = { deps.repository.radio(it.id) },
                fallback = { seed -> withContext(Dispatchers.IO) { deps.sonic?.buildRadio(seed).orEmpty().drop(1) } },
                limit = count)
            val now = engine.state.value
            if (picks.isEmpty() || context != smartContext || !smartShuffle || !now.shuffle) return@launch
            smartIds = smartIds + picks.map { it.id }
            queueGeneration++
            val start = now.index + 1
            // in order, so each insert lands where the finished queue has it
            Autoplay.interleave(List<Song?>(now.entries.size - start) { null }, picks).forEachIndexed { i, pick ->
                if (pick != null) engine.insert(start + i, listOf(pick))
            }
        }
    }

    // puts autoplay's picks back after your own songs once shuffle has mixed them in, in the order they came;
    // true when it moved any, so the next sync sees the regrouped queue
    private fun regroupAutoplay(s: EngineState): Boolean {
        val start = s.index + 1
        val ids = s.entries.map { it.song.id }.toMutableList()
        // autoplay's set keeps the order its picks came in
        val rank = autoplayIds.withIndex().associate { (i, id) -> id to i }
        val ahead = ids.subList(start.coerceAtMost(ids.size), ids.size)
        val grouped = ahead.filter { it !in rank } + ahead.filter { it in rank }.sortedBy { rank.getValue(it) }
        if (grouped == ahead) return false
        queueGeneration++
        // each pick goes to the end in turn, in the order it came
        for (id in grouped.filter { it in rank }) {
            val at = (start until ids.size).first { ids[it] == id }
            engine.move(at, ids.lastIndex)
            ids.add(ids.removeAt(at))
        }
        return true
    }

    // takes the picks shuffle mixed in back out of what's still to come
    private fun dropSmart() {
        smartJob?.cancel()
        smartPending = false
        val ids = smartIds
        smartIds = emptySet()
        if (ids.isEmpty()) return
        val s = engine.state.value
        val drop = (s.index + 1 until s.entries.size).filter { s.entries[it].song.id in ids }
        if (drop.isEmpty()) return
        queueGeneration++
        drop.asReversed().forEach { engine.remove(it) }
    }

    // takes autoplay's upcoming songs back out, last first so the indices ahead stay put
    private fun dropAutoplay() {
        autoplayJob?.cancel()
        val s = engine.state.value
        val ids = autoplayIds
        autoplayIds = emptySet()
        if (ids.isEmpty()) return
        val drop = (s.index + 1 until s.entries.size).filter { s.entries[it].song.id in ids }
        if (drop.isEmpty()) return
        queueGeneration++
        drop.asReversed().forEach { engine.remove(it) }
    }

    private fun maybePersist(s: EngineState) {
        val last = persisted
        val now = uptime()
        val changed = last == null || s.entries !== last.entries || s.index != last.index || s.shuffle != last.shuffle ||
            s.repeat != last.repeat || s.playWhenReady != last.playWhenReady
        // a paused seek gets no later tick to retry it, so only playback is throttled
        if (!changed && (s.positionMs == last?.positionMs || (s.playWhenReady && now - lastPersistMs < 15_000))) return
        persisted = s
        lastPersistMs = now
        persistQueue(s)
    }

    private fun persistQueue(s: EngineState) {
        val key = playingAccountKey.ifBlank { deps.accountKey() }
        // skip saving the empty startup engine so restore is not wiped
        if (key.isBlank() || s.entries.isEmpty()) return
        if (s.entries !== savedEntries) {
            savedEntries = s.entries
            savedTracks = s.entries.map { it.song.toSavedTrack() }
        }
        deps.queueStore.save(key, SavedQueue(
            tracks = savedTracks,
            currentIndex = s.index.coerceAtLeast(0),
            positionSec = (s.positionMs / 1000).toInt().coerceAtLeast(0),
            shuffle = s.shuffle,
            repeat = when (s.repeat) { EngineRepeat.ALL -> 1; EngineRepeat.ONE -> 2; EngineRepeat.OFF -> 0 },
            shuffleOrder = s.shuffleRestoreIds?.takeIf { s.shuffle },
            autoplay = autoplayIds.takeIf { it.isNotEmpty() }?.let { ids -> s.entries.map { it.song.id }.filter(ids::contains) },
            smartShuffle = (smartShuffle && s.shuffle).takeIf { it },
            smartPicks = smartIds.takeIf { it.isNotEmpty() }?.let { ids -> s.entries.map { it.song.id }.filter(ids::contains) },
        ))
    }

    // older builds kept the shuffle order in a "<key>#shuffle-order" side entry
    private fun savedQueue(key: String): SavedQueue? {
        if (key.isBlank()) return null
        val store = deps.queueStore
        val legacyKey = "$key#shuffle-order"
        val legacy = store.get(legacyKey) ?: return store.get(key)
        val saved = store.get(key)?.let { sq ->
            if (!sq.shuffle || sq.shuffleOrder != null) sq
            else sq.copy(shuffleOrder = legacy.tracks.orEmpty().mapNotNull { it.id?.ifEmpty { null } }).also { store.save(key, it) }
        }
        store.clear(legacyKey)
        return saved
    }

    private fun SavedQueue.restorableSongs(): List<Song> =
        tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }

    private fun restoreQueue(sq: SavedQueue, songs: List<Song> = sq.restorableSongs()) {
        if (songs.isEmpty()) return
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        val repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF }
        // buffer at saved position but stay paused
        replaceQueue(songs, idx, sq.positionSec * 1000L, play = false)
        autoplayIds = sq.autoplay.orEmpty().toSet()
        smartShuffle = sq.shuffle && sq.smartShuffle == true
        smartIds = sq.smartPicks.orEmpty().toSet()
        smartPending = false
        engine.setRepeat(EngineRepeat.valueOf(repeat.name))
        if (sq.shuffle) engine.setShuffle(ShuffleTarget.ON, sq.shuffleOrder ?: songs.map { it.id })
        else engine.setShuffle(ShuffleTarget.OFF)
        _state.update {
            it.copy(queue = songs, current = songs[idx], positionSec = sq.positionSec.toFloat(), isPlaying = false,
                currentIndex = idx, shuffle = sq.shuffle, repeat = repeat)
        }
    }

    private fun replaceQueue(songs: List<Song>, index: Int, positionMs: Long = 0, play: Boolean = true) {
        cancelQueueFill()
        autoplayJob?.cancel()
        autoplayIds = emptySet()
        newSmartContext()
        queueGeneration++
        retiredEntries = engine.state.value.entries
        engine.setQueue(songs, index, positionMs, play)
        engine.setSpeed(_state.value.speed)
    }

    // a new queue keeps shuffle with autoplay on and gets picks of its own
    private fun newSmartContext() {
        smartJob?.cancel()
        smartContext++
        smartIds = emptySet()
        smartPending = smartShuffle
    }

    private fun queueSize(): Int = if (retiredEntries != null) _state.value.queue.size else engine.state.value.entries.size

    private fun currentIndex(): Int = if (retiredEntries != null) _state.value.currentIndex else engine.state.value.index

    override fun stopPlayback() {
        cancelQueueFill()
        autoplayJob?.cancel()
        autoplayIds = emptySet()
        newSmartContext()
        smartPending = false
        queueGeneration++
        retiredEntries = engine.state.value.entries
        engine.stop()
        engine.clear()
        lastRecordedId = null
        lastNowPlayingId = null
        _state.update {
            it.copy(current = EMPTY_SONG, queue = emptyList(), isPlaying = false, positionSec = 0f, currentIndex = 0, expanded = false)
        }
    }

    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        engine.sleepFade(0)
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepEndOfTrack = false) }
        if (minutes <= 0) return
        sleepJob = scope.launch {
            delay((minutes * 60_000L - SLEEP_FADE_MS).coerceAtLeast(0L))
            engine.sleepFade(SLEEP_FADE_MS)
            _state.update { it.copy(sleepTimerMinutes = 0) }
        }
    }

    override fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        engine.sleepFade(0)
        _state.update { it.copy(sleepTimerMinutes = 0, sleepEndOfTrack = true) }
    }

    override fun setPreferredDevice(deviceId: String?) {
        _preferredOutput.value = deviceId
        scope.launch { deps.desktopSettings.setOutputDevice(deviceId) }
    }

    override fun setExclusiveOutput(enabled: Boolean) {
        if (enabled && !engine.exclusiveAvailable) return
        _exclusiveOutput.value = enabled
        scope.launch { deps.desktopSettings.setExclusiveMode(enabled) }
    }

    override fun setVolume(value: Float) {
        val level = value.coerceIn(0f, 1f)
        showVolume(level)
        engine.setVolume(volumeGain(level))
    }

    // the dac's own knob already moved the sound, so this only follows it
    private fun showVolume(level: Float) {
        if (level > DesktopSettings.MIN_AUDIBLE_VOLUME) unmuteVolume = level
        val unmute = unmuteVolume
        _volume.value = level
        volumeSave?.cancel()
        volumeSave = scope.launch {
            delay(300)
            deps.desktopSettings.setVolume(level, unmute)
        }
    }

    override fun toggleMute() = setVolume(if (_volume.value > 0f) 0f else unmuteVolume)

    override fun playAll(songs: List<Song>, startIndex: Int, collection: PlaybackCollectionIdentity?) {
        if (songs.isEmpty()) return
        playingAccountKey = deps.accountKey()
        val contextual = songs.map { it.copy(playbackCollection = collection) }
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        replaceQueue(contextual, idx)
        // fresh context plays in order make sure shuffle is off
        engine.setShuffle(ShuffleTarget.OFF)
        _state.update { it.copy(queue = contextual, currentIndex = idx, current = contextual[idx], positionSec = 0f, isPlaying = true, shuffle = false) }
    }

    override fun play(song: Song) = playAll(listOf(song), 0)

    override val aiDj = deps.aiDj?.let { com.aurora.music.ai.DjMode(it, scope, deps.aiDjVoice, deps.aiDjEars) }
    private var djEntries: Any? = null
    private var djIndex = -1
    private val djQueue = object : com.aurora.music.ai.DjMode.Queue {
        override val current: Song? get() = engine.state.value.current?.song
        override val upcoming: List<Song> get() = engine.state.value.let { s -> s.entries.drop(s.index + 1).map { it.song } }
        override val playing: Boolean get() = engine.state.value.playWhenReady
        override fun pause() = engine.pause()
        override fun play() = engine.play()
        override fun append(songs: List<Song>) {
            // the last set ran out before this one was ready
            val ended = engine.state.value.phase == com.aurora.music.desktop.audio.EnginePhase.ENDED
            queueGeneration++
            engine.append(songs)
            if (ended) engine.next()
        }
        override fun replaceUpcoming(songs: List<Song>) {
            val count = queueSize()
            val current = currentIndex()
            queueGeneration++
            if (count - 1 > current) engine.remove(current + 1, count)
            engine.append(songs)
        }
        override fun skipToNext() = engine.next()
    }

    // the dj's queue only moves forward, looping it would replay its lines
    private val djActive get() = aiDj?.state?.value?.active == true

    init {
        aiDj?.let { dj ->
            scope.launch {
                var repeatBefore: RepeatMode? = null
                dj.state.map { it.active }.distinctUntilChanged().collect { active ->
                    if (active) {
                        repeatBefore = _state.value.repeat
                        setRepeat(RepeatMode.OFF)
                        engine.setShuffle(ShuffleTarget.OFF)
                    } else {
                        repeatBefore?.let(::setRepeat)
                        repeatBefore = null
                    }
                }
            }
        }
    }

    override fun startAiDj(onResult: (String) -> Unit) {
        aiDj?.start(djQueue, { playAll(it, 0, null) }) { ok ->
            onResult(appString(if (ok) R.string.ai_dj_started else R.string.ai_dj_nothing))
        }
    }

    override fun switchAiDj() { aiDj?.switchUp(djQueue) }

    override fun steerAiDj(text: String) { aiDj?.steer(text, djQueue) }

    override fun stopAiDj() { aiDj?.stop() }

    override fun aiDjListen(): Boolean = aiDj?.startListening(djQueue) == true

    override fun aiDjHear() { aiDj?.stopListening(djQueue) }

    override fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) {
        val collection = deps.repository.playbackCollectionIdentity(kind, id)
        val picked = loaded.getOrNull(startIndex)
        val songs = deps.repository.playable(kind, id, loaded, picked)
        playAll(songs, songs.indexOfFirst { it === picked }.coerceAtLeast(0), collection)
        fillQueue(kind, id, loaded, total, shuffle = false, collection = collection)
    }

    override fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) {
        val collection = deps.repository.playbackCollectionIdentity(kind, id)
        shufflePlay(deps.repository.playable(kind, id, loaded), collection)
        fillQueue(kind, id, loaded, total, shuffle = true, collection = collection)
    }

    private fun cancelQueueFill() {
        queueFillJob?.cancel()
        queueFillJob = null
        flushFill = null
    }

    // an edit stops the fill, so its fetched tracks land first
    private fun editQueue() {
        flushFill?.invoke()
        queueGeneration++
    }

    private fun fillQueue(kind: String, id: String, loaded: List<Song>, total: Int, shuffle: Boolean, collection: PlaybackCollectionIdentity?) {
        cancelQueueFill()
        if (loaded.isEmpty() || loaded.size >= total || total <= 0) return
        val generation = queueGeneration
        queueFillJob = scope.launch {
            val have = loaded.mapTo(HashSet()) { it.id }
            val pending = ArrayList<Song>()
            fun flush() {
                if (pending.isEmpty() || generation != queueGeneration) return
                engine.append((if (shuffle) pending.shuffled() else pending).map { it.copy(playbackCollection = collection) })
                pending.clear()
            }
            flushFill = ::flush
            var offset = loaded.size
            while (offset < total) {
                val page = runCatching { deps.repository.detailPage(kind, id, offset) }.getOrDefault(emptyList())
                ensureActive()
                if (page.isEmpty() || generation != queueGeneration) break
                pending += deps.repository.playable(kind, id, page.filter { it.id.isNotEmpty() && have.add(it.id) })
                offset += page.size
                // each append re-maps the whole queue, so batch unless it is about to run out
                val s = engine.state.value
                if (pending.size >= FILL_BATCH || s.entries.size - s.index <= FILL_AHEAD) flush()
                delay(180)
            }
            flush()
        }
    }

    // the server's radio when it knows the song better, otherwise tracks that sound alike from sonic analysis
    override fun startSonicRadio(seed: Song, onResult: (String) -> Unit) {
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        scope.launch {
            suspend fun serverRadio(): List<Song> = try { deps.repository.radio(seed.id).filter { it.id != seed.id } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            try {
                val preferServer = deps.repository.prefersServerRadio(seed.id)
                if (preferServer) {
                    val more = serverRadio()
                    if (more.isNotEmpty()) {
                        playAll(listOf(seed) + more, 0)
                        onResult(appString(R.string.text_radio_started_31fe2c))
                        return@launch
                    }
                }
                val sonic = try { deps.sonic?.buildRadio(seed).orEmpty() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                if (sonic.size >= 2) {
                    playAll(sonic, 0)
                    onResult(appString(R.string.text_sonic_radio_similar_tracks_653156, sonic.size - 1))
                } else {
                    val more = if (preferServer) emptyList() else serverRadio()
                    if (more.isNotEmpty()) {
                        playAll(listOf(seed) + more, 0)
                        onResult(appString(R.string.text_radio_started_31fe2c))
                    } else {
                        onResult(appString(R.string.text_not_enough_analyzed_tracks_run_sonic_analysis_in_settings_007d98))
                    }
                }
            } finally {
                loadingRadio = false
            }
        }
    }

    override fun startAutoDj(seed: Song, onResult: (String) -> Unit) {
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        scope.launch {
            val set = runCatching { deps.sonic?.buildAutoDj(seed).orEmpty() }.getOrDefault(emptyList())
            loadingRadio = false
            if (set.size >= 2) {
                playAll(set, 0)
                onResult(appString(R.string.text_auto_dj_tracks_key_tempo_matched_83c6cc, set.size))
            } else startSonicRadio(seed, onResult)
        }
    }

    override fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity?) {
        if (songs.isEmpty()) return
        playingAccountKey = deps.accountKey()
        val contextual = songs.map { it.copy(playbackCollection = collection) }
        val shuffled = contextual.shuffled()
        replaceQueue(shuffled, 0)
        // pass the original order so disabling shuffle restores it
        engine.setShuffle(ShuffleTarget.ON, songs.map { it.id })
        _state.update { it.copy(queue = shuffled, currentIndex = 0, current = shuffled[0], positionSec = 0f, isPlaying = true, shuffle = true) }
    }

    override fun addToQueue(song: Song) {
        if (queueSize() == 0) { play(song); return }
        editQueue()
        // what you add yourself plays before autoplay's picks
        val autoplay = if (retiredEntries == null) autoplayFrom(engine.state.value) else -1
        if (autoplay >= 0) engine.insert(autoplay, listOf(song)) else engine.append(listOf(song))
    }

    override fun playNext(song: Song) {
        val count = queueSize()
        if (count == 0) { play(song); return }
        editQueue()
        engine.insert((currentIndex() + 1).coerceIn(0, count), listOf(song))
    }

    // the song playing now starts over with its stream looked up again, as after its youtube match changed
    override fun reloadIfPlaying(songId: String) {
        val id = com.aurora.music.data.stripMergeNamespace(songId)
        val index = currentIndex()
        val current = engine.state.value.current?.song ?: return
        if (com.aurora.music.data.stripMergeNamespace(current.id) == id && index in 0 until queueSize()) engine.reload(index)
    }

    override fun jumpTo(index: Int) {
        if (index in 0 until queueSize()) {
            engine.seekTo(index, 0)
            engine.play()
        }
    }

    override fun removeFromQueue(index: Int) {
        if (index in 0 until queueSize()) {
            editQueue()
            engine.remove(index)
        }
    }

    // drops only what's queued after the current track history stays
    override fun clearQueue() {
        val count = queueSize()
        val current = currentIndex()
        if (count - 1 > current) {
            queueGeneration++
            engine.remove(current + 1, count)
        }
    }

    override fun moveQueueItem(from: Int, to: Int) {
        val count = queueSize()
        if (from in 0 until count && to in 0 until count && from != to) {
            editQueue()
            engine.move(from, to)
        }
    }

    // radio/podcasts dropped since the backend can't resolve their ids
    override fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit) {
        val title = name.trim()
        if (title.isEmpty()) return
        val ids = _state.value.queue
            .filterNot { it.isRadio() || it.isPodcast() }
            .map { it.id }
            .filter { it.isNotEmpty() }
        if (ids.isEmpty()) { onResult(appString(R.string.text_nothing_to_save_a5dbc6)); return }
        scope.launch {
            val ok = try { deps.repository.createPlaylistFromSongs(title, ids) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            onResult(if (ok) appString(R.string.text_saved_bac2cc, title) else appString(R.string.text_couldn_t_save_playlist_d7a0f6))
        }
    }

    fun play() = engine.play()

    fun pause() = engine.pause()

    override fun togglePlay() {
        if (_state.value.isPlaying) engine.pause() else engine.play()
    }

    override fun seekTo(fraction: Float) {
        val dur = _state.value.durationSec
        if (dur > 0) engine.seekTo((fraction.coerceIn(0f, 1f) * dur * 1000).toLong())
    }

    override fun next() = engine.next()

    override fun previous() {
        if (engine.state.value.positionMs > 4000) engine.seekTo(0) else engine.previousItem()
    }

    // off, then shuffle, then shuffle with autoplay picks mixed in, then off again
    override fun toggleShuffle() {
        if (djActive) return
        val s = engine.state.value
        when {
            !s.shuffle -> engine.setShuffle(ShuffleTarget.ON)
            !smartShuffle && !_state.value.isMix -> {
                smartShuffle = true
                smartPending = true
                _state.update { it.copy(smartShuffle = true) }
                maybeSmart(s)
            }
            else -> {
                smartShuffle = false
                _state.update { it.copy(smartShuffle = false) }
                dropSmart()
                engine.setShuffle(ShuffleTarget.OFF)
            }
        }
    }

    override fun cycleRepeat() {
        if (!djActive) setRepeat(when (_state.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        })
    }

    fun setRepeat(mode: RepeatMode) {
        engine.setRepeat(EngineRepeat.valueOf(mode.name))
        _state.update { it.copy(repeat = mode) }
    }

    override fun toggleLikeCurrent() = likes.toggle(_state.value.current)

    override fun refreshLikes(restart: Boolean) = likes.refresh(restart)

    override fun checkLiked(songs: List<Song>) = likes.check(songs)

    // songs toggle on the source they play from; the heart's menu reaches the other accounts
    override fun toggleLike(id: String, kind: String) = likes.toggle(id, kind)

    override suspend fun likeOptions(song: Song) = likes.options(song)

    override suspend fun setLikedOn(song: Song, option: com.aurora.music.data.LikeOption, liked: Boolean) = likes.set(song, option, liked)
    override fun likedSources() = likes.likedSources()

    override fun setExpanded(value: Boolean) = _state.update { it.copy(expanded = value) }

    override fun setSpeed(value: Float) {
        val snapped = (Math.round(value / 0.05f) * 0.05f).coerceIn(0.5f, 2.0f)
        _state.update { it.copy(speed = snapped) }
        engine.setSpeed(snapped)
    }

    override fun resetSpeedPitch() {
        _state.update { it.copy(speed = 1.0f) }
        engine.setSpeed(1.0f)
    }

    override fun close() {
        val last = engine.state.value
        persistQueue(last)
        deps.queueStore.flushNow()
        deps.resumePoints?.let { saveResume(it); it.flushNow() }
        reporting.close(last, uptime())
        history.finish()
        details.finish()
        mediaControls.close()
        scope.cancel()
        engine.close()
    }

    private companion object {
        const val SLEEP_FADE_MS = 6_000
        const val RESUME_START_MS = 3_000L
        const val RESUME_SAVE_MS = 15_000L
        const val FILL_BATCH = 500
        const val FILL_AHEAD = 50
    }

    // the state collectors start last: the scope can run them on the ui thread before this constructor finishes,
    // and sync reaches members declared further down, such as the dj queue, which would still be null
    init {
        // one bad update is logged and skipped; letting it escape would end the collector and freeze the player bar
        fun safeSync(state: EngineState) = runCatching { sync(state) }.onFailure { com.aurora.music.util.AppLog.e("DesktopPlayer", "Player state sync failed", it) }
        scope.launch { engine.state.collect { safeSync(it) } }
        deps.companion?.let { video -> scope.launch { video.state.collect { safeSync(engine.state.value) } } }
    }
}
