package com.aurora.music.playback.engine

// - advance a frame after both channels are read
internal class SampleFifo(private val capacity: Int) {
    private val samples = DoubleArray(capacity * 2)
    private var read = 0
    var frames = 0; private set
    fun push(input: DoubleArray, offset: Int, count: Int) {
        check(frames + count <= capacity) { "Bounded rack output FIFO is full" }
        var i = 0
        while (i < count) {
            val p = (read + frames + i) % capacity * 2
            samples[p] = input[(offset + i) * 2]; samples[p + 1] = input[(offset + i) * 2 + 1]
            i++
        }
        frames += count
    }
    fun takeLeft(): Double { check(frames > 0); return samples[read * 2] }
    fun takeRight(): Double {
        check(frames > 0)
        val value = samples[read * 2 + 1]; read = (read + 1) % capacity; frames--
        return value
    }
    fun reset() { frames = 0; read = 0 }
    fun pushSilence(count: Int) {
        check(frames + count <= capacity)
        for (i in 0 until count) {
            val p = (read + frames + i) % capacity * 2
            samples[p] = 0.0; samples[p + 1] = 0.0
        }
        frames += count
    }
}
