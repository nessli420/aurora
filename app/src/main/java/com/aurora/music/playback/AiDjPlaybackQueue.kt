package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aurora.music.ai.DjMode
import com.aurora.music.data.MusicRepository
import com.aurora.music.model.Song

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
}
