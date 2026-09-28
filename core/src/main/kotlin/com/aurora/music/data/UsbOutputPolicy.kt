package com.aurora.music.data

enum class UsbOutputMode { DIRECT, PROCESSED }
enum class UsbFallbackPolicy { PAUSE, ANDROID }
enum class UsbDsdMode { PCM, DOP, NATIVE }

object UsbOutputPolicy {
    const val MODE_KEY = "usb_output_mode"
    const val FALLBACK_KEY = "usb_fallback_policy"

    fun decodeMode(value: String?): Result<UsbOutputMode> = runCatching {
        if (value == null) UsbOutputMode.DIRECT else UsbOutputMode.entries.firstOrNull { it.name == value }
            ?: error("Unsupported USB output mode.")
    }

    fun decodeFallback(value: String?): Result<UsbFallbackPolicy> = runCatching {
        if (value == null) UsbFallbackPolicy.PAUSE else UsbFallbackPolicy.entries.firstOrNull { it.name == value }
            ?: error("Unsupported USB fallback policy.")
    }
}

enum class AndroidMixerCapability { NOT_REPORTED, STANDARD, BIT_PERFECT }

data class AndroidOutputCapabilities(
    val route: String? = null,
    val reportedRates: List<Int>? = null,
    val mixer: AndroidMixerCapability = AndroidMixerCapability.NOT_REPORTED,
) {
    fun describe(): String {
        val activeRoute = route ?: return "Start playback to inspect Android output."
        val rates = reportedRates?.filter { it > 0 }?.distinct()?.sorted()
        val rateDescription = when {
            rates == null -> "Rates not reported"
            rates.isEmpty() -> "No rate restriction reported"
            else -> "Reported rates: " + rates.joinToString(" / ") {
                if (it % 1000 == 0) "${it / 1000}" else "${it / 1000.0}"
            } + " kHz"
        }
        val mixerDescription = when (mixer) {
            AndroidMixerCapability.NOT_REPORTED -> "Mixer controls not reported"
            AndroidMixerCapability.STANDARD -> "Standard mixer controls"
            AndroidMixerCapability.BIT_PERFECT -> "Bit-perfect mixer preference available"
        }
        return "$activeRoute\n$rateDescription\n$mixerDescription. DAC format is not verified."
    }
}
