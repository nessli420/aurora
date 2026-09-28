package com.aurora.music.playback

import androidx.media3.common.C
import com.aurora.music.data.AudioSpectrum
import kotlin.math.*

internal data class SpectrumWindow(val rate: Int, val timeUs: Long, val measuredAt: Long,
    val left: FloatArray, val right: FloatArray)

internal class PcmSpectrumTap {
    @Volatile var generation = 0L
        private set
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
        slotIndex = 0; position = 0; validTime = false; expectedUs = C.TIME_UNSET
        generation++
    }

    fun observe(left: Double, right: Double, rate: Int, timeUs: Long) {
        if (timeUs != C.TIME_UNSET && expectedUs != C.TIME_UNSET && abs(timeUs - expectedUs) > 2_000_000L / rate) {
            reset()
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
    private const val MAX_PAIR_AGE_NANOS = 1_000_000_000L
    private const val MAX_CAPTURE_AGE_NANOS = 2_000_000_000L

    fun source(before: List<SpectrumWindow>): AudioSpectrum? = before.filter(::complete)
        .maxByOrNull { it.measuredAt }?.let { input ->
            AudioSpectrum(input.rate, input.timeUs, input.measuredAt, spectrum(input))
        }

    fun snapshot(before: List<SpectrumWindow>, after: List<SpectrumWindow>, nowNanos: Long = System.nanoTime()): AudioSpectrum? {
        fun fresh(window: SpectrumWindow) = nowNanos - window.measuredAt in 0..MAX_CAPTURE_AGE_NANOS
        val input = before.filter(::fresh)
        return aligned(input, after.filter(::fresh)) ?: source(input)
    }

    fun aligned(before: List<SpectrumWindow>, after: List<SpectrumWindow>): AudioSpectrum? {
        val inputs = before.filter(::complete)
        val pair = after.filter(::complete).sortedByDescending { it.measuredAt }.firstNotNullOfOrNull { output ->
            matchingInput(inputs, output)?.let { it to output }
        } ?: return null
        val (input, output) = pair
        return AudioSpectrum(input.rate, output.timeUs, min(input.measuredAt, output.measuredAt),
            spectrum(input), spectrum(output))
    }

    private fun complete(window: SpectrumWindow): Boolean = window.rate > 0 && window.timeUs != C.TIME_UNSET &&
        window.left.size == PcmSpectrumTap.SIZE && window.right.size == PcmSpectrumTap.SIZE

    private fun matchingInput(before: List<SpectrumWindow>, output: SpectrumWindow): SpectrumWindow? {
        val candidates = before.filter { it.rate == output.rate &&
            abs(it.measuredAt - output.measuredAt) < MAX_PAIR_AGE_NANOS }.sortedByDescending { it.measuredAt }
        val toleranceUs = maxOf(1L, 1_000_000L / output.rate)
        for (input in candidates) {
            val offset = ((output.timeUs - input.timeUs).toDouble() * input.rate / 1_000_000).roundToInt()
            if (offset !in 0 until PcmSpectrumTap.SIZE) continue
            if (offset == 0) return input
            val nextTime = input.timeUs + PcmSpectrumTap.SIZE * 1_000_000L / input.rate
            val next = candidates.firstOrNull { it.measuredAt >= input.measuredAt &&
                abs(it.timeUs - nextTime) <= toleranceUs } ?: continue
            // independent tap resets can split the same interval across two windows
            val firstCount = PcmSpectrumTap.SIZE - offset
            val left = FloatArray(PcmSpectrumTap.SIZE)
            val right = FloatArray(PcmSpectrumTap.SIZE)
            input.left.copyInto(left, 0, offset); next.left.copyInto(left, firstCount, 0, offset)
            input.right.copyInto(right, 0, offset); next.right.copyInto(right, firstCount, 0, offset)
            return SpectrumWindow(input.rate, output.timeUs, min(input.measuredAt, next.measuredAt), left, right)
        }
        return null
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
