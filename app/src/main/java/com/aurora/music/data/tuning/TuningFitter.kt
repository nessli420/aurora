package com.aurora.music.data.tuning

import com.aurora.music.data.ParamBand
import com.aurora.music.playback.engine.PrecisionDspCoeffBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

enum class TuningFitChannel { LEFT, RIGHT, LINKED_AVERAGE }

/** All plotted values use the result's common logarithmic frequency axis, in relative dB. */
data class TuningChannelFit(
    val channel: TuningFitChannel,
    val bands: List<ParamBand>,
    val measuredDb: List<Double>,
    val targetDb: List<Double>,
    val correctionDb: List<Double>,
    val fittedDb: List<Double>,
    val predictedDb: List<Double>,
    val errorBeforeDb: Double,
    val errorAfterDb: Double,
)

data class TuningFitResult(
    val algorithmVersion: Int = 1,
    val inputFingerprint: String,
    val config: TuningFitConfig,
    val frequenciesHz: List<Double>,
    val channels: List<TuningChannelFit>,
    val minFrequencyHz: Double,
    val maxFrequencyHz: Double,
    val measuredNormalizationDb: Double,
    val targetNormalizationDb: Double,
    val preampDb: Float,
    val peakBoostDb: Double,
    val peakCutDb: Double,
    val errorBeforeDb: Double,
    val errorAfterDb: Double,
    val notes: List<String> = emptyList(),
)

/**
 * Bounded, cancellable magnitude-only fitting. No settings, files or playback objects are changed.
 * Fits peaking filters using the same binary64 coefficients and Q floor as the production EQ node.
 * The objective is unweighted RMS dB error on 512 evenly log-spaced points in the common measured
 * coverage. Raw input points, L/R separation and optional measured phase remain untouched.
 */
object TuningFitter {
    const val FIT_POINTS = 512
    private const val RESPONSE_POINTS = 4096
    private const val RESPONSE_MARGIN_DB = .5
    private const val BOUND_EPSILON_DB = 1e-6

