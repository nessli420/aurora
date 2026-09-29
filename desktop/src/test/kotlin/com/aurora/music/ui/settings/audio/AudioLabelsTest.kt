package com.aurora.music.ui.settings.audio

import com.aurora.music.data.ir.ImpulseMetadata
import com.aurora.music.localization.AppStrings
import com.aurora.music.playback.engine.SamplePrecision
import com.aurora.music.ui.screens.settings.impulseNumber
import com.aurora.music.ui.screens.settings.peakLabel
import com.aurora.music.ui.screens.settings.summary
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLabelsTest {
    @Test fun impulseLabelsTrimAndFormat() {
        AppStrings.setLocale("en")
        try {
            assertEquals("42.67", impulseNumber(2_048 * 1000.0 / 48_000))
            assertEquals("48", impulseNumber(48.0))
            assertEquals("−∞ dBFS", 0.0.peakLabel())
            assertEquals("0 dBFS", 1.0.peakLabel())
            assertEquals("-6.02 dBFS", 0.5.peakLabel())
            assertEquals("Stereo · 44.1 kHz", ImpulseMetadata(44_100, 2, 1_024, SamplePrecision.PCM_SIGNED_16, 16, 0.5).summary())
            assertEquals("True stereo · LL/LR/RL/RR · 48 kHz", ImpulseMetadata(48_000, 4, 1_024, SamplePrecision.FLOAT_32, 32, 0.5).summary())
        } finally {
            AppStrings.setLocale("")
        }
    }
}
