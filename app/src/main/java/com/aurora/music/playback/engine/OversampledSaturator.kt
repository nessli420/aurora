package com.aurora.music.playback.engine

import kotlin.math.*

class OversampledSaturator(val factor: Int, private val drive: Double) {
    init { require(factor in listOf(2, 4, 8) && drive.isFinite() && drive in 0.0..1.0) }
    val latencyFrames: Int = 32
    val tailFrames: Int = latencyFrames * 2
    private val half = 16 * factor
    private val coefficients = DoubleArray(half * 2 + 1) { i ->
        val x = i - half
        val a = PI * 0.94 * x / factor
        val window = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)
        (if (x == 0) 0.94 / factor else sin(a) / (PI * x)) * window
    }.also { val sum = it.sum(); for (i in it.indices) it[i] /= sum }
    private val up = Array(2) { DoubleArray(coefficients.size) }
    private val down = Array(2) { DoubleArray(coefficients.size) }
    private var position = 0

    fun process(block: AudioBlock) {
        require(block.format.channelCount == 2)
        val k = 1.0 + 5.0 * drive
        for (frame in 0 until block.frameCount) {
            val left = block.samples[frame * 2]; val right = block.samples[frame * 2 + 1]
            for (phase in 0 until factor) {
                for (channel in 0..1) {
                    up[channel][position] = if (phase == 0) (if (channel == 0) left else right) * factor else 0.0
                    var interpolated = 0.0
                    var tap = phase
                    while (tap < coefficients.size) {
                        interpolated += coefficients[tap] * up[channel][(position - tap + coefficients.size) % coefficients.size]
                        tap += factor
                    }
                    val saturated = if (drive == 0.0) interpolated else tanh(k * interpolated) / k
                    down[channel][position] = if (drive == 0.0) saturated else saturated + drive * 0.2 * (saturated * saturated - 0.33)
                    if (phase == 0) {
                        var filtered = 0.0
                        for (tap in coefficients.indices) filtered += coefficients[tap] * down[channel][(position - tap + coefficients.size) % coefficients.size]
                        block.samples[frame * 2 + channel] = filtered
                    }
                }
                position = (position + 1) % coefficients.size
            }
        }
    }

    fun reset() { up.forEach { it.fill(0.0) }; down.forEach { it.fill(0.0) }; position = 0 }

    fun copyStateFrom(previous: OversampledSaturator): Boolean {
        if (factor != previous.factor || drive != previous.drive) return false
        for (channel in 0..1) {
            previous.up[channel].copyInto(up[channel])
            previous.down[channel].copyInto(down[channel])
        }
        position = previous.position
        return true
    }
}
