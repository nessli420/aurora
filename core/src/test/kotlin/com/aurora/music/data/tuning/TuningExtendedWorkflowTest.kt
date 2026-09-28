package com.aurora.music.data.tuning

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import java.security.MessageDigest
import java.util.UUID

class TuningExtendedWorkflowTest {
    private fun flat(name: String = "Fixture") = MeasurementTextImporter.parse("20 0\n20000 0", name).getOrThrow()
    private fun project(config: TuningFitConfig = TuningFitConfig()) = TuningProjectCodec.create("Fixture").copy(measurementLeft = flat(), config = config)

    @Test fun shapeMatchesDefinitionAndPersistsWithoutChangingRawInputs() = runBlocking {
        val config = TuningFitConfig(bandBudget = 8, normalization = TuningNormalization.NONE, smoothingOctaves = 0.0,
            bassDb = 3.0, bassFrequencyHz = 100.0, tiltDbPerOctave = -.25, earGainDb = 2.0, earGainFrequencyHz = 2800.0)
        val input = project(config)
        val fit = TuningFitter.fit(input)
        val channel = fit.channels.single()
        fit.frequenciesHz.forEachIndexed { i, f ->
            val expected = 3.0 / (1.0 + (f / 100.0).pow(4)) - .25 * ln(f / 1000) / ln(2.0) +
                2.0 * exp(-.5 * (ln(f / 2800) / ln(2.0) / .6).pow(2))
            assertEquals(expected, channel.targetDb[i], 1e-12)
            assertEquals(expected, channel.correctionDb[i], 1e-12)
        }
        assertTrue(fit.errorAfterDb < fit.errorBeforeDb * .2)
        val saved = input.copy(generatedFit = fit)
        assertEquals(saved, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(saved)).getOrThrow())
        assertEquals("20 0\n20000 0", saved.measurementLeft!!.sourceText)
        assertTrue(TuningProjectCodec.inputFingerprint(input) != TuningProjectCodec.inputFingerprint(input.copy(config = config.copy(bassDb = 4.0))))
        assertTrue(runCatching { TuningProjectCodec.validate(saved.copy(config = config.copy(tiltDbPerOctave = 0.0))) }.isFailure)
    }

    @Test fun trebleLimitsConstrainCombinedResponseAndIndividualQ() = runBlocking {
        val config = TuningFitConfig(bandBudget = 8, normalization = TuningNormalization.NONE, smoothingOctaves = 0.0,
            trebleStartHz = 4000.0, trebleMaxBoostDb = 1.0, trebleMaxCutDb = 2.0, trebleMaxQ = 1.2)
        val source = (0..511).joinToString("\n") { i ->
            val f = 20 * exp(ln(1000.0) * i / 511)
            "$f ${-6 * exp(-.5 * (ln(f / 11000) / .18).pow(2))}"
        }
        val input = project(config).copy(measurementLeft = MeasurementTextImporter.parse(source).getOrThrow())
        val fit = TuningFitter.fit(input)
        TuningFitter.verifyGeneratedFit(input, fit)
        val channel = fit.channels.single()
        fit.frequenciesHz.forEachIndexed { i, f ->
            assertTrue(channel.fittedDb[i] <= TuningFitter.boostLimit(config, f) + 1e-6)
            assertTrue(channel.fittedDb[i] >= -TuningFitter.cutLimit(config, f) - 1e-6)
        }
        channel.bands.filter { it.freqHz >= 8000 }.forEach { assertTrue(it.q <= 1.20001) }
        assertTrue(fit.errorAfterDb <= fit.errorBeforeDb)
    }

    @Test fun unlinkedLimitsRemainIndependentAndSavedValidationUsesEachChannel() = runBlocking {
        val config = TuningFitConfig(bandBudget = 8, normalization = TuningNormalization.NONE, smoothingOctaves = 0.0,
            leftLimits = TuningChannelLimits(maxBoostDb = 1.0, maxCutDb = 2.0), rightLimits = TuningChannelLimits(maxBoostDb = 9.0, maxCutDb = 15.0))
        val source = (0..200).joinToString("\n") { i -> val f = 20 * exp(ln(1000.0) * i / 200); "$f ${-8 * exp(-.5 * (ln(f / 1000) / .4).pow(2))}" }
        val curve = MeasurementTextImporter.parse(source).getOrThrow()
        val input = project(config).copy(measurementLeft = curve, measurementRight = curve)
        val fit = TuningFitter.fit(input)
        assertTrue(fit.channels[0].fittedDb.max() <= 1.000001)
        assertTrue(fit.channels[1].fittedDb.max() > 6.0)
        val saved = input.copy(generatedFit = fit)
        assertEquals(saved, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(saved)).getOrThrow())
    }

    @Test fun legacyFingerprintAndSavedFitSurviveMissingExtensionFields() = runBlocking {
        val input = project(TuningFitConfig(normalization = TuningNormalization.NONE, smoothingOctaves = 0.0))
        val fit = TuningFitter.fit(input)
        val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
        val root = JsonParser.parseString(TuningProjectCodec.encodeProject(input.copy(generatedFit = fit))).asJsonObject
        val oldKeys = setOf("sampleRate", "bandBudget", "minFrequencyHz", "maxFrequencyHz", "maxBoostDb", "maxCutDb", "minQ", "maxQ", "channelMode", "normalization", "smoothingOctaves")
        listOf(root["config"].asJsonObject, root["generatedFit"].asJsonObject["config"].asJsonObject).forEach { config ->
            config.keySet().toList().filterNot { it in oldKeys }.forEach(config::remove)
        }
        root["measurementLeft"].asJsonObject.remove("format")
        val legacyInput = listOf(input.measurementLeft?.points, null, null, root["config"])
        val digest = MessageDigest.getInstance("SHA-256").digest(gson.toJson(legacyInput).toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(digest, fit.inputFingerprint)
        assertEquals(input.copy(generatedFit = fit), TuningProjectCodec.decodeProject(gson.toJson(root)).getOrThrow())
    }

    @Test fun malformedControlsFailBeforeSaving() {
        for (config in listOf(TuningFitConfig(bassDb = Double.NaN), TuningFitConfig(tiltDbPerOctave = 4.0),
            TuningFitConfig(earGainFrequencyHz = 6000.0), TuningFitConfig(trebleMaxBoostDb = -1.0),
            TuningFitConfig(leftLimits = TuningChannelLimits(minQ = 5.0, maxQ = 1.0)),
            TuningFitConfig(leftTrimDb = 1.0), TuningFitConfig(rightDelayMs = 21.0))) {
            assertTrue(runCatching { TuningProjectCodec.validateConfig(config) }.isFailure)
        }
    }

    @Test fun manualAlignmentPersistsAndAppliesEvenWhenNoEqIsRequired() = runBlocking {
        val input = project(TuningFitConfig(leftTrimDb = -2.5, rightDelayMs = 4.25))
        val fit = TuningFitter.fit(input)
        assertTrue(fit.channels.single().bands.isEmpty())
        val saved = input.copy(generatedFit = fit)
        assertEquals(saved, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(saved)).getOrThrow())
        val limiter = ProcessingRackNode(UUID.randomUUID().toString(), "Final limiter", RackNodeKind.LIMITER)
        val rack = TuningRackPlan.append(ProcessingRack(enabled = false, nodes = listOf(limiter)), saved)
        assertEquals(listOf(RackNodeKind.STEREO, RackNodeKind.DELAY, RackNodeKind.LIMITER), rack.nodes.map { it.kind })
        assertEquals(-2.5f, rack.nodes[0].audio.dspTrimLeftDb, 0f)
        assertEquals(0f, rack.nodes[0].audio.dspTrimRightDb, 0f)
        assertEquals(4.25f, rack.nodes[1].audio.dspDelayRightMs, 0f)
        assertFalse(rack.enabled)
        assertNotEquals(TuningProjectCodec.inputFingerprint(input), TuningProjectCodec.inputFingerprint(input.copy(config = input.config.copy(rightDelayMs = 0.0))))
        val full = ProcessingRack(nodes = List(15) { limiter.copy(id = UUID.randomUUID().toString()) })
        assertTrue(runCatching { TuningRackPlan.append(full, saved) }.isFailure)
    }

    @Test fun forgedFilterIdentityAndBypassAreRejectedBeforeApplication() = runBlocking {
        val input = project(TuningFitConfig(bassDb = 3.0))
        val fit = TuningFitter.fit(input)
        val channel = fit.channels.single()
        assertTrue(channel.bands.isNotEmpty())
        for (band in listOf(channel.bands[0].copy(filterId = "all_pass.v1"), channel.bands[0].copy(enabled = false))) {
            val forged = fit.copy(channels = listOf(channel.copy(bands = listOf(band) + channel.bands.drop(1))))
            assertTrue(runCatching { TuningProjectCodec.validate(input.copy(generatedFit = forged)) }.isFailure)
        }
    }

    @Test fun independentChannelsAccept128TotalBandsButOneStageCannotExceed64() = runBlocking {
        val input = project(TuningFitConfig(bandBudget = 128)).copy(measurementRight = flat("Right"))
        assertEquals(2, TuningFitter.fit(input).channels.size)
        assertTrue(runCatching { TuningFitter.fit(input.copy(measurementRight = null)) }.isFailure)
        assertTrue(runCatching { TuningFitter.fit(input.copy(config = input.config.copy(channelMode = TuningChannelMode.LINKED_AVERAGE))) }.isFailure)
    }

    @Test fun optimizerStaysWithinRecordedErrorGapOfIndependentScipyReference() = runBlocking {
        val text = requireNotNull(javaClass.getResourceAsStream("/tuning/scipy-fit-reference.json")).bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(text).asJsonObject
        root["fixtures"].asJsonArray.forEach { value ->
            val fixture = value.asJsonObject
            val input = project(TuningFitConfig(bandBudget = 6, normalization = TuningNormalization.NONE, smoothingOctaves = 0.0))
                .copy(measurementLeft = MeasurementTextImporter.parse(fixture["measurement"].asString).getOrThrow())
            val fit = TuningFitter.fit(input)
            val reference = fixture["referenceRmsDb"].asDouble
            println("TUNING_REFERENCE ${fixture["name"].asString} aurora=${fit.errorAfterDb} scipy=$reference")
            assertTrue("${fixture["name"]}: Aurora ${fit.errorAfterDb}, reference $reference", fit.errorAfterDb <= reference + .25)
            assertTrue(fit.errorAfterDb < fit.errorBeforeDb * .2)
            TuningFitter.verifyGeneratedFit(input, fit)
        }
    }
}
