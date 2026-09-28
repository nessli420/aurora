package com.aurora.music.data.listening

import com.aurora.music.data.PcmLevels
import com.aurora.music.data.routes.OutputDeviceCategory
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.routes.RouteObservation
import kotlin.math.log10
import kotlin.math.sqrt

enum class SensitivityUnit(val label: String) { DB_PER_VOLT("dB / V"), DB_PER_MILLIWATT("dB / mW") }

data class VolumeCalibration(val index: Int, val attenuationDb: Double)

data class ListeningProfile(
    val id: String,
    val name: String,
    val routeKey: String?,
    val routeLabel: String,
    val sensitivityDb: Double,
    val sensitivityUnit: SensitivityUnit,
    val impedanceOhms: Double,
    val fullScaleVrms: Double,
    val gainSetting: String,
    val gainDb: Double,
    val hardwareAttenuationDb: Double,
    val volumeCurve: List<VolumeCalibration>,
    val provenance: String,
    val uncertaintyDb: Double,
    val volumeMaximum: Int,
)

data class ListeningHistoryEntry(
    val timestampMillis: Long,
    val profileName: String,
    val leftDb: Double?,
    val rightDb: Double?,
    val uncertaintyDb: Double,
)

data class ListeningState(
    val profiles: List<ListeningProfile> = emptyList(),
    val historyEnabled: Boolean = false,
    val history: List<ListeningHistoryEntry> = emptyList(),
    val error: String? = null,
)

