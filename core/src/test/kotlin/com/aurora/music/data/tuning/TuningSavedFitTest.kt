package com.aurora.music.data.tuning

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

internal fun tuningTestProject(stereo: Boolean = false): TuningProject {
    fun measurement(depth: Double, center: Double) = MeasurementTextImporter.parse((0..48).joinToString("\n") { index ->
        val f = 100.0 * 50.0.pow(index / 48.0)
        val db = -depth * exp(-(ln(f / center) / .6).pow(2))
        "$f $db"
    }).getOrThrow()
    return TuningProjectCodec.create("Measured fixture").copy(measurementLeft = measurement(3.0, 1000.0),
        measurementRight = if (stereo) measurement(2.0, 1500.0) else null,
        config = TuningFitConfig(bandBudget = if (stereo) 4 else 2, minFrequencyHz = 100.0, maxFrequencyHz = 5000.0,
            normalization = TuningNormalization.NONE, smoothingOctaves = 0.0))
}

class TuningSavedFitTest {
    @Test fun genuineGeneratedFitsReopenOfflineWithAllPlotAndNormalizationMetadata() = runBlocking {
        val project = tuningTestProject(stereo = true)
        val fit = TuningFitter.fit(project)
        val complete = project.copy(generatedFit = fit)
        assertEquals(complete, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(complete)).getOrThrow())
        assertEquals(listOf(complete), TuningProjectCodec.decodeLibrary(TuningProjectCodec.encodeLibrary(listOf(complete))).getOrThrow())
    }

    @Test fun matchingFingerprintDoesNotAuthorizeForgedPredictionErrorsOrPreamp() = runBlocking {
        val project = tuningTestProject()
        val fit = TuningFitter.fit(project)
        val channel = fit.channels.single()
        val forgeries = listOf(
            fit.copy(errorAfterDb = fit.errorAfterDb + 1.0),
            fit.copy(preampDb = 0f),
            fit.copy(measuredNormalizationDb = fit.measuredNormalizationDb + 1.0),
            fit.copy(channels = listOf(channel.copy(predictedDb = channel.predictedDb.map { it + .5 }))),
            fit.copy(channels = listOf(channel.copy(fittedDb = channel.fittedDb.map { it + .01 }))),
            fit.copy(channels = listOf(channel.copy(targetDb = channel.targetDb.map { it + .5 }))),
        )
        for (forged in forgeries) assertTrue(runCatching { TuningProjectCodec.validate(project.copy(generatedFit = forged)) }.isFailure)
        val envelope = JsonParser.parseString(TuningProjectCodec.encodeLibrary(listOf(project.copy(generatedFit = fit)))).asJsonObject
        envelope.getAsJsonArray("projects")[0].asJsonObject.getAsJsonObject("generatedFit").addProperty("errorAfterDb", 999)
        assertTrue(TuningProjectCodec.decodeLibrary(envelope.toString()).isFailure)
    }

    @Test fun importedBandEditsMustStillMatchPlotsAndConfiguredBounds() = runBlocking {
        val project = tuningTestProject()
        val fit = TuningFitter.fit(project)
        val channel = fit.channels.single()
        assertTrue(channel.bands.isNotEmpty())
        val band = channel.bands.first()
        val altered = listOf(band.copy(freqHz = 40f), band.copy(gainDb = 20f), band.copy(q = 50f), band.copy(type = 1),
            band.copy(gainDb = band.gainDb + .1f))
        for (replacement in altered) {
            val forged = fit.copy(channels = listOf(channel.copy(bands = listOf(replacement) + channel.bands.drop(1))))
            assertTrue(runCatching { TuningProjectCodec.validate(project.copy(generatedFit = forged)) }.isFailure)
        }
    }

    @Test fun changedInputsAndControlsRequireRegenerationWhileNotesMayChange() = runBlocking {
        val project = tuningTestProject()
        val saved = project.copy(generatedFit = TuningFitter.fit(project))
        assertTrue(runCatching { TuningProjectCodec.validate(saved.copy(config = saved.config.copy(bandBudget = 3))) }.isFailure)
        assertTrue(runCatching { TuningProjectCodec.validate(saved.copy(target = saved.measurementLeft)) }.isFailure)
        assertEquals(saved.generatedFit, TuningProjectCodec.validate(saved.copy(name = "Renamed", notes = "New note")).generatedFit)
    }
}
