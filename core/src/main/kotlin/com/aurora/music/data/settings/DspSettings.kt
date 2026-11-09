package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.routes.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface DspSettings {
    val audioPrefs: Flow<AudioPrefs>
    val processingSettings: Flow<ProcessingSettings>
    val processingSnapshot: Flow<ProcessingSnapshot>
    val processingRack: Flow<ProcessingRack>
    suspend fun setProcessingRack(rack: ProcessingRack): Result<Unit>
    suspend fun migrateProcessingRack(): Result<Unit>
    suspend fun resetProcessingRack(): Result<Unit>
    val outputRatePolicy: Flow<com.aurora.music.playback.engine.OutputRatePolicy>
    suspend fun setOutputRatePolicy(value: com.aurora.music.playback.engine.OutputRatePolicy): Result<Unit>
    suspend fun updateOutputRatePolicy(change: (com.aurora.music.playback.engine.OutputRatePolicy) -> com.aurora.music.playback.engine.OutputRatePolicy): Result<Unit>
    val processingRackAssets: Flow<List<ImpulseLibraryEntry>>
    suspend fun applyEqBindingIfEnabled(binding: EqBinding, expectedRoute: RouteObservation? = null): Boolean
    val reverbPrefs: Flow<ReverbPrefs>
    val engineImpulseAssets: Flow<List<ImpulseLibraryEntry>>
    val eqBindings: Flow<List<EqBinding>>
    val autoEqAutoSwitch: Flow<Boolean>
    val activeEqProfile: Flow<String>
    suspend fun setReverb(v: ReverbPrefs): Preferences
    suspend fun setAutoEqAutoSwitch(v: Boolean): Preferences
    suspend fun setActiveEqProfile(name: String): Preferences
    suspend fun applyLegacyEqProfile(name: String, correction: ParsedEq): Result<Unit>
    suspend fun upsertEqBinding(binding: EqBinding): Preferences
    suspend fun bindEqToCurrentOutput(profile: String, preampDb: Float, bands: List<ParamBand>): Result<Unit>
    suspend fun removeEqBinding(deviceKey: String): Preferences
    suspend fun setEqEnabled(v: Boolean): Preferences
    suspend fun setEqPreset(v: Int): Preferences
    suspend fun setEqBands(v: List<Int>): Preferences
    suspend fun setBassBoost(v: Int): Preferences
    suspend fun setVirtualizer(v: Int): Preferences
    suspend fun setLoudness(v: Int): Preferences
    suspend fun setReplayGain(v: Int): Preferences
    suspend fun setDspMode(v: Int): Preferences
    suspend fun setDspGraphicBands(v: List<Float>): Preferences
    suspend fun setDspGraphic(layout: Int, bands: List<Float>): Preferences
    suspend fun setDspParametric(v: List<ParamBand>): Preferences
    suspend fun setDspPreamp(v: Float): Preferences
    suspend fun setDspBalance(v: Float): Preferences
    suspend fun setDspWidth(v: Float): Preferences
    suspend fun setDspCrossfeed(v: Float): Preferences
    suspend fun setDspLimiterEnabled(v: Boolean): Preferences
    suspend fun setDspCeiling(v: Float): Preferences
    suspend fun setDspCompEnabled(v: Boolean): Preferences
    suspend fun setDspCompThresh(v: Float): Preferences
    suspend fun setDspCompRatio(v: Float): Preferences
    suspend fun setDspConvEnabled(v: Boolean): Preferences
    suspend fun setDspConvMakeup(v: Float): Preferences
    suspend fun setDspConvIr(path: String, name: String): Preferences
    suspend fun setDspGraphicLayout(v: Int): Preferences
    suspend fun setDspSaturation(v: Float): Preferences
    suspend fun setDspDelayLeft(v: Float): Preferences
    suspend fun setDspDelayRight(v: Float): Preferences
    suspend fun setDspTrimLeft(v: Float): Preferences
    suspend fun setDspTrimRight(v: Float): Preferences
}

internal class DspSettingsStore(private val dataStore: DataStore<Preferences>, private val editor: ProcessingEditor) : DspSettings {
    private val gson = Gson()

    override val audioPrefs: Flow<AudioPrefs> = editor.audioPrefs

