package com.aurora.music.desktop.connect

import com.aurora.music.R
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.DesktopPlayer
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.desktop.player.artworkBytes
import com.aurora.music.localization.appString
import com.aurora.music.playback.network.endpoint.ConnectCommand
import com.aurora.music.playback.network.endpoint.ConnectHost
import com.aurora.music.playback.network.endpoint.ConnectOption
import com.aurora.music.playback.network.endpoint.ConnectStatus
import com.aurora.music.playback.network.endpoint.ConnectTrack
import com.aurora.music.playback.network.endpoint.RendererException
import com.aurora.music.playback.network.endpoint.resolveConnectTracks
import com.aurora.music.playback.network.endpoint.windowedFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class DesktopConnectHost(private val player: DesktopPlayer, private val container: DesktopContainer) : ConnectHost {
    private fun <T> onMain(action: () -> T): T = runBlocking { withTimeout(5_000) { withContext(Dispatchers.Main) { action() } } }

    override fun status(): ConnectStatus {
        val state = player.state.value
        val presets = runBlocking { runCatching { container.settingsStore.processingPresetLibrary.first().presets }.getOrDefault(emptyList()) }
        return ConnectStatus(
            playing = state.isPlaying, positionMs = (state.positionSec * 1000).toLong(), durationMs = state.durationSec * 1000L,
            index = state.currentIndex, shuffle = state.shuffle, repeat = state.repeat.wire(), volume = player.volume.value,
            speed = state.speed, liked = state.hasTrack && state.isCurrentLiked,
            sleepMinutes = state.sleepTimerMinutes, sleepEndOfTrack = state.sleepEndOfTrack,
            outputs = listOf(ConnectOption("", appString(R.string.text_automatic_ac9041))) + player.outputs.value.map { ConnectOption(it.id.take(256), it.name.take(128)) },
            output = player.preferredOutput.value.orEmpty().take(256),
            presets = presets.map { ConnectOption(it.id, it.name) },
        ).windowedFrom(if (state.hasTrack) state.queue.size else 0) { ConnectTrack.of(state.queue[it]) }
    }

    override fun execute(command: ConnectCommand) {
        val songs = when (command) {
            is ConnectCommand.Play -> runBlocking(Dispatchers.IO) { container.repository.resolveConnectTracks(command.tracks) }
            is ConnectCommand.Add -> runBlocking(Dispatchers.IO) { container.repository.resolveConnectTracks(command.tracks) }
            else -> emptyList()
        }
        if (command is ConnectCommand.Preset) {
            runBlocking(Dispatchers.IO) { container.settingsStore.applyProcessingPreset(command.id) }
                .getOrElse { throw RendererException("preset_failed", it.message ?: "That preset could not be applied.", 409) }
            return
        }
        onMain {
            val state = player.state.value
            fun requireIndex(index: Int) {
                if (index !in state.queue.indices) throw RendererException("invalid_index", "Queue position is unavailable.", 409)
            }
            when (command) {
                is ConnectCommand.Playing -> if (state.isPlaying != command.value) player.togglePlay()
                is ConnectCommand.Seek -> if (state.durationSec > 0) player.seekTo(command.positionMs / (state.durationSec * 1000f))
                is ConnectCommand.Jump -> { requireIndex(command.index); player.jumpTo(command.index) }
                ConnectCommand.Next -> player.next()
                ConnectCommand.Previous -> player.previous()
                is ConnectCommand.Shuffle -> if (state.shuffle != command.value) player.toggleShuffle()
                is ConnectCommand.Repeat -> repeat(3) { if (player.state.value.repeat.wire() != command.mode) player.cycleRepeat() }
                is ConnectCommand.Volume -> player.setVolume(command.value)
                is ConnectCommand.Speed -> player.setSpeed(command.value)
                is ConnectCommand.Like -> if (state.hasTrack && state.isCurrentLiked != command.value) player.toggleLike(state.current.id)
                is ConnectCommand.Sleep -> if (command.endOfTrack) player.setSleepEndOfTrack() else player.setSleepTimer(command.minutes)
                is ConnectCommand.Output -> player.setPreferredDevice(command.id.ifBlank { null })
                is ConnectCommand.Play -> {
                    val start = songs.indexOfFirst { it.id == command.tracks[command.index].id }
                    if (start < 0) throw RendererException("not_available", "Those songs aren't available on that device.", 409)
                    player.playAll(songs, start)
                    if (command.positionMs > 0 && songs[start].durationSec > 0) player.seekTo(command.positionMs / (songs[start].durationSec * 1000f))
                    if (!command.playing && player.state.value.isPlaying) player.togglePlay()
                }
                is ConnectCommand.Add -> {
                    if (songs.isEmpty()) throw RendererException("not_available", "Those songs aren't available on that device.", 409)
                    if (command.index < 0) songs.forEach(player::addToQueue) else songs.asReversed().forEach(player::playNext)
                }
                is ConnectCommand.Remove -> { requireIndex(command.index); player.removeFromQueue(command.index) }
                is ConnectCommand.Move -> { requireIndex(command.from); requireIndex(command.to); player.moveQueueItem(command.from, command.to) }
                ConnectCommand.Clear -> player.clearQueue()
                ConnectCommand.Stop -> player.stopPlayback()
                is ConnectCommand.Preset -> Unit
            }
        }
    }

    override fun artwork(id: String): ByteArray? {
        val url = player.state.value.queue.firstOrNull { it.id == id }?.artworkUrl?.takeIf { it.isNotBlank() } ?: return null
        return runBlocking { runCatching { artworkBytes(url, container.http, container.artworkRepository) }.getOrNull() }
    }
}

internal fun RepeatMode.wire(): Int = when (this) { RepeatMode.OFF -> 0; RepeatMode.ONE -> 1; RepeatMode.ALL -> 2 }
