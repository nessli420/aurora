package com.aurora.music.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.aurora.music.data.SettingsKeys as Keys

internal fun readAudioPrefs(p: Preferences): AudioPrefs =
    AudioPrefs(
        eqEnabled = p[Keys.EQ_ENABLED] ?: false,
        eqPreset = p[Keys.EQ_PRESET] ?: -1,
        eqBands = p[Keys.EQ_BANDS]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
        bassBoost = p[Keys.BASS_BOOST] ?: 0,
        virtualizer = p[Keys.VIRTUALIZER] ?: 0,
        loudnessGain = p[Keys.LOUDNESS] ?: 0,
        replayGain = p[Keys.REPLAY_GAIN] ?: 0,
        dspMode = p[Keys.DSP_MODE] ?: DspMode.SYSTEM,
        dspGraphicBands = p[Keys.DSP_GRAPHIC]?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: emptyList(),
        dspParametric = parseParametric(p[Keys.DSP_PARAMETRIC]),
        dspPreampDb = p[Keys.DSP_PREAMP] ?: 0f,
        dspBalance = p[Keys.DSP_BALANCE] ?: 0f,
        dspWidth = p[Keys.DSP_WIDTH] ?: 1f,
        dspCrossfeed = p[Keys.DSP_CROSSFEED] ?: 0f,
        dspLimiterEnabled = p[Keys.DSP_LIMITER] ?: true,
        dspLimiterCeilingDb = p[Keys.DSP_CEILING] ?: -0.3f,
        dspCompEnabled = p[Keys.DSP_COMP] ?: false,
        dspCompThreshDb = p[Keys.DSP_COMP_THRESH] ?: -18f,
        dspCompRatio = p[Keys.DSP_COMP_RATIO] ?: 2f,
        dspConvEnabled = p[Keys.DSP_CONV] ?: false,
        dspConvIrPath = p[Keys.DSP_CONV_PATH].orEmpty(),
        dspConvIrName = p[Keys.DSP_CONV_NAME].orEmpty(),
        dspConvMakeupDb = p[Keys.DSP_CONV_MAKEUP] ?: 0f,
        dspGraphicLayout = p[Keys.DSP_GRAPHIC_LAYOUT] ?: 0,
        dspSaturation = p[Keys.DSP_SATURATION] ?: 0f,
        dspDelayLeftMs = p[Keys.DSP_DELAY_L] ?: 0f,
        dspDelayRightMs = p[Keys.DSP_DELAY_R] ?: 0f,
        dspTrimLeftDb = p[Keys.DSP_TRIM_L] ?: 0f,
        dspTrimRightDb = p[Keys.DSP_TRIM_R] ?: 0f,
    )

internal fun writeProcessingAudio(p: MutablePreferences, a: AudioPrefs) {
    p[Keys.EQ_ENABLED] = a.eqEnabled
    p[Keys.EQ_PRESET] = a.eqPreset
    p[Keys.EQ_BANDS] = a.eqBands.joinToString(",")
    p[Keys.BASS_BOOST] = a.bassBoost
    p[Keys.VIRTUALIZER] = a.virtualizer
    p[Keys.LOUDNESS] = a.loudnessGain
    p[Keys.REPLAY_GAIN] = a.replayGain
    p[Keys.DSP_MODE] = a.dspMode
    p[Keys.DSP_GRAPHIC] = a.dspGraphicBands.joinToString(",")
    p[Keys.DSP_PREAMP] = a.dspPreampDb
    p[Keys.DSP_BALANCE] = a.dspBalance
    p[Keys.DSP_WIDTH] = a.dspWidth
    p[Keys.DSP_CROSSFEED] = a.dspCrossfeed
    p[Keys.DSP_LIMITER] = a.dspLimiterEnabled
    p[Keys.DSP_CEILING] = a.dspLimiterCeilingDb
    p[Keys.DSP_COMP] = a.dspCompEnabled
    p[Keys.DSP_COMP_THRESH] = a.dspCompThreshDb
    p[Keys.DSP_COMP_RATIO] = a.dspCompRatio
    p[Keys.DSP_CONV] = a.dspConvEnabled
    p[Keys.DSP_CONV_PATH] = a.dspConvIrPath
    p[Keys.DSP_CONV_NAME] = a.dspConvIrName
    p[Keys.DSP_CONV_MAKEUP] = a.dspConvMakeupDb
    p[Keys.DSP_GRAPHIC_LAYOUT] = a.dspGraphicLayout
    p[Keys.DSP_SATURATION] = a.dspSaturation
    p[Keys.DSP_DELAY_L] = a.dspDelayLeftMs
    p[Keys.DSP_DELAY_R] = a.dspDelayRightMs
    p[Keys.DSP_TRIM_L] = a.dspTrimLeftDb
    p[Keys.DSP_TRIM_R] = a.dspTrimRightDb
    p[Keys.DSP_PARAMETRIC] = ParamBandCodec.encodePreference(a.dspParametric)
}

