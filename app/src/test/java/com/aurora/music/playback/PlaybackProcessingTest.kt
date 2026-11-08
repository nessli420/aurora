package com.aurora.music.playback

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingRack
import com.aurora.music.playback.chain.applyDsp
import com.aurora.music.playback.chain.dspParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackProcessingTest {
    private val calls = mutableListOf<String>()
    private val processing = PlaybackProcessing(CoroutineScope(Dispatchers.Unconfined), { calls += "pushMix" }, { calls += "refresh" })

    @Test fun applyConfiguresEveryChainLikeTheSharedDspMapping() {
        val precision = PrecisionBlockProcessor().also { processing.addPrecision(it) }
        val compatibility = PrecisionBlockProcessor().also { processing.addCompatibility(it) }
        val cases = listOf(AudioPrefs(dspMode = DspMode.CUSTOM, dspConvEnabled = true) to false,
            AudioPrefs(dspMode = DspMode.OFF) to false, AudioPrefs(dspMode = DspMode.OFF) to true)
        for ((prefs, mono) in cases) {
            processing.lastAudioPrefs = prefs
            processing.monoAudioPref = mono
            processing.apply()
            val reference = PrecisionBlockProcessor().apply {
                applyDsp(prefs.dspParams(mono), prefs.dspMode, mono, prefs.dspConvEnabled, prefs.dspConvMakeupDb)
            }
            for (chain in listOf(precision, compatibility)) {
                assertEquals("mode ${prefs.dspMode} mono $mono", reference.enabled, chain.enabled)
                assertEquals("mode ${prefs.dspMode} mono $mono", reference.convolutionEnabled, chain.convolutionEnabled)
            }
        }
    }

    @Test fun applyPushesTheMixBeforeRefreshingAndNeedsSettings() {
        processing.apply()
        assertEquals(emptyList<String>(), calls)
        processing.lastAudioPrefs = AudioPrefs(dspMode = DspMode.OFF)
        processing.apply()
        assertEquals(listOf("pushMix", "refresh"), calls)
    }

    @Test fun mixConfigPassesValuesThroughAndOnlyCarriesAnEnabledCustomRack() {
        val prefs = AudioPrefs(dspMode = DspMode.CUSTOM)
        val rack = ProcessingRack.legacy(prefs).copy(enabled = true)
        processing.lastAudioPrefs = prefs
        processing.lastRack = rack
        val config = processing.mixAudioConfig(0.25, replayGain = 2, audioSessionId = 7)
        assertEquals(0.25, config.relativeVolume, 0.0)
        assertEquals(2, config.replayGain)
        assertEquals(7, config.audioSessionId)
        assertSame(rack, config.rack)
        processing.lastAudioPrefs = prefs.copy(dspMode = DspMode.OFF)
        assertNull(processing.mixAudioConfig(1.0, 0, 0).rack)
        processing.lastAudioPrefs = prefs
        processing.lastRack = rack.copy(enabled = false)
        assertNull(processing.mixAudioConfig(1.0, 0, 0).rack)
    }
}
