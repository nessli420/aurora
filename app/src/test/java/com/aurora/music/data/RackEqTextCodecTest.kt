package com.aurora.music.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.UUID

class RackEqTextCodecTest {
    private val band = ParamBand(1000f, -3f, 1f)
    private fun filter(index: Int = 1, type: String = "PK", frequency: String = "1000", gain: String = "-3", q: String = "1") =
        "Filter $index: ON $type Fc $frequency Hz Gain $gain dB Q $q"
    private fun node(kind: RackNodeKind = RackNodeKind.GAIN, bands: List<ParamBand> = emptyList()) =
        ProcessingRackNode(UUID.randomUUID().toString(), "Existing", kind, audio = AudioPrefs(dspParametric = bands))
    private fun error(text: String, line: Int): String {
        val failure = RackEqTextCodec.parse(text).exceptionOrNull()
        assertNotNull("Invalid EQ text was accepted: $text", failure)
        val message = failure!!.message.orEmpty()
        assertTrue(message, message.startsWith("Line $line:"))
        return message
    }

    @Test fun parsesCanonicalSubsetWithCommentsBomCrLfCaseAndOptionalLabels() {
        val text = "\uFEFF# Exported profile\r\nPreamp:\t-6.25 dB # Headroom\r\n" +
            "filter: on pk fc 1e3 hz gain -3 db q 1\r\n" +
            "Filter 8: ON LSC Fc 85.5 Hz Gain +2.25 dB Q .707\r\n" +
            "Filter 8: ON HSC Fc 1.2E+4 Hz Gain -1.5 dB Q 0.9\r\n"
        val parsed = RackEqTextCodec.parse(text).getOrThrow()
        assertEquals(-6.25f, parsed.preampDb, 0f)
        assertEquals(listOf(band, ParamBand(85.5f, 2.25f, .707f, BandType.LOW_SHELF),
            ParamBand(12000f, -1.5f, .9f, BandType.HIGH_SHELF)), parsed.bands)
        // APO labels are not interpreted: duplicate labels still represent two ordered filters.
        assertEquals(3, parsed.bands.size)
    }

    @Test fun preampIsOptionalButEmptyAndCommentOnlyDocumentsFail() {
        assertEquals(ParsedEq(0f, listOf(band)), RackEqTextCodec.parse(filter()).getOrThrow())
        assertEquals(ParsedEq(-4f, emptyList()), RackEqTextCodec.parse("Preamp: -4 dB").getOrThrow())
        assertEquals(ParsedEq(0f, emptyList()), RackEqTextCodec.parse("Preamp: 0 dB").getOrThrow())
        error("", 1)
        error("# No processing\n\t\n", 1)
    }

