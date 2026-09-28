package com.aurora.music.playback.chain

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingRack
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.PrecisionBlockProcessor

data class DspChainSettings(
    val audio: AudioPrefs = AudioPrefs(dspMode = DspMode.OFF),
    val mono: Boolean = false,
    val rack: ProcessingRack? = null,
)

fun AudioPrefs.dspParams(mono: Boolean): DspParams {
    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
    return DspParams(
        graphic = FloatArray(layout.freqs.size) { dspGraphicBands.getOrElse(it) { 0f } },
        graphicFreqs = layout.freqs,
        graphicQ = layout.q,
        parametric = dspParametric.map(DspBand::from),
        preampDb = dspPreampDb,
        balance = dspBalance,
        width = if (mono) 0f else dspWidth,
        crossfeed = dspCrossfeed,
        saturation = dspSaturation,
        delayLeftMs = dspDelayLeftMs,
        delayRightMs = dspDelayRightMs,
        trimLeftDb = dspTrimLeftDb,
        trimRightDb = dspTrimRightDb,
        limiterEnabled = dspLimiterEnabled,
        limiterCeilingDb = dspLimiterCeilingDb,
        compEnabled = dspCompEnabled,
        compThreshDb = dspCompThreshDb,
        compRatio = dspCompRatio,
    )
}

fun ProcessingRack?.activeFor(mode: Int): ProcessingRack? = this?.takeIf { it.enabled && mode == DspMode.CUSTOM }

fun PrecisionBlockProcessor.applyDsp(params: DspParams, mode: Int, mono: Boolean, convolution: Boolean, makeupDb: Float) {
    update(if (mode == DspMode.CUSTOM) params.copy(width = if (mono) 0f else params.width)
        else DspParams(width = if (mono) 0f else 1f, limiterEnabled = false))
    enabled = mode == DspMode.CUSTOM || mono
    convolutionEnabled = convolution
    setMakeup(makeupDb)
}

fun PrecisionBlockProcessor.applySettings(settings: DspChainSettings, params: DspParams = settings.audio.dspParams(settings.mono)) {
    val mode = settings.audio.dspMode
    applyDsp(params, mode, settings.mono, settings.audio.dspConvEnabled, settings.audio.dspConvMakeupDb)
    updateRack(settings.rack.activeFor(mode))
}