data class ListeningObservation(
    val route: RouteObservation,
    val playing: Boolean,
    val postDsp: PcmLevels?,
    val downstreamGain: Double?,
    val volumeIndex: Int?,
    val volumeMuted: Boolean,
    val pathSupported: Boolean,
    val nowNanos: Long = System.nanoTime(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val allowHistory: Boolean = true,
    val volumeMaximum: Int? = null,
)

sealed interface ListeningEstimate {
    data class Unavailable(val reason: String) : ListeningEstimate
    data class Available(
        val profileId: String,
        val profileName: String,
        val leftDb: Double?,
        val rightDb: Double?,
        val uncertaintyDb: Double,
        val timestampMillis: Long,
    ) : ListeningEstimate {
        val maximumDb: Double? get() = listOfNotNull(leftDb, rightDb).maxOrNull()
    }
}

object ListeningMath {
    fun validate(profile: ListeningProfile): ListeningProfile {
        require(profile.id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid calibration ID." }
        require(profile.name.isNotBlank() && profile.name.length <= 80) { "Enter a headphone name." }
        require(profile.routeKey == null || profile.routeKey.matches(Regex("android:[0-9]+:[a-f0-9]{64}"))) { "Invalid output binding." }
        require(profile.routeLabel.length <= 120) { "Output name is too long." }
        require(profile.sensitivityDb.isFinite() && profile.sensitivityDb in 40.0..160.0) { "Sensitivity must be 40–160 dB." }
        require(profile.impedanceOhms.isFinite() && profile.impedanceOhms in 1.0..10000.0) { "Impedance must be 1–10,000 ohms." }
        require(profile.fullScaleVrms.isFinite() && profile.fullScaleVrms in 0.001..50.0) { "DAC output must be 0.001–50 V RMS." }
        require(profile.gainSetting.isNotBlank() && profile.gainSetting.length <= 80) { "Name the DAC gain setting." }
        require(profile.gainDb.isFinite() && profile.gainDb in -80.0..40.0) { "Gain must be −80 to 40 dB." }
        require(profile.hardwareAttenuationDb.isFinite() && profile.hardwareAttenuationDb in -160.0..0.0) { "Hardware attenuation must be −160 to 0 dB." }
        require(profile.provenance.isNotBlank() && profile.provenance.length <= 600) { "Enter the calibration source." }
        require(profile.uncertaintyDb.isFinite() && profile.uncertaintyDb in 1.0..40.0) { "Uncertainty must be 1–40 dB." }
        require(profile.volumeCurve.size in 1..256) { "Enter at least one measured volume point." }
        require(profile.volumeMaximum in 1..1000) { "The Android volume range is unavailable." }
        require(profile.volumeCurve.map { it.index }.distinct().size == profile.volumeCurve.size) { "Volume points must have unique indices." }
        require(profile.volumeCurve.all { it.index in 0..profile.volumeMaximum && it.attenuationDb.isFinite() && it.attenuationDb in -160.0..0.0 }) {
            "Volume points must fit this output's range and use attenuation −160 to 0 dB."
        }
        val sorted = profile.volumeCurve.sortedBy { it.index }
        require(sorted.zipWithNext().all { (a, b) -> b.attenuationDb >= a.attenuationDb }) { "Higher volume indices must not have lower measured levels." }
        return profile.copy(name = profile.name.trim(), gainSetting = profile.gainSetting.trim(),
            provenance = profile.provenance.trim(), volumeCurve = sorted)
    }

    fun estimate(profile: ListeningProfile, observation: ListeningObservation, confirmedGeneration: Long?): ListeningEstimate {
        fun unavailable(reason: String) = ListeningEstimate.Unavailable(reason)
        if (runCatching { validate(profile) }.isFailure) return unavailable("Calibration data is incomplete.")
        val route = observation.route
        if (route.route.kind != ProcessingRouteKind.ANDROID || route.route.key == null || route.route.key != profile.routeKey)
            return unavailable("This output has no matching calibration.")
        if (route.route.category !in setOf(OutputDeviceCategory.HEADPHONES, OutputDeviceCategory.USB, OutputDeviceCategory.BLUETOOTH))
            return unavailable("Headphone calibration requires a confirmed headphone output.")
        if (confirmedGeneration != route.generation) return unavailable("Confirm the headphones, gain and hardware volume.")
        if (!observation.playing) return unavailable("Playback is paused.")
        if (!observation.pathSupported) return unavailable("This playback path has unmeasured gain stages.")
        val levels = observation.postDsp ?: return unavailable("Waiting for measured output samples.")
        if (observation.nowNanos - levels.measuredAtNanos !in 0..2_000_000_000L || levels.windowFrames <= 0 || levels.invalidSamples != 0L)
            return unavailable("Output samples are unavailable.")
        if (observation.volumeMuted) return unavailable("Output is muted.")
        if (observation.volumeMaximum != profile.volumeMaximum) return unavailable("The Android volume range has changed or is unavailable.")
        val gain = observation.downstreamGain?.takeIf { it.isFinite() && it > 0.0 && it <= 16.0 }
            ?: return unavailable("Player gain is unavailable or muted.")
        if (!levels.leftPeak.isFinite() || !levels.rightPeak.isFinite() || minOf(levels.leftPeak, levels.rightPeak) < 0.0 ||
            !levels.leftRms.isFinite() || !levels.rightRms.isFinite() || minOf(levels.leftRms, levels.rightRms) < 0.0)
            return unavailable("Output samples are invalid.")
        val peak = maxOf(levels.leftPeak, levels.rightPeak, levels.leftRms, levels.rightRms)
        // android's clipping order relative to volume is not measured.
        if (peak > 1.0 || peak * gain > 1.0) return unavailable("Output may be clipped.")
        val volume = profile.volumeCurve.singleOrNull { it.index == observation.volumeIndex }
            ?: return unavailable("This volume step has no measured calibration.")
        val sensitivityPerVolt = when (profile.sensitivityUnit) {
            SensitivityUnit.DB_PER_VOLT -> profile.sensitivityDb
            SensitivityUnit.DB_PER_MILLIWATT -> profile.sensitivityDb + 10.0 * log10(1000.0 / profile.impedanceOhms)
        }
        // dac voltage is specified for a full-scale sine, whose digital rms is 1/sqrt(2).
        val commonDb = sensitivityPerVolt + 20.0 * log10(profile.fullScaleVrms * sqrt(2.0) * gain) +
            profile.gainDb + profile.hardwareAttenuationDb + volume.attenuationDb
        fun level(rms: Double): Double? = if (rms == 0.0) null else commonDb + 20.0 * log10(rms)
        return ListeningEstimate.Available(profile.id, profile.name, level(levels.leftRms), level(levels.rightRms),
            profile.uncertaintyDb, observation.timestampMillis)
    }

    fun parseVolumeCurve(text: String): List<VolumeCalibration> = text.split(',', ';', '\n').filter { it.isNotBlank() }.map { token ->
        val pair = token.trim().split('=')
        require(pair.size == 2) { "Use index=dB for each volume point." }
        VolumeCalibration(pair[0].trim().toIntOrNull() ?: error("Invalid volume index."),
            pair[1].trim().toDoubleOrNull() ?: error("Invalid volume attenuation."))
    }
}
