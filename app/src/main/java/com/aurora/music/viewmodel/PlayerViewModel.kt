package com.aurora.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SavedQueue
import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.data.toSavedTrack
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackService
import com.aurora.music.playback.PresetContextPublisher
import com.aurora.music.playback.MediaSearchRequest
import com.aurora.music.data.PlaybackCollectionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.pow

enum class RepeatMode { OFF, ALL, ONE }

private val EMPTY_SONG = Song("", "Nothing playing", "", "", "", 0)

data class PlayerUiState(
    val current: Song = EMPTY_SONG,
    val queue: List<Song> = emptyList(),
    val isPlaying: Boolean = false,
    val positionSec: Float = 0f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val expanded: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 0.0f,
    val matchPitch: Boolean = true,
    val likedIds: Set<String> = emptySet(),
    val currentIndex: Int = 0,
    val sleepTimerMinutes: Int = 0,
    val sleepEndOfTrack: Boolean = false,
    val bpm: Int = 0,
    val camelot: String = "",
    val keyName: String = "",
    val isMix: Boolean = false,
    val timelineDurationSec: Int = 0,
    val isLive: Boolean = false,
    val hasVideo: Boolean = false,
) {
    val durationSec: Int get() = if (isLive) 0 else timelineDurationSec.takeIf { it > 0 } ?: current.durationSec
    val progress: Float get() = if (durationSec == 0) 0f else (positionSec / durationSec).coerceIn(0f, 1f)
    val isCurrentLiked: Boolean get() = likedIds.contains(current.id)
    val hasTrack: Boolean get() = current.id.isNotEmpty()
}

