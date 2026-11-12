package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@UnstableApi
internal class PlaybackFades(
    private val scope: CoroutineScope,
    private val live: () -> ExoPlayer,
    private val setLive: (ExoPlayer) -> Unit,
    private val attach: (Player) -> Unit,
    private val isLocal: () -> Boolean,
    private val bitPerfect: () -> Boolean,
    private val usbSink: () -> com.decent.usbaudio.media3.UsbAudioSink?,
    private val independentOutput: () -> Boolean,
    private val newDeck: () -> ExoPlayer,
    private val onNewDeck: () -> Unit,
) {
    private val player get() = live()
    var fadePlayer: ExoPlayer? = null
        private set
    @Volatile var crossfadeMs: Int = 0
    @Volatile var replayGainMode: Int = 0
    @Volatile var sleepFadeActive = false
        private set
    private var sleepFadeStartMs = 0L
    private var sleepFadeMs = 0
    @Volatile var wakeFadeActive = false
        private set
    private var wakeFadeStartMs = 0L
    private var wakeFadeMs = 0
    var xfadeActive = false
        private set
    @Volatile private var xfadeBpPending = false   // bit-perfect crossfade fired, awaiting the transition
    private var xfadeStartMs = 0L
    private var xfadeDurationMs = 0L
    private var xfadeElapsedMs = 0L
    private var preparedKey: String? = null
    private var preparedFailedKey: String? = null
    private var nativeEligibilityKey: String? = null
    private var nativeEligible = false
    var crossfadeCurve = "SMOOTH"
    var crossfadeHeadroom = true
    var gapless = true
    private var activeCurve = "SMOOTH"
    private var activeHeadroom = true
    private var xfadeExpectedId: String? = null
    private var xfadeTailIndex = C.INDEX_UNSET
    private var xfadeInGain = 1f
    private var xfadeOutGain = 1f

    fun applyIdleGain() {
        if (!isLocal() || xfadeActive || sleepFadeActive || wakeFadeActive) return
        player.volume = replayGainMultiplier()
    }

    fun tick() {
        if (!isLocal()) return
        val now = android.os.SystemClock.elapsedRealtime()
        var master = 1f
        if (sleepFadeActive) {
            val t = rampProgress(now, sleepFadeStartMs, sleepFadeMs)
            master *= 1f - t
            if (t >= 1f) { sleepFadeActive = false; player.pause(); fadePlayer?.pause() }
        }
        if (wakeFadeActive) {
            val t = rampProgress(now, wakeFadeStartMs, wakeFadeMs)
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
        val audible = player.isPlaying || (bitPerfect() && player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && usbSink()?.nativeEngineActive == true)
        if (crossfadeMs <= 0 || !audible || player.isCurrentMediaItemLive) {
            if (crossfadeMs <= 0) clearPrepared()
            return
        }
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        val next = if (repeatOne) player.currentMediaItemIndex else player.nextMediaItemIndex
        if (next == C.INDEX_UNSET) { clearPrepared(); return }
        // dj speech plays whole and alone, a fade would cut its end or bury its start
        if (player.currentMediaItem?.mediaId.orEmpty().startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX) ||
            player.getMediaItemAt(next).mediaId.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX)) { clearPrepared(); return }
        // gapless albums play straight through, crossfade only bridges different albums
        if (gapless && sameAlbum(player.currentMediaItem, player.getMediaItemAt(next))) { clearPrepared(); return }
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val remaining = remainingMs(duration, player.currentPosition, speed)
        val fade = fadeWindowMs(crossfadeMs, duration, speed)
        val key = "${player.currentMediaItemIndex}:$next:${player.getMediaItemAt(next).localConfiguration?.uri}:${player.mediaItemCount}"
        if (bitPerfect()) {
            val sink = usbSink() ?: return
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
                val shape = nativeTailShape(crossfadeCurve)
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
        // snapshot duration, shape and gain so a prefs edit cannot jump an in-flight fade
        xfadeDurationMs = remaining.coerceAtLeast(1)
        xfadeElapsedMs = 0
        xfadeStartMs = android.os.SystemClock.elapsedRealtime()
        activeCurve = crossfadeCurve
        activeHeadroom = crossfadeHeadroom
        xfadeOutGain = replayGainMultiplier()
        xfadeInGain = replayGainMultiplier(incoming.currentMediaItem)
        val outgoing = player
        // keep physical order so shuffle can still restore it
        incoming.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(incoming.mediaItemCount))
        incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
        // only the incoming deck holds audio focus
        outgoing.setAudioAttributes(outgoing.audioAttributes, false)
        incoming.setAudioAttributes(incoming.audioAttributes, !independentOutput())
        setLive(incoming)
        fadePlayer = outgoing
        xfadeTailIndex = outgoing.currentMediaItemIndex
        // a cloned queue must not auto-advance the fading deck at its end
        outgoing.pauseAtEndOfMediaItems = true
        incoming.pauseAtEndOfMediaItems = false
        xfadeExpectedId = incoming.currentMediaItem?.mediaId
        xfadeActive = true
        preparedKey = null
        incoming.volume = 0f
        attach(incoming)
        incoming.play()
        android.util.Log.i("AuroraCrossfade", "begin durationMs=$xfadeDurationMs curve=$activeCurve protected=$activeHeadroom")
    }

    private fun driveXfade(master: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        val tail = fadePlayer ?: return endXfade()
        val playing = player.isPlaying
        // pause, focus loss or incoming buffering freezes both decks and the envelope clock
        if (playing) {
            // a tail that ended early (trimmed silence) must not resume into its cloned queue, the fade-in just hurries
            if (tailDone(tail)) { tail.pause(); xfadeDurationMs = minOf(xfadeDurationMs, xfadeElapsedMs + 1000) }
            else tail.playWhenReady = true
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

    fun followLive(playing: Boolean) {
        val tail = fadePlayer ?: return
        if (!playing || !tailDone(tail)) tail.playWhenReady = playing
    }

    private fun tailDone(tail: ExoPlayer) = tail.playbackState == Player.STATE_ENDED || tail.currentMediaItemIndex != xfadeTailIndex ||
        !tail.playWhenReady && tail.duration != C.TIME_UNSET && tail.currentPosition >= tail.duration - 100

    fun endXfade(master: Float = 1f) {
        xfadeActive = false
        xfadeExpectedId = null
        clearPrepared()
        player.volume = replayGainMultiplier() * master
    }

    fun clearPrepared() {
        fadePlayer?.takeIf { it.mediaItemCount > 0 }?.run { volume = 0f; pause(); clearMediaItems() }
        preparedKey = null
    }

    fun cancel() {
        if (xfadeActive) endXfade() else clearPrepared()
    }

    fun onTimelineChanged() {
        if (!xfadeActive) {
            clearPrepared()
            preparedFailedKey = null
        }
    }

    fun rearmNative() {
        xfadeBpPending = false
    }

    fun sleepFade(ms: Int) {
        if (ms > 0) { sleepFadeMs = ms; sleepFadeStartMs = android.os.SystemClock.elapsedRealtime(); sleepFadeActive = true }
        else { sleepFadeActive = false; player.volume = replayGainMultiplier() }
    }

    fun startWakeFade(ms: Int) {
        wakeFadeMs = ms
        wakeFadeStartMs = android.os.SystemClock.elapsedRealtime()
        wakeFadeActive = true
    }

    fun stopWakeFade() {
        wakeFadeActive = false
    }

    fun stopFades() {
        sleepFadeActive = false
        wakeFadeActive = false
    }

    fun release() {
        runCatching { fadePlayer?.release() }
        fadePlayer = null
    }

    private fun ensureFadePlayer(): ExoPlayer {
        fadePlayer?.let { return it }
        return newDeck().also { fadePlayer = it; onNewDeck() }
    }

    fun replayGainMultiplier(item: MediaItem? = player.currentMediaItem): Float {
        if (replayGainMode == 0) return 1f
        val extras = item?.mediaMetadata?.extras ?: return 1f
        val db = if (replayGainMode == 2) extras.getFloat("rgAlbum", 0f) else extras.getFloat("rgTrack", 0f)
        return replayGainLinear(db)
    }

    companion object {
        fun replayGainLinear(db: Float): Float = if (db.isFinite()) Math.pow(10.0, db / 20.0).toFloat().coerceIn(0.1f, 1f) else 1f

        fun remainingMs(durationMs: Long, positionMs: Long, speed: Float): Long = ((durationMs - positionMs) / speed).toLong()

        fun fadeWindowMs(crossfadeMs: Int, durationMs: Long, speed: Float): Long =
            crossfadeMs.toLong().coerceAtMost((durationMs / speed / 2).toLong())

        fun sameAlbum(a: MediaItem?, b: MediaItem?): Boolean {
            val album = a?.mediaMetadata?.extras?.getString("aurora.albumId")
            return !album.isNullOrBlank() && album == b?.mediaMetadata?.extras?.getString("aurora.albumId")
        }

        fun nativeTailShape(curve: String): Int = when (curve) { "LINEAR" -> 1; "POWER" -> 2; else -> 0 }

        fun rampProgress(nowMs: Long, startMs: Long, lengthMs: Int): Float =
            ((nowMs - startMs).toFloat() / lengthMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}
