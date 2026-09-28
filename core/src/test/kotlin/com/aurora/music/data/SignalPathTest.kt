package com.aurora.music.data

import org.junit.Assert.*
import org.junit.Test

/** Evidence boundary regressions. These make no claim to test a physical DAC or Android's mixer. */
class SignalPathTest {
    private val pcm16 = SignalFormat(44100, 16, 2, "integer PCM")
    private val pcm24 = SignalFormat(96000, 24, 2, "integer PCM")
    private val float32 = SignalFormat(96000, 32, 2, "float PCM")
    private val local = SignalPathFacts(
        kind = PlaybackPathKind.ANDROID, sourceCopy = "Local playable copy", codec = "FLAC",
        sourceFormat = pcm24, sourceBitrate = 1_200_000, decodedFormat = pcm24,
        decoderName = "Native libFLAC", androidTrackFormat = float32,
    )

    @Test fun pcm16DoesNotBypassProcessorsWhenFloatPreferenceIsEnabled() {
        assertFalse(usesFloatPcmPath(true, pcm16))
        assertTrue(usesFloatPcmPath(true, pcm24))
        assertTrue(usesFloatPcmPath(true, float32))
        assertFalse(usesFloatPcmPath(false, pcm24))
        assertFalse(usesFloatPcmPath(true, null))
    }

    @Test fun configuredButBypassedDspDoesNotClaimToModifyAudio() {
        val path = buildSignalPath(local.copy(processorPath = "Float path; app processors bypassed",
            bypassedNodes = listOf("Custom DSP", "Convolution", "Mono downmix")))
        assertEquals(Preservation.UNKNOWN, path.preservation)
        assertTrue(path.processing.detail.contains("Bypassed / unapplied: Custom DSP"))
        assertFalse(path.bitPerfect)
    }

    @Test fun customDspOwnsMonoEvenWhenTheSeparateMonoProcessorIsDisabled() {
        assertEquals(MonoProcessingLocation.CUSTOM_DSP, monoProcessingLocation(true, true, false))
        assertEquals(MonoProcessingLocation.PROCESSOR, monoProcessingLocation(true, false, true))
        assertEquals(MonoProcessingLocation.BYPASSED, monoProcessingLocation(true, false, false))
        assertEquals(MonoProcessingLocation.OFF, monoProcessingLocation(false, true, false))
    }

    @Test fun appliedSampleChangesInvalidateAnyOutputGrant() {
        listOf("Custom DSP", "ReplayGain attenuation", "Player volume", "Sleep fade", "Alarm fade",
            "Crossfade", "Speed adjustment", "Native varispeed").forEach { modification ->
            val path = buildSignalPath(local.copy(modifications = listOf(modification),
                mixerGrant = true, mixerGrantMatchesFormat = true))
            assertEquals(modification, Preservation.MODIFIED, path.preservation)
            assertFalse(modification, path.bitPerfect)
            assertTrue(path.reasons.contains(modification))
        }
    }

    @Test fun nativeTransportDoesNotDependOnAndroidMixerGrant() {
        val facts = local.copy(kind = PlaybackPathKind.NATIVE_USB, androidTrackFormat = null,
            nativeTransportFormat = pcm24, nativeClockAccepted = true)
        val absent = buildSignalPath(facts)
        val granted = buildSignalPath(facts.copy(mixerGrant = true, mixerGrantMatchesFormat = true))
        assertEquals(absent, granted)
        assertEquals(pcm24, absent.outputStage.format)
        assertFalse(absent.outputStage.detail.contains("AudioTrack"))
        assertEquals(Preservation.UNKNOWN, absent.preservation)
    }

    @Test fun processedUsbReportsValidBitsSeparatelyFromContainerAndClock() {
        val path = buildSignalPath(local.copy(kind = PlaybackPathKind.PROCESSED_USB,
            decodedFormat = pcm24, nativeTransportFormat = pcm24, nativeContainerBits = 32,
            nativeClockRate = 96000, nativeClockAccepted = true, modifications = listOf("Software volume")))
            .copy(usbDiagnostics = UsbDiagnostics(96000, 512, 2, 1))
        assertEquals("Processed USB transport", path.output)
        assertEquals(24, path.outputStage.format?.bitDepth)
        assertTrue(path.outputStage.detail.contains("32-bit USB container"))
        assertTrue(path.outputStage.detail.contains("96000 Hz clock readback"))
        assertEquals(Preservation.MODIFIED, path.preservation)
        assertFalse(path.bitPerfect)
        assertTrue(path.toDiagnosticReport().contains("USB packet errors/timeouts: 2 / 1"))
        assertFalse(path.reasons.any { it.contains("Android mixer") })
    }

    @Test fun dsdConversionCannotClaimPreservedSamplesOnVerifiedUsbClock() {
        val path = buildSignalPath(local.copy(kind = PlaybackPathKind.DECODED_USB,
            sourceFormat = SignalFormat(2822400, 1, 2, "DSD"), decodedFormat = pcm24,
            nativeTransportFormat = pcm24, nativeClockAccepted = true,
            modifications = listOf("DSD converted to PCM")))
        assertEquals(Preservation.MODIFIED, path.preservation)
        assertEquals(2822400, path.source.format?.rateHz)
        assertEquals(96000, path.decoder.format?.rateHz)
        assertFalse(path.bitPerfect)
    }

