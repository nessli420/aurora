package com.aurora.music.data.listening

import com.aurora.music.data.PcmLevels
import com.aurora.music.data.routes.OutputDeviceCategory
import com.aurora.music.data.routes.ProcessingRoute
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.routes.RouteObservation
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ListeningLevelsTest {
    @Test fun fullScaleSineUsesSpecifiedRmsVoltage() {
        val result = available(profile(), observation(rms = 1.0 / sqrt(2.0)))
        assertEquals(100.0, result.leftDb!!, 1e-10)
        assertEquals(100.0, result.rightDb!!, 1e-10)
    }

    @Test fun squareWaveHasHigherRmsThanSamePeakSine() {
        assertEquals(103.0102999566, available(profile(), observation(rms = 1.0)).maximumDb!!, 1e-8)
    }

    @Test fun milliwattSensitivityConvertsThroughImpedance() {
        val p = profile().copy(sensitivityUnit = SensitivityUnit.DB_PER_MILLIWATT, sensitivityDb = 100.0, impedanceOhms = 32.0)
        assertEquals(114.9485002168, available(p, observation()).maximumDb!!, 1e-8)
        assertEquals(104.9485002168, available(p.copy(impedanceOhms = 320.0), observation()).maximumDb!!, 1e-8)
    }

    @Test fun allDownstreamGainsAreCountedOnceAfterMeasuredDsp() {
        val p = profile().copy(fullScaleVrms = 2.0, gainDb = 6.0, hardwareAttenuationDb = -3.0,
            volumeCurve = listOf(VolumeCalibration(10, -12.0)))
        val source = observation(rms = 0.25 / sqrt(2.0)).copy(downstreamGain = 0.5)
        assertEquals(78.9588001734, available(p, source).maximumDb!!, 1e-8)
    }

    @Test fun channelsAndSilenceAreIndependent() {
        val o = observation()
        val value = available(profile(), o.copy(postDsp = o.postDsp!!.copy(leftRms = 0.0)))
        assertNull(value.leftDb)
        assertEquals(100.0, value.maximumDb!!, 1e-10)
        assertNull(available(profile(), observation(rms = 0.0)).maximumDb)
    }

    @Test fun floatOverloadCannotBeEstimatedEvenAfterDownstreamAttenuation() {
        val o = observation(rms = 0.4)
        listOf(o.postDsp!!.copy(leftPeak = 1.2), o.postDsp.copy(rightPeak = 1.2),
            o.postDsp.copy(leftRms = 1.2, leftPeak = 1.8)).forEach { levels ->
            listOf(1.0, 0.25).forEach { gain ->
                val result = ListeningMath.estimate(profile(), o.copy(postDsp = levels, downstreamGain = gain), 1L)
                assertEquals(ListeningEstimate.Unavailable("Output may be clipped."), result)
            }
        }
    }

    @Test fun downstreamBoostRejectsOverloadButFullScaleBoundaryRemainsValid() {
        val o = observation(rms = 0.25)
        val levels = o.postDsp!!.copy(leftPeak = 0.5, rightPeak = 0.4)
        assertTrue(ListeningMath.estimate(profile(), o.copy(postDsp = levels, downstreamGain = 2.0), 1L) is ListeningEstimate.Available)
        assertUnavailable(profile(), o.copy(postDsp = levels, downstreamGain = 2.001))
        assertEquals(100.0, available(profile(), observation()).maximumDb!!, 1e-10)
        assertEquals(103.0102999566, available(profile(), observation(rms = 1.0)).maximumDb!!, 1e-8)
    }

    @Test fun volumeStepsAreMeasuredNotInterpolated() {
        val p = profile().copy(volumeCurve = listOf(VolumeCalibration(2, -40.0), VolumeCalibration(10, 0.0)))
        assertUnavailable(p, observation().copy(volumeIndex = 6))
        assertUnavailable(p, observation().copy(volumeIndex = null))
        assertUnavailable(p, observation().copy(volumeMaximum = 25))
        assertUnavailable(p, observation().copy(volumeMaximum = null))
    }

    @Test fun unknownRouteOrChangedHardwareConfirmationInvalidatesEstimate() {
        val o = observation()
        assertUnavailable(profile(), o.copy(route = o.route.copy(generation = o.route.generation + 1)))
        assertUnavailable(profile(), o.copy(route = o.route.copy(route = o.route.route.copy(key = null))))
        assertUnavailable(profile(), o.copy(route = o.route.copy(route = o.route.route.copy(key = "android:3:" + "b".repeat(64)))))
        assertUnavailable(profile(), o.copy(route = o.route.copy(route = o.route.route.copy(kind = ProcessingRouteKind.CAST))))
        assertUnavailable(profile(), o.copy(route = o.route.copy(route = o.route.route.copy(category = OutputDeviceCategory.SPEAKER))))
        assertTrue(ListeningMath.estimate(profile(), o, null) is ListeningEstimate.Unavailable)
    }

    @Test fun missingStaleOrUnmeasuredOutputNeverReturnsAnEstimate() {
        val o = observation()
        listOf(o.copy(playing = false), o.copy(pathSupported = false), o.copy(postDsp = null), o.copy(volumeMuted = true),
            o.copy(downstreamGain = null), o.copy(downstreamGain = Double.NaN), o.copy(downstreamGain = 0.0),
            o.copy(nowNanos = o.nowNanos + 3_000_000_000L), o.copy(nowNanos = 1L),
            o.copy(postDsp = o.postDsp!!.copy(invalidSamples = 1L)),
            o.copy(postDsp = o.postDsp.copy(leftPeak = Double.NaN)),
            o.copy(postDsp = o.postDsp.copy(rightPeak = -1.0)),
            o.copy(postDsp = o.postDsp.copy(leftRms = Double.NaN))).forEach { assertUnavailable(profile(), it) }
    }

    @Test fun calibrationRejectsInvalidAndNonmonotonicValues() {
        val p = profile()
        listOf(p.copy(impedanceOhms = 0.0), p.copy(fullScaleVrms = Double.NaN), p.copy(gainDb = Double.POSITIVE_INFINITY),
            p.copy(provenance = ""), p.copy(uncertaintyDb = 0.0), p.copy(hardwareAttenuationDb = 1.0),
            p.copy(routeKey = "usb serial number"), p.copy(volumeCurve = listOf(VolumeCalibration(1, -20.0), VolumeCalibration(2, -30.0))),
            p.copy(volumeCurve = listOf(VolumeCalibration(1, -20.0), VolumeCalibration(1, -10.0))))
            .forEach { assertTrue(runCatching { ListeningMath.validate(it) }.isFailure) }
    }

    @Test fun volumeCurveParserAcceptsSeparateMeasuredPoints() {
        assertEquals(listOf(VolumeCalibration(1, -40.0), VolumeCalibration(3, -20.0), VolumeCalibration(10, 0.0)),
            ListeningMath.parseVolumeCurve("1=-40\n3=-20; 10=0"))
        assertTrue(runCatching { ListeningMath.parseVolumeCurve("10:50%") }.isFailure)
    }

    private fun available(profile: ListeningProfile, observation: ListeningObservation) =
        ListeningMath.estimate(profile, observation, 1L) as ListeningEstimate.Available
    private fun assertUnavailable(profile: ListeningProfile, observation: ListeningObservation) =
        assertTrue(ListeningMath.estimate(profile, observation, 1L) is ListeningEstimate.Unavailable)

    companion object {
        val key = "android:22:" + "a".repeat(64)
        fun route() = ProcessingRoute(ProcessingRouteKind.ANDROID, key, "DAC", category = OutputDeviceCategory.USB)
        fun profile() = ListeningProfile("calibration-1", "Headphones", key, "DAC", 100.0, SensitivityUnit.DB_PER_VOLT,
            32.0, 1.0, "Low", 0.0, 0.0, listOf(VolumeCalibration(10, 0.0)), "Measured loaded voltage; published 1 kHz sensitivity", 4.0, 15)
        fun observation(rms: Double = 1.0 / sqrt(2.0)): ListeningObservation = ListeningObservation(RouteObservation(1, route()),
            playing = true, postDsp = PcmLevels(48000, 2, 4800, 4800, 1.0, 1.0, rms, rms, 0L, 0L, 100000L, 10_000_000_000L),
            downstreamGain = 1.0, volumeIndex = 10, volumeMuted = false, pathSupported = true,
            nowNanos = 10_000_000_000L, timestampMillis = 1700000000000L, volumeMaximum = 15)
    }
}
