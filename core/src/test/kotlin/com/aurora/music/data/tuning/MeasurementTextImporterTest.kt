package com.aurora.music.data.tuning

import org.junit.Assert.*
import org.junit.Test

class MeasurementTextImporterTest {
    private fun failure(text: String, line: Int, phase: Boolean = false): String {
        val result = MeasurementTextImporter.parse(text, phaseColumn = phase)
        assertTrue("Malformed measurement accepted", result.isFailure)
        return result.exceptionOrNull()!!.message.orEmpty().also { assertTrue(it, it.startsWith("Line $line:")) }
    }

    @Test fun rewHeaderRetainsExactRawSourceAndUnwrappedPhase() {
        val source = "\uFEFF* Measurement data saved by REW\r\n* Dated: 10-Sep-2026\r\n" +
            "* Freq(Hz) SPL(dB) Phase(degrees)\r\n20.000 75.125 -450.5\r\n40.000 77.25 720.125\r\n"
        val provenance = MeasurementProvenance("Own microphone", "REW sweep", "Left speaker, seat 1")
        val result = MeasurementTextImporter.parse(source, "Left", provenance = provenance, importedAtMs = 42).getOrThrow()
        assertEquals(source, result.sourceText)
        assertEquals(provenance, result.provenance)
        assertEquals(42L, result.importedAtMs)
        assertEquals(listOf(FrequencyResponsePoint(20.0, 75.125, -450.5), FrequencyResponsePoint(40.0, 77.25, 720.125)), result.points)
    }

    @Test fun commaSemicolonTabAndSpaceDataRetainUnchangedNumericValues() {
        for (delimiter in listOf(",", ";", "\t", " ")) {
            val source = "20${delimiter}-1.25\n40${delimiter}2.5\n100${delimiter}0.125"
            val result = MeasurementTextImporter.parse(source).getOrThrow()
            assertEquals(source, result.sourceText)
            assertEquals(listOf(-1.25, 2.5, .125), result.points.map { it.magnitudeDb })
            assertTrue(result.points.all { it.phaseDegrees == null })
        }
    }

    @Test fun quotedCsvHeaderAndScientificNotationAreExplicitlySupported() {
        val result = MeasurementTextImporter.parse("\"Frequency [Hz]\",\"Magnitude [dB]\",\"Phase [degrees]\"\n" +
            "\"2e1\",\"-1.125e-2\",\"9E1\"\n\"4e1\",\"-0.0\",\"-0.0\"").getOrThrow()
        assertEquals(FrequencyResponsePoint(20.0, -.01125, 90.0), result.points[0])
        assertEquals((-0.0).toRawBits(), result.points[1].magnitudeDb.toRawBits())
        assertEquals((-0.0).toRawBits(), result.points[1].phaseDegrees!!.toRawBits())
    }

    @Test fun unlabeledThirdColumnRequiresAnExplicitPhaseChoice() {
        val source = "20 1 45\n40 2 -30"
        assertTrue(failure(source, 1).contains("ambiguous"))
        assertEquals(listOf(45.0, -30.0), MeasurementTextImporter.parse(source, phaseColumn = true).getOrThrow().points.map { it.phaseDegrees })
        failure("Frequency Hz, SPL dB, Phase\n20,1,45\n40,2,-30", 1)
        assertTrue(MeasurementTextImporter.parse("Frequency Hz, SPL dB, Phase\n20,1,45\n40,2,-30", phaseColumn = true).isSuccess)
    }

    @Test fun mixedMissingAndExtraColumnsRejectTheWholeMeasurement() {
        for (row in listOf("40 2 3", "40", "40 2 3 4", "40,,2", "40;1,5")) {
            failure("20 1\n$row", 2, phase = true)
        }
        failure("Frequency (Hz), SPL (dB), Phase (degrees)\n20,1\n40,2", 2)
        failure("20 1 0\n40 2", 2, phase = true)
    }

    @Test fun duplicateDescendingAndZeroFrequenciesAreNotReorderedOrAveraged() {
        for (row in listOf("20 9", "10 9", "0 9", "-1 9")) failure("20 1\n$row", 2)
        failure("1000001 1\n1000002 2", 1)
    }

    @Test fun unsupportedUnitsAndMultipleMeasurementsAreRejected() {
        for (header in listOf("Frequency (kHz),Magnitude (dB)", "* Freq(Hz) Z(Ohms) Phase(degrees)",
            "* Freq(Hz) SPL(dB) Phase(radians)", "# \"Frequency (kHz)\",\"SPL (dB)\"")) {
            failure("$header\n20 1\n40 2", 1)
        }
        failure("20 1\n40 2\nFrequency (Hz),SPL (dB)\n80 3", 3)
        failure("Header with unknown meaning\n20 1\n40 2", 1)
    }

    @Test fun malformedNonfiniteAndUnderflowNumbersAreRejectedWithLineNumbers() {
        for (value in listOf("NaN", "Infinity", "1e309", "1e-400", "1x", "0x1p2", "1_000", "--3")) {
            failure("20 1\n40 $value", 2)
        }
        failure("20 1\n40 1001", 2)
        failure("20 1 0\n40 2 1e10", 2, phase = true)
        failure("20 1\n40 " + "0".repeat(65), 2)
        failure("20 1\n40 2\u0000", 2)
    }

    @Test fun inputAndPointBudgetsRejectWithoutTruncation() {
        val maximum = (1..MeasurementTextImporter.MAX_POINTS).joinToString("\n") { "$it 0" }
        assertEquals(MeasurementTextImporter.MAX_POINTS, MeasurementTextImporter.parse(maximum).getOrThrow().points.size)
        failure("$maximum\n${MeasurementTextImporter.MAX_POINTS + 1} 0", MeasurementTextImporter.MAX_POINTS + 1)
        failure("#" + "a".repeat(MeasurementTextImporter.MAX_LINE_CHARS) + "\n20 1\n40 2", 1)
        failure("#\n".repeat(MeasurementTextImporter.MAX_LINES) + "20 1\n40 2", MeasurementTextImporter.MAX_LINES + 1)
        failure(("#" + "é".repeat(1000) + "\n").repeat(263), 1)
    }

    @Test fun emptyOrSinglePointInputsFailAndCommentsDoNotBecomeData() {
        failure("", 1)
        failure("# note\n* REW\n// saved", 1)
        failure("20 1", 1)
        assertEquals(2, MeasurementTextImporter.parse("# note\n20 1\n// middle\n40 2\n* end").getOrThrow().points.size)
    }

    @Test fun leftAndRightImportsRemainIndependentOnDifferentFrequencyGrids() {
        val left = MeasurementTextImporter.parse("20 1\n40 3", "Left").getOrThrow()
        val right = MeasurementTextImporter.parse("25 8\n80 10", "Right").getOrThrow()
        val project = TuningProjectCodec.create("Stereo").copy(measurementLeft = left, measurementRight = right)
        val validated = TuningProjectCodec.validate(project)
        assertEquals(left.points, validated.measurementLeft!!.points)
        assertEquals(right.points, validated.measurementRight!!.points)
        assertNotEquals(left.id, right.id)
    }
}