    @Test fun decodedUsbAndNativeTailRemainQualified() {
        val path = buildSignalPath(local.copy(kind = PlaybackPathKind.DECODED_USB,
            nativeTransportFormat = pcm24, nativeClockAccepted = false, nativeTailSubmitted = true))
        assertEquals(Preservation.UNKNOWN, path.preservation)
        assertTrue(path.reasons.any { it.contains("completion is not reported") })
        assertTrue(path.reasons.any { it.contains("not confirmed") })
    }

    @Test fun staleLocalFieldsCannotSurviveCastMixOrIdle() {
        val dirty = local.copy(activeNodes = listOf("Custom DSP"), modifications = listOf("ReplayGain"),
            nativeTransportFormat = pcm24, mixerGrant = true, nativeClockAccepted = true,
            requestedDevice = "USB audio device")
        listOf(PlaybackPathKind.CAST, PlaybackPathKind.MIX, PlaybackPathKind.IDLE).forEach { kind ->
            val path = buildSignalPath(dirty.copy(kind = kind))
            assertEquals(kind.name, 0, path.sampleRateHz)
            assertEquals(kind.name, "", path.codec)
            assertNull(kind.name, path.source.format)
            assertNull(kind.name, path.outputStage.format)
            assertFalse(kind.name, path.toDiagnosticReport().contains("1200000"))
            assertFalse(kind.name, path.toDiagnosticReport().contains("ReplayGain"))
            assertFalse(path.bitPerfect)
        }
        assertEquals(Preservation.MODIFIED, buildSignalPath(dirty.copy(kind = PlaybackPathKind.MIX)).preservation)
        assertEquals(Preservation.UNKNOWN, buildSignalPath(dirty.copy(kind = PlaybackPathKind.CAST)).preservation)
        assertFalse(buildSignalPath(dirty.copy(kind = PlaybackPathKind.IDLE)).active)
    }

    @Test fun missingOutputMeasurementsAreNotCopiedFromDecoder() {
        val path = buildSignalPath(local.copy(androidTrackFormat = null, sourceBitrate = null))
        assertNull(path.outputStage.format)
        assertEquals(96000, path.sampleRateHz)
        assertFalse(path.source.detail.contains("bit/s"))
        assertTrue(path.outputStage.detail.contains("hardware format unknown"))
        assertEquals(Preservation.UNKNOWN, path.preservation)
    }

    @Test fun outputRateDepthAndChannelChangesAreReported() {
        listOf(pcm24.copy(rateHz = 48000), pcm24.copy(bitDepth = 16), pcm24.copy(channels = 1)).forEach { converted ->
            val path = buildSignalPath(local.copy(androidTrackFormat = converted))
            assertEquals(converted.describe(), Preservation.MODIFIED, path.preservation)
            assertFalse(path.bitPerfect)
        }
    }

    @Test fun grantWithWrongFormatCannotClaimPreservation() {
        val path = buildSignalPath(local.copy(mixerGrant = true, mixerGrantMatchesFormat = false))
        assertEquals(Preservation.UNKNOWN, path.preservation)
        assertTrue(path.reasons.any { it.contains("does not match") })
        assertFalse(path.bitPerfect)
    }

    @Test fun diagnosticsNameEvidenceBoundariesAndKeepUnknowns() {
        val path = buildSignalPath(local.copy(androidTrackFormat = null, restartRequired = true))
        val report = path.toDiagnosticReport()
        assertTrue(report.contains("1200000 bit/s"))
        assertTrue(report.contains("service or restart the app"))
        assertTrue(report.contains("not measured"))
        assertTrue(report.contains("not verified"))
        assertFalse(report.contains("http://"))
        assertFalse(report.contains("https://"))
    }

    @Test fun confirmedRouteShowsOnlyCategoryWithoutClaimingHardwareFormat() {
        val category = com.aurora.music.data.routes.OutputDeviceCategory.BLUETOOTH
        val path = buildSignalPath(local.copy(confirmedDevice = category, requestedDevice = "speaker",
            mixerGrant = true, mixerGrantMatchesFormat = true))
        assertTrue(path.device.detail.startsWith("Bluetooth output"))
        assertTrue(path.device.evidence.contains("routed-device"))
        assertFalse(path.device.detail.contains("speaker"))
        assertFalse(path.reasons.any { it.contains("actual route") })
        assertEquals(Preservation.UNKNOWN, path.preservation)
        assertFalse(path.bitPerfect)
        assertTrue(buildSignalPath(local).device.detail.contains("unknown"))
        assertTrue(buildSignalPath(local.copy(kind = PlaybackPathKind.MIX, confirmedDevice = category))
            .device.detail.startsWith("Bluetooth output"))
    }
}