@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AuroraApplication).container

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    val videoPlayer: Player? get() = controller
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var queueFillJob: Job? = null
    private var mediaSearchJob: Job? = null
    private var playbackRequestGeneration = 0L
    private val initialQueueReady = CompletableDeferred<Unit>()
    private var queueToken: String? = null
    private var songById: Map<String, Song> = emptyMap()

    @Volatile private var scrobbleEnabled = true
    @Volatile private var autoplayEnabled = false
    @Volatile private var privateSession = false

    // likes merge server stars + local playlist likes
    private var serverLikedIds: Set<String> = emptySet()
    private var likedPlaylistIds: Set<String> = emptySet()
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // account the live queue belongs to so it persists/restores under the right key
    @Volatile private var playingAccountKey: String = ""
    private var openRestoreAttempted = false
    private var lastPersistMs = 0L
    // clearing for account switch must not overwrite the saved queue
    @Volatile private var suppressPersist = false
    private var playStartMs: Long = 0L
    private var lastDiscordSig: String? = null
    private var lastDiscordPosSec = 0f
    private var lastDiscordWallMs = 0L
    private var lastKeyInfoId: String? = null
    private var lastKeyInfo: com.aurora.music.data.SonicEngine.TrackKey? = null
    @Volatile private var loadingRadio = false

    // bit-perfect quirk: the native flac engine plays audio (and currentPosition advances off engine frames)
    // while exoplayer can still report STATE_BUFFERING because the native-engine loadcontrol blocks loading, so
    // c.isPlaying reads false on a cold connect leaving the bar frozen + a paused icon until a manual pause/play.
    // treat "wants to play and is buffering" as playing so the ui tracks the audible reality.
    private val Player.effectivelyPlaying: Boolean
        get() = isPlaying || (playWhenReady && playbackState == Player.STATE_BUFFERING)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                maybeAutoplay()
                // last track has no transition to honour the end-of-track sleep so do it here
                if (_state.value.sleepEndOfTrack) { controller?.pause(); _state.update { it.copy(sleepEndOfTrack = false) } }
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // reset position so the bar doesn't show the previous track during the gap usb sink lags across a skip
            _state.update { it.copy(positionSec = 0f) }
            if (_state.value.sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _state.update { it.copy(sleepEndOfTrack = false) }
            }
        }
    }

    private fun maybeNowPlaying() {
        val cur = _state.value.current
        if (cur.id.isEmpty() || cur.id == lastNowPlayingId) return
        lastNowPlayingId = cur.id
        // radio/podcasts aren't library tracks never scrobble them
        if (cur.isRadio() || cur.isPodcast() || cur.id.startsWith("aurora-mix:")) return
        playStartMs = System.currentTimeMillis()
        if (!privateSession) { container.lastfm.nowPlaying(cur); container.listenBrainz.nowPlaying(cur) }
    }

    private fun recordIfPlayed(posSec: Float) {
        val cur = _state.value.current
        if (cur.id.isEmpty() || posSec < 30f || cur.id == lastRecordedId) return
        lastRecordedId = cur.id
        // radio/podcasts carry synthetic ids don't record to history server or scrobblers
        if (cur.isRadio() || cur.isPodcast() || cur.id.startsWith("aurora-mix:")) return
        container.playHistory.record(cur, System.currentTimeMillis())
        if (privateSession) return
        if (scrobbleEnabled) viewModelScope.launch { runCatching { container.repository.scrobble(cur.id) } }
        container.lastfm.scrobble(cur, playStartMs)
        container.listenBrainz.scrobble(cur, playStartMs)
    }

    private fun maybeAutoplay() {
        if (!autoplayEnabled || loadingRadio) return
        val c = controller ?: return
        val seed = _state.value.current.id.ifEmpty { return }
        if (seed.startsWith("aurora-mix:")) return
        loadingRadio = true
        viewModelScope.launch {
            val more = runCatching { container.repository.radio(seed) }.getOrDefault(emptyList())
                .filter { it.id !in songById.keys }
            if (more.isNotEmpty()) {
                songById = songById + more.associateBy { it.id }
                c.addMediaItems(more.map { toMediaItem(it) })
                c.play()
                syncFromController()
            }
            loadingRadio = false
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get().also { it.addListener(listener) }
            controller = c
            // service survived but vm is fresh rebuild the domain queue so the queue ui isn't empty
            if (c.mediaItemCount > 0 && songById.isEmpty()) rehydrateFromController()
            syncFromController()
            startTicker()
        }, ContextCompat.getMainExecutor(app))

        // restore a saved queue only if the service came up empty otherwise keep its queue
        viewModelScope.launch {
            container.sessionReady.collect { ready ->
                if (ready != true || openRestoreAttempted) return@collect
                openRestoreAttempted = true
                while (controller == null) delay(50)
                val c = controller ?: return@collect
                playingAccountKey = container.currentAccountKey()
                val saved = playingAccountKey.takeIf { it.isNotBlank() }?.let { container.queueStore.get(it) }
                if (c.mediaItemCount == 0 && saved != null) restoreQueue(saved)
                initialQueueReady.complete(Unit)
            }
        }

        viewModelScope.launch {
            container.sessionReady.collect { ready -> if (ready == true) refreshLikes() }
        }
        // subsonic can't star playlists so locally-liked ones merge into the same set
        viewModelScope.launch {
            container.settingsStore.likedPlaylists.collect { ids ->
                likedPlaylistIds = ids
                recomputeLikes()
            }
        }
        viewModelScope.launch {
            container.settingsStore.privateSession.collect { privateSession = it }
        }
        viewModelScope.launch {
            container.settingsStore.playbackPrefs.collect { p ->
                scrobbleEnabled = p.scrobble
                autoplayEnabled = p.autoplayRadio
                if (_state.value.speed == 1.0f && p.defaultSpeed != 1.0f && _state.value.current.id.isEmpty()) {
                    _state.update { it.copy(speed = p.defaultSpeed) }
                }
            }
        }
        // on account change save outgoing queue first stop then restore incoming epoch only bumps on real transitions
        viewModelScope.launch {
            container.accountEpoch.drop(1).collect {
                persistQueue()
                container.queueStore.requestFlush()
                suppressPersist = true         // clearing below must not wipe what we just saved
                stopPlayback()
                suppressPersist = false
                val key = container.currentAccountKey()
                playingAccountKey = key
                val saved = key.takeIf { it.isNotBlank() }?.let { container.queueStore.get(it) }
                if (saved != null) restoreQueue(saved)
            }
        }
    }

    fun stopPlayback() {
        cancelMediaSearch()
        cancelQueueFill()
        container.mixController.commands.tryEmit(com.aurora.music.mix.MixCommand.Stop)
        controller?.let { c ->
            runCatching { c.pause(); c.stop(); c.clearMediaItems() }
        }
        songById = emptyMap()
        lastRecordedId = null
        lastNowPlayingId = null
        _state.update {
            it.copy(current = EMPTY_SONG, queue = emptyList(), isPlaying = false, positionSec = 0f, currentIndex = 0, expanded = false)
        }
    }

    private fun persistQueue() {
        // The editable mix is persisted by MixStore; do not restore it as an ordinary queue.
        if (container.mixController.activeProject != null) return
        if (suppressPersist) return
        val c = controller ?: return
        val key = playingAccountKey.ifBlank { container.currentAccountKey() }
        if (key.isBlank()) return
        // don't clear on an empty controller it fires at startup before restore and wipes what we're about to restore
        if (c.mediaItemCount == 0 || c.currentMediaItem?.mediaId.orEmpty().startsWith("aurora-mix:")) return
        val songs = (0 until c.mediaItemCount).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
        if (songs.isEmpty()) return
        container.queueStore.save(key, SavedQueue(
            tracks = songs.filterNot { it.id.startsWith("aurora-mix:") }.map { it.toSavedTrack() },
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            positionSec = (c.currentPosition / 1000).toInt().coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 },
        ))
    }

    private fun restoreQueue(sq: SavedQueue) {
        val c = controller ?: return
        val songs = sq.tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }
        if (songs.isEmpty()) return
        songById = songs.associateBy { it.id }
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        val delivery = deliverQueue(songs, idx, sq.positionSec.toLong() * 1000)
        c.playbackParameters = currentParams()
        c.repeatMode = when (sq.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        c.prepare()   // buffer at saved position but stay paused
        _state.update {
            it.copy(queue = delivery.songs, current = songs[idx], positionSec = sq.positionSec.toFloat(), isPlaying = false, currentIndex = delivery.currentIndex, shuffle = sq.shuffle,
                repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF })
        }
        if (sq.shuffle) {
            // queue is already in saved order tell the service shuffle is on and remember that order
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                    putInt("target", 1)
                    putStringArrayList("order", ArrayList(songs.map { it.id }))
                }),
                android.os.Bundle.EMPTY,
            )
        }
    }

    private fun rehydrateFromController() {
        val c = controller ?: return
        val songs = (0 until c.mediaItemCount).map { i ->
            val mi = c.getMediaItemAt(i)
            val md = mi.mediaMetadata
            Song(
                id = mi.mediaId,
                title = md.title?.toString() ?: "",
                artist = md.artist?.toString() ?: "",
                album = md.albumTitle?.toString() ?: "",
                artworkUrl = md.artworkUri?.toString() ?: "",
                durationSec = 0,
                accent = com.aurora.music.util.accentFor(mi.mediaId),
                streamUrl = mi.localConfiguration?.uri?.toString() ?: "",
                replayGainTrack = md.extras?.getFloat("rgTrack", 0f) ?: 0f,
                replayGainAlbum = md.extras?.getFloat("rgAlbum", 0f) ?: 0f,
            )
        }
        songById = songs.associateBy { it.id }
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sendSleepFade(start = false)
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepEndOfTrack = false) }
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            val total = minutes * 60_000L
            delay((total - SLEEP_FADE_MS).coerceAtLeast(0L))
            sendSleepFade(start = true)
            _state.update { it.copy(sleepTimerMinutes = 0) }
        }
    }

    fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        sendSleepFade(start = false)
        _state.update { it.copy(sleepTimerMinutes = 0, sleepEndOfTrack = true) }
    }

    private fun sendSleepFade(start: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP_FADE, android.os.Bundle().apply {
                putInt("fadeMs", if (start) SLEEP_FADE_MS.toInt() else 0)
            }),
            android.os.Bundle.EMPTY,
        )
    }

    fun setPreferredDevice(deviceId: Int) {
        container.preferredAudioDeviceId.value = deviceId
    }

    fun preferredDeviceId(): Int = container.preferredAudioDeviceId.value

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(200)
                val c = controller ?: continue
                if (c.effectivelyPlaying) {
                    val posSec = (c.currentPosition / 1000f).coerceAtLeast(0f)
                    _state.update { it.copy(positionSec = posSec) }
                    maybeNowPlaying()
                    recordIfPlayed(posSec)
                    val now = System.currentTimeMillis()
                    if (now - lastPersistMs > 2000) { lastPersistMs = now; persistQueue() }
                }
            }
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        container.mixController.activeProject?.clips?.let { clips ->
            songById = songById + clips.associate { it.song.id to it.song }
        }
        val mediaId = c.currentMediaItem?.mediaId
        val cur = if (mediaId.orEmpty().startsWith("aurora-mix:")) {
            val metadata = c.currentMediaItem!!.mediaMetadata
            Song(mediaId!!, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(), "",
                metadata.artworkUri?.toString().orEmpty(), (c.duration.coerceAtLeast(0) / 1000).toInt())
        } else mediaId?.let { songById[it] } ?: _state.value.current
        val q = (0 until c.mediaItemCount).mapNotNull { i -> songById[c.getMediaItemAt(i).mediaId] }
        if (cur.id != lastKeyInfoId) { lastKeyInfoId = cur.id; lastKeyInfo = runCatching { container.sonicEngine.keyInfo(cur.id) }.getOrNull() }
        val ki = lastKeyInfo
        _state.update {
            it.copy(
                current = cur,
                timelineDurationSec = (c.duration.coerceAtLeast(0) / 1000).toInt(),
                isLive = c.isCurrentMediaItemLive || cur.isRadio(),
                hasVideo = c.currentTracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO) &&
                    c.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE),
                isMix = container.mixController.activeProject != null,
                isPlaying = c.effectivelyPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                positionSec = (c.currentPosition / 1000f).coerceAtLeast(0f),
                queue = if (q.isNotEmpty()) q else it.queue,
                currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
                bpm = ki?.bpm ?: 0,
                camelot = ki?.camelot ?: "",
                keyName = ki?.name ?: "",
            )
        }
        updateDiscordPresence()
        maybeEnrichLocal(_state.value.current)
        persistQueue()
    }

    // mediastore lacks sample-rate/bit-depth pull them for the playing track via retriever once each
    private val enrichedLocal = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun maybeEnrichLocal(song: Song) {
        if (song.id.isEmpty() || !song.streamUrl.startsWith("content://")) return
        if (song.sampleRateHz > 0) return
        if (!enrichedLocal.add(song.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val mmr = android.media.MediaMetadataRetriever()
            val result = runCatching {
                mmr.setDataSource(getApplication(), Uri.parse(song.streamUrl))
                fun key(k: Int) = mmr.extractMetadata(k)?.toIntOrNull() ?: 0
                val sr = if (android.os.Build.VERSION.SDK_INT >= 31) key(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) else 0
                val bd = if (android.os.Build.VERSION.SDK_INT >= 31) key(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE) else 0
                val br = key(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE) / 1000
                Triple(sr, bd, br)
            }.getOrNull()
            runCatching { mmr.release() }
            val (sr, bd, br) = result ?: return@launch
            if (sr <= 0 && bd <= 0 && br <= 0) return@launch
            fun enrich(s: Song) = s.copy(
                sampleRateHz = if (sr > 0) sr else s.sampleRateHz,
                bitDepth = if (bd > 0) bd else s.bitDepth,
                bitrateKbps = if (s.bitrateKbps > 0) s.bitrateKbps else br,
            )
            songById = songById.mapValues { (id, s) -> if (id == song.id) enrich(s) else s }
            _state.update { st -> if (st.current.id == song.id) st.copy(current = enrich(st.current)) else st }
        }
    }

    private fun updateDiscordPresence() {
        val s = _state.value
        if (s.current.id.isEmpty()) return
        val sig = "${s.current.id}|${s.isPlaying}"
        val now = System.currentTimeMillis()
        val pos = s.positionSec
        // discord animates the bar client-side from timestamps re-push on desync (loop/seek) else it sticks at the end
        val expected = lastDiscordPosSec + if (s.isPlaying) (now - lastDiscordWallMs) / 1000f else 0f
        val desynced = pos < expected - 2f || pos > expected + 2f
        if (sig == lastDiscordSig && !desynced) return
        lastDiscordSig = sig
        lastDiscordPosSec = pos
        lastDiscordWallMs = now
        container.discord.update(s.current, s.isPlaying, pos)
    }

    private fun toMediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(song.streamUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .apply { if (song.artworkUrl.isNotBlank()) setArtworkUri(Uri.parse(song.artworkUrl)) }
                .setExtras(PresetContextPublisher.extras(song, container.repository.playbackSourceIdentity(song)))
                .build()
        )
        .build()

    private fun leaveMixThen(action: () -> Unit): Boolean {
        val c = controller ?: return false
        if (container.mixController.state.value.projectId.isBlank() && !c.currentMediaItem?.mediaId.orEmpty().startsWith("aurora-mix:")) return false
        val generation = playbackRequestGeneration
        val account = container.currentAccountKey()
        val epoch = container.accountEpoch.value
        val future = c.sendCustomCommand(SessionCommand(PlaybackService.CMD_EXIT_MIX, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
        future.addListener({
            viewModelScope.launch {
                repeat(100) {
                    if (generation != playbackRequestGeneration || epoch != container.accountEpoch.value ||
                        account != container.currentAccountKey() || container.sessionReady.value != true) return@launch
                    if (c.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) { action(); return@launch }
                    delay(20)
                }
            }
        }, ContextCompat.getMainExecutor(getApplication()))
        return true
    }

    fun playAll(songs: List<Song>, startIndex: Int = 0, collection: PlaybackCollectionIdentity? = null) {
        cancelMediaSearch()
        if (leaveMixThen { playAll(songs, startIndex, collection) }) return
        val c = controller ?: return
        if (songs.isEmpty()) return
        container.haptic()
        playingAccountKey = container.currentAccountKey()
        val contextual = songs.map { it.copy(playbackCollection = collection) }
        songById = contextual.associateBy { it.id }
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val delivery = deliverQueue(contextual, idx, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        // fresh context plays in order make sure shuffle is off
        sendShuffle(0)
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = contextual[idx], positionSec = 0f, isPlaying = true) }
    }

    fun play(song: Song) = playAll(listOf(song), 0)

    fun playFromSearch(request: MediaSearchRequest) {
        cancelMediaSearch()
        mediaSearchJob = viewModelScope.launch {
            try {
                withTimeout(15_000) {
                    container.sessionReady.first { it == true }
                    initialQueueReady.await()
                    val epoch = container.accountEpoch.value
                    val account = container.currentAccountKey()
                    if (request.kind == MediaSearchRequest.Kind.RESUME) {
                        controller?.takeIf { it.mediaItemCount > 0 }?.let {
                            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
                            if (it.playbackState == Player.STATE_IDLE) it.prepare()
                            it.play()
                        }
                    } else {
                        val songs = request.resolve(container.repository::search, container.repository::collectionTracks,
                            container.repository::allPlaylists)
                        ensureActive()
                        if (container.accountEpoch.value == epoch && container.currentAccountKey() == account &&
                            container.sessionReady.value == true && songs.isNotEmpty()) {
                            mediaSearchJob = null
                            playAll(songs)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(getApplication(), "Couldn't play this search.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cancelMediaSearch() {
        playbackRequestGeneration++
        mediaSearchJob?.cancel()
        mediaSearchJob = null
    }

    fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) {
        cancelMediaSearch()
        if (leaveMixThen { playCollection(kind, id, loaded, startIndex, total) }) return
        val collection = container.repository.playbackCollectionIdentity(kind, id)
        playAll(loaded, startIndex, collection)
        fillQueue(kind, id, loaded, total, shuffle = false, collection = collection)
    }

    fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) {
        cancelMediaSearch()
        if (leaveMixThen { shuffleCollection(kind, id, loaded, total) }) return
        val collection = container.repository.playbackCollectionIdentity(kind, id)
        shufflePlay(loaded, collection)
        fillQueue(kind, id, loaded, total, shuffle = true, collection = collection)
    }

    private data class QueueDelivery(val songs: List<Song>, val currentIndex: Int)

    private fun cancelQueueFill() {
        queueFillJob?.cancel()
        queueFillJob = null
    }

    private fun queueItem(song: Song, token: String): MediaItem {
        val item = toMediaItem(song)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(PlaybackService.QUEUE_TOKEN, token)
        }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    private suspend fun appendQueue(c: MediaController, token: String, expectedCount: Int,
        songs: List<Song>, prepend: Boolean = false, waitForInitialQueue: Boolean = false): Boolean {
        if (controller !== c) return false
        val args = Bundle().apply {
            putString("token", token)
            putInt("count", expectedCount)
            putBoolean("prepend", prepend)
            putParcelableArrayList("items", ArrayList(songs.map { queueItem(it, token).toBundleIncludeLocalConfiguration() }))
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + if (waitForInitialQueue) 2_000 else 0
        while (true) {
            val future = c.sendCustomCommand(SessionCommand(PlaybackService.CMD_QUEUE_APPEND, Bundle.EMPTY), args)
            val result = suspendCancellableCoroutine<androidx.media3.session.SessionResult?> { continuation ->
                future.addListener({
                    if (continuation.isActive) continuation.resume(runCatching { future.get() }.getOrNull())
                }, ContextCompat.getMainExecutor(getApplication()))
                continuation.invokeOnCancellation { future.cancel(false) }
            }
            if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) return true
            val reason = result?.extras?.getString("reason") ?: "session unavailable"
            if (reason != "queue changed" || android.os.SystemClock.elapsedRealtime() >= deadline) {
                android.util.Log.w("AuroraQueue", "Backfill stopped: $reason; expected=$expectedCount; actual=${result?.extras?.getInt("count", -1)}")
                return false
            }
            delay(25)
        }
    }

    // sets a safe initial window (containing startIndex) then backfills the rest of a large in-memory
    // list in chunks, forward first then the songs before startIndex prepended so skip-back still works.
    // returns whatever was actually included in the synchronous setMediaItems call, and where the
    // "current" song landed within it — the controller resets to a local index for the windowed case.
    private fun deliverQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long): QueueDelivery {
        cancelQueueFill()
        val c = controller ?: return QueueDelivery(emptyList(), 0)
        val token = java.util.UUID.randomUUID().toString().also { queueToken = it }
        if (songs.size <= QUEUE_BATCH) {
            c.setMediaItems(songs.map { queueItem(it, token) }, startIndex, startPositionMs)
            return QueueDelivery(songs, startIndex)
        }
        val windowEnd = (startIndex + QUEUE_BATCH).coerceAtMost(songs.size)
        val initial = songs.subList(startIndex, windowEnd)
        c.setMediaItems(initial.map { queueItem(it, token) }, 0, startPositionMs)
        queueFillJob = viewModelScope.launch {
            var delivered = initial.size
            var tail = windowEnd
            while (tail < songs.size) {
                val chunk = songs.subList(tail, (tail + QUEUE_BATCH).coerceAtMost(songs.size))
                if (!appendQueue(c, token, delivered, chunk, waitForInitialQueue = delivered == initial.size)) return@launch
                delivered += chunk.size
                syncFromController()
                tail += chunk.size
                delay(40)
            }
            var head = startIndex
            while (head > 0) {
                val chunkStart = (head - QUEUE_BATCH).coerceAtLeast(0)
                val chunk = songs.subList(chunkStart, head)
                if (!appendQueue(c, token, delivered, chunk, prepend = true, waitForInitialQueue = delivered == initial.size)) return@launch
                delivered += chunk.size
                syncFromController()
                head = chunkStart
                delay(40)
            }
        }
        return QueueDelivery(initial, 0)
    }

    private fun fillQueue(kind: String, id: String, loaded: List<Song>, total: Int, shuffle: Boolean, collection: PlaybackCollectionIdentity?) {
        cancelQueueFill()
        if (loaded.size >= total || total <= 0) return
        queueFillJob = viewModelScope.launch {
            val c = controller ?: return@launch
            val token = queueToken ?: return@launch
            var delivered = c.mediaItemCount
            val initialCount = delivered
            val have = loaded.mapTo(HashSet()) { it.id }
            var offset = loaded.size
            while (offset < total) {
                val page = runCatching { container.repository.detailPage(kind, id, offset) }.getOrDefault(emptyList())
                ensureActive()
                if (page.isEmpty()) break
                val fresh = page.filter { it.id.isNotEmpty() && have.add(it.id) }
                if (fresh.isNotEmpty()) {
                    val toAdd = (if (shuffle) fresh.shuffled() else fresh).map { it.copy(playbackCollection = collection) }
                    for (chunk in toAdd.chunked(QUEUE_BATCH)) {
                        if (!appendQueue(c, token, delivered, chunk, waitForInitialQueue = delivered == initialCount)) return@launch
                        delivered += chunk.size
                        songById = songById + chunk.associateBy { it.id }
                        syncFromController()
                    }
                }
                offset += page.size
                delay(180) // stay clear of spotify rate limit
            }
        }
    }

    fun startSonicRadio(seed: Song = _state.value.current, onResult: (String) -> Unit = {}) {
        cancelMediaSearch()
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        viewModelScope.launch {
            val sonic = runCatching { container.sonicEngine.buildRadio(seed) }.getOrDefault(emptyList())
            if (sonic.size >= 2) {
                playAll(sonic, 0)
                onResult("Sonic radio · ${sonic.size - 1} similar tracks")
            } else {
                val more = runCatching { container.repository.radio(seed.id) }.getOrDefault(emptyList())
                    .filter { it.id != seed.id }
                if (more.isNotEmpty()) {
                    playAll(listOf(seed) + more, 0)
                    onResult("Radio started")
                } else {
                    onResult("Not enough analyzed tracks — run Sonic analysis in Settings")
                }
            }
            loadingRadio = false
        }
    }

    fun startAutoDj(seed: Song = _state.value.current, onResult: (String) -> Unit = {}) {
        cancelMediaSearch()
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        viewModelScope.launch {
            val set = runCatching { container.sonicEngine.buildAutoDj(seed) }.getOrDefault(emptyList())
            if (set.size >= 2) {
                playAll(set, 0)
                onResult("Auto-DJ · ${set.size} tracks, key & tempo matched")
            } else {
                loadingRadio = false
                startSonicRadio(seed, onResult)
                return@launch
            }
            loadingRadio = false
        }
    }

    fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity? = null) {
        cancelMediaSearch()
        if (leaveMixThen { shufflePlay(songs, collection) }) return
        val c = controller ?: return
        if (songs.isEmpty()) return
        playingAccountKey = container.currentAccountKey()
        val contextual = songs.map { it.copy(playbackCollection = collection) }
        songById = contextual.associateBy { it.id }
        val shuffled = contextual.shuffled()
        val delivery = deliverQueue(shuffled, 0, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = shuffled[0], positionSec = 0f, isPlaying = true, shuffle = true) }
        // pass the original order so disabling shuffle restores it
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                putInt("target", 1)
                putStringArrayList("order", ArrayList(songs.map { it.id }))
            }),
            android.os.Bundle.EMPTY,
        )
    }

    fun addToQueue(song: Song) {
        if (leaveMixThen { addToQueue(song) }) return
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        c.addMediaItem(toMediaItem(song))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        syncFromController()
    }

    fun playNext(song: Song) {
        if (leaveMixThen { playNext(song) }) return
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        val idx = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItem(idx, toMediaItem(song))
        syncFromController()
    }

    fun jumpTo(index: Int) {
        cancelMediaSearch()
        val c = controller ?: return
        container.haptic()
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            c.play()
            syncFromController()
        }
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.removeMediaItem(index)
            syncFromController()
        }
    }

    fun clearQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        val last = c.mediaItemCount - 1
        if (last > current) {
            c.removeMediaItems(current + 1, last + 1)
            syncFromController()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != to) {
            c.moveMediaItem(from, to)
            syncFromController()
        }
    }

    // radio/podcasts dropped since the backend can't resolve their ids
    fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit = {}) {
        val title = name.trim()
        if (title.isEmpty()) return
        val ids = _state.value.queue
            .filterNot { it.isRadio() || it.isPodcast() }
            .map { it.id }
            .filter { it.isNotEmpty() }
            .distinct()
        if (ids.isEmpty()) { onResult("Nothing to save"); return }
        viewModelScope.launch {
            val ok = runCatching { container.repository.createPlaylistFromSongs(title, ids) }.getOrDefault(false)
            onResult(if (ok) "Saved “$title”" else "Couldn't save playlist")
        }
    }

    fun togglePlay() {
        cancelMediaSearch()
        val c = controller ?: return
        container.haptic()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(fraction: Float) {
        cancelMediaSearch()
        val c = controller ?: return
        val dur = _state.value.durationSec
        if (dur > 0) c.seekTo((fraction.coerceIn(0f, 1f) * dur * 1000).toLong())
    }

    fun next() {
        cancelMediaSearch()
        container.haptic()
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        cancelMediaSearch()
        val c = controller ?: return
        container.haptic()
        if (c.currentPosition > 4000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun toggleShuffle() = sendShuffle(-1)

    // target 1 on 0 off -1 toggle service physically reorders and restores original order on disable
    private fun sendShuffle(target: Int) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply { putInt("target", target) }),
            android.os.Bundle.EMPTY,
        )
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleLikeCurrent() = toggleLike(_state.value.current.id)

    private fun recomputeLikes() {
        _state.update { it.copy(likedIds = serverLikedIds + likedPlaylistIds) }
    }

    private val likeChecked = java.util.Collections.synchronizedSet(HashSet<String>())

    fun refreshLikes() {
        viewModelScope.launch {
            serverLikedIds = runCatching { container.repository.starredIds() }.getOrDefault(serverLikedIds)
            recomputeLikes()
        }
    }

    fun checkLiked(ids: List<String>) {
        val toCheck = ids.filter { it.isNotEmpty() && it !in serverLikedIds && it !in likeChecked }.distinct()
        if (toCheck.isEmpty()) return
        viewModelScope.launch {
            val liked = runCatching { container.repository.likedSongIds(toCheck) }.getOrNull() ?: return@launch
            likeChecked.addAll(toCheck)
            if (liked.isNotEmpty()) {
                serverLikedIds = serverLikedIds + liked
                recomputeLikes()
            }
        }
    }

    // song/album/artist persist to the server playlist persists locally
    fun toggleLike(id: String, kind: String = "song") {
        if (id.startsWith("aurora-mix:")) return
        if (id.isEmpty()) return
        val nowLiked = !_state.value.likedIds.contains(id)
        if (kind == "playlist") {
            likedPlaylistIds = if (nowLiked) likedPlaylistIds + id else likedPlaylistIds - id
            recomputeLikes()
            viewModelScope.launch { runCatching { container.settingsStore.setPlaylistLiked(id, nowLiked) } }
            // also sync to backend no-op for backends that can't star playlists
            viewModelScope.launch { runCatching { container.repository.setStarred(id, nowLiked, "playlist") } }
        } else {
            serverLikedIds = if (nowLiked) serverLikedIds + id else serverLikedIds - id
            recomputeLikes()
            viewModelScope.launch { runCatching { container.repository.setStarred(id, nowLiked, kind) } }
        }
    }

    fun setExpanded(value: Boolean) = _state.update { it.copy(expanded = value) }

    fun setSpeed(value: Float) {
        val snapped = (Math.round(value / 0.05f) * 0.05f).coerceIn(0.5f, 2.0f)
        _state.update { it.copy(speed = snapped) }
        controller?.playbackParameters = currentParams()
    }

    fun setPitch(value: Float) {
        _state.update { it.copy(pitch = value.coerceIn(-6f, 6f)) }
        controller?.playbackParameters = currentParams()
    }

    fun setMatchPitch(match: Boolean) {
        _state.update { it.copy(matchPitch = match) }
        controller?.playbackParameters = currentParams()
    }

    fun resetSpeedPitch() {
        _state.update { it.copy(speed = 1.0f, pitch = 0.0f) }
        controller?.playbackParameters = currentParams()
    }

    private fun currentParams(): PlaybackParameters {
        val s = _state.value
        // match pitch to speed = no time-stretch else preserve/shift pitch
        val pitchRatio = if (s.matchPitch) s.speed else 2f.pow(s.pitch / 12f)
        return PlaybackParameters(s.speed, pitchRatio)
    }

    override fun onCleared() {
        persistQueue()
        container.queueStore.requestFlush()
        ticker?.cancel()
        queueFillJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    private companion object {
        const val SLEEP_FADE_MS = 6_000L
        // each MediaItem's parcelled metadata (artwork uri, title/artist/album, extras) is a few KB;
        // a binder transaction caps near 1mb, so a few hundred full items can silently get truncated
        // by the session. deliverQueue keeps the initial setMediaItems call well under that.
        const val QUEUE_BATCH = 120
    }
}
