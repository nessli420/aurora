package com.aurora.music.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

@UnstableApi
internal class LocalShuffleQueue(private val player: ExoPlayer) {
    var originalOrder: List<String>? = null

    private var pendingNeutralize = false

    fun setShuffle(target: Int, providedOrder: List<String>?) {
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
            if (player.mediaItemCount <= 1) {
                player.shuffleModeEnabled = true
                originalOrder = currentIds()
                return
            }
            val ids = currentIds()
            originalOrder = ids
            val currentId = player.currentMediaItem?.mediaId
            val rest = ids.filter { it != currentId }.shuffled()
            val desired = (if (currentId != null) listOf(currentId) else emptyList()) + rest
            applyOrder(desired)
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
        } else {
            val original = originalOrder
            if (original != null) {
                val present = currentIds()
                val restored = original.filter { it in present } + present.filter { it !in original }
                applyOrder(restored)
            }
            player.shuffleModeEnabled = false
            originalOrder = null
        }
    }

    fun maybeNeutralize() {
        if (!pendingNeutralize) return
        val count = player.mediaItemCount
        if (count <= 0) return
        runCatching { player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(count)) }
        pendingNeutralize = false
    }

    private fun currentIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun applyOrder(target: List<String>) {
        for (i in target.indices) {
            if (i >= player.mediaItemCount) break
            val want = target[i]
            var current = -1
            var j = i
            while (j < player.mediaItemCount) {
                if (player.getMediaItemAt(j).mediaId == want) { current = j; break }
                j++
            }
            if (current in (i + 1) until player.mediaItemCount) player.moveMediaItem(current, i)
        }
    }
}
