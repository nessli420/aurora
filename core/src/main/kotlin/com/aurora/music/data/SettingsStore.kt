package com.aurora.music.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aurora.music.data.SettingsKeys as Keys
import com.aurora.music.data.settings.AccountSettings
import com.aurora.music.data.settings.AccountSettingsStore
import com.aurora.music.data.settings.AppearanceSettings
import com.aurora.music.data.settings.AppearanceSettingsStore
import com.aurora.music.data.settings.CollectionSettings
import com.aurora.music.data.settings.CollectionSettingsStore
import com.aurora.music.data.settings.DspSettings
import com.aurora.music.data.settings.DspSettingsStore
import com.aurora.music.data.settings.GeneralSettings
import com.aurora.music.data.settings.GeneralSettingsStore
import com.aurora.music.data.settings.IntegrationSettings
import com.aurora.music.data.settings.IntegrationSettingsStore
import com.aurora.music.data.settings.LibrarySettings
import com.aurora.music.data.settings.LibrarySettingsStore
import com.aurora.music.data.settings.PlaybackSettings
import com.aurora.music.data.settings.PlaybackSettingsStore
import com.aurora.music.data.settings.PresetSettings
import com.aurora.music.data.settings.PresetSettingsStore
import com.aurora.music.data.settings.ProcessingEditor
import com.aurora.music.data.settings.RackLibrarySettings
import com.aurora.music.data.settings.RackLibrarySettingsStore
import com.aurora.music.data.tuning.TuningProjectCodec
import com.aurora.music.data.tuning.TuningTargetCatalog
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.routes.*
import com.aurora.music.data.rules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.first