    override val processingSettings: Flow<ProcessingSettings> = dataStore.data.map { p ->
        val audio = readAudioPrefs(p)
        val playback = readPlaybackPrefs(p)
        ProcessingSettings(audio, playback, editor.readProcessingRack(p, audio, playback.monoAudio))
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val processingSnapshot: Flow<ProcessingSnapshot> = dataStore.data.map { p ->
        val audio = readAudioPrefs(p)
        val playback = readPlaybackPrefs(p)
        ProcessingSnapshot(audio, ProcessingPlaybackPrefs.from(playback),
            p[Keys.AUTOEQ_PROFILE].orEmpty(), editor.readProcessingRack(p, audio, playback.monoAudio))
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val processingRack: Flow<ProcessingRack> = processingSettings.map { it.rack }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun setProcessingRack(rack: ProcessingRack): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = ProcessingRackCodec.validate(rack)
            val encoded = ProcessingRackCodec.encode(validated)
            dataStore.edit { p ->
                p[Keys.ACTIVE_RACK_IMPULSES] = ImpulseLibraryCodec.encodeLibrary(editor.resolveRackAssets(p, validated))
                p[Keys.PROCESSING_RACK] = encoded
                if (validated.enabled) p[Keys.DSP_MODE] = DspMode.CUSTOM
                editor.holdManualRoute(p)
            }
            Unit
        }
    }

