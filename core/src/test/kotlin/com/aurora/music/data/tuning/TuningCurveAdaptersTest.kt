package com.aurora.music.data.tuning

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TuningCurveAdaptersTest {
    @Test fun autoEqArraysSelectRawOrTargetWithoutLosingOriginalJson() {
        val text = """{"frequency":[20,1000,20000],"raw":[3,0,-4],"target":[5,1,-2]}"""
        val raw = TuningCurveAdapters.parse(text, "Raw", TuningCurveFormat.AUTOEQ_RAW).getOrThrow()
        val target = TuningCurveAdapters.parse(text, "Target", TuningCurveFormat.AUTOEQ_TARGET).getOrThrow()
        assertEquals(listOf(3.0, 0.0, -4.0), raw.points.map { it.magnitudeDb })
        assertEquals(listOf(5.0, 1.0, -2.0), target.points.map { it.magnitudeDb })
        assertEquals(text, target.sourceText)
        val project = TuningProjectCodec.create("AutoEq").copy(measurementLeft = raw, target = target)
        assertEquals(project, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(project)).getOrThrow())
    }

    @Test fun autoEqRejectsMalformedArraysUnknownEnvelopeAndNonFiniteOrDuplicateValues() {
        listOf("""{"frequency":[20,100],"raw":[0]}""", """{"frequency":[20,20],"raw":[0,1]}""",
            """{"frequency":[20,100],"raw":[0,"NaN"]}""", """{"frequency":[20,100],"raw":[0,1e-999]}""",
            """{"fr":{"frequency":[20,100],"raw":[0,1]}}""",
            """{"frequency":[20,100],"raw":[0,1],"raw":[1,0]}""").forEach {
            assertTrue(it, TuningCurveAdapters.parse(it, "Invalid", TuningCurveFormat.AUTOEQ_RAW).isFailure)
        }
    }

    @Test fun squigRewTextAndAutoEqCsvRetainSourceAndProvenance() {
        val source = "* REW export\n* Freq(Hz), SPL(dB), Phase(degrees)\n20, 75, -15\n20000, 72, -380"
        val p = MeasurementProvenance("711", "User measurement", "Left seating 1")
        val curve = TuningCurveAdapters.parse(source, "Left", TuningCurveFormat.SQUIG, provenance = p).getOrThrow()
        assertEquals(-380.0, curve.points.last().phaseDegrees!!, 0.0)
        assertEquals(p, TuningProjectCodec.validateCurve(curve).provenance)
        val csv = "frequency,raw\n20,2\n20000,-3\n"
        val target = TuningCurveAdapters.parse(csv, "Custom", TuningCurveFormat.AUTOEQ_CSV).getOrThrow()
        assertEquals(csv, TuningProjectCodec.validateCurve(target).sourceText)
        assertEquals(-3.0, target.points.last().magnitudeDb, 0.0)
    }

    @Test fun waveletIsAnExplicitLinkedCorrectionFitAndRequiresTheVendorGrid() {
        val source = requireNotNull(javaClass.getResourceAsStream("/tuning/wavelet-neutral.txt")).bufferedReader().use { it.readText() }
        val curve = TuningCurveAdapters.parse(source, "Wavelet", TuningCurveFormat.WAVELET).getOrThrow()
        val project = TuningCurveAdapters.correctionProject(curve)
        assertEquals(TuningChannelMode.LINKED_AVERAGE, project.config.channelMode)
        assertEquals(TuningNormalization.NONE, project.config.normalization)
        assertEquals(curve, project.target)
        assertTrue(project.measurementLeft!!.points.all { it.magnitudeDb == 0.0 })
        assertTrue(TuningCurveAdapters.parse(source.replace("21 0;", "21.5 0;"), "Wrong grid", TuningCurveFormat.WAVELET).isFailure)
        assertTrue(TuningCurveAdapters.parse("GraphicEQ: 20 0; 1000 1; 20000 0", "Short", TuningCurveFormat.WAVELET).isFailure)
        assertTrue(runCatching { TuningTargetCatalog.fromCurve(curve) }.isFailure)
    }

    @Test fun targetCatalogRoundTripsRawDataAndRejectsTamperingOrDuplicates() {
        val curve = TuningCurveAdapters.parse("frequency,raw\n20,2\n20000,-3", "My target", TuningCurveFormat.AUTOEQ_CSV,
            provenance = MeasurementProvenance("Calibrated room mic", "My measurement")).getOrThrow()
        val target = TuningTargetCatalog.fromCurve(curve)
        val json = TuningTargetCatalog.encodeLibrary(listOf(target))
        assertEquals(listOf(target), TuningTargetCatalog.decodeLibrary(json).getOrThrow())
        val renamed = target.copy(name = "Renamed")
        assertEquals(listOf(renamed), TuningTargetCatalog.upsert(listOf(target), renamed).getOrThrow())
        assertTrue(TuningTargetCatalog.delete(listOf(target), target.id).getOrThrow().isEmpty())
        val tampered = JsonParser.parseString(json).asJsonObject
        tampered["targets"].asJsonArray[0].asJsonObject["curve"].asJsonObject.addProperty("sourceText", "frequency,raw\n20,9\n20000,-3")
        assertTrue(TuningTargetCatalog.decodeLibrary(tampered.toString()).isFailure)
        assertTrue(runCatching { TuningTargetCatalog.encodeLibrary(listOf(target, target)) }.isFailure)
        assertTrue(TuningTargetCatalog.decodeLibrary(json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")).isFailure)
    }

    @Test fun publishedSourcesHavePinnedIdentityAndRefuseChangedContent() {
        assertEquals(4, TuningTargetCatalog.published.size)
        TuningTargetCatalog.published.forEach {
            assertTrue(it.url.contains(TuningTargetCatalog.SOURCE_REVISION))
            assertEquals(64, it.sha256.length)
            assertTrue(it.rig.isNotBlank())
            assertTrue(runCatching { TuningTargetCatalog.decodePublished(it, "frequency,raw\n20,0\n20000,0".toByteArray()) }.isFailure)
        }
    }
}
