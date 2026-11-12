package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

// crossfade swaps decks, so always act on the live one
@UnstableApi
internal class LocalShuffleQueue(private val live: () -> ExoPlayer) {
    var originalOrder: List<String>? = null

    private var pendingNeutralize = false

    fun setShuffle(target: Int, providedOrder: List<String>?) {
        val player = live()
        val enable = when (target) {
            1 -> true
            0 -> false
            else -> !player.shuffleModeEnabled
        }
        if (providedOrder == null && enable == player.shuffleModeEnabled && enable == (originalOrder != null)) return

        if (enable && providedOrder != null) {
            originalOrder = providedOrder
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
            return
        }
        if (enable) {
            val items = items(player)
            originalOrder = items.map { it.mediaId }
            if (items.size > 1) {
                val current = player.currentMediaItemIndex
                arrange(player, emptyList(), items.filterIndexed { i, _ -> i != current }.shuffled())
            }
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
        } else {
            val original = originalOrder
            if (original != null) {
                val items = items(player)
                val order = restoredOrder(items.map { it.mediaId }, original)
                val at = order.indexOf(player.currentMediaItemIndex)
                if (at >= 0) arrange(player, order.subList(0, at).map(items::get), order.subList(at + 1, order.size).map(items::get))
            }
            player.shuffleModeEnabled = false
            originalOrder = null
        }
    }

    // takes these songs out of what's still to come, by id, so a reorder in between can't point it at the wrong ones
    fun dropAhead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val player = live()
        val drop = ids.toHashSet()
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            if (player.getMediaItemAt(i).mediaId in drop) player.removeMediaItem(i)
        }
    }

    fun maybeNeutralize() {
        if (!pendingNeutralize) return
        val player = live()
        val count = player.mediaItemCount
        if (count <= 0) return
        runCatching { player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(count)) }
        pendingNeutralize = false
    }

    private fun items(player: ExoPlayer): List<MediaItem> = List(player.mediaItemCount, player::getMediaItemAt)

    // two timeline changes at most, every change republishes the whole queue
    private fun arrange(player: ExoPlayer, before: List<MediaItem>, after: List<MediaItem>) {
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount || after.isNotEmpty()) player.replaceMediaItems(current + 1, player.mediaItemCount, after)
        if (current > 0 || before.isNotEmpty()) player.replaceMediaItems(0, current, before)
    }

    companion object {
        fun restoredOrder(queue: List<String>, original: List<String>): List<Int> {
            val unused = queue.indices.groupByTo(HashMap(), queue::get)
            val placed = BooleanArray(queue.size)
            val order = ArrayList<Int>(queue.size)
            for (id in original) {
                val index = unused[id]?.removeFirstOrNull() ?: continue
                placed[index] = true
                order += index
            }
            queue.indices.filterTo(order) { !placed[it] }
            return order
        }
    }
}
