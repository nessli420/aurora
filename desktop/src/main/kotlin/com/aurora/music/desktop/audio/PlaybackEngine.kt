package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.model.Song
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.PcmLevelMeter
import com.aurora.music.playback.VisualizerController
import com.aurora.music.playback.chain.DspChainReport
import com.aurora.music.playback.chain.DspChainSettings
import com.aurora.music.playback.engine.RackNodeMeter
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PlaybackEngine : AutoCloseable {
    val state: StateFlow<EngineState>
    val events: SharedFlow<EngineEvent>
    val outputs: StateFlow<List<AudioDevice>>
    val config: EngineConfig
    val beforeMeter: PcmLevelMeter
    val afterMeter: PcmLevelMeter
    var visualizer: VisualizerController?

    fun setQueue(songs: List<Song>, startIndex: Int = 0, startPositionMs: Long = 0, play: Boolean = true)
    fun insert(index: Int, songs: List<Song>)
    fun append(songs: List<Song>)
    fun move(from: Int, to: Int)
    fun remove(fromIndex: Int, toIndex: Int = fromIndex + 1)
    fun replace(index: Int, song: Song)
    fun clear()
    fun seekTo(index: Int, positionMs: Long)
    fun seekTo(positionMs: Long)
    fun next()
    fun previousItem()
    fun play()
    fun pause()
    fun stop()
    fun setRepeat(mode: RepeatMode)
    fun setShuffle(target: ShuffleTarget, originalOrder: List<String>? = null)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float)
    fun sleepFade(fadeMs: Int)
    fun wakeFade(fadeMs: Int)
    fun configure(config: EngineConfig)
    fun setOutput(deviceId: String?, exclusive: Boolean)
    fun applyDsp(settings: DspChainSettings)
    fun setLegacyImpulse(impulse: ImpulseResponse?, makeupDb: Float)
    fun setRackImpulses(impulses: Map<String, ImpulseResponse>)
    fun setRelativeVolume(volume: Double)
    fun dspReport(): DspChainReport?
    fun rackMeters(): List<RackNodeMeter>
}
