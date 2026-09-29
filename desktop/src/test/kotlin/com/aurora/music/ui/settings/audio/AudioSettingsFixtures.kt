package com.aurora.music.ui.settings.audio

import com.aurora.music.data.AudioMeasurements
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.AudioSpectrum
import com.aurora.music.data.ParamBand
import com.aurora.music.data.PcmLevels
import com.aurora.music.data.Preservation
import com.aurora.music.data.SignalFormat
import com.aurora.music.data.SignalPath
import com.aurora.music.data.SignalStage
import com.aurora.music.playback.engine.RackNodeMeter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sin

internal object AudioFixtures {
    val customAudio = AudioPrefs(
        dspGraphicBands = listOf(3f, 2f, 1f, 0f, -1f, -2f, 0f, 1f, 2f, 3f),
        dspParametric = listOf(ParamBand(60f, 4f, 0.8f), ParamBand(3_200f, -3f, 2f), ParamBand(9_000f, 2.5f, 1.2f)),
        dspPreampDb = -3f,
        dspWidth = 1.2f,
        dspCrossfeed = 0.3f,
    )

    private fun levels(peak: Double, rms: Double) = PcmLevels(48_000, 2, 4_800, 480_000, peak, peak * 0.93, rms, rms * 0.95, 0, 0, null, 0)

    val activePath = SignalPath(
        active = true,
        note = "Samples are processed by the custom DSP chain.",
        preservation = Preservation.MODIFIED,
        reasons = listOf("Samples are processed by the custom DSP chain.", "Equalizer and limiter are active"),
        source = SignalStage("Source", "Local FLAC file", "Read from disk", SignalFormat(44_100, 24, 2, "FLAC")),
        decoder = SignalStage("Decoder", "FFmpeg", "Decoded to float PCM", SignalFormat(44_100, 32, 2, "PCM float")),
        processing = SignalStage("Processing", "Custom DSP: EQ, limiter", "Engine report"),
        resampling = SignalStage("Resampling", "44.1 kHz to 48 kHz", "Output negotiation", SignalFormat(48_000, 32, 2)),
        outputStage = SignalStage("Output", "WASAPI shared", "Endpoint mix format", SignalFormat(48_000, 32, 2, "IEEE float")),
        device = SignalStage("Device", "Speakers (Realtek)", "Default endpoint"),
        measurements = AudioMeasurements(levels(0.71, 0.21), levels(0.52, 0.17), playing = true, afterAvailable = true, overlappingPlayers = false,
            spectrum = AudioSpectrum(48_000, 0, 0,
                List(513) { bin -> (-30f - bin * 0.09f + 8f * sin(bin / 9f)).coerceAtLeast(-98f) },
                List(513) { bin -> (-34f - bin * 0.08f + 6f * sin(bin / 11f)).coerceAtLeast(-98f) })),
        nodeMeters = listOf(RackNodeMeter("eq", 0.62, -1.5, emptyList())),
    )

    fun impulseWav(frames: Int = 2_048, rate: Int = 48_000): ByteArray {
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val envelope = exp(-i / 180.0)
            data.putShort((envelope * sin(i * 0.21) * 26_000).toInt().toShort())
            data.putShort((envelope * sin(i * 0.17 + 0.6) * 22_000).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(frames * 4)
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }
}
