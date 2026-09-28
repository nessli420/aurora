package com.aurora.music.playback.compare

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class ComparisonSelection { A, B, X }

data class ComparisonLevels(val gainA: Double, val gainB: Double, val rmsA: Double,
    val rmsB: Double, val residualDb: Double, val peak: Double)

data class ComparisonAudio(val rate: Int, val a: FloatArray, val b: FloatArray,
    val levels: ComparisonLevels, val latencyA: Int = 0, val latencyB: Int = 0) {
    init {
        require(rate in 8_000..192_000 && a.size == b.size && a.size % 2 == 0 && a.size >= rate * 2)
        require(a.all { it.isFinite() } && b.all { it.isFinite() })
    }
    val frames: Int get() = a.size / 2
}

object ComparisonMatching {
    fun match(rate: Int, a: DoubleArray, b: DoubleArray, latencyA: Int = 0,
        latencyB: Int = 0, warmupFrames: Int = 0): ComparisonAudio {
        require(a.size % 2 == 0 && b.size % 2 == 0 && latencyA >= 0 && latencyB >= 0 && warmupFrames >= 0)
        require(a.all { it.isFinite() } && b.all { it.isFinite() }) { "A preset produced invalid samples." }
        val startA = latencyA + warmupFrames
        val startB = latencyB + warmupFrames
        val frames = min(a.size / 2 - startA, b.size / 2 - startB)
        require(frames >= rate) { "Choose a longer audio selection." }
        var squareA = 0.0; var squareB = 0.0
        for (i in 0 until frames * 2) {
            squareA += a[startA * 2 + i].pow(2)
            squareB += b[startB * 2 + i].pow(2)
        }
        val rmsA = sqrt(squareA / (frames * 2)); val rmsB = sqrt(squareB / (frames * 2))
        require(rmsA > 1e-9 && rmsB > 1e-9) { "Both presets need an audible selection." }
        require(rmsA.isFinite() && rmsB.isFinite()) { "Preset gain is too high." }
        val target = min(rmsA, rmsB)
        var gainA = target / rmsA; var gainB = target / rmsB
        var peak = 0.0
        for (i in 0 until frames * 2) peak = max(peak,
            max(abs(a[startA * 2 + i] * gainA), abs(b[startB * 2 + i] * gainB)))
        val headroom = min(1.0, 10.0.pow(-1.0 / 20) / max(peak, 1e-30))
        gainA *= headroom; gainB *= headroom
        val outA = FloatArray(frames * 2) { (a[startA * 2 + it] * gainA).toFloat() }
        val outB = FloatArray(frames * 2) { (b[startB * 2 + it] * gainB).toFloat() }
        val measuredA = sqrt(outA.sumOf { it.toDouble().pow(2) } / outA.size)
        val measuredB = sqrt(outB.sumOf { it.toDouble().pow(2) } / outB.size)
        val residual = 20 * log10(measuredA / measuredB)
        require(abs(residual) <= .1) { "Level matching exceeds 0.1 dB." }
        return ComparisonAudio(rate, outA, outB,
            ComparisonLevels(gainA, gainB, measuredA, measuredB, residual, peak * headroom), latencyA, latencyB)
    }
}

data class AbxAnswer(val number: Int, val guessA: Boolean, val xIsA: Boolean)
data class AbxResult(val planned: Int, val answered: Int, val correct: Int, val interrupted: Boolean,
    val probability: Double?, val trials: List<AbxAnswer> = emptyList())

class AbxTrial(assignments: BooleanArray = BooleanArray(16) { SecureRandomHolder.random.nextBoolean() }) {
    private val assignments = assignments.copyOf()
    init { require(assignments.size in 8..32) }
    private val answers = mutableListOf<Boolean>()
    private var interrupted = false
    val planned: Int get() = assignments.size
    val answered: Int get() = answers.size
    val complete: Boolean get() = answers.size == planned
    val active: Boolean get() = !complete && !interrupted
    internal fun xIsA(): Boolean { check(active); return assignments[answers.size] }
    fun guess(a: Boolean) { check(active); answers += a }
    fun interrupt() { if (!complete) interrupted = true }
    fun result(): AbxResult {
        check(!active) { "Finish or stop the test before revealing results." }
        val correct = answers.indices.count { answers[it] == assignments[it] }
        return AbxResult(planned, answered, correct, interrupted,
            if (complete && !interrupted) chanceProbability(planned, correct) else null,
            answers.indices.map { AbxAnswer(it + 1, answers[it], assignments[it]) })
    }

    companion object {
        fun chanceProbability(trials: Int, correct: Int): Double {
            require(trials in 1..32 && correct in 0..trials)
            var combination = 1.0; var sum = 0.0
            for (k in 0..trials) {
                if (k >= correct) sum += combination
                if (k < trials) combination *= (trials - k).toDouble() / (k + 1)
            }
            return (sum / 2.0.pow(trials)).coerceIn(0.0, 1.0)
        }
    }
}

private object SecureRandomHolder { val random = SecureRandom() }

class ComparisonMixer(private val audio: ComparisonAudio) {
    private val transitionFrames = max(1, audio.rate / 50)
    private val edgeFrames = min(audio.rate / 100, audio.frames / 4)
    private var selectedA = true
    private var targetA = true
    private var currentGain = 1.0
    private var fromGain = 1.0
    private var transition = transitionFrames
    private var cursor = 0

    fun select(a: Boolean) {
        fromGain = currentGain
        targetA = a
        transition = 0
    }

    fun render(destination: FloatArray, frames: Int) {
        require(frames >= 0 && frames * 2 <= destination.size)
        for (frame in 0 until frames) {
            if (transition < transitionFrames) {
                transition++
                val middle = transitionFrames / 2
                if (transition <= middle) currentGain = fromGain * (1 - transition.toDouble() / middle)
                else {
                    selectedA = targetA
                    currentGain = (transition - middle).toDouble() / (transitionFrames - middle)
                }
            }
            val envelope = min(1.0, min(cursor.toDouble(), (audio.frames - 1 - cursor).toDouble()) / max(1, edgeFrames))
            for (channel in 0..1) {
                val index = cursor * 2 + channel
                destination[frame * 2 + channel] = ((if (selectedA) audio.a[index] else audio.b[index]) * currentGain * envelope).toFloat()
            }
            cursor = (cursor + 1) % audio.frames
        }
    }
}
