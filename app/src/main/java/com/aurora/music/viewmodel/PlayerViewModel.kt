package com.aurora.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SavedQueue
import com.aurora.music.data.toSavedTrack
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) {
    val durationSec: Int get() = current.durationSec
    val progress: Float get() = if (durationSec == 0) 0f else (positionSec / durationSec).coerceIn(0f, 1f)
    val isCurrentLiked: Boolean get() = likedIds.contains(current.id)
    val hasTrack: Boolean get() = current.id.isNotEmpty()
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AuroraApplication).container

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var queueFillJob: Job? = null
    private var songById: Map<String, Song> = emptyMap()

    @Volatile private var scrobbleEnabled = true
    @Volatile private var autoplayEnabled = false
    @Volatile private var privateSession = false

    // Likes are merged from two sources: server stars (songs/albums/artists) + local playlist likes.
    private var serverLikedIds: Set<String> = emptySet()
    private var likedPlaylistIds: Set<String> = emptySet()
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // The account the live queue belongs to, so it's persisted/restored under the right key.
    @Volatile private var playingAccountKey: String = ""
    private var openRestoreAttempted = false
    private var lastPersistMs = 0L
    // While stopping for an account switch we clear the queue; don't let that overwrite the saved one.
    @Volatile private var suppressPersist = false
    private var playStartMs: Long = 0L
    private var lastDiscordSig: String? = null
    @Volatile private var loadingRadio = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                maybeAutoplay()
                // End-of-track sleep on the final track: nothing auto-transitions to, so honour it here.
                if (_state.value.sleepEndOfTrack) { controller?.pause(); _state.update { it.copy(sleepEndOfTrack = false) } }
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // Reset the displayed position immediately on every track change so the mini-player
            // progress bar doesn't show the previous track's spot during the transition gap. The
            // ticker then reports the real position. (Matters most for the bit-perfect USB sink,
            // whose position lags briefly across a skip.)
            _state.update { it.copy(positionSec = 0f) }
            // "Stop after this track": when the current track ends and auto-advances, pause at the
            // start of the next one.
            if (_state.value.sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _state.update { it.copy(sleepEndOfTrack = false) }
            }
        }
    }

    /** Fire Last.fm "now playing" the moment a new track starts (unless this is a private session). */
    private fun maybeNowPlaying() {
        val cur = _state.value.current
        if (cur.id.isEmpty() || cur.id == lastNowPlayingId) return
        lastNowPlayingId = cur.id
        playStartMs = System.currentTimeMillis()
        if (!privateSession) { container.lastfm.nowPlaying(cur); container.listenBrainz.nowPlaying(cur) }
    }

    /** Log to local history always; report to the server + Last.fm only when not a private session. */
    private fun recordIfPlayed(posSec: Float) {
        val cur = _state.value.current
        if (cur.id.isEmpty() || posSec < 30f || cur.id == lastRecordedId) return
        lastRecordedId = cur.id
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
            // The service survived (e.g. app backgrounded, not killed) but this VM is fresh — rebuild
            // the domain queue from the live MediaItems so the queue UI isn't empty.
            if (c.mediaItemCount > 0 && songById.isEmpty()) rehydrateFromController()
            syncFromController()
            startTicker()
        }, ContextCompat.getMainExecutor(app))

        // Restore a saved queue once the session resolves — but only if the service came up empty
        // (i.e. it was torn down by a swipe-away). If it still has a queue, keep playing that.
        viewModelScope.launch {
            container.sessionReady.collect { ready ->
                if (ready != true || openRestoreAttempted) return@collect
                openRestoreAttempted = true
                while (controller == null) delay(50)
                val c = controller ?: return@collect
                playingAccountKey = container.currentAccountKey()
                val saved = playingAccountKey.takeIf { it.isNotBlank() }?.let { container.queueStore.get(it) }
                if (c.mediaItemCount == 0 && saved != null) restoreQueue(saved)
            }
        }

        // Load starred ids once the session/client is ready (and reload on re-login).
        viewModelScope.launch {
            container.sessionReady.collect { ready -> if (ready == true) refreshLikes() }
        }
        // Locally-liked playlists (Subsonic can't star playlists) merge into the same set.
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
        // On a genuine account change (logout / server switch / add): save the OUTGOING account's
        // queue first (so it's not lost), stop playback, then restore the INCOMING account's queue.
        // The epoch only bumps on real transitions (not the initial load), so drop the current value.
        viewModelScope.launch {
            container.accountEpoch.drop(1).collect {
                persistQueue()                 // under playingAccountKey = the account we were playing
                container.queueStore.requestFlush()
                suppressPersist = true         // the clearing below must not wipe what we just saved
                stopPlayback()
                suppressPersist = false
                val key = container.currentAccountKey()
                playingAccountKey = key
                val saved = key.takeIf { it.isNotBlank() }?.let { container.queueStore.get(it) }
                if (saved != null) restoreQueue(saved)
            }
        }
    }

    /** Stop playback and clear the queue (logout / switching servers). */
    fun stopPlayback() {
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

    /** Snapshot the live queue to [QueueStore] under the account it belongs to. */
    private fun persistQueue() {
        if (suppressPersist) return
        val c = controller ?: return
        val key = playingAccountKey.ifBlank { container.currentAccountKey() }
        if (key.isBlank()) return
        // Don't clear on an empty controller — that fires at startup (before restore) and would wipe
        // the queue we're about to restore. A saved queue is simply overwritten when a new one plays.
        if (c.mediaItemCount == 0) return
        val songs = (0 until c.mediaItemCount).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
        if (songs.isEmpty()) return
        container.queueStore.save(key, SavedQueue(
            tracks = songs.map { it.toSavedTrack() },
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            positionSec = (c.currentPosition / 1000).toInt().coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 },
        ))
    }

    /** Load a saved queue (paused, at its saved position + modes). */
    private fun restoreQueue(sq: SavedQueue) {
        val c = controller ?: return
        val songs = sq.tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }
        if (songs.isEmpty()) return
        songById = songs.associateBy { it.id }
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        c.setMediaItems(songs.map { toMediaItem(it) }, idx, sq.positionSec.toLong() * 1000)
        c.playbackParameters = currentParams()
        c.repeatMode = when (sq.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        c.prepare()   // buffer at the saved position, but stay paused
        _state.update {
            it.copy(queue = songs, current = songs[idx], positionSec = sq.positionSec.toFloat(), isPlaying = false, currentIndex = idx, shuffle = sq.shuffle,
                repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF })
        }
        if (sq.shuffle) {
            // Queue is already in its saved order — tell the service shuffle is on and remember that order.
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                    putInt("target", 1)
                    putStringArrayList("order", ArrayList(songs.map { it.id }))
                }),
                android.os.Bundle.EMPTY,
            )
        }
    }

    /** Rebuild the domain queue from the live MediaItems (after a VM recreation with the service alive). */
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

    /** Schedule pausing playback after [minutes] (0 = cancel). The last few seconds fade out. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sendSleepFade(start = false) // cancel any in-progress fade + restore volume
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepEndOfTrack = false) }
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            val total = minutes * 60_000L
            delay((total - SLEEP_FADE_MS).coerceAtLeast(0L))
            sendSleepFade(start = true) // service fades to silence then pauses
            _state.update { it.copy(sleepTimerMinutes = 0) }
        }
    }

    /** Pause once the current track finishes ("stop after this track"). */
    fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        sendSleepFade(start = false)
        _state.update { it.copy(sleepTimerMinutes = 0, sleepEndOfTrack = true) }
    }

    /** Tell the service to fade-out-then-pause (start=true) or cancel a pending fade (start=false). */
    private fun sendSleepFade(start: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP_FADE, android.os.Bundle().apply {
                putInt("fadeMs", if (start) SLEEP_FADE_MS.toInt() else 0)
            }),
            android.os.Bundle.EMPTY,
        )
    }

    /** Route output to a specific device id (0 = system default). */
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
                if (c.isPlaying) {
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
        val mediaId = c.currentMediaItem?.mediaId
        val cur = mediaId?.let { songById[it] } ?: _state.value.current
        val q = (0 until c.mediaItemCount).mapNotNull { i -> songById[c.getMediaItemAt(i).mediaId] }
        _state.update {
            it.copy(
                current = cur,
                isPlaying = c.isPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                positionSec = (c.currentPosition / 1000f).coerceAtLeast(0f),
                queue = if (q.isNotEmpty()) q else it.queue,
                currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            )
        }
        updateDiscordPresence()
        maybeEnrichLocal(_state.value.current)
        persistQueue()   // capture structural changes (track transition, queue edits, mode toggles)
    }

    // Local files: MediaStore gives us format + bitrate, but not sample-rate/bit-depth. Pull those
    // (and a more precise bitrate) for the *playing* track via MediaMetadataRetriever, once each.
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

    /** Push Rich Presence when the track or play/pause state changes (no-op when not connected). */
    private fun updateDiscordPresence() {
        val s = _state.value
        if (s.current.id.isEmpty()) return
        val sig = "${s.current.id}|${s.isPlaying}"
        if (sig == lastDiscordSig) return
        lastDiscordSig = sig
        container.discord.update(s.current, s.isPlaying, s.positionSec)
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
                .setExtras(android.os.Bundle().apply {
                    putFloat("rgTrack", song.replayGainTrack)
                    putFloat("rgAlbum", song.replayGainAlbum)
                })
                .build()
        )
        .build()

    /** Play a list of songs starting at [startIndex] — the proper "play from here" behaviour. */
    fun playAll(songs: List<Song>, startIndex: Int = 0) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        container.haptic()
        playingAccountKey = container.currentAccountKey()
        songById = songs.associateBy { it.id }
        val items = songs.map { toMediaItem(it) }
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        c.setMediaItems(items, idx, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        // Starting a fresh context plays in order — make sure shuffle is off.
        sendShuffle(0)
        _state.update { it.copy(queue = songs, current = songs[idx], positionSec = 0f, isPlaying = true) }
    }

    fun play(song: Song) = playAll(listOf(song), 0)

    /**
     * Play a whole collection (album/playlist/liked) from [startIndex] using the already-loaded
     * [loaded] tracks, then lazily page in the rest in the background so the FULL tracklist lands in
     * the queue — without blocking playback or tripping the rate limit. [total] is the collection's
     * real track count.
     */
    fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) {
        playAll(loaded, startIndex)
        fillQueue(kind, id, loaded, total, shuffle = false)
    }

    /** Shuffle-play a whole collection, then lazily append the remaining (shuffled) pages. */
    fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) {
        shufflePlay(loaded)
        fillQueue(kind, id, loaded, total, shuffle = true)
    }

    /** Background: page the rest of a collection's tracks and append them to the live queue. */
    private fun fillQueue(kind: String, id: String, loaded: List<Song>, total: Int, shuffle: Boolean) {
        queueFillJob?.cancel()
        if (loaded.size >= total || total <= 0) return
        queueFillJob = viewModelScope.launch {
            val c = controller ?: return@launch
            val have = loaded.mapTo(HashSet()) { it.id }
            var offset = loaded.size
            while (offset < total) {
                val page = runCatching { container.repository.detailPage(kind, id, offset) }.getOrDefault(emptyList())
                if (page.isEmpty()) break
                val fresh = page.filter { it.id.isNotEmpty() && have.add(it.id) }
                if (fresh.isNotEmpty()) {
                    val toAdd = if (shuffle) fresh.shuffled() else fresh
                    songById = songById + toAdd.associateBy { it.id }
                    c.addMediaItems(toAdd.map { toMediaItem(it) })
                    syncFromController()
                }
                offset += page.size
                delay(180) // gentle — stay clear of Spotify's rate limit
            }
        }
    }

    /**
     * Shuffle a whole collection and play from the top — so it starts on a RANDOM track, not the
     * first one. The unshuffled order is handed to the service so "turn shuffle off" can still
     * restore the real album/playlist order.
     */
    fun shufflePlay(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        playingAccountKey = container.currentAccountKey()
        songById = songs.associateBy { it.id }
        val shuffled = songs.shuffled()
        c.setMediaItems(shuffled.map { toMediaItem(it) }, 0, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        _state.update { it.copy(queue = shuffled, current = shuffled[0], positionSec = 0f, isPlaying = true, shuffle = true) }
        // Shuffle is ON; pass the original order (by id) so disabling restores it.
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                putInt("target", 1)
                putStringArrayList("order", ArrayList(songs.map { it.id }))
            }),
            android.os.Bundle.EMPTY,
        )
    }

    /** Append a track to the end of the queue. */
    fun addToQueue(song: Song) {
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        c.addMediaItem(toMediaItem(song))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        syncFromController()
    }

    /** Insert a track right after the current one. */
    fun playNext(song: Song) {
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        val idx = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItem(idx, toMediaItem(song))
        syncFromController()
    }

    /** Jump to a specific queue position. */
    fun jumpTo(index: Int) {
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

    /** Clear all upcoming tracks, keeping the one currently playing. */
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

    fun togglePlay() {
        val c = controller ?: return
        container.haptic()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        val dur = _state.value.durationSec
        if (dur > 0) c.seekTo((fraction.coerceIn(0f, 1f) * dur * 1000).toLong())
    }

    fun next() {
        container.haptic()
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        container.haptic()
        if (c.currentPosition > 4000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun toggleShuffle() = sendShuffle(-1)

    /** Ask the service to set shuffle (target: 1 = on, 0 = off, -1 = toggle). It physically
     *  reorders the queue and restores the original order on disable. The target travels in the
     *  command's extras so the same action also works (as a toggle) from the notification button. */
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

    // Track ids we've already resolved a liked/not-liked answer for, so we don't re-query them.
    private val likeChecked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Re-pull starred ids from the server (call on app resume — handles other-device changes). */
    fun refreshLikes() {
        viewModelScope.launch {
            serverLikedIds = runCatching { container.repository.starredIds() }.getOrDefault(serverLikedIds)
            recomputeLikes()
        }
    }

    /**
     * Lazily resolve which of [ids] are liked (Spotify: one batched /me/tracks/contains call per 50)
     * and merge them into the like set, so the heart shows on liked tracks beyond the first page
     * that [starredIds] returns — without paging the whole liked library (which trips the rate
     * limit). Call it when a tracklist becomes visible.
     */
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

    /** Toggle a like. [kind]: song/album/artist persist to the server; playlist persists locally. */
    fun toggleLike(id: String, kind: String = "song") {
        if (id.isEmpty()) return
        val nowLiked = !_state.value.likedIds.contains(id)
        if (kind == "playlist") {
            likedPlaylistIds = if (nowLiked) likedPlaylistIds + id else likedPlaylistIds - id
            recomputeLikes()
            viewModelScope.launch { runCatching { container.settingsStore.setPlaylistLiked(id, nowLiked) } }
            // Also sync to the server backend (Spotify follows/unfollows the playlist on your
            // account). Harmless no-op for backends that can't star playlists (Subsonic).
            viewModelScope.launch { runCatching { container.repository.setStarred(id, nowLiked, "playlist") } }
        } else {
            serverLikedIds = if (nowLiked) serverLikedIds + id else serverLikedIds - id
            recomputeLikes()
            viewModelScope.launch { runCatching { container.repository.setStarred(id, nowLiked, kind) } }
        }
    }

    fun setExpanded(value: Boolean) = _state.update { it.copy(expanded = value) }

    fun setSpeed(value: Float) {
        // Snap to 0.05 steps.
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
        // Match pitch to speed = no time-stretch (analog-style); else preserve/shift pitch.
        val pitchRatio = if (s.matchPitch) s.speed else 2f.pow(s.pitch / 12f)
        return PlaybackParameters(s.speed, pitchRatio)
    }

    override fun onCleared() {
        persistQueue()                          // capture the final state before the controller goes
        container.queueStore.requestFlush()
        ticker?.cancel()
        queueFillJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    private companion object {
        // Length of the volume fade-out applied at the end of a sleep timer, before pausing.
        const val SLEEP_FADE_MS = 6_000L
    }
}
