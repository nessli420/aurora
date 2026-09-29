package com.aurora.music.desktop.player

import com.aurora.music.desktop.audio.EngineConfig
import com.aurora.music.desktop.audio.EngineEvent
import com.aurora.music.desktop.audio.EnginePhase
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.PlaybackEngine
import com.aurora.music.desktop.audio.PlaybackQueue
import com.aurora.music.desktop.audio.RepeatMode
import com.aurora.music.desktop.audio.ShuffleTarget
import com.aurora.music.desktop.audio.TransitionReason
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.model.Song
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.PcmLevelMeter
import com.aurora.music.playback.VisualizerController
import com.aurora.music.playback.chain.DspChainReport
import com.aurora.music.playback.chain.DspChainSettings
import com.aurora.music.playback.engine.RackNodeMeter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

internal class FakeEngine(random: Random = Random(7)) : PlaybackEngine {
    private val queue = PlaybackQueue(random)
    override val state = MutableStateFlow(EngineState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val outputs = MutableStateFlow<List<AudioDevice>>(emptyList())
    override var config = EngineConfig()
    override val beforeMeter = PcmLevelMeter()
    override val afterMeter = PcmLevelMeter()
    override var visualizer: VisualizerController? = null
    val calls = CopyOnWriteArrayList<String>()

    var positionMs = 0L
    var playWhenReady = false
    var phase = EnginePhase.IDLE
    var speed = 1f
    var volume = 1f
    var closed = false

    fun publish() {
        state.value = EngineState(queue.entries, queue.index, positionMs, 200_000, playWhenReady, phase,
            shuffle = queue.shuffle, shuffleRestoreIds = queue.restoreIds, repeat = queue.repeat, speed = speed, volume = volume)
    }

    fun at(positionMs: Long) {
        this.positionMs = positionMs
        publish()
    }

    override fun setQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long, play: Boolean) {
        calls += "setQueue"
        val from = queue.current
        queue.set(songs, startIndex)
        positionMs = startPositionMs
        playWhenReady = play && songs.isNotEmpty()
        phase = if (songs.isEmpty()) EnginePhase.IDLE else EnginePhase.READY
        publish()
        events.tryEmit(EngineEvent.Transition(from, queue.current, TransitionReason.QUEUE_CHANGED, null))
    }

    override fun insert(index: Int, songs: List<Song>) = edit { queue.insert(index, songs) }
    override fun append(songs: List<Song>) = edit { queue.append(songs) }
    override fun move(from: Int, to: Int) = edit { queue.move(from, to) }
    override fun remove(fromIndex: Int, toIndex: Int) = edit { queue.remove(fromIndex, toIndex) }
    override fun replace(index: Int, song: Song) = edit { queue.replace(index, song) }

    override fun clear() {
        calls += "clear"
        queue.clear()
        phase = EnginePhase.IDLE
        positionMs = 0
        publish()
    }

    override fun seekTo(index: Int, positionMs: Long) {
        if (index !in 0 until queue.size) return
        queue.select(index)
        at(positionMs)
    }

    override fun seekTo(positionMs: Long) = at(positionMs)

    override fun next() {
        val index = queue.next()
        if (index >= 0) seekTo(index, 0)
    }

    override fun previousItem() {
        val index = queue.previous()
        if (index >= 0) seekTo(index, 0)
    }

    override fun play() {
        if (queue.size == 0) return
        playWhenReady = true
        phase = EnginePhase.READY
        publish()
    }

    override fun pause() {
        calls += "pause"
        playWhenReady = false
        publish()
    }

    override fun stop() {
        calls += "stop"
        playWhenReady = false
        phase = EnginePhase.IDLE
        publish()
    }

    override fun setRepeat(mode: RepeatMode) = edit { queue.repeat = mode }
    override fun setShuffle(target: ShuffleTarget, originalOrder: List<String>?) = edit { queue.setShuffle(target, originalOrder) }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        publish()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun sleepFade(fadeMs: Int) {
        calls += "sleepFade($fadeMs)"
    }

    override fun wakeFade(fadeMs: Int) = Unit

    override fun configure(config: EngineConfig) {
        this.config = config
    }

    override fun setOutput(deviceId: String?, exclusive: Boolean) {
        calls += "setOutput($deviceId, $exclusive)"
    }

    override fun applyDsp(settings: DspChainSettings) = Unit
    override fun setLegacyImpulse(impulse: ImpulseResponse?, makeupDb: Float) = Unit
    override fun setRackImpulses(impulses: Map<String, ImpulseResponse>) = Unit
    override fun dspReport(): DspChainReport? = null
    override fun rackMeters(): List<RackNodeMeter> = emptyList()

    override fun close() {
        closed = true
    }

    private fun edit(change: () -> Unit) {
        change()
        publish()
    }
}

internal fun song(id: String, title: String = "Title $id") =
    Song(id, title, "Artist", "Album", "", 200, streamUrl = "https://music.example/$id.flac")

internal fun songs(count: Int) = List(count) { song("s$it") }
