package com.aurora.music.desktop.audio

import com.aurora.music.data.SettingsStore
import com.aurora.music.data.ir.ImpulseLibraryFiles
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.chain.DspChainSettings
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun PlaybackEngine.bindSettings(store: SettingsStore, scope: CoroutineScope): Job = scope.launch {
    launch {
        combine(store.processingSettings, store.outputRatePolicy) { settings, policy -> settings to policy }.collect { (settings, policy) ->
            applyDsp(DspChainSettings(settings.audio, settings.playback.monoAudio, settings.rack))
            val playback = settings.playback
            configure(config.copy(
                crossfadeMs = playback.crossfadeSec * 1000,
                crossfadeCurve = playback.crossfadeCurve,
                crossfadeHeadroom = playback.crossfadeHeadroom,
                replayGain = settings.audio.replayGain,
                outputRatePolicy = policy,
            ))
        }
    }
    launch {
        store.audioPrefs.map { it.dspConvIrPath }.distinctUntilChanged().collectLatest { path ->
            val loaded = withContext(Dispatchers.IO) {
                if (path.isBlank()) Result.success<ImpulseResponse?>(null) else ImpulseResponse.loadWavResult(File(path))
            }
            loaded.exceptionOrNull()?.let { AppLog.w(TAG, "The selected impulse response could not be loaded", it) }
            setLegacyImpulse(loaded.getOrNull(), store.audioPrefs.first().dspConvMakeupDb)
        }
    }
    launch {
        store.processingRackAssets.collectLatest { entries ->
            val loaded = withContext(Dispatchers.IO) {
                entries.mapNotNull { entry ->
                    ImpulseLibraryFiles.validateAsset(entry, entry.prepared != null).getOrNull()
                        ?.let { ImpulseResponse.loadWavResult(it).getOrNull() }?.let { entry.id to it }
                }.toMap()
            }
            setRackImpulses(loaded)
        }
    }
}

private const val TAG = "AuroraEngineSettings"
