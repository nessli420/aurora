package com.aurora.music.playback.chain

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.PrecisionBlockProcessor
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class DspSettingsTest {
    private val custom = AudioPrefs(
        dspMode = DspMode.CUSTOM, dspGraphicLayout = 1, dspGraphicBands = listOf(3f, -2f, 1.5f),
        dspParametric = listOf(ParamBand(freqHz = 900f, gainDb = -4f, q = 1.2f)),
        dspPreampDb = -3f, dspBalance = 0.2f, dspWidth = 1.4f, dspCrossfeed = 0.3f, dspSaturation = 0.1f,
        dspDelayLeftMs = 0.5f, dspTrimRightDb = -1f, dspLimiterCeilingDb = -1f, dspCompEnabled = true,
        dspCompThreshDb = -20f, dspCompRatio = 3f, dspConvMakeupDb = 2f,
    )

    @Test fun paramsFollowTheSelectedGraphicLayoutAndMono() {
        val params = custom.dspParams(mono = false)
        val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS[1]
        assertArrayEquals(layout.freqs, params.graphicFreqs, 0f)
        assertEquals(layout.q, params.graphicQ)
        assertEquals(15, params.graphic.size)
        assertEquals(listOf(3f, -2f, 1.5f, 0f), params.graphic.take(4))
        assertEquals(listOf(DspBand.from(custom.dspParametric[0])), params.parametric)
        assertEquals(1.4f, params.width)
        assertEquals(0f, custom.dspParams(mono = true).width)
        assertEquals(DspCoeffBuilder.GRAPHIC_LAYOUTS[0].freqs.size, AudioPrefs(dspGraphicLayout = 9).dspParams(false).graphic.size)
    }

    @Test fun sharedMappingMatchesThePlaybackServiceMappingSampleForSample() {
        for (mono in listOf(false, true)) for (mode in listOf(DspMode.CUSTOM, DspMode.OFF, DspMode.SYSTEM)) {
            val prefs = custom.copy(dspMode = mode)
            val shared = PrecisionBlockProcessor().apply { applySettings(DspChainSettings(prefs, mono)) }
            val inline = PrecisionBlockProcessor().apply { legacyApply(prefs, mono) }
            assertEquals(inline.enabled, shared.enabled)
            assertEquals(inline.convolutionEnabled, shared.convolutionEnabled)
            assertArrayEquals("mode $mode mono $mono", render(inline), render(shared), 0.0)
        }
    }

    @Test fun offModeOnlyProcessesForMonoAndRacksNeedCustomMode() {
        val off = PrecisionBlockProcessor().apply { applySettings(DspChainSettings(AudioPrefs(dspMode = DspMode.OFF))) }
        assertFalse(off.enabled)
        val input = input()
        assertArrayEquals(input, render(off), 0.0)
        assertTrue(PrecisionBlockProcessor().apply { applySettings(DspChainSettings(AudioPrefs(dspMode = DspMode.OFF), mono = true)) }.enabled)
        val rack = ProcessingRack.legacy(custom, false).copy(enabled = true)
        assertSame(rack, rack.activeFor(DspMode.CUSTOM))
        assertNull(rack.activeFor(DspMode.OFF))
        assertNull(rack.copy(enabled = false).activeFor(DspMode.CUSTOM))
    }

    private fun PrecisionBlockProcessor.legacyApply(ap: AudioPrefs, mono: Boolean) {
        val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(ap.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
        val params = DspParams(
            graphic = FloatArray(layout.freqs.size) { ap.dspGraphicBands.getOrElse(it) { 0f } },
            graphicFreqs = layout.freqs, graphicQ = layout.q, parametric = ap.dspParametric.map(DspBand::from),
            preampDb = ap.dspPreampDb, balance = ap.dspBalance, width = if (mono) 0f else ap.dspWidth,
            crossfeed = ap.dspCrossfeed, saturation = ap.dspSaturation, delayLeftMs = ap.dspDelayLeftMs,
            delayRightMs = ap.dspDelayRightMs, trimLeftDb = ap.dspTrimLeftDb, trimRightDb = ap.dspTrimRightDb,
            limiterEnabled = ap.dspLimiterEnabled, limiterCeilingDb = ap.dspLimiterCeilingDb, compEnabled = ap.dspCompEnabled,
            compThreshDb = ap.dspCompThreshDb, compRatio = ap.dspCompRatio,
        )
        update(if (ap.dspMode == DspMode.CUSTOM) params else DspParams(width = if (mono) 0f else 1f, limiterEnabled = false))
        enabled = ap.dspMode == DspMode.CUSTOM || mono
        convolutionEnabled = ap.dspConvEnabled
        setMakeup(ap.dspConvMakeupDb)
        updateRack(null)
    }

    private fun input() = DoubleArray(4_096 * 2) { sin(it * 0.021) * if (it % 2 == 0) 0.7 else -0.4 }

    private fun render(processor: PrecisionBlockProcessor): DoubleArray {
        processor.configure(48_000)
        val input = input()
        val block = AudioBlock(AudioStreamFormat(48_000, ChannelLayout.STEREO), PrecisionBlockProcessor.INPUT_FRAMES)
        val result = ArrayList<Double>()
        var offset = 0
        while (offset < input.size / 2) {
            val count = minOf(PrecisionBlockProcessor.INPUT_FRAMES, input.size / 2 - offset)
            block.begin(count)
            System.arraycopy(input, offset * 2, block.samples, 0, count * 2)
            while (true) { val out = processor.getOutput() ?: break; for (i in 0 until out.sampleCount) result += out.samples[i] }
            if (processor.queueInput(block)) offset += count
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) { val out = processor.getOutput() ?: break; for (i in 0 until out.sampleCount) result += out.samples[i] }
        return result.toDoubleArray()
    }
}
