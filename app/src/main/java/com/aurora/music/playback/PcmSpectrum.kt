package com.aurora.music.playback

import androidx.media3.common.C
import com.aurora.music.data.AlignedSpectrum
import kotlin.math.*

internal data class SpectrumWindow(val rate: Int, val timeUs: Long, val measuredAt: Long,
    val left: FloatArray, val right: FloatArray)

internal class PcmSpectrumTap {
    private class Slot {
        @Volatile var revision = 0L
        var rate = 0; var timeUs = C.TIME_UNSET; var measuredAt = 0L
        val left = FloatArray(SIZE); val right = FloatArray(SIZE)
    }
    private val slots = Array(8) { Slot() }
    private var slotIndex = 0
    private var position = 0
    private var validTime = false
    private var expectedUs = C.TIME_UNSET

    fun reset() {
        slots.forEach { if (it.revision and 1L == 0L) it.revision++; it.measuredAt = 0; it.revision++ }
        slotIndex = 0; position = 0; expectedUs = C.TIME_UNSET
    }

    fun observe(left: Double, right: Double, rate: Int, timeUs: Long) {
        if (timeUs != C.TIME_UNSET && expectedUs != C.TIME_UNSET && abs(timeUs - expectedUs) > 2_000_000L / rate) {
            slots[slotIndex].apply { if (revision and 1L == 0L) revision++; measuredAt = 0; revision++ }
            position = 0
        }
        val slot = slots[slotIndex]
        if (position == 0) {
            slot.revision++
            slot.rate = rate; slot.timeUs = timeUs
            validTime = timeUs != C.TIME_UNSET
        }
        validTime = validTime && timeUs != C.TIME_UNSET
        slot.left[position] = left.toFloat(); slot.right[position] = right.toFloat()
        position++
        expectedUs = if (timeUs == C.TIME_UNSET) C.TIME_UNSET else timeUs + 1_000_000L / rate
        if (position == SIZE) {
            if (!validTime) slot.timeUs = C.TIME_UNSET
            slot.measuredAt = System.nanoTime(); slot.revision++
            slotIndex = (slotIndex + 1) % slots.size; position = 0
        }
    }

    fun windows(): List<SpectrumWindow> = slots.mapNotNull { slot ->
        val revision = slot.revision
        if (revision and 1L != 0L || slot.measuredAt == 0L || slot.timeUs == C.TIME_UNSET) return@mapNotNull null
        val result = SpectrumWindow(slot.rate, slot.timeUs, slot.measuredAt, slot.left.copyOf(), slot.right.copyOf())
        result.takeIf { revision == slot.revision }
    }

    companion object { const val SIZE = 2048 }
}

internal object PcmSpectrumAnalyzer {
    fun aligned(before: List<SpectrumWindow>, after: List<SpectrumWindow>): AlignedSpectrum? {
        val pair = after.sortedByDescending { it.timeUs }.firstNotNullOfOrNull { output ->
            before.firstOrNull { input -> input.rate == output.rate && abs(input.timeUs - output.timeUs) <= 1_000_000L / input.rate &&
                abs(input.measuredAt - output.measuredAt) < 1_000_000_000L }?.let { it to output }
        } ?: return null
        val (input, output) = pair
        return AlignedSpectrum(input.rate, input.timeUs, min(input.measuredAt, output.measuredAt),
            spectrum(input), spectrum(output))
    }

    private fun spectrum(window: SpectrumWindow): List<Float> {
        val size = PcmSpectrumTap.SIZE
        val fft = Fft(size)
        val power = DoubleArray(size / 2 + 1)
        for (channel in listOf(window.left, window.right)) {
            val real = FloatArray(size) { i -> (channel[i] * (.5 - .5 * cos(2 * PI * i / size))).toFloat() }
            val imaginary = FloatArray(size)
            fft.transform(real, imaginary, false)
            for (i in power.indices) power[i] += (real[i].toDouble().pow(2) + imaginary[i].toDouble().pow(2)) / 2
        }
        return power.mapIndexed { i, value ->
            val calibration = if (i == 0 || i == size / 2) 4.0 else 16.0
            (10 * log10((value * calibration / (size.toDouble() * size)).coerceAtLeast(1e-16))).toFloat()
        }
    }
}