internal fun writeProcessingPlayback(p: MutablePreferences, a: ProcessingPlaybackPrefs) {
    p[Keys.OUTPUT_RATE_POLICY] = OutputRatePolicyCodec.encode(a.outputRatePolicy)
    p[Keys.SKIP_SILENCE] = a.skipSilence
    p[Keys.CROSSFADE] = a.crossfadeSec
    p[Keys.CROSSFADE_CURVE] = a.crossfadeCurve
    p[Keys.CROSSFADE_HEADROOM] = a.crossfadeHeadroom
    p[Keys.GAPLESS] = a.gapless
    p[Keys.DEFAULT_SPEED] = a.defaultSpeed
    p[Keys.MONO] = a.monoAudio
    p[Keys.PREFER_HIRES] = a.preferHighRes
    p[Keys.BIT_PERFECT_USB] = a.bitPerfectUsb
    p[Keys.USB_OUTPUT_MODE] = a.usbOutputMode.name
    p[Keys.USB_FALLBACK_POLICY] = a.usbFallbackPolicy.name
    p[Keys.INDEPENDENT_OUTPUT] = a.independentOutput
}

private fun parseParametric(s: String?): List<ParamBand> = ParamBandCodec.decodePreference(s)

internal fun readPlaybackPrefs(p: Preferences): PlaybackPrefs =
    PlaybackPrefs(
        skipSilence = p[Keys.SKIP_SILENCE] ?: false,
        crossfadeSec = p[Keys.CROSSFADE] ?: 0,
        crossfadeCurve = p[Keys.CROSSFADE_CURVE] ?: "SMOOTH",
        crossfadeHeadroom = p[Keys.CROSSFADE_HEADROOM] ?: true,
        gapless = p[Keys.GAPLESS] ?: true,
        defaultSpeed = p[Keys.DEFAULT_SPEED] ?: 1.0f,
        monoAudio = p[Keys.MONO] ?: false,
        streamWifi = p[Keys.STREAM_WIFI] ?: 0,
        streamCellular = p[Keys.STREAM_CELLULAR] ?: 0,
        downloadBitrate = p[Keys.DOWNLOAD_BITRATE] ?: 0,
        preferHighRes = p[Keys.PREFER_HIRES] ?: false,
        scrobble = p[Keys.SCROBBLE] ?: true,
        autoplayRadio = p[Keys.AUTOPLAY_RADIO] ?: false,
        bitPerfectUsb = p[Keys.BIT_PERFECT_USB] ?: false,
        usbOutputMode = UsbOutputPolicy.decodeMode(p[Keys.USB_OUTPUT_MODE]).getOrThrow(),
        usbFallbackPolicy = UsbOutputPolicy.decodeFallback(p[Keys.USB_FALLBACK_POLICY]).getOrThrow(),
        usbDsdMode = p[Keys.USB_DSD_MODE]?.let { value -> UsbDsdMode.entries.firstOrNull { it.name == value } } ?: UsbDsdMode.PCM,
        usbDsdExperimental = p[Keys.USB_DSD_EXPERIMENTAL] ?: false,
        independentOutput = p[Keys.INDEPENDENT_OUTPUT] ?: false,
        outputRatePolicy = OutputRatePolicyCodec.decode(p[Keys.OUTPUT_RATE_POLICY]).getOrThrow(),
    )

internal fun readVisualizerPrefs(p: Preferences): VisualizerPrefs {
    val d = VisualizerPrefs()
    return VisualizerPrefs(
        style = p[Keys.VIZ_STYLE] ?: d.style,
        colorSource = p[Keys.VIZ_COLOR_SOURCE] ?: d.colorSource,
        primaryColor = p[Keys.VIZ_PRIMARY] ?: d.primaryColor,
        secondaryColor = p[Keys.VIZ_SECONDARY] ?: d.secondaryColor,
        background = p[Keys.VIZ_BACKGROUND] ?: d.background,
        barCount = p[Keys.VIZ_BAR_COUNT] ?: d.barCount,
        smoothing = p[Keys.VIZ_SMOOTHING] ?: d.smoothing,
        sensitivity = p[Keys.VIZ_SENSITIVITY] ?: d.sensitivity,
        minHz = p[Keys.VIZ_MIN_HZ] ?: d.minHz,
        maxHz = p[Keys.VIZ_MAX_HZ] ?: d.maxHz,
        peakHold = p[Keys.VIZ_PEAK_HOLD] ?: d.peakHold,
        mirror = p[Keys.VIZ_MIRROR] ?: d.mirror,
        fftSize = p[Keys.VIZ_FFT_SIZE] ?: d.fftSize,
        fpsCap = p[Keys.VIZ_FPS] ?: d.fpsCap,
        rotate = p[Keys.VIZ_ROTATE] ?: d.rotate,
        particleCount = p[Keys.VIZ_PARTICLES] ?: d.particleCount,
        showAlbumArt = p[Keys.VIZ_ALBUM_ART] ?: d.showAlbumArt,
        showTrackInfo = p[Keys.VIZ_TRACK_INFO] ?: d.showTrackInfo,
    )
}