class SettingsStore private constructor(
    private val dataStore: DataStore<Preferences>, processing: ProcessingEditor, filesDir: File, cacheDir: File,
) : AccountSettings by AccountSettingsStore(dataStore),
    IntegrationSettings by IntegrationSettingsStore(dataStore),
    AppearanceSettings by AppearanceSettingsStore(dataStore),
    PlaybackSettings by PlaybackSettingsStore(dataStore, processing::editManualProcessing),
    LibrarySettings by LibrarySettingsStore(dataStore),
    CollectionSettings by CollectionSettingsStore(dataStore),
    GeneralSettings by GeneralSettingsStore(dataStore),
    DspSettings by DspSettingsStore(dataStore, processing),
    PresetSettings by PresetSettingsStore(dataStore, processing, filesDir, cacheDir),
    RackLibrarySettings by RackLibrarySettingsStore(dataStore, processing, filesDir) {
    constructor(dataStore: DataStore<Preferences>, filesDir: File, cacheDir: File) :
        this(dataStore, ProcessingEditor(dataStore, filesDir), filesDir, cacheDir)

    // typed so json round-trips losslessly
    suspend fun exportPrefs(includeCredentials: Boolean = true): PrefsBackup {
        val p = dataStore.data.first().let { if (includeCredentials) it else it.withoutCredentials() }
        val strings = HashMap<String, String>(); val ints = HashMap<String, Int>(); val longs = HashMap<String, Long>()
        val booleans = HashMap<String, Boolean>(); val floats = HashMap<String, Float>(); val sets = HashMap<String, List<String>>()
        for ((k, v) in p.asMap()) when (v) {
            is String -> strings[k.name] = v
            is Int -> ints[k.name] = v
            is Long -> longs[k.name] = v
            is Boolean -> booleans[k.name] = v
            is Float -> floats[k.name] = v
            is Set<*> -> sets[k.name] = v.filterIsInstance<String>()
        }
        return PrefsBackup(strings, ints, longs, booleans, floats, sets)
    }

    suspend fun importPrefs(b: PrefsBackup) = dataStore.edit { p ->
        b.strings.forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
        b.ints.forEach { (k, v) -> p[intPreferencesKey(k)] = v }
        b.longs.forEach { (k, v) -> p[longPreferencesKey(k)] = v }
        b.booleans.forEach { (k, v) -> p[booleanPreferencesKey(k)] = v }
        b.floats.forEach { (k, v) -> p[floatPreferencesKey(k)] = v }
        b.stringSets.forEach { (k, v) -> p[stringSetPreferencesKey(k)] = v.toSet() }
    }

    /** Replace one validated backup atomically after its caller has restored/remapped all assets. */
    suspend fun restoreBackupPrefs(b: PrefsBackup): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val names = listOf(b.strings.keys, b.ints.keys, b.longs.keys, b.booleans.keys, b.floats.keys, b.stringSets.keys)
                .flatMap { it }
            require(names.distinct().size == names.size) { "Backup contains preference keys with conflicting types." }
            require(b.floats.values.all { it.isFinite() }) { "Backup contains invalid numeric preferences." }
            val replacement = mutablePreferencesOf()
            b.strings.forEach { (k, v) -> replacement[stringPreferencesKey(k)] = v }
            b.ints.forEach { (k, v) -> replacement[intPreferencesKey(k)] = v }
            b.longs.forEach { (k, v) -> replacement[longPreferencesKey(k)] = v }
            b.booleans.forEach { (k, v) -> replacement[booleanPreferencesKey(k)] = v }
            b.floats.forEach { (k, v) -> replacement[floatPreferencesKey(k)] = v }
            b.stringSets.forEach { (k, v) -> replacement[stringSetPreferencesKey(k)] = v.toSet() }
            validateBackupAudioText(replacement)
            LocalProfileCodec.decode(replacement[Keys.LOCAL_PROFILE])
            ArtistSeparatorsCodec.decode(replacement[Keys.ARTIST_SEPARATORS])
            val audio = ProcessingPresetCodec.validateAudio(readAudioPrefs(replacement))
            val playback = readPlaybackPrefs(replacement)
            ProcessingPresetCodec.validatePlayback(ProcessingPlaybackPrefs.from(playback))
            val library = ProcessingPresetCodec.decode(replacement[Keys.PROCESSING_PRESETS])
            require(library.error == null) { library.error.orEmpty() }
            val rack = replacement[Keys.PROCESSING_RACK]?.let { ProcessingRackCodec.decode(it).getOrThrow() }
                ?: ProcessingRack.legacy(audio, playback.monoAudio)
            TuningProjectCodec.decodeLibrary(replacement[Keys.TUNING_PROJECTS]).getOrThrow()
            TuningTargetCatalog.decodeLibrary(replacement[Keys.TUNING_TARGETS]).getOrThrow()
            ImpulseLibraryCodec.decodeLibrary(replacement[Keys.IMPULSE_LIBRARY]).getOrThrow()
            ImpulseLibraryCodec.decodeLibrary(replacement[Keys.ACTIVE_RACK_IMPULSES]).getOrThrow()
            ProcessingRouteCodec.decode(replacement[Keys.PROCESSING_ROUTES]).getOrThrow()
            PresetRuleCodec.decode(replacement[Keys.PRESET_RULES]).getOrThrow()
            PresetRuleSessionCodec.decode(replacement[Keys.PRESET_RULE_SESSION]).getOrThrow()
            OutputRatePolicyCodec.decode(replacement[Keys.OUTPUT_RATE_POLICY]).getOrThrow()
            RackSubchainCodec.decode(replacement[Keys.RACK_SUBCHAINS]).getOrThrow()
            val extensionKey = stringPreferencesKey(com.aurora.music.extensions.ExtensionCodec.PREFERENCE_KEY)
            replacement[extensionKey]?.let { json ->
                val grants = com.aurora.music.extensions.ExtensionCodec.decode(json)
                replacement[extensionKey] = com.aurora.music.extensions.ExtensionCodec.encode(grants.map { it.copy(enabled = false) })
            }
            if (rack.enabled) replacement[Keys.DSP_MODE] = DspMode.CUSTOM
            // Asset ownership, hashes and remapping belong to the bundle reader before this call.
            // Keeping this transaction about preferences also permits restoring an exact snapshot
            // whose disabled historical entries already reference unavailable source files.
            dataStore.edit { p ->
                val restored = replacement.withAccountsFrom(p.toPreferences())
                p.clear()
                restored.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    p[key as Preferences.Key<Any>] = value
                }
            }
            Unit
        }
    }

    private fun validateBackupAudioText(p: Preferences) {
        p[Keys.EQ_BANDS]?.takeIf { it.isNotBlank() }?.split(",")?.forEach {
            require(it.toIntOrNull() != null) { "Backup contains an invalid system EQ band." }
        }
        p[Keys.DSP_GRAPHIC]?.takeIf { it.isNotBlank() }?.split(",")?.forEach {
            require(it.toFloatOrNull()?.isFinite() == true) { "Backup contains an invalid graphic EQ band." }
        }
        p[Keys.DSP_PARAMETRIC]?.takeIf { it.isNotBlank() }?.split(";")?.forEach { entry ->
            require(ParamBandCodec.decodePreference(entry).size == 1) { "Backup contains an invalid parametric EQ band." }
        }
    }
}