    suspend fun fit(project: TuningProject, config: TuningFitConfig = project.config,
        onProgress: ((completedBands: Int, totalBands: Int) -> Unit)? = null): TuningFitResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val prepared = prepare(project, config)
        val frequencies = prepared.frequencies
        val notes = prepared.notes.toMutableList()
        val target = prepared.target
        val grid = ResponseGrid(frequencies, config.sampleRate)
        var completedBudget = 0
        var maximumBoost = 0.0; var maximumCut = 0.0
        val channels = prepared.measured.mapIndexed { index, (channel, measuredValues) ->
            currentCoroutineContext().ensureActive()
            val budget = config.bandBudget / prepared.measured.size + if (index < config.bandBudget % prepared.measured.size) 1 else 0
            val desired = DoubleArray(FIT_POINTS) { target[it] - measuredValues[it] }
            val fittedBands = optimize(desired, grid, budget, config) { count -> onProgress?.invoke(completedBudget + count, config.bandBudget) }
            val checked = enforceFinalBounds(fittedBands, desired, grid, config)
            val actual = grid.response(checked.first)
            val before = rms(desired)
            val after = rms(DoubleArray(FIT_POINTS) { desired[it] - actual[it] })
            check(after <= before + 1e-10) { "A fitted response must not worsen the initial error." }
            maximumBoost = maxOf(maximumBoost, checked.second.first)
            maximumCut = maxOf(maximumCut, checked.second.second)
            completedBudget += budget
            onProgress?.invoke(completedBudget, config.bandBudget)
            if (checked.first.isEmpty() && before > .05) notes += "$channel: no correction improved error within the selected bounds; zero correction is retained."
            TuningChannelFit(channel, checked.first, measuredValues.toList(), target.toList(), desired.toList(), actual.toList(),
                DoubleArray(FIT_POINTS) { measuredValues[it] + actual[it] }.toList(), before, after)
        }
        val preamp = preampFor(maximumBoost)
        notes += "Proposed preamp uses the largest fitted L/R response plus $RESPONSE_MARGIN_DB dB margin; it is a frequency-response estimate, not a transient or hardware clipping guarantee."
        currentCoroutineContext().ensureActive()
        TuningFitResult(inputFingerprint = TuningProjectCodec.inputFingerprint(project.copy(config = config)), config = config,
            frequenciesHz = frequencies.toList(), channels = channels, minFrequencyHz = frequencies.first(), maxFrequencyHz = frequencies.last(),
            measuredNormalizationDb = prepared.normalization.first, targetNormalizationDb = prepared.normalization.second,
            preampDb = preamp, peakBoostDb = maximumBoost, peakCutDb = maximumCut,
            errorBeforeDb = sqrt(channels.sumOf { it.errorBeforeDb * it.errorBeforeDb } / channels.size),
            errorAfterDb = sqrt(channels.sumOf { it.errorAfterDb * it.errorAfterDb } / channels.size), notes = notes)
    }

    /**
     * Validates imported/saved results against the inputs and actual production filter response.
     * A fingerprint proves which inputs were selected, not that supplied curves or preamp are true.
     * This bounded pure check performs no fitting and never calls project validation recursively.
     */
    fun verifyGeneratedFit(project: TuningProject, fit: TuningFitResult) {
        require(fit.algorithmVersion == 1 && fit.config == project.config &&
            fit.inputFingerprint == TuningProjectCodec.inputFingerprint(project)) { "The saved fit is stale; generate it again." }
        val prepared = prepare(project, fit.config)
        val c = fit.config
        fun number(actual: Double, expected: Double, label: String) {
            require(actual.isFinite() && expected.isFinite() && abs(actual - expected) <= 1e-6 * maxOf(1.0, abs(expected))) {
                "Saved $label does not match the measured inputs and generated filters; generate the fit again." }
        }
        fun values(actual: List<Double>, expected: DoubleArray, label: String) {
            require(actual.size == expected.size) { "Saved $label has an invalid point count." }
            for (index in expected.indices) number(actual[index], expected[index], label)
        }
        values(fit.frequenciesHz, prepared.frequencies, "frequency grid")
        number(fit.minFrequencyHz, prepared.frequencies.first(), "minimum frequency")
        number(fit.maxFrequencyHz, prepared.frequencies.last(), "maximum frequency")
        number(fit.measuredNormalizationDb, prepared.normalization.first, "measurement normalization")
        number(fit.targetNormalizationDb, prepared.normalization.second, "target normalization")
        require(fit.channels.map { it.channel } == prepared.measured.map { it.first }) { "Saved channels do not match the selected fitting mode." }
        val grid = ResponseGrid(prepared.frequencies, c.sampleRate)
        var maximumBoost = 0.0; var maximumCut = 0.0
        var totalBefore = 0.0; var totalAfter = 0.0
        fit.channels.forEachIndexed { index, channel ->
            val budget = c.bandBudget / fit.channels.size + if (index < c.bandBudget % fit.channels.size) 1 else 0
            require(channel.bands.size <= budget) { "Saved fit exceeds the per-channel band budget." }
            channel.bands.forEach { band ->
                require(band.type == 0 && band.freqHz.isFinite() && band.gainDb.isFinite() && band.q.isFinite()) { "Invalid generated peak filter." }
                require(band.freqHz >= prepared.frequencies.first() * (1 - 1e-6) &&
                    band.freqHz <= prepared.frequencies.last() * (1 + 1e-6) && band.freqHz < c.sampleRate * .5) { "Saved filter exceeds measured fitting coverage." }
                require(band.gainDb >= -c.maxCutDb - 1e-5 && band.gainDb <= c.maxBoostDb + 1e-5 &&
                    band.q >= c.minQ - 1e-5 && band.q <= c.maxQ + 1e-5) { "Saved filter exceeds its gain or Q bounds." }
            }
            val measured = prepared.measured[index].second
            val desired = DoubleArray(FIT_POINTS) { prepared.target[it] - measured[it] }
            val actual = grid.response(channel.bands)
            val predicted = DoubleArray(FIT_POINTS) { measured[it] + actual[it] }
            val before = rms(desired)
            val after = rms(DoubleArray(FIT_POINTS) { desired[it] - actual[it] })
            require(after <= before + 1e-10) { "Saved correction worsens the original fitting error." }
            values(channel.measuredDb, measured, "measurement curve")
            values(channel.targetDb, prepared.target, "target curve")
            values(channel.correctionDb, desired, "requested correction")
            values(channel.fittedDb, actual, "fitted response")
            values(channel.predictedDb, predicted, "predicted response")
            number(channel.errorBeforeDb, before, "channel error before")
            number(channel.errorAfterDb, after, "channel error after")
            totalBefore += before * before; totalAfter += after * after
            val dense = verificationGrid(channel.bands, c.sampleRate).response(channel.bands)
            val boost = maxOf(0.0, dense.maxOrNull() ?: 0.0)
            val cut = maxOf(0.0, -(dense.minOrNull() ?: 0.0))
            require(boost <= c.maxBoostDb + BOUND_EPSILON_DB && cut <= c.maxCutDb + BOUND_EPSILON_DB) { "Generated filters exceed combined boost or cut bounds." }
            maximumBoost = maxOf(maximumBoost, boost); maximumCut = maxOf(maximumCut, cut)
        }
        number(fit.peakBoostDb, maximumBoost, "peak boost")
        number(fit.peakCutDb, maximumCut, "peak cut")
        number(fit.preampDb.toDouble(), preampFor(maximumBoost).toDouble(), "preamp")
        number(fit.errorBeforeDb, sqrt(totalBefore / fit.channels.size), "overall error before")
        number(fit.errorAfterDb, sqrt(totalAfter / fit.channels.size), "overall error after")
    }

    private data class Prepared(val frequencies: DoubleArray, val measured: List<Pair<TuningFitChannel, DoubleArray>>,
        val target: DoubleArray, val normalization: Pair<Double, Double>, val notes: List<String>)

    private fun prepare(project: TuningProject, config: TuningFitConfig): Prepared {
        TuningProjectCodec.validateConfig(config)
        val inputs = selectInputs(project, config.channelMode)
        inputs.forEach { validateCurve(it.second) }
        project.target?.let(::validateCurve)
        val minimum = maxOf(config.minFrequencyHz, inputs.maxOf { it.second.points.first().frequencyHz },
            project.target?.points?.first()?.frequencyHz ?: config.minFrequencyHz)
        val maximum = minOf(config.maxFrequencyHz, config.sampleRate * .5 * (1.0 - 1e-6),
            inputs.minOf { it.second.points.last().frequencyHz }, project.target?.points?.last()?.frequencyHz ?: config.maxFrequencyHz)
        require(maximum > minimum * 1.01) { "The selected measurements, target and fitting range need overlapping frequency coverage below Nyquist." }
        val frequencies = logarithmicGrid(minimum, maximum, FIT_POINTS)
        val notes = mutableListOf(
            "Calculated magnitude fit over measured coverage; imported phase is retained but is not corrected.",
            "Fit error uses $FIT_POINTS equally log-spaced points. Predicted curves exclude the proposed common preamp.",
        )
        val normalization = normalization(inputs.map { it.second }, project.target, config, notes)
        val target = smooth(project.target?.let { interpolate(it, frequencies) } ?: DoubleArray(FIT_POINTS), frequencies, config.smoothingOctaves)
        target.indices.forEach { target[it] -= normalization.second }
        val measured = inputs.map { (channel, curve) -> channel to smooth(interpolate(curve, frequencies), frequencies, config.smoothingOctaves).also { values ->
            values.indices.forEach { values[it] -= normalization.first }
        } }
        val selected = if (config.channelMode == TuningChannelMode.LINKED_AVERAGE) {
            notes += "Linked mode fits the arithmetic mean of L/R magnitudes in dB; individual channel errors can differ. Both raw channels remain stored."
            listOf(TuningFitChannel.LINKED_AVERAGE to DoubleArray(FIT_POINTS) { i -> measured.sumOf { it.second[i] } / measured.size })
        } else measured
        if (config.smoothingOctaves > 0) notes += "Measured and target curves use ${config.smoothingOctaves}-octave centered smoothing; stored source values are unchanged."
        if (selected.size > 1) notes += "The total ${config.bandBudget}-band budget is split across independently fitted channels; normalization uses one shared measurement offset."
        return Prepared(frequencies, selected, target, normalization, notes)
    }

    private fun selectInputs(project: TuningProject, mode: TuningChannelMode): List<Pair<TuningFitChannel, MeasurementCurve>> {
        val left = project.measurementLeft?.let { TuningFitChannel.LEFT to it }
        val right = project.measurementRight?.let { TuningFitChannel.RIGHT to it }
        return when (mode) {
            TuningChannelMode.LEFT -> listOf(requireNotNull(left) { "Import a left measurement before fitting the left channel." })
            TuningChannelMode.RIGHT -> listOf(requireNotNull(right) { "Import a right measurement before fitting the right channel." })
            TuningChannelMode.INDEPENDENT -> listOfNotNull(left, right).also { require(it.isNotEmpty()) { "Import at least one measurement before fitting." } }
            TuningChannelMode.LINKED_AVERAGE -> {
                require(left != null && right != null) { "Linked averaging requires both left and right measurements." }
                listOf(left, right)
            }
        }
    }

    private fun validateCurve(curve: MeasurementCurve) {
        require(curve.points.size in 2..MeasurementTextImporter.MAX_POINTS) { "Unsupported fitting curve point count." }
        require(curve.points.all { it.frequencyHz.isFinite() && it.frequencyHz > 0 && it.frequencyHz <= 1_000_000 &&
            it.magnitudeDb.isFinite() && abs(it.magnitudeDb) <= 1000 }) {
            "Curve frequencies and magnitudes must be finite and within supported bounds." }
        require((1 until curve.points.size).all { curve.points[it].frequencyHz > curve.points[it - 1].frequencyHz }) {
            "Curve frequencies must be strictly increasing." }
    }

    private fun normalization(curves: List<MeasurementCurve>, target: MeasurementCurve?, c: TuningFitConfig,
        notes: MutableList<String>): Pair<Double, Double> {
        if (c.normalization == TuningNormalization.NONE) return 0.0 to 0.0
        val minimum = maxOf(200.0, curves.maxOf { it.points.first().frequencyHz }, target?.points?.first()?.frequencyHz ?: 200.0)
        val maximum = minOf(2000.0, curves.minOf { it.points.last().frequencyHz }, target?.points?.last()?.frequencyHz ?: 2000.0)
        require(maximum > minimum) { "Normalization needs measured/target overlap in 200–2000 Hz; choose no normalization or import wider coverage." }
        val reference = logarithmicGrid(minimum, maximum, 128)
        val measuredMean = curves.sumOf { interpolate(it, reference).average() } / curves.size
        val targetMean = target?.let { interpolate(it, reference).average() } ?: 0.0
        notes += "Normalization subtracts one shared measured mean and a separate target mean over $minimum–$maximum Hz, retaining the measured L/R level difference."
        return measuredMean to targetMean
    }

    private fun interpolate(curve: MeasurementCurve, frequencies: DoubleArray): DoubleArray {
        var position = 0
        return DoubleArray(frequencies.size) { index ->
            val frequency = frequencies[index]
            while (position < curve.points.lastIndex - 1 && curve.points[position + 1].frequencyHz < frequency) position++
            val low = curve.points[position]; val high = curve.points[position + 1]
            require(frequency >= curve.points.first().frequencyHz * (1 - 1e-12) && frequency <= curve.points.last().frequencyHz * (1 + 1e-12)) {
                "Fitting does not extrapolate beyond measured coverage." }
            val ratio = (ln(frequency / low.frequencyHz) / ln(high.frequencyHz / low.frequencyHz)).coerceIn(0.0, 1.0)
            low.magnitudeDb + (high.magnitudeDb - low.magnitudeDb) * ratio
        }
    }

    private fun smooth(values: DoubleArray, frequencies: DoubleArray, octaves: Double): DoubleArray {
        if (octaves == 0.0) return values
        val halfWidth = ln(2.0) * octaves * .5
        val prefix = DoubleArray(values.size + 1)
        for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
        var low = 0; var high = 0
        return DoubleArray(values.size) { index ->
            while (low < index && ln(frequencies[index] / frequencies[low]) > halfWidth) low++
            while (high < values.lastIndex && ln(frequencies[high + 1] / frequencies[index]) <= halfWidth) high++
            high = maxOf(high, index)
            (prefix[high + 1] - prefix[low]) / (high - low + 1)
        }
    }

    private data class Candidate(val band: ParamBand, val response: DoubleArray, val error: Double)

    private suspend fun optimize(desired: DoubleArray, grid: ResponseGrid, budget: Int, c: TuningFitConfig,
        progress: (Int) -> Unit): List<ParamBand> {
        val bands = mutableListOf<ParamBand>()
        val curves = mutableListOf<DoubleArray>()
        val total = DoubleArray(desired.size)
        var error = sumSquares(desired)
        for (iteration in 0 until budget) {
            currentCoroutineContext().ensureActive(); progress(iteration)
            val residual = DoubleArray(desired.size) { desired[it] - total[it] }
            if (residual.maxOf { abs(it) } < .05) break
            val best = findBand(residual, total, grid, c, error) ?: break
            if (best.error >= error - 1e-8) break
            bands += best.band; curves += best.response
            total.indices.forEach { total[it] += best.response[it] }
            error = best.error
        }
        // Two bounded coordinate passes refine earlier choices against the final residual.
        repeat(2) {
            for (index in bands.indices) {
                currentCoroutineContext().ensureActive()
                val base = DoubleArray(total.size) { total[it] - curves[index][it] }
                val residual = DoubleArray(total.size) { desired[it] - base[it] }
                val candidate = refine(Candidate(bands[index], curves[index], error), residual, base, grid, c)
                if (candidate.error < error - 1e-9) {
                    bands[index] = candidate.band; curves[index] = candidate.response; error = candidate.error
                    total.indices.forEach { total[it] = base[it] + candidate.response[it] }
                }
            }
        }
        return bands.toList()
    }

    private suspend fun findBand(residual: DoubleArray, total: DoubleArray, grid: ResponseGrid,
        c: TuningFitConfig, initialError: Double): Candidate? {
        val peaks = residual.indices.sortedByDescending { abs(residual[it]) }
        val centers = mutableListOf<Int>()
        for (index in peaks) {
            if (centers.all { abs(it - index) >= 8 }) centers += index
            if (centers.size == 4) break
        }
        centers += listOf(residual.size / 4, residual.size / 2, residual.size * 3 / 4)
        val scratch = DoubleArray(residual.size)
        var best: Candidate? = null
        var evaluations = 0
        for (index in centers.distinct()) {
            val gain = residual[index].coerceIn(-c.maxCutDb, c.maxBoostDb)
            if (abs(gain) < .01) continue
            val qs = listOf(estimateQ(residual, grid.frequencies, index), c.minQ, sqrt(c.minQ * c.maxQ), c.maxQ, .707, 1.4, 2.8)
                .map { it.coerceIn(c.minQ, c.maxQ) }.distinct()
            for (q in qs) for (scale in doubleArrayOf(.5, .75, 1.0, 1.25)) {
                if (evaluations++ % 16 == 0) currentCoroutineContext().ensureActive()
                val band = makeBand(grid.frequencies[index], gain * scale, q, c, grid)
                val score = score(band, residual, total, grid, c, scratch)
                if (score < (best?.error ?: initialError) - 1e-9) best = Candidate(band, scratch.copyOf(), score)
            }
        }
        return best?.let { refine(it, residual, total, grid, c) }
    }

    private suspend fun refine(initial: Candidate, residual: DoubleArray, total: DoubleArray,
        grid: ResponseGrid, c: TuningFitConfig): Candidate {
        var best = initial
        val scratch = DoubleArray(residual.size)
        for (step in doubleArrayOf(.15, .06, .02)) {
            currentCoroutineContext().ensureActive()
            val b = best.band
            val proposals = listOf(
                makeBand(b.freqHz * (1.0 - step), b.gainDb.toDouble(), b.q.toDouble(), c, grid),
                makeBand(b.freqHz * (1.0 + step), b.gainDb.toDouble(), b.q.toDouble(), c, grid),
                makeBand(b.freqHz.toDouble(), b.gainDb - step * 4, b.q.toDouble(), c, grid),
                makeBand(b.freqHz.toDouble(), b.gainDb + step * 4, b.q.toDouble(), c, grid),
                makeBand(b.freqHz.toDouble(), b.gainDb.toDouble(), b.q * (1.0 - step * 2), c, grid),
                makeBand(b.freqHz.toDouble(), b.gainDb.toDouble(), b.q * (1.0 + step * 2), c, grid),
            )
            for (band in proposals) {
                val error = score(band, residual, total, grid, c, scratch)
                if (error < best.error - 1e-9) best = Candidate(band, scratch.copyOf(), error)
            }
        }
        return best
    }

    private fun makeBand(frequency: Double, gain: Double, q: Double, c: TuningFitConfig, grid: ResponseGrid) = ParamBand(
        boundedFloat(frequency, grid.frequencies.first(), grid.frequencies.last()),
        boundedFloat(gain, -c.maxCutDb, c.maxBoostDb), boundedFloat(q, c.minQ, c.maxQ), 0)

    private fun boundedFloat(value: Double, low: Double, high: Double): Float {
        val minimum = low.toFloat().let { if (it.toDouble() < low) Math.nextUp(it) else it }
        val maximum = high.toFloat().let { if (it.toDouble() > high) Math.nextDown(it) else it }
        // ParamBand persists Float values. An exact/narrow decimal range may contain no Float;
        // use its nearest representable value, with the same ULP tolerance as saved-fit validation.
        return if (minimum <= maximum) value.toFloat().coerceIn(minimum, maximum) else value.coerceIn(low, high).toFloat()
    }

    private fun score(band: ParamBand, residual: DoubleArray, total: DoubleArray,
        grid: ResponseGrid, c: TuningFitConfig, scratch: DoubleArray): Double {
        grid.band(band, scratch)
        var error = 0.0
        for (i in scratch.indices) {
            val combined = total[i] + scratch[i]
            if (!combined.isFinite() || combined > c.maxBoostDb + BOUND_EPSILON_DB || combined < -c.maxCutDb - BOUND_EPSILON_DB) return Double.POSITIVE_INFINITY
            val difference = residual[i] - scratch[i]
            error += difference * difference
        }
        return error
    }

    private suspend fun enforceFinalBounds(bands: List<ParamBand>, desired: DoubleArray, grid: ResponseGrid,
        config: TuningFitConfig): Pair<List<ParamBand>, Pair<Double, Double>> {
        var selected = bands
        val baseline = sumSquares(desired)
        repeat(10) {
            currentCoroutineContext().ensureActive()
            val dense = verificationGrid(selected, config.sampleRate)
            val response = dense.response(selected)
            val boost = maxOf(0.0, response.maxOrNull() ?: 0.0)
            val cut = maxOf(0.0, -(response.minOrNull() ?: 0.0))
            val final = grid.response(selected)
            val finalError = sumSquares(DoubleArray(desired.size) { desired[it] - final[it] })
            if (boost <= config.maxBoostDb + BOUND_EPSILON_DB && cut <= config.maxCutDb + BOUND_EPSILON_DB && finalError <= baseline + 1e-10) {
                return selected to (boost to cut)
            }
            if (finalError > baseline + 1e-10 || selected.isEmpty()) return emptyList<ParamBand>() to (0.0 to 0.0)
            val scale = minOf(if (boost > 0) config.maxBoostDb / boost else 1.0,
                if (cut > 0) config.maxCutDb / cut else 1.0, .999).coerceIn(0.0, 1.0) * .999
            selected = selected.map { it.copy(gainDb = (it.gainDb * scale).toFloat()) }.filter { abs(it.gainDb) >= .001f }
        }
        return emptyList<ParamBand>() to (0.0 to 0.0)
    }

    private fun verificationGrid(bands: List<ParamBand>, rate: Int): ResponseGrid {
        val nyquist = rate * .5
        val frequencies = logarithmicGrid(1.0, nyquist * (1 - 1e-9), RESPONSE_POINTS).toMutableList()
        frequencies += 0.0; frequencies += nyquist
        for (band in bands) for (step in -4..4) frequencies += (band.freqHz * exp(step / (band.q * 4.0))).coerceIn(0.0, nyquist)
        return ResponseGrid(frequencies.distinct().sorted().toDoubleArray(), rate)
    }

    private fun estimateQ(residual: DoubleArray, frequencies: DoubleArray, center: Int): Double {
        val sign = if (residual[center] < 0) -1 else 1
        val half = abs(residual[center]) * .5
        var low = center; var high = center
        while (low > 0 && residual[low] * sign > half) low--
        while (high < residual.lastIndex && residual[high] * sign > half) high++
        val ratio = (frequencies[high] / frequencies[low]).coerceAtLeast(1.001)
        return sqrt(ratio) / (ratio - 1)
    }

    /** Precomputed unit-circle values; all candidate coefficients come from production math. */
    private class ResponseGrid(val frequencies: DoubleArray, private val rate: Int) {
        private val cosine = DoubleArray(frequencies.size) { cos(2 * PI * frequencies[it] / rate) }
        private val sine = DoubleArray(frequencies.size) { sin(2 * PI * frequencies[it] / rate) }
        private val cosine2 = DoubleArray(frequencies.size) { cos(4 * PI * frequencies[it] / rate) }
        private val sine2 = DoubleArray(frequencies.size) { sin(4 * PI * frequencies[it] / rate) }
        fun band(band: ParamBand, output: DoubleArray) {
            val b = PrecisionDspCoeffBuilder.band(0, band.freqHz.toDouble(), band.gainDb.toDouble(), band.q.toDouble().coerceAtLeast(.1), rate)
            for (i in output.indices) {
                val nr = b.b0 + b.b1 * cosine[i] + b.b2 * cosine2[i]
                val ni = -b.b1 * sine[i] - b.b2 * sine2[i]
                val dr = 1 + b.a1 * cosine[i] + b.a2 * cosine2[i]
                val di = -b.a1 * sine[i] - b.a2 * sine2[i]
                output[i] = 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
            }
        }
        fun response(bands: List<ParamBand>): DoubleArray {
            val sum = DoubleArray(frequencies.size)
            val scratch = DoubleArray(frequencies.size)
            for (selected in bands) { band(selected, scratch); sum.indices.forEach { sum[it] += scratch[it] } }
            return sum
        }
    }

    private fun logarithmicGrid(minimum: Double, maximum: Double, count: Int) = DoubleArray(count) {
        minimum * exp(ln(maximum / minimum) * it / (count - 1))
    }.also { it[0] = minimum; it[count - 1] = maximum }
    private fun sumSquares(values: DoubleArray): Double = values.sumOf { it * it }
    private fun rms(values: DoubleArray): Double = sqrt(sumSquares(values) / values.size)
    private fun preampFor(boost: Double): Float = if (boost > 1e-6) -(boost + RESPONSE_MARGIN_DB).toFloat() else 0f
}