    /** Rebuild a disabled graph from current effective legacy settings in one transaction. */
    override suspend fun migrateProcessingRack(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            dataStore.edit { p ->
                p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(
                    ProcessingRack.legacy(readAudioPrefs(p), readPlaybackPrefs(p).monoAudio))
                editor.holdManualRoute(p)
            }
            Unit
        }
    }

    override suspend fun resetProcessingRack(): Result<Unit> = migrateProcessingRack()

    override val outputRatePolicy = dataStore.data
        .map { it[Keys.OUTPUT_RATE_POLICY] }.distinctUntilChanged()
        .map { OutputRatePolicyCodec.decode(it).getOrThrow() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun setOutputRatePolicy(value: com.aurora.music.playback.engine.OutputRatePolicy): Result<Unit> = impulseResult {
        val encoded = OutputRatePolicyCodec.encode(value)
        editor.editManualProcessing { it[Keys.OUTPUT_RATE_POLICY] = encoded }
    }

    override suspend fun updateOutputRatePolicy(change: (com.aurora.music.playback.engine.OutputRatePolicy) -> com.aurora.music.playback.engine.OutputRatePolicy): Result<Unit> = impulseResult {
        editor.editManualProcessing { preferences ->
            val current = OutputRatePolicyCodec.decode(preferences[Keys.OUTPUT_RATE_POLICY]).getOrThrow()
            preferences[Keys.OUTPUT_RATE_POLICY] = OutputRatePolicyCodec.encode(change(current))
        }
    }

    private data class RackAssetSources(val rack: String?, val active: String?, val subchains: String?, val library: String?)

    // legacy racks carry no impulse ids so only the raw json matters
    override val processingRackAssets: Flow<List<ImpulseLibraryEntry>> = dataStore.data
        .map { p -> RackAssetSources(p[Keys.PROCESSING_RACK], p[Keys.ACTIVE_RACK_IMPULSES], p[Keys.RACK_SUBCHAINS], p[Keys.IMPULSE_LIBRARY]) }
        .distinctUntilChanged()
        .map { s -> editor.resolveRackAssets(s.rack?.let { editor.decodeRack(it) }?.nodes.orEmpty(), s.active, s.subchains, s.library, strict = false) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override suspend fun applyEqBindingIfEnabled(binding: EqBinding, expectedRoute: RouteObservation?): Boolean {
        var applied = false
        dataStore.edit { p ->
            if (editor.orderedRulesBlockFallback(p)) return@edit
            if (p[Keys.AUTOEQ_SWITCH] != true || binding !in editor.parseBindings(p[Keys.EQ_BINDINGS])) return@edit
            val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
            val observed = editor.processingRoutes.current
            val key = observed.route.key
            if (expectedRoute != null && (expectedRoute != observed || key == null || !observed.route.switchesDeviceEq)) return@edit
            if (key != null && (key in rules.manual || rules.enabled && rules.bindings.any { it.routeKey == key })) return@edit
            p[Keys.DSP_PARAMETRIC] = ParamBandCodec.encodePreference(binding.bands)
            p[Keys.DSP_PREAMP] = binding.preampDb
            p[Keys.DSP_MODE] = DspMode.CUSTOM
            p[Keys.AUTOEQ_PROFILE] = binding.profileName
            applied = true
        }
        return applied
    }

    override val reverbPrefs: Flow<ReverbPrefs> = dataStore.data.map { p ->
        val d = ReverbPrefs()
        val c = ConcertPrefs()
        ReverbPrefs(
            enabled = p[Keys.REVERB_ENABLED] ?: d.enabled, preset = p[Keys.REVERB_PRESET] ?: d.preset,
            mix = p[Keys.REVERB_MIX] ?: d.mix, decaySec = p[Keys.REVERB_DECAY] ?: d.decaySec,
            size = p[Keys.REVERB_SIZE] ?: d.size, predelayMs = p[Keys.REVERB_PREDELAY] ?: d.predelayMs,
            damping = p[Keys.REVERB_DAMPING] ?: d.damping, lowCutHz = p[Keys.REVERB_LOW_CUT] ?: d.lowCutHz,
            highCutHz = p[Keys.REVERB_HIGH_CUT] ?: d.highCutHz, width = p[Keys.REVERB_WIDTH] ?: d.width,
            early = p[Keys.REVERB_EARLY] ?: d.early, modulation = p[Keys.REVERB_MODULATION] ?: d.modulation,
            concert = ConcertPrefs(
                enabled = p[Keys.CONCERT_ENABLED] ?: c.enabled, venue = p[Keys.CONCERT_VENUE] ?: c.venue, sound = p[Keys.CONCERT_SOUND] ?: c.sound,
                amount = p[Keys.CONCERT_AMOUNT] ?: c.amount, size = p[Keys.CONCERT_SIZE] ?: c.size,
                openness = p[Keys.CONCERT_OPENNESS] ?: c.openness,
                stageX = p[Keys.CONCERT_STAGE_X] ?: c.stageX, stageY = p[Keys.CONCERT_STAGE_Y] ?: c.stageY,
                listenerX = p[Keys.CONCERT_LISTENER_X] ?: c.listenerX, listenerY = p[Keys.CONCERT_LISTENER_Y] ?: c.listenerY,
                showDb = p[Keys.CONCERT_SHOW_DB] ?: c.showDb, crowd = p[Keys.CONCERT_CROWD] ?: c.crowd, earplugs = p[Keys.CONCERT_EARPLUGS] ?: c.earplugs,
                binaural = p[Keys.CONCERT_BINAURAL] ?: c.binaural, occupancy = p[Keys.CONCERT_OCCUPANCY] ?: c.occupancy,
                temperatureC = p[Keys.CONCERT_TEMPERATURE] ?: c.temperatureC, humidity = p[Keys.CONCERT_HUMIDITY] ?: c.humidity,
                stageDb = p[Keys.CONCERT_STAGE_DB] ?: c.stageDb, subLayout = p[Keys.CONCERT_SUBS] ?: c.subLayout,
                roomImpulse = p[Keys.CONCERT_ROOM_IMPULSE],
            ),
        ).clamped()
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // the rack's impulses plus the library impulse a measured concert room plays through
    override val engineImpulseAssets: Flow<List<ImpulseLibraryEntry>> = combine(processingRackAssets, editor.impulseLibrary,
        reverbPrefs.map { r -> r.concertPrefs.takeIf { r.enabled && it.enabled }?.roomImpulse }.distinctUntilChanged()) { rack, library, room ->
        rack + listOfNotNull(library.firstOrNull { it.id == room }?.takeIf { rack.none { r -> r.id == room } })
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // distinctUntilChanged so a settings write doesnt re-fire and wipe a just-applied correction
    override val eqBindings: Flow<List<EqBinding>> = dataStore.data.map { it[Keys.EQ_BINDINGS] }.distinctUntilChanged()
        .map { editor.parseBindings(it) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override val autoEqAutoSwitch: Flow<Boolean> = dataStore.data.map { it[Keys.AUTOEQ_SWITCH] ?: false }.distinctUntilChanged()
    override val activeEqProfile: Flow<String> = dataStore.data.map { it[Keys.AUTOEQ_PROFILE] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun setReverb(v: ReverbPrefs) = dataStore.edit { p ->
        val r = v.clamped()
        p[Keys.REVERB_ENABLED] = r.enabled; p[Keys.REVERB_PRESET] = r.preset; p[Keys.REVERB_MIX] = r.mix
        p[Keys.REVERB_DECAY] = r.decaySec; p[Keys.REVERB_SIZE] = r.size; p[Keys.REVERB_PREDELAY] = r.predelayMs
        p[Keys.REVERB_DAMPING] = r.damping; p[Keys.REVERB_LOW_CUT] = r.lowCutHz; p[Keys.REVERB_HIGH_CUT] = r.highCutHz
        p[Keys.REVERB_WIDTH] = r.width; p[Keys.REVERB_EARLY] = r.early; p[Keys.REVERB_MODULATION] = r.modulation
        val c = r.concertPrefs
        p[Keys.CONCERT_ENABLED] = c.enabled; p[Keys.CONCERT_VENUE] = c.venue; p[Keys.CONCERT_SOUND] = c.sound; p[Keys.CONCERT_AMOUNT] = c.amount
        p[Keys.CONCERT_SIZE] = c.size; p[Keys.CONCERT_OPENNESS] = c.openness
        p[Keys.CONCERT_STAGE_X] = c.stageX; p[Keys.CONCERT_STAGE_Y] = c.stageY
        p[Keys.CONCERT_LISTENER_X] = c.listenerX; p[Keys.CONCERT_LISTENER_Y] = c.listenerY
        p[Keys.CONCERT_SHOW_DB] = c.showDb; p[Keys.CONCERT_CROWD] = c.crowd; p[Keys.CONCERT_EARPLUGS] = c.earplugs
        p[Keys.CONCERT_BINAURAL] = c.binaural; p[Keys.CONCERT_OCCUPANCY] = c.occupancy
        p[Keys.CONCERT_TEMPERATURE] = c.temperatureC; p[Keys.CONCERT_HUMIDITY] = c.humidity
        p[Keys.CONCERT_STAGE_DB] = c.stageDb; p[Keys.CONCERT_SUBS] = c.subLayout
        c.roomImpulse?.let { p[Keys.CONCERT_ROOM_IMPULSE] = it } ?: p.remove(Keys.CONCERT_ROOM_IMPULSE)
    }

    override suspend fun setAutoEqAutoSwitch(v: Boolean) = dataStore.edit { it[Keys.AUTOEQ_SWITCH] = v }
    override suspend fun setActiveEqProfile(name: String) = editor.editManualProcessing { it[Keys.AUTOEQ_PROFILE] = name }

    override suspend fun applyLegacyEqProfile(name: String, correction: ParsedEq): Result<Unit> = impulseResult {
        require(correction.bands.size <= com.aurora.music.playback.DspCoeffBuilder.MAX_PARAMETRIC) {
            "The standard equalizer supports up to ${com.aurora.music.playback.DspCoeffBuilder.MAX_PARAMETRIC} parametric bands."
        }
        require(correction.preampDb.isFinite() && correction.preampDb in -60f..24f) { "Invalid equalizer preamp." }
        val bands = ParamBandCodec.encodePreference(correction.bands)
        editor.editManualProcessing { p ->
            p[Keys.DSP_PARAMETRIC] = bands
            p[Keys.DSP_PREAMP] = correction.preampDb
            p[Keys.DSP_MODE] = DspMode.CUSTOM
            p[Keys.AUTOEQ_PROFILE] = name
        }
    }

    override suspend fun upsertEqBinding(binding: EqBinding) = dataStore.edit { p ->
        val cur = editor.parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == binding.deviceKey }
        p[Keys.EQ_BINDINGS] = gson.toJson(cur + binding)
    }

    override suspend fun bindEqToCurrentOutput(profile: String, preampDb: Float, bands: List<ParamBand>): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val expected = editor.processingRoutes.current
            val key = expected.route.key ?: error("Output identity is unavailable.")
            require(expected.route.bindsDeviceEq) { "This output bypasses local processing." }
            dataStore.edit { p ->
                require(editor.processingRoutes.current == expected) { "The output changed. Try again." }
                val binding = EqBinding(key, expected.route.label, profile, preampDb, bands)
                p[Keys.EQ_BINDINGS] = gson.toJson(editor.parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == key } + binding)
                p[Keys.AUTOEQ_SWITCH] = true
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(rules.copy(manual = rules.manual - key))
            }
            Unit
        }
    }

    override suspend fun removeEqBinding(deviceKey: String) = dataStore.edit { p ->
        p[Keys.EQ_BINDINGS] = gson.toJson(editor.parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == deviceKey })
    }

    override suspend fun setEqEnabled(v: Boolean) = editor.editManualProcessing { it[Keys.EQ_ENABLED] = v }
    override suspend fun setEqPreset(v: Int) = editor.editManualProcessing { it[Keys.EQ_PRESET] = v }
    override suspend fun setEqBands(v: List<Int>) = editor.editManualProcessing { it[Keys.EQ_BANDS] = v.joinToString(",") }
    override suspend fun setBassBoost(v: Int) = editor.editManualProcessing { it[Keys.BASS_BOOST] = v }
    override suspend fun setVirtualizer(v: Int) = editor.editManualProcessing { it[Keys.VIRTUALIZER] = v }
    override suspend fun setLoudness(v: Int) = editor.editManualProcessing { it[Keys.LOUDNESS] = v }
    override suspend fun setReplayGain(v: Int) = editor.editManualProcessing { it[Keys.REPLAY_GAIN] = v }

    override suspend fun setDspMode(v: Int) = editor.editManualProcessing { p ->
        p[Keys.DSP_MODE] = v
        if (v != DspMode.CUSTOM) {
            val rack = p[Keys.PROCESSING_RACK]?.let { ProcessingRackCodec.decode(it).getOrNull() }
            if (rack?.enabled == true) p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(rack.copy(enabled = false))
        }
    }
    override suspend fun setDspGraphicBands(v: List<Float>) = editor.editManualProcessing { it[Keys.DSP_GRAPHIC] = v.joinToString(",") }
    override suspend fun setDspGraphic(layout: Int, bands: List<Float>) = editor.editManualProcessing {
        it[Keys.DSP_GRAPHIC_LAYOUT] = layout
        it[Keys.DSP_GRAPHIC] = bands.joinToString(",")
    }
    override suspend fun setDspParametric(v: List<ParamBand>) = editor.editManualProcessing {
        it[Keys.DSP_PARAMETRIC] = ParamBandCodec.encodePreference(v)
    }
    override suspend fun setDspPreamp(v: Float) = editor.editManualProcessing { it[Keys.DSP_PREAMP] = v }
    override suspend fun setDspBalance(v: Float) = editor.editManualProcessing { it[Keys.DSP_BALANCE] = v }
    override suspend fun setDspWidth(v: Float) = editor.editManualProcessing { it[Keys.DSP_WIDTH] = v }
    override suspend fun setDspCrossfeed(v: Float) = editor.editManualProcessing { it[Keys.DSP_CROSSFEED] = v }
    override suspend fun setDspLimiterEnabled(v: Boolean) = editor.editManualProcessing { it[Keys.DSP_LIMITER] = v }
    override suspend fun setDspCeiling(v: Float) = editor.editManualProcessing { it[Keys.DSP_CEILING] = v }
    override suspend fun setDspCompEnabled(v: Boolean) = editor.editManualProcessing { it[Keys.DSP_COMP] = v }
    override suspend fun setDspCompThresh(v: Float) = editor.editManualProcessing { it[Keys.DSP_COMP_THRESH] = v }
    override suspend fun setDspCompRatio(v: Float) = editor.editManualProcessing { it[Keys.DSP_COMP_RATIO] = v }
    override suspend fun setDspConvEnabled(v: Boolean) = editor.editManualProcessing { it[Keys.DSP_CONV] = v }
    override suspend fun setDspConvMakeup(v: Float) = editor.editManualProcessing { it[Keys.DSP_CONV_MAKEUP] = v }
    override suspend fun setDspConvIr(path: String, name: String) = editor.editManualProcessing {
        it[Keys.DSP_CONV_PATH] = path; it[Keys.DSP_CONV_NAME] = name
    }
    override suspend fun setDspGraphicLayout(v: Int) = editor.editManualProcessing { it[Keys.DSP_GRAPHIC_LAYOUT] = v }
    override suspend fun setDspSaturation(v: Float) = editor.editManualProcessing { it[Keys.DSP_SATURATION] = v }
    override suspend fun setDspDelayLeft(v: Float) = editor.editManualProcessing { it[Keys.DSP_DELAY_L] = v }
    override suspend fun setDspDelayRight(v: Float) = editor.editManualProcessing { it[Keys.DSP_DELAY_R] = v }
    override suspend fun setDspTrimLeft(v: Float) = editor.editManualProcessing { it[Keys.DSP_TRIM_L] = v }
    override suspend fun setDspTrimRight(v: Float) = editor.editManualProcessing { it[Keys.DSP_TRIM_R] = v }
}
