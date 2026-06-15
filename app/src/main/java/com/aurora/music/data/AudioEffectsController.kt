package com.aurora.music.data

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// effect calls guarded some devices dont implement every effect
class AudioEffectsController(
    private val sessionId: Int,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
) {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    var bandCount: Int = 5; private set
    var bandFreqsHz: List<Int> = listOf(60, 230, 910, 3600, 14000); private set
    var minBandMb: Int = -1500; private set
    var maxBandMb: Int = 1500; private set
    var presetNames: List<String> = emptyList(); private set
    var available: Boolean = false; private set

    // gated here not in prefs so system effects never stack with custom dsp while ui keeps values
    @Volatile private var masterEnabled: Boolean = true
    @Volatile private var lastPrefs: AudioPrefs? = null

    init {
        create()
        scope.launch { settingsStore.audioPrefs.collect { lastPrefs = it; apply(it) } }
    }

    fun setMasterEnabled(on: Boolean) {
        if (masterEnabled == on) return
        masterEnabled = on
        lastPrefs?.let { apply(it) }
    }

    private fun create() {
        runCatching {
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            bandCount = eq.numberOfBands.toInt()
            bandFreqsHz = (0 until bandCount).map { (eq.getCenterFreq(it.toShort()) / 1000) }
            val range = eq.bandLevelRange
            minBandMb = range[0].toInt()
            maxBandMb = range[1].toInt()
            presetNames = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            available = true
        }
        runCatching { bassBoost = BassBoost(0, sessionId) }
        runCatching { virtualizer = Virtualizer(0, sessionId) }
        runCatching { loudness = LoudnessEnhancer(sessionId) }
    }

    private fun apply(prefs: AudioPrefs) {
        if (!masterEnabled) {
            runCatching { equalizer?.setEnabled(false) }
            runCatching { bassBoost?.setEnabled(false) }
            runCatching { virtualizer?.setEnabled(false) }
            runCatching { loudness?.setEnabled(false) }
            return
        }
        runCatching {
            equalizer?.let { eq ->
                eq.setEnabled(prefs.eqEnabled)
                if (prefs.eqEnabled) {
                    if (prefs.eqPreset in 0 until presetNames.size) {
                        eq.usePreset(prefs.eqPreset.toShort())
                    } else {
                        for (i in 0 until bandCount) {
                            val mb = prefs.eqBands.getOrElse(i) { 0 }.coerceIn(minBandMb, maxBandMb)
                            eq.setBandLevel(i.toShort(), mb.toShort())
                        }
                    }
                }
            }
        }
        runCatching {
            bassBoost?.let { bb ->
                bb.setEnabled(prefs.bassBoost > 0)
                if (bb.strengthSupported) bb.setStrength(prefs.bassBoost.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            virtualizer?.let { v ->
                v.setEnabled(prefs.virtualizer > 0)
                if (v.strengthSupported) v.setStrength(prefs.virtualizer.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            loudness?.let { l ->
                l.setEnabled(prefs.loudnessGain > 0)
                l.setTargetGain(prefs.loudnessGain.coerceIn(0, 2000))
            }
        }
    }

    fun presetBandLevels(preset: Int): List<Int> = runCatching {
        val eq = equalizer ?: return emptyList()
        eq.usePreset(preset.toShort())
        (0 until bandCount).map { eq.getBandLevel(it.toShort()).toInt() }
    }.getOrDefault(List(bandCount) { 0 })
}
