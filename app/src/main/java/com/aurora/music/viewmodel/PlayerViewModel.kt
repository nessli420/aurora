package com.aurora.music.viewmodel

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SavedQueue
import com.aurora.music.data.isPodcast
import com.aurora.music.ai.isDjSpeech
import com.aurora.music.data.isRadio
import com.aurora.music.data.toSavedTrack
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackService
import com.aurora.music.playback.MediaSearchRequest
import com.aurora.music.data.PlaybackCollectionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import com.aurora.music.playback.network.problem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.pow

enum class RepeatMode { OFF, ALL, ONE }

private val EMPTY_SONG = Song("", appString(R.string.text_nothing_playing_13ae37), "", "", "", 0)

data class PlayerUiState(
    val current: Song = EMPTY_SONG,
    val queue: List<Song> = emptyList(),
    val isPlaying: Boolean = false,
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
    val videoLoading: Boolean = false,
    val videoQualityHeight: Int? = null,
    val canSelectVideoQuality: Boolean = false,
    val videoIsCanvas: Boolean = false,
    val supportsCanvas: Boolean = false,
    val supportsComments: Boolean = false,
    val canPostComments: Boolean = false,
    val commentsAvailable: Boolean = false,
    val commentsEnabled: Boolean = true,
    val comments: List<com.aurora.music.model.TrackComment> = emptyList(),
    val supportsMusicVideo: Boolean = true,
    val longFormSkipButtons: Boolean = true,
    // where autoplay's picks start among the songs still to come, or -1
    val autoplayFrom: Int = -1,
    // shuffle's second tap: autoplay picks mixed in among the shuffled songs, marked in the queue
    val smartShuffle: Boolean = false,
    val smartPicks: Set<String> = emptySet(),
    val remote: Boolean = false,
) {
    val durationSec: Int get() = if (isLive) 0 else timelineDurationSec.takeIf { it > 0 } ?: current.durationSec
    val skipButtons: Boolean get() = longFormSkipButtons && !isMix && !isLive &&
        com.aurora.music.data.LongForm.skips(current.id, maxOf(current.durationSec, durationSec), current.genre)
    fun progressAt(positionSec: Float): Float = if (durationSec == 0) 0f else (positionSec / durationSec).coerceIn(0f, 1f)
    val isCurrentLiked: Boolean get() = likedIds.contains(current.id)
    val hasTrack: Boolean get() = current.id.isNotEmpty()
    // controlling another device keeps the bar, so there is always a way back
    val showsBar: Boolean get() = hasTrack || remote
}

