package com.aurora.music.data.listening

import com.google.gson.Gson

object ListeningLevelCodec {
    const val MAX_PROFILES = 40
    const val MAX_HISTORY = 2880
    const val MAX_BYTES = 2_000_000
    private val gson = Gson()

    private data class Document(val version: Int? = 1, val profiles: List<Profile?>? = null,
        val historyEnabled: Boolean? = false, val history: List<History?>? = null)
    private data class Profile(val id: String? = null, val name: String? = null, val routeKey: String? = null,
        val routeLabel: String? = null, val sensitivityDb: Double? = null, val sensitivityUnit: String? = null,
        val impedanceOhms: Double? = null, val fullScaleVrms: Double? = null, val gainSetting: String? = null,
        val gainDb: Double? = null, val hardwareAttenuationDb: Double? = null, val volumeCurve: List<Volume?>? = null,
        val provenance: String? = null, val uncertaintyDb: Double? = null, val volumeMaximum: Int? = null)
    private data class Volume(val index: Int? = null, val attenuationDb: Double? = null)
    private data class History(val timestampMillis: Long? = null, val profileName: String? = null,
        val leftDb: Double? = null, val rightDb: Double? = null, val uncertaintyDb: Double? = null)

    fun decode(json: String, portable: Boolean = false): ListeningState {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Listening data is too large." }
        val doc = requireNotNull(gson.fromJson(json, Document::class.java)) { "Invalid listening data." }
        require(doc.version == 1) { "Unsupported listening data version." }
        val profiles = requireNotNull(doc.profiles) { "Missing calibrations." }
        require(profiles.size <= MAX_PROFILES) { "Too many calibrations." }
        val decoded = profiles.map { nullable ->
            val p = requireNotNull(nullable) { "Invalid calibration." }
            ListeningMath.validate(ListeningProfile(requireNotNull(p.id), requireNotNull(p.name), if (portable) null else p.routeKey,
                if (portable) "" else p.routeLabel.orEmpty(), requireNotNull(p.sensitivityDb),
                SensitivityUnit.valueOf(requireNotNull(p.sensitivityUnit)), requireNotNull(p.impedanceOhms),
                requireNotNull(p.fullScaleVrms), requireNotNull(p.gainSetting), requireNotNull(p.gainDb),
                requireNotNull(p.hardwareAttenuationDb), requireNotNull(p.volumeCurve).map {
                    VolumeCalibration(requireNotNull(it?.index), requireNotNull(it?.attenuationDb))
                }, requireNotNull(p.provenance), requireNotNull(p.uncertaintyDb), requireNotNull(p.volumeMaximum)))
        }
        require(decoded.map { it.id }.distinct().size == decoded.size) { "Duplicate calibration IDs." }
        val history = if (portable) emptyList() else doc.history.orEmpty().also {
            require(it.size <= MAX_HISTORY) { "Too many history entries." }
        }.map { nullable ->
            val h = requireNotNull(nullable) { "Invalid history entry." }
            val timestamp = requireNotNull(h.timestampMillis).also { require(it > 0) }
            val name = requireNotNull(h.profileName).also { require(it.isNotBlank() && it.length <= 80) }
            listOfNotNull(h.leftDb, h.rightDb).forEach { require(it.isFinite() && it in -1000.0..1000.0) }
            val uncertainty = requireNotNull(h.uncertaintyDb).also { require(it.isFinite() && it in 1.0..40.0) }
            ListeningHistoryEntry(timestamp, name, h.leftDb, h.rightDb, uncertainty)
        }.sortedByDescending { it.timestampMillis }
        return ListeningState(decoded, !portable && doc.historyEnabled == true, history)
    }

    fun encode(state: ListeningState, portable: Boolean = false): String {
        require(state.profiles.size <= MAX_PROFILES && state.history.size <= MAX_HISTORY)
        val doc = Document(profiles = state.profiles.map { original ->
            val p = ListeningMath.validate(original)
            Profile(p.id, p.name, if (portable) null else p.routeKey, if (portable) null else p.routeLabel,
                p.sensitivityDb, p.sensitivityUnit.name, p.impedanceOhms, p.fullScaleVrms, p.gainSetting, p.gainDb,
                p.hardwareAttenuationDb, p.volumeCurve.map { Volume(it.index, it.attenuationDb) }, p.provenance, p.uncertaintyDb, p.volumeMaximum)
        }, historyEnabled = if (portable) false else state.historyEnabled,
            history = if (portable) emptyList() else state.history.map { History(it.timestampMillis, it.profileName, it.leftDb, it.rightDb, it.uncertaintyDb) })
        return gson.toJson(doc).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Listening data is too large." } }
    }
}