    @Test fun everyAcceptedFloatRoundTripsWithoutLocaleDependentFormatting() {
        val originalLocale = Locale.getDefault()
        try {
            val profiles = listOf(
                ParsedEq(-0.0f, listOf(ParamBand(10f, -0.0f, .1f), ParamBand(24000f, 30f, 100f, 2))),
                ParsedEq(-java.lang.Float.intBitsToFloat(1), listOf(ParamBand(1234.5677f,
                    java.lang.Float.intBitsToFloat(1), 1.2345678f, 1))),
                ParsedEq(-59.999996f, listOf(ParamBand(1000.00006f, -29.999998f, 0.10000001f))),
            )
            for (locale in listOf(Locale.GERMANY, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                for (profile in profiles) {
                    val text = RackEqTextCodec.encode(profile)
                    assertFalse(text.contains(','))
                    assertEquals(profile, RackEqTextCodec.parse(text).getOrThrow())
                }
            }
        } finally { Locale.setDefault(originalLocale) }
    }

    @Test fun sixtyFourActiveBandsAndPreampSurviveTextAndRackRoundTrip() {
        val profile = ParsedEq(-8.123456f, List(64) { ParamBand(20f + it * 350.25f,
            (it % 7 - 3).toFloat(), .5f + it * .01f, it % 3) })
        val parsed = RackEqTextCodec.parse(RackEqTextCodec.encode(profile)).getOrThrow()
        assertEquals(profile, parsed)
        val rack = RackEqTextCodec.appendToRack(ProcessingRack(), parsed, "64 bands").getOrThrow()
        assertEquals(listOf(RackNodeKind.GAIN, RackNodeKind.EQ), rack.nodes.map { it.kind })
        assertEquals(profile.preampDb, rack.nodes[0].audio.dspPreampDb, 0f)
        assertEquals(profile.bands, rack.nodes[1].audio.dspParametric)
        assertFalse(rack.enabled)
        assertEquals(rack, ProcessingRackCodec.decode(ProcessingRackCodec.encode(rack)).getOrThrow())
        error((1..65).joinToString("\n") { filter(it) }, 65)
    }

    @Test fun unsupportedActiveCommandsRejectTheWholeProfileAtTheirLine() {
        for (command in listOf("Channel: L", "Include: other.txt", "Device: all", "GraphicEQ: 20 -3; 100 2",
            "Convolution: room.wav", "Delay: 10 ms", "Copy: L=R", "Eval: x=1", "If: 1", "Stage: post-mix")) {
            error("Preamp: -3 dB\n${filter()}\n$command", 3)
        }
    }

    @Test fun offRowsAreExplicitlyRejectedInsteadOfLosingDisabledMetadata() {
        for (off in listOf("Filter 2: OFF", filter(2).replace("ON", "OFF"), "Filter: off HP Fc 10 Hz")) {
            assertTrue(error("${filter()}\n$off", 2).contains("OFF filters"))
        }
        assertEquals(1, RackEqTextCodec.parse("${filter()}\n# Filter: OFF").getOrThrow().bands.size)
    }

    @Test fun unsupportedFilterMathAndMalformedTailsAreNeverPartiallyImported() {
        for (line in listOf(filter(type = "LS"), filter(type = "HS"), filter(type = "PEQ"), filter(type = "AP"),
            filter(type = "UNKNOWN"), "Filter: ON LSC 6 dB Fc 100 Hz Gain 3 dB",
            "Filter: ON PK Fc 100 Hz Gain 3 dB BW Oct 1", filter() + " extra", filter() + "; Preamp: 4 dB",
            filter().replace("Gain -3 dB", "Gain -3"), filter().replace("ON", "MAYBE"), "Filter 1 ON PK")) {
            error("# Profile\n$line", 2)
        }
    }

    @Test fun duplicateAndMalformedPreampsAreRejectedRatherThanOverwrittenOrSummedLossily() {
        assertTrue(error("Preamp: -2 dB\nPreamp: -4 dB", 2).contains("Multiple"))
        for (line in listOf("Preamp -2 dB", "Preamp: -2", "Preamp: -2 dB extra", "Preamp: `-2*3` dB")) error(line, 1)
    }

    @Test fun malformedNonFiniteUnderflowAndOutOfRangeNumbersFailWithLineNumbers() {
        for (value in listOf("NaN", "Infinity", "-Infinity", "1,2", "0x1p2", "1e", "1e999", "--2")) {
            error("# Imported\n" + filter(gain = value), 2)
        }
        for (value in listOf("1e-100", "-1e-9999")) {
            assertTrue(error(filter(gain = value), 1).contains("becoming zero"))
            error("Preamp: $value dB", 1)
        }
        for (value in listOf("9.9", "24001", "-1", "0")) error(filter(frequency = value), 1)
        for (value in listOf("-30.1", "30.1")) error(filter(gain = value), 1)
        for (value in listOf("0", ".01", ".099", "100.1")) error(filter(q = value), 1)
        assertTrue(error(filter(q = ".05"), 1).contains("clamped during playback"))
        for (value in listOf("-60.1", "24.1")) error("Preamp: $value dB", 1)
        error(filter(q = "0".repeat(64) + "1"), 1)
    }

    @Test fun inputByteLineLengthAndLineCountBoundsApplyBeforeParsing() {
        error("#" + "a".repeat(RackEqTextCodec.MAX_TEXT_BYTES), 1)
        error(("#" + "é".repeat(132) + "\n").repeat(1000), 1)
        error("#" + "a".repeat(RackEqTextCodec.MAX_LINE_CHARS), 1)
        error("#\n".repeat(RackEqTextCodec.MAX_LINES) + "Preamp: 0 dB", RackEqTextCodec.MAX_LINES + 1)
        assertTrue(RackEqTextCodec.parse("#\n".repeat(RackEqTextCodec.MAX_LINES - 1) + "Preamp: 0 dB").isSuccess)
        error("${filter()}\u0000", 1)
    }

    @Test fun appendPreservesExistingGraphAndEnabledStateAndAddsOnlyRequiredNodes() {
        for (enabled in listOf(false, true)) {
            val existing = ProcessingRack(enabled = enabled, name = "Current", nodes = listOf(node()))
            val zeroGain = RackEqTextCodec.appendToRack(existing, ParsedEq(0f, listOf(band)), "Imported").getOrThrow()
            assertEquals(existing, zeroGain.copy(nodes = zeroGain.nodes.dropLast(1)))
            assertEquals(RackNodeKind.EQ, zeroGain.nodes.last().kind)
            assertTrue(zeroGain.nodes.last().audio.dspGraphicBands.isEmpty())
            assertTrue(zeroGain.nodes.last().audio.dspConvIrPath.isEmpty())
            val nonzero = RackEqTextCodec.appendToRack(existing, ParsedEq(-4f, listOf(band)), "Imported").getOrThrow()
            assertEquals(existing, nonzero.copy(nodes = nonzero.nodes.dropLast(2)))
            assertEquals(listOf(RackNodeKind.GAIN, RackNodeKind.EQ), nonzero.nodes.takeLast(2).map { it.kind })
            assertEquals(-4f, nonzero.nodes[1].audio.dspPreampDb, 0f)
            assertEquals(0f, nonzero.nodes[2].audio.dspPreampDb, 0f)
        }
    }

    @Test fun nodeCapacityIsCheckedForTheCompleteImportBeforeReturningANewGraph() {
        val fifteen = ProcessingRack(nodes = List(15) { node() })
        val frozen = ProcessingRackCodec.encode(fifteen)
        assertTrue(RackEqTextCodec.appendToRack(fifteen, ParsedEq(-1f, listOf(band)), "Full").isFailure)
        assertEquals(frozen, ProcessingRackCodec.encode(fifteen))
        assertEquals(16, RackEqTextCodec.appendToRack(fifteen, ParsedEq(0f, listOf(band)), "Fits").getOrThrow().nodes.size)
        val sixteen = fifteen.copy(nodes = fifteen.nodes + node())
        assertTrue(RackEqTextCodec.appendToRack(sixteen, ParsedEq(0f, listOf(band)), "Full").isFailure)
        assertEquals(16, RackEqTextCodec.appendToRack(fifteen.copy(nodes = fifteen.nodes.take(14)),
            ParsedEq(-1f, listOf(band)), "Fits").getOrThrow().nodes.size)
    }

    @Test fun totalBandCapacityIncludesBypassedEqAndLegacyNodes() {
        val existing = ProcessingRack(nodes = listOf(node(RackNodeKind.LEGACY_DSP, List(12) { band }),
            node(RackNodeKind.EQ, List(51) { band }).copy(bypass = true)))
        assertTrue(RackEqTextCodec.appendToRack(existing, ParsedEq(0f, listOf(band)), "Fits").isSuccess)
        assertTrue(RackEqTextCodec.appendToRack(existing, ParsedEq(0f, listOf(band, band)), "Too many").isFailure)
        assertEquals(63, existing.nodes.sumOf { it.audio.dspParametric.size })
    }

    @Test fun encodingAndAppendingRejectInvalidDirectProfilesWithoutClamping() {
        val invalid = listOf(ParsedEq(Float.NaN, listOf(band)), ParsedEq(25f, listOf(band)),
            ParsedEq(0f, listOf(band.copy(type = 3))), ParsedEq(0f, listOf(band.copy(q = 0f))),
            ParsedEq(0f, listOf(band.copy(q = .05f))),
            ParsedEq(0f, listOf(band.copy(gainDb = Float.POSITIVE_INFINITY))), ParsedEq(0f, List(65) { band }))
        for (profile in invalid) {
            assertTrue(runCatching { RackEqTextCodec.encode(profile) }.isFailure)
            assertTrue(RackEqTextCodec.appendToRack(ProcessingRack(), profile, "Invalid").isFailure)
        }
        assertTrue(RackEqTextCodec.appendToRack(ProcessingRack(), ParsedEq(0f, listOf(band)), " ").isFailure)
    }

    @Test fun appendedSnapshotsDetachMutableListsAndKeepStableUniqueIds() {
        val bands = mutableListOf(band)
        val nodes = mutableListOf(node())
        val result = RackEqTextCodec.appendToRack(ProcessingRack(nodes = nodes), ParsedEq(-2f, bands), "x".repeat(80)).getOrThrow()
        bands.clear(); nodes.clear()
        assertEquals(3, result.nodes.size)
        assertEquals(listOf(band), result.nodes.last().audio.dspParametric)
        assertEquals(3, result.nodes.map { UUID.fromString(it.id) }.distinct().size)
        val decoded = ProcessingRackCodec.decode(ProcessingRackCodec.encode(result)).getOrThrow()
        assertEquals(result.nodes.map { it.id }, decoded.nodes.map { it.id })
        val again = RackEqTextCodec.appendToRack(result, ParsedEq(0f, listOf(band)), "Again").getOrThrow()
        assertEquals(4, again.nodes.map { it.id }.distinct().size)
    }
}
