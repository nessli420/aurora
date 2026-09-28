package com.aurora.music.desktop.audio

internal class MasterGain {
    @Volatile var volume = 1.0
    private var sleep: Ramp? = null
    private var wake: Ramp? = null
    private var current = 1.0

    val sleepFinished: Boolean get() = sleep?.done == true

    fun sleepFade(frames: Long) {
        sleep = if (frames > 0) Ramp(frames, 1.0, 0.0) else null
        current = target()
    }

    fun wakeFade(frames: Long) {
        wake = if (frames > 0) Ramp(frames, 0.0, 1.0) else null
        current = target()
    }

    fun cancelWake() {
        wake = null
    }

    fun endSleep() {
        sleep = null
        current = target()
    }

    fun apply(samples: DoubleArray, frames: Int): Boolean {
        val from = current
        sleep?.let { it.elapsed += frames }
        wake?.let {
            it.elapsed += frames
            if (it.done) wake = null
        }
        val to = target()
        current = to
        if (from == 1.0 && to == 1.0) return true
        val step = (to - from) / frames
        var i = 0
        while (i < frames) {
            val gain = from + step * i
            samples[i * 2] *= gain
            samples[i * 2 + 1] *= gain
            i++
        }
        return false
    }

    private fun target() = volume * (sleep?.value ?: 1.0) * (wake?.value ?: 1.0)

    private class Ramp(val frames: Long, val from: Double, val to: Double) {
        var elapsed = 0L
        val done: Boolean get() = elapsed >= frames
        val value: Double get() = from + (to - from) * (elapsed.toDouble() / frames).coerceIn(0.0, 1.0)
    }
}
