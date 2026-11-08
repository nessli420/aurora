package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aurora.music.ai.DjMode
import com.aurora.music.data.MusicRepository
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// the dj talks to the session's live player directly, so it keeps going with no screen around
internal class AiDjPlaybackQueue(
    private val live: () -> Player,
    private val dj: DjMode,
    private val repository: MusicRepository,
) : DjMode.Queue {
    private fun song(item: MediaItem): Song = dj.known(item.mediaId) ?: item.mediaMetadata.let {
        Song(item.mediaId, it.title?.toString().orEmpty(), it.artist?.toString().orEmpty(), it.albumTitle?.toString().orEmpty(), "", 0)
    }

    override val current: Song? get() = live().currentMediaItem?.let(::song)

    override val upcoming: List<Song>
        get() = live().let { p -> (p.currentMediaItemIndex + 1 until p.mediaItemCount).map { song(p.getMediaItemAt(it)) } }

    override val playing: Boolean get() = live().isPlaying

    override fun append(songs: List<Song>) {
        val p = live()
        val ended = p.playbackState == Player.STATE_ENDED
        val first = p.mediaItemCount
        p.addMediaItems(songs.map { songMediaItem(it, repository) })
        // the last set ran out before this one was ready
        if (ended) p.seekTo(first, 0)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    override fun replaceUpcoming(songs: List<Song>) {
        val p = live()
        val from = p.currentMediaItemIndex + 1
        if (from < p.mediaItemCount) p.removeMediaItems(from, p.mediaItemCount)
        append(songs)
    }

    override fun skipToNext() = live().seekToNextMediaItem()

    override fun pause() = live().pause()

    override fun play() = live().play()

    private var owner: Player? = null
    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) dj.onQueueChanged(this@AiDjPlaybackQueue)
            // the dj's queue only moves forward, looping it would replay its lines
            if (dj.state.value.active && p.repeatMode != Player.REPEAT_MODE_OFF) p.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    fun follow(next: Player) {
        owner?.removeListener(listener)
        next.addListener(listener)
        owner = next
    }

    fun watch(scope: CoroutineScope, modeChanged: () -> Unit) {
        var repeatBefore: Int? = null
        scope.launch {
            dj.state.map { it.active }.distinctUntilChanged().collect { active ->
                val deck = live()
                if (active) {
                    repeatBefore = deck.repeatMode
                    deck.repeatMode = Player.REPEAT_MODE_OFF
                } else {
                    repeatBefore?.let { if (deck.repeatMode == Player.REPEAT_MODE_OFF) deck.repeatMode = it }
                    repeatBefore = null
                }
                modeChanged()
            }
        }
    }

    fun close() { owner?.removeListener(listener) }
}
