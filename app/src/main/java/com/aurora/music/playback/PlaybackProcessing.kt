package com.aurora.music.playback

import com.aurora.music.data.AudioEffectsController
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.playback.chain.activeFor
import com.aurora.music.playback.chain.applyDsp
import com.aurora.music.playback.chain.dspParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class PlaybackProcessing(
    private val scope: CoroutineScope,
    private val pushMix: () -> Unit,
    private val refresh: () -> Unit,
) {
    @Volatile private var lastIrPath: String = ""
    var audioEffects: AudioEffectsController? = null
    @Volatile var monoAudioPref: Boolean = false
    @Volatile var lastAudioPrefs: AudioPrefs? = null
    private val precisionChains = mutableListOf<PrecisionBlockProcessor>()
    private val compatibilityChains = mutableListOf<PrecisionBlockProcessor>()
    var lastRack: com.aurora.music.data.ProcessingRack? = null
    @Volatile var outputRatePolicy = com.aurora.music.playback.engine.OutputRatePolicy()
    var rackImpulses: Map<String, ImpulseResponse> = emptyMap()
        private set
    var currentImpulse: ImpulseResponse? = null
        private set
    var impulseLoadFailure: String? = null
        private set
    var currentDspParams = DspParams()
        private set
    @Volatile var reverbPrefs = com.aurora.music.data.ReverbPrefs()
        private set

    fun addPrecision(chain: PrecisionBlockProcessor) { precisionChains += chain }

    fun addCompatibility(chain: PrecisionBlockProcessor) { compatibilityChains += chain }

    // runtime-switchable via volatile flags no rebuild the two eq engines are mutually exclusive so they never stack
    fun apply() {
        val ap = lastAudioPrefs ?: return
        val mode = ap.dspMode
        val params = ap.dspParams(monoAudioPref)
        currentDspParams = params
        audioEffects?.setMasterEnabled(mode == DspMode.SYSTEM)

        if (ap.dspConvIrPath != lastIrPath) {
            lastIrPath = ap.dspConvIrPath
            scope.launch {
                val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (ap.dspConvIrPath.isBlank()) Result.success<ImpulseResponse?>(null)
                    else ImpulseResponse.loadWavResult(java.io.File(ap.dspConvIrPath))
                }
                if (lastIrPath != ap.dspConvIrPath) return@launch
                val ir = loaded.getOrNull()
                // wav validation messages are safe to share, file errors may leak local paths
                impulseLoadFailure = loaded.exceptionOrNull()?.let {
                    if (it is IllegalArgumentException) it.message else "The selected impulse response could not be read"
                }
                currentImpulse = ir
                // a slow load must not restore an older makeup gain
                val makeupDb = lastAudioPrefs?.dspConvMakeupDb ?: ap.dspConvMakeupDb
                (precisionChains + compatibilityChains).forEach { it.setImpulse(ir, makeupDb) }
                pushMix()
            }
        }
        (precisionChains + compatibilityChains).forEach { chain ->
            // off and system mono still run in binary64 before the output boundary
            chain.applyDsp(params, mode, monoAudioPref, ap.dspConvEnabled, ap.dspConvMakeupDb)
            chain.updateRack(lastRack.activeFor(mode))
            chain.reverbSettings = reverbPrefs
        }
        pushMix()
        refresh()
    }

    suspend fun loadRackImpulses(entries: List<ImpulseLibraryEntry>) {
        val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
            entries.mapNotNull { entry ->
                val file = com.aurora.music.data.ir.ImpulseLibraryFiles.validateAsset(entry, entry.prepared != null).getOrNull()
                file?.let { ImpulseResponse.loadWavResult(it).getOrNull() }?.let { entry.id to it }
            }.toMap()
        }
        rackImpulses = loaded
        (precisionChains + compatibilityChains).forEach { it.setRackImpulses(loaded) }
    }

    fun setReverb(prefs: com.aurora.music.data.ReverbPrefs) {
        reverbPrefs = prefs
        (precisionChains + compatibilityChains).forEach { it.reverbSettings = prefs }
    }

    fun setRelativeVolume(volume: Double) {
        (precisionChains + compatibilityChains).forEach { it.relativeVolume = volume }
    }

    fun mixAudioConfig(volume: Double, replayGain: Int, audioSessionId: Int) = com.aurora.music.mix.MixAudioConfig(
        params = currentDspParams, mode = lastAudioPrefs?.dspMode ?: DspMode.OFF,
        mono = monoAudioPref, impulse = currentImpulse, convolution = lastAudioPrefs?.dspConvEnabled == true,
        convolutionGain = lastAudioPrefs?.dspConvMakeupDb ?: 0f, audioSessionId = audioSessionId,
        replayGain = replayGain, rack = lastRack?.takeIf { it.enabled && lastAudioPrefs?.dspMode == DspMode.CUSTOM },
        rackImpulses = rackImpulses, tpdfDither = outputRatePolicy.tpdfDither, relativeVolume = volume,
        noiseShaping = outputRatePolicy.noiseShaping == true)
}
