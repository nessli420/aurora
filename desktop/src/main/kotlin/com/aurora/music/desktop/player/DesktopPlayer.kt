package com.aurora.music.desktop.player

import com.aurora.music.R
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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

    private var sleepJob: Job? = null
    private var queueFillJob: Job? = null
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

    private var autoplayEnabled = false
    private var privateSession = false
    private var serverLikedIds: Set<String> = emptySet()
    private var likedPlaylistIds: Set<String> = emptySet()
    private val likeChecked = HashSet<String>()
    private var likeEdits = 0
    private val likesInFlight = mutableListOf<String>()
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // account the live queue belongs to so it persists/restores under the right key
    private var playingAccountKey = ""
    private var openRestoreAttempted = false
    private var playStartMs = 0L
    private var loadingRadio = false

    init {
        engine.visualizer = deps.visualizer
        engine.bindSettings(deps.settingsStore, scope)
        scope.launch { engine.state.collect(::sync) }
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            while (true) {
                delay(1_000)
                val current = engine.state.value
                reporting.sample(current, uptime())
                history.track(current, uptime())
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
                playingAccountKey = deps.accountKey()
                val saved = savedQueue(playingAccountKey)
                if (queueSize() == 0 && saved != null) restoreQueue(saved)
            }
        }
        // subsonic can't star playlists so locally-liked ones merge into the same set
        scope.launch {
            deps.settingsStore.likedPlaylists.collect { ids ->
                likedPlaylistIds = ids
                recomputeLikes()
            }
        }
        scope.launch {
            deps.settingsStore.privateSession.collect {
                privateSession = it
                history.allowed = !it
            }
        }
        scope.launch {
            deps.settingsStore.playbackPrefs.collect { p ->
                autoplayEnabled = p.autoplayRadio
                if (_state.value.speed == 1.0f && p.defaultSpeed != 1.0f && _state.value.current.id.isEmpty()) {
                    _state.update { it.copy(speed = p.defaultSpeed) }
                }
            }
        }
        // account change saves the outgoing queue then restores the incoming one
        scope.launch {
            deps.accountEpoch.drop(1).collect {
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
        _state.update {
            it.copy(
                current = cur,
                timelineDurationSec = (s.durationMs.coerceAtLeast(0) / 1000).toInt(),
                isLive = s.live || cur.isRadio(),
                isPlaying = s.effectivelyPlaying,
                shuffle = s.shuffle,
                repeat = RepeatMode.valueOf(s.repeat.name),
                positionSec = (s.positionMs / 1000f).coerceAtLeast(0f),
                queue = syncedQueue,
                currentIndex = s.index.coerceAtLeast(0),
            )
        }
        if (s.effectivelyPlaying) {
            maybeNowPlaying()
            recordIfPlayed(_state.value.positionSec)
        }
        maybePersist(s)
    }

    private fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Transition -> {
                reporting.transition(event, engine.state.value, uptime())
                if (_state.value.sleepEndOfTrack && (event.reason == TransitionReason.AUTO || event.reason == TransitionReason.REPEAT)) sleepNow()
            }
            is EngineEvent.Discontinuity -> reporting.discontinuity(event, engine.state.value, uptime())
            EngineEvent.Ended -> if (_state.value.sleepEndOfTrack) sleepNow() else maybeAutoplay()
            is EngineEvent.Failed -> _messages.tryEmit(event.failure.message)
            is EngineEvent.OutputFallback -> _messages.tryEmit(event.reason)
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

    private fun maybeAutoplay() {
        if (!autoplayEnabled || loadingRadio) return
        val seed = _state.value.current.id.ifEmpty { return }
        loadingRadio = true
        scope.launch {
            val queued = engine.state.value.entries.mapTo(HashSet()) { it.song.id }
            val more = runCatching { deps.repository.radio(seed) }.getOrDefault(emptyList()).filter { it.id !in queued }
            if (more.isNotEmpty()) {
                queueGeneration++
                engine.append(more)
                engine.play()
            }
            loadingRadio = false
        }
    }

    private fun maybePersist(s: EngineState) {
        val last = persisted
        val now = uptime()
        val changed = last == null || s.entries !== last.entries || s.index != last.index || s.shuffle != last.shuffle ||
            s.repeat != last.repeat || s.playWhenReady != last.playWhenReady
        if (!changed && (s.positionMs == last?.positionMs || now - lastPersistMs < 2_000)) return
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

    private fun restoreQueue(sq: SavedQueue) {
        val songs = sq.tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }
        if (songs.isEmpty()) return
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        val repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF }
        // buffer at saved position but stay paused
        replaceQueue(songs, idx, sq.positionSec * 1000L, play = false)
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
        queueGeneration++
        retiredEntries = engine.state.value.entries
        engine.setQueue(songs, index, positionMs, play)
        engine.setSpeed(_state.value.speed)
    }

    private fun queueSize(): Int = if (retiredEntries != null) _state.value.queue.size else engine.state.value.entries.size

    private fun currentIndex(): Int = if (retiredEntries != null) _state.value.currentIndex else engine.state.value.index

    override fun stopPlayback() {
        cancelQueueFill()
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
        if (level > DesktopSettings.MIN_AUDIBLE_VOLUME) unmuteVolume = level
        val unmute = unmuteVolume
        _volume.value = level
        engine.setVolume(volumeGain(level))
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

    override fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) {
        val collection = deps.repository.playbackCollectionIdentity(kind, id)
        playAll(loaded, startIndex, collection)
        fillQueue(kind, id, loaded, total, shuffle = false, collection = collection)
    }

    override fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) {
        val collection = deps.repository.playbackCollectionIdentity(kind, id)
        shufflePlay(loaded, collection)
        fillQueue(kind, id, loaded, total, shuffle = true, collection = collection)
    }

    private fun cancelQueueFill() {
        queueFillJob?.cancel()
        queueFillJob = null
    }

    private fun fillQueue(kind: String, id: String, loaded: List<Song>, total: Int, shuffle: Boolean, collection: PlaybackCollectionIdentity?) {
        cancelQueueFill()
        if (loaded.isEmpty() || loaded.size >= total || total <= 0) return
        val generation = queueGeneration
        queueFillJob = scope.launch {
            val have = loaded.mapTo(HashSet()) { it.id }
            var offset = loaded.size
            while (offset < total) {
                val page = runCatching { deps.repository.detailPage(kind, id, offset) }.getOrDefault(emptyList())
                ensureActive()
                if (page.isEmpty() || generation != queueGeneration) break
                val fresh = page.filter { it.id.isNotEmpty() && have.add(it.id) }
                if (fresh.isNotEmpty()) engine.append((if (shuffle) fresh.shuffled() else fresh).map { it.copy(playbackCollection = collection) })
                offset += page.size
                delay(180)
            }
        }
    }

    override fun startSonicRadio(seed: Song, onResult: (String) -> Unit) {
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        scope.launch {
            try {
                val more = try { deps.repository.radio(seed.id).filter { it.id != seed.id } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                if (more.isNotEmpty()) {
                    playAll(listOf(seed) + more, 0)
                    onResult(appString(R.string.text_radio_started_31fe2c))
                } else {
                    onResult(appString(R.string.text_no_results_b993b0))
                }
            } finally {
                loadingRadio = false
            }
        }
    }

    override fun startAutoDj(seed: Song, onResult: (String) -> Unit) = startSonicRadio(seed, onResult)

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
        queueGeneration++
        engine.append(listOf(song))
    }

    override fun playNext(song: Song) {
        val count = queueSize()
        if (count == 0) { play(song); return }
        queueGeneration++
        engine.insert((currentIndex() + 1).coerceIn(0, count), listOf(song))
    }

    override fun jumpTo(index: Int) {
        if (index in 0 until queueSize()) {
            engine.seekTo(index, 0)
            engine.play()
        }
    }

    override fun removeFromQueue(index: Int) {
        if (index in 0 until queueSize()) {
            queueGeneration++
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
            queueGeneration++
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

    override fun toggleShuffle() = engine.setShuffle(ShuffleTarget.TOGGLE)

    override fun cycleRepeat() = setRepeat(when (_state.value.repeat) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.OFF
    })

    fun setRepeat(mode: RepeatMode) {
        engine.setRepeat(EngineRepeat.valueOf(mode.name))
        _state.update { it.copy(repeat = mode) }
    }

    override fun toggleLikeCurrent() = toggleLike(_state.value.current.id)

    private fun recomputeLikes() {
        _state.update { it.copy(likedIds = serverLikedIds + likedPlaylistIds) }
    }

    override fun refreshLikes() {
        scope.launch {
            val edits = likeEdits
            val fetched = runCatching { deps.repository.starredIds() }.getOrNull() ?: return@launch
            // a like toggled mid-fetch makes the fetched set stale
            if (edits != likeEdits) return@launch
            serverLikedIds = fetched - likesInFlight + likesInFlight.filter { it in serverLikedIds }
            recomputeLikes()
        }
    }

    override fun checkLiked(ids: List<String>) {
        val toCheck = ids.filter { it.isNotEmpty() && it !in serverLikedIds && it !in likeChecked }.distinct()
        if (toCheck.isEmpty()) return
        scope.launch {
            val liked = runCatching { deps.repository.likedSongIds(toCheck) }.getOrNull() ?: return@launch
            likeChecked.addAll(toCheck)
            if (liked.isNotEmpty()) {
                serverLikedIds = serverLikedIds + liked
                recomputeLikes()
            }
        }
    }

    // song/album/artist persist to the server playlist persists locally
    override fun toggleLike(id: String, kind: String) {
        if (id.isEmpty() || id.startsWith("aurora-mix:")) return
        val nowLiked = !_state.value.likedIds.contains(id)
        if (kind == "playlist") {
            likedPlaylistIds = if (nowLiked) likedPlaylistIds + id else likedPlaylistIds - id
            recomputeLikes()
            scope.launch { runCatching { deps.settingsStore.setPlaylistLiked(id, nowLiked) } }
            // also sync to backend no-op for backends that can't star playlists
            scope.launch { runCatching { deps.repository.setStarred(id, nowLiked, "playlist") } }
        } else {
            serverLikedIds = if (nowLiked) serverLikedIds + id else serverLikedIds - id
            likeEdits++
            likesInFlight += id
            recomputeLikes()
            scope.launch {
                runCatching { deps.repository.setStarred(id, nowLiked, kind) }
                likeEdits++
                likesInFlight -= id
            }
        }
    }

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
        reporting.close(last, uptime())
        history.finish()
        mediaControls.close()
        scope.cancel()
        engine.close()
    }

    private companion object {
        const val SLEEP_FADE_MS = 6_000
    }
}