@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AuroraApplication).container

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    // position ticks live apart from the main state so they don't recompose the whole app shell
    private val _position = MutableStateFlow(0f)
    val position: StateFlow<Float> = _position.asStateFlow()
    private val activelyPlaying = MutableStateFlow(false)
    private val restoreRush = MutableStateFlow(false)

    private var controller: MediaController? = null
    private val companionVideo = CompanionVideoController(
        app = app,
        scope = viewModelScope,
        resolver = container.youtubeResolver,
        audioPlayer = { controller },
        currentSong = { _state.value.current },
        canvasFor = { container.repository.canvasUrl(it) },
        onStateChanged = { video ->
            _state.update { it.copy(
                hasVideo = video.ready,
                videoLoading = video.loading,
                canSelectVideoQuality = video.ready && !video.canvas,
                videoQualityHeight = video.qualityHeight,
                videoIsCanvas = video.canvas,
            ) }
        },
    )
    val videoPlayer: Player? get() = companionVideo.player ?: controller
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var queueFillJob: Job? = null
    private var restoreFill: Job? = null
    private var mediaSearchJob: Job? = null
    private var playbackRequestGeneration = 0L
    private val initialQueueReady = CompletableDeferred<Unit>()
    private var queueToken: String? = null
    private var songById: Map<String, Song> = emptyMap()
    private val remoteSession get() = container.networkOutput.session.value

    @Volatile private var autoplayEnabled = true
    // songs autoplay queued: they sit after the user's own, and turning autoplay off takes them back out
    private var autoplayIds: Set<String> = emptySet()
    private var autoplayJob: Job? = null
    // the last song a top-up ran for, so a seed with no radio isn't asked again on every sync
    private var autoplayTriedFor: String? = null
    // shuffle's second tap: autoplay picks mixed in among your shuffled songs; the next tap takes them back out
    private var smartShuffle = false
    private var smartIds: Set<String> = emptySet()
    private var smartJob: Job? = null
    // a new queue under shuffle with autoplay still wants its picks
    private var smartPending = false
    // bumped with every new queue, so picks fetched for an old one are dropped
    private var smartContext = 0L
    private var syncedShuffle = false
    @Volatile private var privateSession = false

    private val likes = com.aurora.music.data.Likes(viewModelScope, container.repository, container.settingsStore::setPlaylistLiked)
    @Volatile private var mergeFeatures = com.aurora.music.data.MergeFeatures()
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // account the live queue belongs to so it persists/restores under the right key
    @Volatile private var playingAccountKey: String = ""
    private var openRestoreAttempted = false
    private var lastPersistMs = 0L
    private var savedTracks: List<com.aurora.music.data.SavedTrack> = emptyList()
    private var savedTracksFor: Map<String, Song>? = null
    private var savedTracksCount = -1
    private var savedTracksDirty = true
    private var queueSongs: List<Song> = emptyList()
    private var queueSongsTimeline: androidx.media3.common.Timeline? = null
    private var queueSongsFor: Map<String, Song>? = null
    private var lastSavedQueue: Pair<String, SavedQueue>? = null
    // clearing for account switch must not overwrite the saved queue
    @Volatile private var suppressPersist = false
    private var playStartMs: Long = 0L
    private var lastKeyInfoId: String? = null
    private var lastKeyInfo: com.aurora.music.data.SonicEngine.TrackKey? = null
    @Volatile private var loadingRadio = false

    val aiDj get() = container.aiDjMode

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) savedTracksDirty = true
            // locally new ids only arrive with a timeline change
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) && remoteSession != null)) fillFromController()
            syncFromController()
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) companionVideo.sync(forceSeek = true)
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                maybeAutoplay()
                // last track has no transition to honour the end-of-track sleep so do it here
                if (_state.value.sleepEndOfTrack) { controller?.pause(); _state.update { it.copy(sleepEndOfTrack = false) } }
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // reset position so the bar doesn't show the previous track during the gap usb sink lags across a skip
            _position.value = 0f
            if (_state.value.sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _state.update { it.copy(sleepEndOfTrack = false) }
            }
        }
    }

    private fun maybeNowPlaying() {
        if (remoteSession != null) return
        val cur = _state.value.current
        if (cur.id.isEmpty() || cur.id == lastNowPlayingId) return
        lastNowPlayingId = cur.id
        // radio/podcasts aren't library tracks never scrobble them
        if (cur.isRadio() || cur.isPodcast() || cur.id.startsWith("aurora-mix:")) return
        playStartMs = System.currentTimeMillis()
        if (!privateSession) { container.lastfm.nowPlaying(cur); container.listenBrainz.nowPlaying(cur) }
    }

    private fun recordIfPlayed(posSec: Float) {
        if (remoteSession != null) return
        val cur = _state.value.current
        if (cur.id.isEmpty() || posSec < 30f || cur.id == lastRecordedId) return
        lastRecordedId = cur.id
        // radio/podcasts carry synthetic ids don't record to history server or scrobblers
        if (cur.isRadio() || cur.isPodcast() || cur.id.startsWith("aurora-mix:")) return
        if (privateSession) return
        container.lastfm.scrobble(cur, playStartMs)
        container.listenBrainz.scrobble(cur, playStartMs)
    }

    // where the songs autoplay queued start in what's still to come, or -1
    private fun autoplayFrom(c: Player): Int {
        if (autoplayIds.isEmpty()) return -1
        return (c.currentMediaItemIndex + 1 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId in autoplayIds } ?: -1
    }

    // once the last song in the queue starts, more like what has been playing follows it, so the music never stops;
    // shuffle reorders the queue itself, so the last item is the last to play
    private fun maybeAutoplay() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (!autoplayEnabled || c.repeatMode != Player.REPEAT_MODE_OFF || index < 0 || index != count - 1 || c.isCurrentMediaItemLive) return
        val last = c.getMediaItemAt(index).mediaId
        val tried = "$last#$count"
        if (tried == autoplayTriedFor || autoplayJob?.isActive == true || queueFillJob?.isActive == true || restoreFill?.isActive == true) return
        if (loadingRadio || remoteSession != null || aiDj.state.value.active || _state.value.isMix) return
        val songs = (0 until count).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
        if (songs.lastOrNull()?.let(com.aurora.music.data.Autoplay::follows) != true) return
        autoplayTriedFor = tried
        val seeds = com.aurora.music.data.Autoplay.seeds(songs).ifEmpty { return }
        autoplayJob = viewModelScope.launch {
            val more = com.aurora.music.data.Autoplay.next(seeds, songs, hides = container.repository::isHidden,
                radio = { container.repository.radio(it.id) },
                // the sonic list leads with the seed itself
                fallback = { seed -> withContext(Dispatchers.IO) { container.sonicEngine.buildRadio(seed).drop(1) } })
            // a new queue or an edit meanwhile means these were picked for a queue that is gone
            val now = controller ?: return@launch
            if (more.isEmpty() || now.mediaItemCount != count || now.getMediaItemAt(count - 1).mediaId != last) return@launch
            val ended = now.playbackState == Player.STATE_ENDED
            songById = songById + more.associateBy { it.id }
            autoplayIds = autoplayIds + more.map { it.id }
            now.addMediaItems(more.map { toMediaItem(it) })
            // the last song finished before the picks arrived, so carry on into them
            if (ended) { now.seekTo(count, 0); now.play() }
            syncFromController()
        }
    }

    // shuffle with autoplay: picks like the queue's songs go in after every few of yours, ahead of time
    private fun maybeSmart() {
        val c = controller ?: return
        if (!smartPending || !smartShuffle || !c.shuffleModeEnabled || smartJob?.isActive == true) return
        // a queue still paging in gets its picks once it's whole, so they spread over all of it
        if (queueFillJob?.isActive == true || restoreFill?.isActive == true) return
        if (remoteSession != null || aiDj.state.value.active || _state.value.isMix) return
        val index = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (index < 0) return
        smartPending = false
        val context = smartContext
        val songs = (0 until count).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
        val current = songById[c.getMediaItemAt(index).mediaId] ?: return
        val upcoming = (index + 1 until count).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
        val limit = com.aurora.music.data.Autoplay.smartCount(count - index - 1).takeIf { it > 0 } ?: return
        // the playing song first, then what's coming, one per artist
        val seeds = com.aurora.music.data.Autoplay.seeds((listOf(current) + upcoming).asReversed(), com.aurora.music.data.Autoplay.SMART_SEEDS)
            .ifEmpty { return }
        smartJob = viewModelScope.launch {
            val picks = com.aurora.music.data.Autoplay.next(seeds, songs, hides = container.repository::isHidden,
                radio = { container.repository.radio(it.id) },
                fallback = { seed -> withContext(Dispatchers.IO) { container.sonicEngine.buildRadio(seed).drop(1) } },
                limit = limit)
            val now = controller ?: return@launch
            if (picks.isEmpty() || context != smartContext || !smartShuffle || !now.shuffleModeEnabled) return@launch
            val start = now.currentMediaItemIndex + 1
            val ahead = (start until now.mediaItemCount).map(now::getMediaItemAt)
            val merged = com.aurora.music.data.Autoplay.interleave(ahead, picks.map { toMediaItem(it) })
            // the songs before the first pick stay as they are, so the next one keeps its buffer
            val keep = minOf(com.aurora.music.data.Autoplay.SMART_STRIDE, ahead.size)
            songById = songById + picks.associateBy { it.id }
            smartIds = smartIds + picks.map { it.id }
            now.replaceMediaItems(start + keep, now.mediaItemCount, merged.drop(keep))
            syncFromController()
        }
    }

    // puts autoplay's picks back after your own songs once shuffle has mixed them in, in the order they came
    private fun regroupAutoplay(c: Player): Boolean {
        val start = c.currentMediaItemIndex + 1
        if (start <= 0 || start >= c.mediaItemCount) return false
        val ahead = (start until c.mediaItemCount).map(c::getMediaItemAt)
        // autoplay's set keeps the order its picks came in
        val rank = autoplayIds.withIndex().associate { (i, id) -> id to i }
        val (picks, yours) = ahead.partition { it.mediaId in rank }
        val grouped = yours + picks.sortedBy { rank.getValue(it.mediaId) }
        if (grouped.map { it.mediaId } == ahead.map { it.mediaId }) return false
        c.replaceMediaItems(start, c.mediaItemCount, grouped)
        return true
    }

    // takes the picks shuffle mixed in back out of what's still to come
    private fun dropSmart() {
        smartJob?.cancel()
        smartPending = false
        val ids = smartIds
        smartIds = emptySet()
        val c = controller ?: return
        if (ids.isEmpty()) return
        (c.currentMediaItemIndex + 1 until c.mediaItemCount).filter { c.getMediaItemAt(it).mediaId in ids }
            .asReversed().forEach { c.removeMediaItem(it) }
    }

    // a new queue keeps shuffle with autoplay on and gets picks of its own
    private fun newSmartContext() {
        smartJob?.cancel()
        smartContext++
        smartIds = emptySet()
        smartPending = smartShuffle
    }

    // takes autoplay's upcoming songs back out, last first so the indices ahead stay put
    private fun dropAutoplay() {
        autoplayJob?.cancel()
        val ids = autoplayIds
        autoplayIds = emptySet()
        val c = controller ?: return
        (c.currentMediaItemIndex + 1 until c.mediaItemCount).filter { c.getMediaItemAt(it).mediaId in ids }
            .asReversed().forEach { c.removeMediaItem(it) }
        syncFromController()
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

        viewModelScope.launch {
            container.networkOutput.session.collectLatest { session ->
                if (session == null) { syncFromController(); return@collectLatest }
                kotlinx.coroutines.flow.combine(session.status, session.error, session.connected) { status, _, _ -> status }.collect { status ->
                    status?.let(::syncRemote)
                    if (status?.current == null) syncFromController()
                }
            }
        }

        // restore a saved queue only if the service came up empty otherwise keep its queue
        viewModelScope.launch {
            container.sessionReady.collect { ready ->
                if (ready != true || openRestoreAttempted) return@collect
                openRestoreAttempted = true
                while (controller == null) delay(50)
                val c = controller ?: return@collect
                val key = container.currentAccountKey()
                playingAccountKey = key
                val saved = key.takeIf { it.isNotBlank() }?.let { kotlinx.coroutines.withContext(Dispatchers.IO) { container.queueStore.get(it) } }
                // an account switch during the read must not leave this queue under the new key
                if (playingAccountKey == key && c.mediaItemCount == 0 && saved != null && remoteSession == null) restoreQueue(saved)
                initialQueueReady.complete(Unit)
            }
        }

        viewModelScope.launch {
            container.sessionReady.collect { ready -> if (ready == true) refreshLikes() }
        }
        viewModelScope.launch { likes.likedIds.collect { ids -> _state.update { it.copy(likedIds = ids) } } }
        viewModelScope.launch { likes.failures.collect { android.widget.Toast.makeText(getApplication(), appString(R.string.like_failed), android.widget.Toast.LENGTH_SHORT).show() } }
        // the playing song's heart counts likes on every account, found by recording
        viewModelScope.launch {
            _state.map { it.current }.distinctUntilChanged { a, b -> a.id == b.id && a.title == b.title && a.artist == b.artist }
                .collect { if (it.id.isNotEmpty()) likes.check(listOf(it)) }
        }
        // merge options apply to the song on screen right away
        viewModelScope.launch {
            container.settingsStore.mergeFeatures.collect {
                mergeFeatures = it
                commentsFor = null
                syncFromController()
            }
        }
        // a song downloaded after likes were loaded still belongs in the offline liked songs
        viewModelScope.launch {
            container.downloadManager.downloads.collect { downloads ->
                val liked = likes.likedIds.value
                container.repository.markDownloadsLiked(downloads.values.filter { it.liked != true && it.id in liked }.map { it.id }, true)
            }
        }
        // subsonic can't star playlists so locally-liked ones merge into the same set
        viewModelScope.launch { container.settingsStore.likedPlaylists.collect(likes::setLikedPlaylists) }
        viewModelScope.launch {
            container.settingsStore.privateSession.collect { privateSession = it }
        }
        viewModelScope.launch {
            container.settingsStore.playbackPrefs.collect { p ->
                if (autoplayEnabled && !p.autoplayRadio) dropAutoplay()
                autoplayEnabled = p.autoplayRadio
                _state.update { it.copy(longFormSkipButtons = p.longFormSkipButtons) }
                if (_state.value.speed == 1.0f && p.defaultSpeed != 1.0f && _state.value.current.id.isEmpty()) {
                    _state.update { it.copy(speed = p.defaultSpeed) }
                }
            }
        }
        // on account change save outgoing queue first stop then restore incoming epoch only bumps on real transitions
        viewModelScope.launch {
            container.accountEpoch.drop(1).collect {
                // likes belong to the account and session readiness doesn't change on a switch, so reload them here
                likes.reset()
                persistQueue(force = true)
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
        if (remoteSession != null) { container.networkOutput.control(null); return }
        companionVideo.clear()
        cancelMediaSearch()
        cancelQueueFill()
        container.mixController.commands.tryEmit(com.aurora.music.mix.MixCommand.Stop)
        controller?.let { c ->
            runCatching { c.pause(); c.stop(); c.clearMediaItems() }
        }
        songById = emptyMap()
        autoplayJob?.cancel()
        autoplayIds = emptySet()
        newSmartContext()
        smartPending = false
        lastRecordedId = null
        lastNowPlayingId = null
        _state.update {
            it.copy(current = EMPTY_SONG, queue = emptyList(), isPlaying = false, currentIndex = 0, expanded = false)
        }
        _position.value = 0f
    }

    private fun persistQueue(force: Boolean = false) {
        // The editable mix is persisted by MixStore; do not restore it as an ordinary queue.
        if (container.mixController.activeProject != null) return
        // a restore still filling in holds only a window of the queue the store already has
        if (suppressPersist || remoteSession != null || restoreFill?.isActive == true) return
        val c = controller ?: return
        val key = playingAccountKey.ifBlank { container.currentAccountKey() }
        if (key.isBlank()) return
        // don't clear on an empty controller it fires at startup before restore and wipes what we're about to restore
        if (c.mediaItemCount == 0 || c.currentMediaItem?.mediaId.orEmpty().startsWith("aurora-mix:")) return
        // the track list only gets rebuilt when the timeline or the song map changed
        val tracks = savedTracks.takeIf { !savedTracksDirty && savedTracksFor === songById && savedTracksCount == c.mediaItemCount }
            ?: (0 until c.mediaItemCount).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
                .filterNot { it.id.startsWith("aurora-mix:") }.map { it.toSavedTrack() }
                .also { savedTracks = it; savedTracksFor = songById; savedTracksCount = c.mediaItemCount; savedTracksDirty = false }
        if (tracks.isEmpty()) return
        val queue = SavedQueue(
            tracks = tracks,
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            positionSec = (c.currentPosition / 1000).toInt().coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 },
            autoplay = autoplayIds.takeIf { it.isNotEmpty() }?.let { ids -> tracks.mapNotNull { it.id?.takeIf(ids::contains) } },
            smartShuffle = (smartShuffle && c.shuffleModeEnabled).takeIf { it },
            smartPicks = smartIds.takeIf { it.isNotEmpty() }?.let { ids -> tracks.mapNotNull { it.id?.takeIf(ids::contains) } },
        )
        val last = lastSavedQueue
        val now = System.currentTimeMillis()
        if (!force && last != null && last.first == key && last.second.copy(positionSec = queue.positionSec) == queue) {
            // a moving position alone is saved every few seconds while playing and exactly on pause
            if (last.second.positionSec == queue.positionSec) return
            if (c.effectivelyPlaying && now - lastPersistMs < POSITION_PERSIST_MS) return
        }
        lastPersistMs = now
        lastSavedQueue = key to queue
        container.queueStore.save(key, queue)
    }

    private fun restoreQueue(sq: SavedQueue) {
        val c = controller ?: return
        val songs = sq.tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }
        if (songs.isEmpty()) return
        songById = songs.associateBy { it.id }
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        val delivery = deliverQueue(songs, idx, sq.positionSec.toLong() * 1000, restoring = true)
        autoplayIds = sq.autoplay.orEmpty().toSet()
        smartShuffle = sq.shuffle && sq.smartShuffle == true
        smartIds = sq.smartPicks.orEmpty().toSet()
        smartPending = false
        c.playbackParameters = currentParams()
        c.repeatMode = when (sq.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        // buffer at the saved position but stay paused, once launch is over; a play tap prepares sooner
        viewModelScope.launch {
            delay(RESTORE_DEFER_MS)
            if (controller === c && c.mediaItemCount > 0 && c.playbackState == Player.STATE_IDLE && c.playerError == null) c.prepare()
        }
        _state.update {
            it.copy(queue = delivery.songs, current = songs[idx], isPlaying = false, currentIndex = delivery.currentIndex, shuffle = sq.shuffle,
                repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF })
        }
        _position.value = sq.positionSec.toFloat()
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
        // the saved copy keeps the format details a media item drops
        val savedQueue = container.queueStore.get(playingAccountKey.ifBlank { container.currentAccountKey() })
        val saved = savedQueue?.tracks?.associateBy { it.id }
        // a recreated screen still knows which of the playing queue's songs autoplay added
        if (autoplayIds.isEmpty()) autoplayIds = savedQueue?.autoplay.orEmpty().toSet()
        if (smartIds.isEmpty() && savedQueue?.smartShuffle == true && c.shuffleModeEnabled) {
            smartShuffle = true
            smartIds = savedQueue.smartPicks.orEmpty().toSet()
        }
        songById = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            aiDj.known(item.mediaId) ?: saved?.get(item.mediaId)?.toSong() ?: songOf(item)
        }.associateBy { it.id }
    }

    // another device's queue arrives as bare media items, so build songs from them
    private fun fillFromController() {
        val c = controller ?: return
        val replace = remoteSession != null
        val missing = (0 until c.mediaItemCount).map(c::getMediaItemAt).filter { replace || it.mediaId !in songById }
        if (missing.isNotEmpty()) songById = songById + missing.associate { it.mediaId to (aiDj.known(it.mediaId) ?: songOf(it)) }
    }

    private fun songOf(mi: MediaItem): Song {
        val md = mi.mediaMetadata
        return Song(
            id = mi.mediaId,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            artworkUrl = md.artworkUri?.toString() ?: "",
            durationSec = md.extras?.getInt("aurora.durationSec") ?: 0,
            accentArgb = com.aurora.music.util.accentArgbFor(mi.mediaId),
            streamUrl = md.extras?.getString("aurora.preferred.original") ?: mi.localConfiguration?.uri?.toString() ?: "",
            albumId = md.extras?.getString("aurora.albumId").orEmpty(),
            artistId = md.extras?.getString("aurora.artistId").orEmpty(),
            replayGainTrack = md.extras?.getFloat("rgTrack", 0f) ?: 0f,
            replayGainAlbum = md.extras?.getFloat("rgAlbum", 0f) ?: 0f,
        )
    }

    private fun remoteIdle(session: com.aurora.music.playback.network.endpoint.ConnectSession) = EMPTY_SONG.copy(title = session.name,
        artist = session.problem()
            ?: appString(if (session.connected.value) R.string.connect_idle_empty else R.string.connect_idle_connecting))

    private fun syncRemote(remote: com.aurora.music.playback.network.endpoint.ConnectStatus) {
        val id = remote.current?.id ?: return
        _state.update { it.copy(speed = remote.speed, sleepTimerMinutes = remote.sleepMinutes, sleepEndOfTrack = remote.sleepEndOfTrack) }
        likes.remote(id, remote.liked)
    }

    fun setSleepTimer(minutes: Int) {
        if (remoteSession != null) { container.networkOutput.command(com.aurora.music.playback.network.endpoint.ConnectCommand.Sleep(minutes, false)); return }
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
        if (remoteSession != null) { container.networkOutput.command(com.aurora.music.playback.network.endpoint.ConnectCommand.Sleep(0, true)); return }
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

    // sleeps while paused and slows to 1 s when no screen is showing the position
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            activelyPlaying.collectLatest { playing ->
                companionVideo.sync()
                if (!playing) return@collectLatest
                while (true) {
                    delay(if (_position.subscriptionCount.value > 0) 200 else 1000)
                    val c = controller ?: continue
                    companionVideo.sync()
                    if (!c.effectivelyPlaying) continue
                    val posSec = (c.currentPosition / 1000f).coerceAtLeast(0f)
                    _position.value = posSec
                    maybeNowPlaying()
                    recordIfPlayed(posSec)
                    persistQueue()
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
        val queued = if (mediaId.orEmpty().startsWith("aurora-mix:")) {
            val metadata = c.currentMediaItem!!.mediaMetadata
            Song(mediaId!!, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(), "",
                metadata.artworkUri?.toString().orEmpty(), (c.duration.coerceAtLeast(0) / 1000).toInt())
        } else mediaId?.let { songById[it] } ?: remoteSession?.let(::remoteIdle) ?: _state.value.current
        val cur = com.aurora.music.playback.PreferredPlayback.applyTo(queued, c.currentMediaItem)
        companionVideo.clearIfSongChanged(cur.id)
        val sourceType = container.repository.sourceType(cur)
        loadComments(cur)
        val videoUri = c.currentMediaItem?.localConfiguration?.uri
        // the queue only gets rebuilt when the timeline or the song map changed
        val timeline = c.currentTimeline
        val q = queueSongs.takeIf { timeline === queueSongsTimeline && songById === queueSongsFor }
            ?: (0 until c.mediaItemCount).mapNotNull { i -> songById[c.getMediaItemAt(i).mediaId] }
                .also { queueSongs = it; queueSongsTimeline = timeline; queueSongsFor = songById }
        if (cur.id != lastKeyInfoId) {
            val id = cur.id
            lastKeyInfoId = id
            lastKeyInfo = null
            // the sonic store can block on its first parse
            viewModelScope.launch {
                val info = withContext(Dispatchers.IO) { runCatching { container.sonicEngine.keyInfo(id) }.getOrNull() } ?: return@launch
                if (lastKeyInfoId != id) return@launch
                lastKeyInfo = info
                _state.update { it.copy(bpm = info.bpm, camelot = info.camelot, keyName = info.name) }
            }
        }
        val ki = lastKeyInfo
        _state.update {
            it.copy(
                current = cur,
                timelineDurationSec = (c.duration.coerceAtLeast(0) / 1000).toInt(),
                isLive = c.isCurrentMediaItemLive || (cur.isRadio() && !cur.isDjSpeech()),
                hasVideo = (c.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO) &&
                    c.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) || companionVideo.isReady,
                canSelectVideoQuality = if (companionVideo.isReady) !companionVideo.isCanvas
                    else videoUri?.scheme == "aurora-yt" && videoUri.host == "video",
                videoQualityHeight = if (companionVideo.isReady) companionVideo.qualityHeight.takeIf { !companionVideo.isCanvas }
                    else videoUri?.getQueryParameter("quality")?.toIntOrNull(),
                videoIsCanvas = companionVideo.isCanvas,
                supportsCanvas = container.repository.supportsCanvas(cur) && mergeFeatures.canvasFor(sourceType),
                supportsMusicVideo = mergeFeatures.videosFor(sourceType),
                isMix = container.mixController.activeProject != null,
                isPlaying = c.effectivelyPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                queue = if (q.isNotEmpty() || remoteSession != null) q else it.queue,
                remote = remoteSession != null,
                currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
                autoplayFrom = autoplayFrom(c),
                smartShuffle = smartShuffle && c.shuffleModeEnabled,
                smartPicks = smartIds,
                bpm = ki?.bpm ?: 0,
                camelot = ki?.camelot ?: "",
                keyName = ki?.name ?: "",
            )
        }
        _position.value = (c.currentPosition / 1000f).coerceAtLeast(0f)
        activelyPlaying.value = c.effectivelyPlaying
        maybeEnrichLocal(_state.value.current)
        maybeFillAlbum(_state.value.current)
        persistQueue()
        // shuffle went off some other way (a new queue in order, the notification), so its picks go too
        // (only on the change itself: a restored queue is briefly in order until the service shuffles it again)
        val shuffled = c.shuffleModeEnabled
        if (smartShuffle && syncedShuffle && !shuffled && remoteSession == null) { smartShuffle = false; dropSmart() }
        val shuffledNow = shuffled && !syncedShuffle
        syncedShuffle = shuffled
        // shuffle mixes only your songs; autoplay's picks stay after them
        // regrouping syncs again with the new order, which is the one to fill
        if (shuffledNow && remoteSession == null && autoplayIds.isNotEmpty() && regroupAutoplay(c)) return
        maybeSmart()
        maybeAutoplay()
    }

    private val albumLookups = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun maybeFillAlbum(song: Song) {
        if (song.id.isEmpty() || song.album.isNotBlank() || song.streamUrl.isBlank() || song.isRadio() || song.isPodcast()) return
        if (song.id.startsWith("aurora-mix:") || !albumLookups.add(song.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching { container.repository.songFor(song.id) }.getOrNull()
                ?.takeIf { it.album.isNotBlank() } ?: return@launch
            fun fill(s: Song) = s.copy(album = found.album, albumId = s.albumId.ifBlank { found.albumId })
            songById = songById.mapValues { (id, s) -> if (id == song.id) fill(s) else s }
            _state.update { st -> st.copy(current = if (st.current.id == song.id) fill(st.current) else st.current,
                queue = st.queue.map { if (it.id == song.id) fill(it) else it }) }
        }
    }

    fun requestMusicVideo(canvas: Boolean, onResult: (String) -> Unit) {
        companionVideo.request(_state.value.current, canvas) {
            onResult(appString(if (canvas) R.string.canvas_not_found else R.string.video_match_not_found))
        }
    }

    fun setMusicVideoVisible(visible: Boolean) {
        companionVideo.setVisible(visible)
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

    private fun toMediaItem(song: Song): MediaItem = com.aurora.music.playback.songMediaItem(song, container.repository)

    // an edit while a restored queue fills in waits for it, else the backfill's count check drops the rest
    private fun afterRestoreFill(action: (shift: Int) -> Unit): Boolean {
        val fill = restoreFill?.takeIf { it.isActive } ?: return false
        val c = controller ?: return false
        val before = c.currentMediaItemIndex
        restoreRush.value = true
        viewModelScope.launch { fill.join(); if (controller === c) action(c.currentMediaItemIndex - before) }
        return true
    }

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
        val picked = songs.getOrNull(startIndex)
        // a lone song is an explicit pick and plays even when hidden
        val kept = if (songs.size > 1) container.repository.visible(songs) else songs
        if (kept.size != songs.size) return playAll(kept, kept.indexOfFirst { it === picked }.coerceAtLeast(0), collection)
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
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = contextual[idx], isPlaying = true) }
        _position.value = 0f
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
                android.widget.Toast.makeText(getApplication(), appString(R.string.text_couldn_t_play_this_search_a68a3a), android.widget.Toast.LENGTH_SHORT).show()
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
        val picked = loaded.getOrNull(startIndex)
        val songs = container.repository.playable(kind, id, loaded, picked)
        playAll(songs, songs.indexOfFirst { it === picked }.coerceAtLeast(0), collection)
        fillQueue(kind, id, loaded, total, shuffle = false, collection = collection)
    }

    fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) {
        cancelMediaSearch()
        if (leaveMixThen { shuffleCollection(kind, id, loaded, total) }) return
        val collection = container.repository.playbackCollectionIdentity(kind, id)
        shufflePlay(container.repository.playable(kind, id, loaded), collection)
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
    private fun deliverQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long, restoring: Boolean = false): QueueDelivery {
        autoplayJob?.cancel()
        autoplayIds = emptySet()
        newSmartContext()
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
        // each appended chunk's timeline event already syncs the state
        if (restoring) restoreRush.value = false
        queueFillJob = viewModelScope.launch {
            // a restored queue nobody plays or edits yet fills in after launch rather than during it
            if (restoring) withTimeoutOrNull(RESTORE_DEFER_MS) { combine(activelyPlaying, restoreRush) { p, r -> p || r }.first { it } }
            var delivered = initial.size
            var tail = windowEnd
            while (tail < songs.size) {
                val chunk = songs.subList(tail, (tail + QUEUE_BATCH).coerceAtMost(songs.size))
                if (!appendQueue(c, token, delivered, chunk, waitForInitialQueue = delivered == initial.size)) return@launch
                delivered += chunk.size
                tail += chunk.size
                delay(40)
            }
            var head = startIndex
            while (head > 0) {
                val chunkStart = (head - QUEUE_BATCH).coerceAtLeast(0)
                val chunk = songs.subList(chunkStart, head)
                if (!appendQueue(c, token, delivered, chunk, prepend = true, waitForInitialQueue = delivered == initial.size)) return@launch
                delivered += chunk.size
                head = chunkStart
                delay(40)
            }
        }
        if (restoring) restoreFill = queueFillJob
        return QueueDelivery(initial, 0)
    }

    // the in-memory backfill deliverQueue just started must finish first, or everything past the first batch is lost
    private fun fillQueue(kind: String, id: String, loaded: List<Song>, total: Int, shuffle: Boolean, collection: PlaybackCollectionIdentity?) {
        if (loaded.size >= total || total <= 0) return
        val backfill = queueFillJob
        queueFillJob = viewModelScope.launch {
            try {
                backfill?.join()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                backfill?.cancel()
                throw cancelled
            }
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
                val fresh = container.repository.playable(kind, id, page.filter { it.id.isNotEmpty() && have.add(it.id) })
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
            suspend fun serverRadio(): List<Song> = try {
                container.repository.radio(seed.id).filter { it.id != seed.id }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            try {
                val preferServer = container.repository.prefersServerRadio(seed.id)
                if (preferServer) {
                    val more = serverRadio()
                    if (more.isNotEmpty()) {
                        playAll(listOf(seed) + more, 0)
                        onResult(appString(R.string.text_radio_started_31fe2c))
                        return@launch
                    }
                }
                val sonic = try { container.sonicEngine.buildRadio(seed) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                if (sonic.size >= 2) {
                    playAll(sonic, 0)
                    onResult(appString(R.string.text_sonic_radio_similar_tracks_653156, (sonic.size - 1)))
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

    fun startAutoDj(seed: Song = _state.value.current, onResult: (String) -> Unit = {}) {
        cancelMediaSearch()
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        viewModelScope.launch {
            val set = runCatching { container.sonicEngine.buildAutoDj(seed) }.getOrDefault(emptyList())
            if (set.size >= 2) {
                playAll(set, 0)
                onResult(appString(R.string.text_auto_dj_tracks_key_tempo_matched_83c6cc, (set.size)))
            } else {
                loadingRadio = false
                startSonicRadio(seed, onResult)
                return@launch
            }
            loadingRadio = false
        }
    }

    fun startAiDj(onResult: (String) -> Unit = {}) {
        val queue = container.aiDjQueue ?: return onResult(appString(R.string.ai_dj_nothing))
        aiDj.start(queue, { playAll(it, 0) }) { ok -> onResult(appString(if (ok) R.string.ai_dj_started else R.string.ai_dj_nothing)) }
    }

    fun switchAiDj() { container.aiDjQueue?.let(aiDj::switchUp) }

    fun steerAiDj(text: String) { container.aiDjQueue?.let { aiDj.steer(text, it) } }

    fun stopAiDj() = aiDj.stop()

    fun listenAiDj(): Boolean = container.aiDjQueue?.let(aiDj::startListening) == true

    fun hearAiDj() { container.aiDjQueue?.let(aiDj::stopListening) }

    fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity? = null) {
        val kept = container.repository.visible(songs)
        if (kept.size != songs.size) return shufflePlay(kept, collection)
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
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = shuffled[0], isPlaying = true, shuffle = true) }
        _position.value = 0f
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
        if (afterRestoreFill { addToQueue(song) }) return
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        // what you add yourself plays before autoplay's picks
        val autoplay = autoplayFrom(c)
        if (autoplay >= 0) c.addMediaItem(autoplay, toMediaItem(song)) else c.addMediaItem(toMediaItem(song))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        syncFromController()
    }

    fun playNext(song: Song) {
        if (leaveMixThen { playNext(song) }) return
        if (afterRestoreFill { playNext(song) }) return
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
        if (afterRestoreFill { removeFromQueue(index + it) }) return
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.removeMediaItem(index)
            syncFromController()
        }
    }

    fun clearQueue() {
        if (afterRestoreFill { clearQueue() }) return
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
        if (ids.isEmpty()) { onResult(appString(R.string.text_nothing_to_save_a5dbc6)); return }
        viewModelScope.launch {
            val ok = try { container.repository.createPlaylistFromSongs(title, ids) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { false }
            onResult(if (ok) appString(R.string.text_saved_bac2cc, (title)) else appString(R.string.text_couldn_t_save_playlist_d7a0f6))
        }
    }

    fun togglePlay() {
        cancelMediaSearch()
        val c = controller ?: return
        container.haptic()
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun seekTo(fraction: Float) {
        cancelMediaSearch()
        val c = controller ?: return
        val dur = _state.value.durationSec
        if (dur > 0) c.seekTo((fraction.coerceIn(0f, 1f) * dur * 1000).toLong())
    }

    fun setVideoQuality(height: Int?) {
        if (companionVideo.setQuality(height)) return
        val c = controller ?: return
        val index = c.currentMediaItemIndex.takeIf { it in 0 until c.mediaItemCount } ?: return
        val item = c.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme != "aurora-yt" || uri.host != "video") return
        val quality = height?.coerceAtLeast(144)
        val currentQuality = uri.getQueryParameter("quality")?.toIntOrNull()
        if (currentQuality == quality && (quality != null || uri.getQueryParameter("quality") in setOf(null, "auto"))) return
        val position = c.currentPosition
        val playWhenReady = c.playWhenReady
        val updatedUri = uri.buildUpon().clearQuery().apply {
            quality?.let { appendQueryParameter("quality", it.toString()) }
        }.build()
        val updated = item.buildUpon().setUri(updatedUri).build()
        c.replaceMediaItem(index, updated)
        c.seekTo(index, position)
        c.playWhenReady = playWhenReady
    }

    // the song as it was queued, before a better copy took over its stream
    fun queued(song: Song): Song = songById[song.id] ?: song

    // the playing song from another source, or itself after its match changed, carrying on where it was
    fun swapCurrent(song: Song) {
        val c = controller ?: return
        if (remoteSession != null) return
        val index = c.currentMediaItemIndex.takeIf { it in 0 until c.mediaItemCount } ?: return
        val old = c.getMediaItemAt(index)
        val target = if (song.id == old.mediaId) queued(song) else song
        val fresh = toMediaItem(target)
        val extras = Bundle(fresh.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            old.mediaMetadata.extras?.getString(PlaybackService.QUEUE_TOKEN)?.let { putString(PlaybackService.QUEUE_TOKEN, it) }
            putBoolean(com.aurora.music.playback.PreferredPlayback.PINNED, true)
        }
        // a late-resolved link gets a new uri, so the stream resolved for the old pick isn't reused
        val uri = Uri.parse(target.streamUrl).let {
            if (it.scheme == "aurora-yt" || it.scheme == "aurora-sc") it.buildUpon().appendQueryParameter("swap", System.nanoTime().toString()).build() else it
        }
        val item = fresh.buildUpon().setUri(uri).setMediaMetadata(fresh.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        val position = c.currentPosition
        val playWhenReady = c.playWhenReady
        songById = songById + (target.id to target)
        c.replaceMediaItem(index, item)
        c.seekTo(index, position)
        c.playWhenReady = playWhenReady
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

    // off, then shuffle, then shuffle with autoplay picks mixed in, then off again
    fun toggleShuffle() {
        if (afterRestoreFill { toggleShuffle() }) return
        val c = controller ?: return
        if (remoteSession != null) return sendShuffle(-1)
        when {
            !c.shuffleModeEnabled -> sendShuffle(1)
            !smartShuffle && !_state.value.isMix -> {
                smartShuffle = true
                smartPending = true
                _state.update { it.copy(smartShuffle = true) }
                maybeSmart()
            }
            else -> {
                smartShuffle = false
                smartJob?.cancel()
                smartPending = false
                val picks = smartIds
                smartIds = emptySet()
                _state.update { it.copy(smartShuffle = false, smartPicks = emptySet()) }
                // the service takes the picks out by id along with shuffle, so the reorder can't shift them
                sendShuffle(0, picks)
            }
        }
    }

    // target 1 on 0 off -1 toggle service physically reorders and restores original order on disable;
    // drop = songs to take out of what's still to come first
    private fun sendShuffle(target: Int, drop: Collection<String> = emptyList()) {
        if (afterRestoreFill { sendShuffle(target, drop) }) return
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                putInt("target", target)
                if (drop.isNotEmpty()) putStringArrayList("drop", ArrayList(drop))
            }),
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

    fun toggleLikeCurrent() {
        val remote = remoteSession?.status?.value
        if (remote != null) container.networkOutput.command(com.aurora.music.playback.network.endpoint.ConnectCommand.Like(!remote.liked))
        else likes.toggle(_state.value.current)
    }

    private var commentsFor: String? = null
    private var commentsJob: Job? = null

    // comments load once per track; the player offers them only once a soundcloud track for the song turned up
    private fun loadComments(song: Song) {
        if (song.id == commentsFor) return
        commentsFor = song.id
        commentsJob?.cancel()
        val type = container.repository.sourceType(song)
        val available = container.repository.supportsComments(song) && type?.name !in mergeFeatures.commentsOff
        _state.update { it.copy(comments = emptyList(), supportsComments = false, canPostComments = container.repository.canPostComments(song),
            commentsAvailable = available, commentsEnabled = mergeFeatures.comments) }
        if (!available || !mergeFeatures.commentsFor(type)) return
        commentsJob = viewModelScope.launch {
            val list = runCatching { container.repository.comments(song) }.getOrNull() ?: return@launch
            if (_state.value.current.id == song.id) _state.update { it.copy(comments = list, supportsComments = true) }
        }
    }

    fun setCommentsEnabled(on: Boolean) {
        viewModelScope.launch { container.settingsStore.setMergeFeatures(mergeFeatures.copy(comments = on)) }
    }

    // posted at the moment the track is at, the way soundcloud pins a comment
    fun postComment(body: String) {
        val song = _state.value.current
        val at = (controller?.currentPosition ?: 0L).coerceAtLeast(0L)
        viewModelScope.launch {
            val result = runCatching { container.repository.postComment(song, body, at) }
            val posted = result.getOrNull()
            if (posted == null) {
                val needsVerify = result.exceptionOrNull() is com.aurora.music.data.remote.SoundCloudCheckException
                android.widget.Toast.makeText(getApplication(), appString(if (needsVerify) R.string.comment_needs_verify else R.string.comment_failed),
                    if (needsVerify) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
            }
            else if (_state.value.current.id == song.id) _state.update { it.copy(comments = (it.comments + posted).sortedBy { c -> c.timestampMs }) }
        }
    }

    private var launchLikes: Job? = null

    // the launch resume and session refreshes fold into one that waits past the first home frames
    fun refreshLikes(restart: Boolean = false) {
        if (launchLikes == null) launchLikes = viewModelScope.launch { delay(LAUNCH_LIKES_DELAY_MS); likes.refresh() }
        else if (launchLikes?.isActive != true) likes.refresh(restart)
    }

    fun checkLiked(songs: List<Song>) = likes.check(songs)
    fun checkVisibleLiked(songs: List<Song>) = likes.checkVisible(songs)
    fun checkLikedList(songs: List<Song>) = likes.checkList(songs)

    // songs toggle on the source they play from; the heart's menu reaches the other accounts
    fun toggleLike(id: String, kind: String = "song") = likes.toggle(id, kind)

    // a connected device keeps its own likes, so its songs get no per-account menu
    suspend fun likeOptions(song: Song): List<com.aurora.music.data.LikeOption> = if (remoteSession != null) emptyList() else likes.options(song)
    suspend fun setLikedOn(song: Song, option: com.aurora.music.data.LikeOption, liked: Boolean) = likes.set(song, option, liked)
    fun likedSources(): List<com.aurora.music.data.LikedSource> = if (remoteSession != null) emptyList() else likes.likedSources()

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
        companionVideo.clear()
        persistQueue(force = true)
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
        const val POSITION_PERSIST_MS = 15_000L
        const val RESTORE_DEFER_MS = 5_000L
        const val LAUNCH_LIKES_DELAY_MS = 3_000L
        // each MediaItem's parcelled metadata (artwork uri, title/artist/album, extras) is a few KB;
        // a binder transaction caps near 1mb, so a few hundred full items can silently get truncated
        // by the session. deliverQueue keeps the initial setMediaItems call well under that.
        const val QUEUE_BATCH = 120
    }
}
