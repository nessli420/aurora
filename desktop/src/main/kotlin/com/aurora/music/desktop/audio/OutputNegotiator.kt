package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.playback.engine.OutputRateDecision
import com.aurora.music.playback.engine.OutputRateNegotiator
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlin.math.abs

data class NegotiatedOutput(
    val deviceId: String?,
    val exclusive: Boolean,
    val sampleRate: Int,
    val encoding: OutputEncoding,
    val rateFallbackReason: String? = null,
    val fallbackReason: String? = null,
)

class OutputNegotiator(private val backend: OutputBackend) {
    private val encodings = HashMap<Pair<String?, Int>, List<OutputEncoding>>()
    private val mixRates = HashMap<String?, Int>()

    fun clear() {
        encodings.clear()
        mixRates.clear()
    }

    fun shared(deviceId: String?, fallbackReason: String? = null) = NegotiatedOutput(deviceId, false,
        mixRates.getOrPut(deviceId) { backend.mixRate(deviceId) ?: DEFAULT_RATE }, OutputEncoding.F32, fallbackReason = fallbackReason)

    fun negotiate(deviceId: String?, exclusive: Boolean, sourceRate: Int, policy: OutputRatePolicy): NegotiatedOutput {
        if (!exclusive) return shared(deviceId)
        val rates = (RATES + sourceRate).distinct().filter { supported(deviceId, it).isNotEmpty() }.toIntArray()
        if (rates.isEmpty()) return shared(deviceId, "Exclusive mode is unavailable on this device")
        val requested = OutputRateNegotiator.choose(sourceRate, policy, rates)
        val chosen = if (requested.sampleRate in rates) requested else {
            val family = if (sourceRate % 11_025 == 0) 44_100 else 48_000
            val rate = rates.filter { it % family == 0 }.ifEmpty { rates.toList() }.minBy { abs(it.toLong() - sourceRate) }
            OutputRateDecision(rate, "${requested.sampleRate} Hz is unavailable; using $rate Hz.")
        }
        return NegotiatedOutput(deviceId, true, chosen.sampleRate, supported(deviceId, chosen.sampleRate).first(), chosen.fallbackReason)
    }

    private fun supported(deviceId: String?, rate: Int) = encodings.getOrPut(deviceId to rate) {
        PREFERENCE.filter { backend.supportsExclusive(deviceId, rate, it) }
    }

    companion object {
        val PREFERENCE = listOf(OutputEncoding.S24_IN_32, OutputEncoding.S32, OutputEncoding.S24, OutputEncoding.S16)
        private val RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
        private const val DEFAULT_RATE = 48_000
    }
}
