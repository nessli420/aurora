package com.aurora.music.playback.engine

enum class OutputRateMode { FOLLOW_SOURCE, FIXED, COMPATIBLE_MAXIMUM }

data class OutputRatePolicy(
    val mode: OutputRateMode = OutputRateMode.FOLLOW_SOURCE,
    val fixedRate: Int = 48_000,
    val preserveFamily: Boolean = true,
    val maximumRate: Int = 192_000,
    val tpdfDither: Boolean = false,
)

data class OutputRateDecision(val sampleRate: Int, val fallbackReason: String? = null)

object OutputRateNegotiator {
    fun choose(sourceRate: Int, policy: OutputRatePolicy, supportedRates: IntArray): OutputRateDecision {
        require(sourceRate in 8_000..768_000)
        val supported = supportedRates.filter { it in 8_000..384_000 }.distinct().sorted()
        if (policy.mode == OutputRateMode.FOLLOW_SOURCE) return OutputRateDecision(sourceRate)
        if (supported.isEmpty()) return OutputRateDecision(sourceRate, "Output rates are unknown; following source.")
        val requested = when (policy.mode) {
            OutputRateMode.FIXED -> policy.fixedRate
            OutputRateMode.COMPATIBLE_MAXIMUM -> {
                val family = if (sourceRate % 11_025 == 0) 44_100 else 48_000
                supported.filter { it <= policy.maximumRate && (!policy.preserveFamily || it % family == 0) }.maxOrNull()
                    ?: return OutputRateDecision(sourceRate, "No supported rate in the source family; following source.")
            }
            else -> sourceRate
        }
        return if (requested in supported) OutputRateDecision(requested)
        else OutputRateDecision(sourceRate, "$requested Hz is unavailable; following source.")
    }
}
