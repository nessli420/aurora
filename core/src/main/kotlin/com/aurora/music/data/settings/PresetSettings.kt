package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.routes.*
import com.aurora.music.data.rules.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

interface PresetSettings {
    val processingRoutes: ProcessingRouteMonitor
    val presetRuleContext: PresetRuleContextMonitor
    val processingPresetLibrary: Flow<ProcessingPresetLibrary>
    suspend fun saveProcessingPreset(name: String): Result<ProcessingPreset>
    val processingRouteRules: Flow<ProcessingRouteRules>
    val presetRules: Flow<PresetRuleSet>
    suspend fun savePresetRule(rule: PresetRule): Result<Unit>
    suspend fun removePresetRule(id: String): Result<Unit>
    suspend fun movePresetRule(id: String, delta: Int): Result<Unit>
    suspend fun setPresetRulesEnabled(enabled: Boolean): Result<Unit>
    suspend fun setPresetRuleManualHold(hold: Boolean): Result<Unit>
    suspend fun transitionPresetRule(expected: PresetRuleInput): Result<PresetRuleTransition?>
    suspend fun setRouteRulesEnabled(enabled: Boolean): Result<Unit>
    suspend fun holdCurrentRoute(hold: Boolean): Result<Unit>
    suspend fun chooseRouteHeadphones(key: String, headphones: String): Result<Unit>
    suspend fun bindCurrentRoute(presetId: String, headphones: String, expected: RouteObservation = processingRoutes.current): Result<Unit>
    suspend fun removeRouteBinding(binding: RoutePresetBinding): Result<Unit>
    suspend fun applyProcessingPreset(id: String): Result<ProcessingPresetApplyResult>
    suspend fun applyRoutePreset(expected: RouteObservation, presetId: String): Result<ProcessingPresetApplyResult?>
    suspend fun duplicateProcessingPreset(id: String, name: String): Result<ProcessingPreset>
    suspend fun renameProcessingPreset(id: String, name: String): Result<Unit>
    suspend fun deleteProcessingPreset(id: String): Result<Unit>
    suspend fun exportProcessingPreset(id: String, output: OutputStream): Result<Unit>
    suspend fun importProcessingPreset(input: InputStream): Result<ProcessingPreset>
}

internal class PresetSettingsStore(private val dataStore: DataStore<Preferences>, private val editor: ProcessingEditor, private val filesDir: File, private val cacheDir: File) : PresetSettings {
    override val processingRoutes: ProcessingRouteMonitor = editor.processingRoutes
    override val presetRuleContext: PresetRuleContextMonitor = editor.presetRuleContext

    private val presetLibraryMemo = RawMemo(ProcessingPresetCodec::decode)

    override val processingPresetLibrary: Flow<ProcessingPresetLibrary> = dataStore.data
        .map { it[Keys.PROCESSING_PRESETS] }
        .distinctUntilChanged()
        .map { presetLibraryMemo(it) }
        .flowOn(Dispatchers.Default)

    private fun presetList(p: Preferences): List<ProcessingPreset> {
        val library = ProcessingPresetCodec.decode(p[Keys.PROCESSING_PRESETS])
        require(library.error == null) { library.error.orEmpty() }
        return library.presets
    }

    override suspend fun saveProcessingPreset(name: String): Result<ProcessingPreset> = withContext(Dispatchers.IO) {
        runCatching {
            val title = ProcessingPresetCodec.name(name)
            var saved: ProcessingPreset? = null
            dataStore.edit { p ->
                val previous = presetList(p)
                require(previous.size < ProcessingPresetCodec.MAX_PRESETS) { "You can save up to 100 presets." }
                var audio = readAudioPrefs(p)
                val playback = readPlaybackPrefs(p)
                val rack = editor.readProcessingRack(p, audio, playback.monoAudio)
                val needsImpulse = if (rack.enabled) rack.requiresImpulseResponse() else audio.dspConvEnabled
                var hash = ""
                if (audio.dspConvIrPath.isNotBlank()) {
                    val source = File(audio.dspConvIrPath)
                    if (source.isFile && source.canRead()) {
                        require(source.length() in 1..MAX_PRESET_IR_BYTES) { "Impulse response must be between 1 byte and 64 MB." }
                        // Keep this asset even if the edit subsequently fails or is cancelled:
                        // DataStore may have committed before the caller observes cancellation.
                        // Cleanup must eventually check durable and active references first.
                        val destination = editor.copyPresetAsset(source, "Impulse response exceeds 64 MB.")
                        hash = editor.irHash(destination)
                        audio = audio.copy(dspConvIrPath = destination.absolutePath)
                    } else require(!needsImpulse) { "The selected impulse response is missing. Select it again before saving." }
                } else require(!needsImpulse) { "Select an impulse response before saving enabled convolution." }
                val (savedRack, assets) = editor.copyRackAssets(rack, editor.resolveRackAssets(p, rack))
                val preset = ProcessingPreset(UUID.randomUUID().toString(), title,
                    createdAtMs = System.currentTimeMillis(), audio = audio,
                    playback = ProcessingPlaybackPrefs.from(playback),
                    activeEqProfile = p[Keys.AUTOEQ_PROFILE].orEmpty(), irSha256 = hash, rack = savedRack, rackImpulseAssets = assets)
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous + preset)
                saved = preset
            }
            requireNotNull(saved)
        }
    }

    override val processingRouteRules: Flow<ProcessingRouteRules> = dataStore.data
        .map { it[Keys.PROCESSING_ROUTES] }.distinctUntilChanged()
        .map { ProcessingRouteCodec.decode(it).getOrThrow() }.distinctUntilChanged()

    override val presetRules: Flow<PresetRuleSet> = dataStore.data
        .map { it[Keys.PRESET_RULES] }.distinctUntilChanged()
        .map { PresetRuleCodec.decode(it).getOrThrow() }.distinctUntilChanged()

    override suspend fun savePresetRule(rule: PresetRule): Result<Unit> = editPresetRules(rule.presetId) { rules ->
        val existing = rules.rules.indexOfFirst { it.id == rule.id }
        rules.copy(rules = rules.rules.toMutableList().apply {
            if (existing < 0) add(rule) else set(existing, rule)
        })
    }

    override suspend fun removePresetRule(id: String): Result<Unit> = editPresetRules { rules ->
        rules.copy(rules = rules.rules.filterNot { it.id == id })
    }

    override suspend fun movePresetRule(id: String, delta: Int): Result<Unit> = editPresetRules { rules ->
        require(delta == -1 || delta == 1)
        val list = rules.rules.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        require(index >= 0) { "The rule no longer exists." }
        val target = (index + delta).coerceIn(0, list.lastIndex)
        list.add(target, list.removeAt(index))
        rules.copy(rules = list)
    }

    override suspend fun setPresetRulesEnabled(enabled: Boolean): Result<Unit> = editPresetRules { it.copy(enabled = enabled) }

    override suspend fun setPresetRuleManualHold(hold: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val rules = PresetRuleCodec.decode(p[Keys.PRESET_RULES]).getOrThrow()
                p[Keys.PRESET_RULES] = PresetRuleCodec.encode(rules.copy(manualHold = hold))
                if (hold) p.remove(Keys.PRESET_RULE_SESSION)
                else processingRoutes.current.route.key?.let { key ->
                    val outputs = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                    p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(outputs.copy(manual = outputs.manual - key))
                }
            }
            Unit
        }
    }

    private suspend fun editPresetRules(requiredPresetId: String? = null, change: (PresetRuleSet) -> PresetRuleSet): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val next = change(PresetRuleCodec.decode(p[Keys.PRESET_RULES]).getOrThrow())
                if (requiredPresetId != null) require(presetList(p).any { it.id == requiredPresetId }) { "The preset no longer exists." }
                p[Keys.PRESET_RULES] = PresetRuleCodec.encode(next)
            }
            Unit
        }
    }

    override suspend fun transitionPresetRule(expected: PresetRuleInput): Result<PresetRuleTransition?> = withContext(Dispatchers.IO) {
        impulseResult {
            // no rule can have applied without a session, so skip the file read
            if (expected.rules.rules.isEmpty() && dataStore.data.first()[Keys.PRESET_RULE_SESSION] == null) return@impulseResult null
            var result: PresetRuleTransition? = null
            dataStore.edit { p ->
                fun current() = PresetRuleInput(presetRuleContext.current, processingRoutes.current,
                    PresetRuleCodec.decode(p[Keys.PRESET_RULES]).getOrThrow(),
                    ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow())
                if (!PresetRuleEngine.mayCommit(expected, current())) return@edit
                if (expected.route.route.kind == ProcessingRouteKind.UNKNOWN && expected.rules.enabled) return@edit
                val saved = PresetRuleSessionCodec.decode(p[Keys.PRESET_RULE_SESSION]).getOrThrow()
                if (expected.rules.manualHold || expected.route.route.key in expected.outputRules.manual) {
                    p.remove(Keys.PRESET_RULE_SESSION)
                    return@edit
                }
                val winner = PresetRuleEngine.winner(expected)
                if (winner == null && saved == null) return@edit
                val now = captureProcessingState(p)
                if (!PresetRuleEngine.mayCommit(expected, current())) return@edit
                if (saved != null && !sameProcessing(now, saved.applied)) {
                    p.remove(Keys.PRESET_RULE_SESSION)
                    p[Keys.PRESET_RULES] = PresetRuleCodec.encode(expected.rules.copy(manualHold = true))
                    result = PresetRuleTransition(RuleTransitionAction.RETAINED)
                    return@edit
                }
                if (winner == null) {
                    if (saved == null) return@edit
                    validatePresetImpulse(saved.baseline)
                    if (!PresetRuleEngine.mayCommit(expected, current())) return@edit
                    val applied = applyPresetSnapshot(p, saved.baseline, validateImpulse = false)
                    p.remove(Keys.PRESET_RULE_SESSION)
                    result = PresetRuleTransition(RuleTransitionAction.RESTORED, restartRequired = applied.restartRequired)
                } else {
                    val preset = presetList(p).firstOrNull { it.id == winner.presetId }
                        ?: error("The rule's preset was deleted.")
                    val effective = preset.copy(audio = if (preset.rack.enabled) preset.audio.copy(dspMode = DspMode.CUSTOM) else preset.audio)
                    if (saved?.ruleId == winner.id && saved.presetId == preset.id && sameProcessing(now, effective)) {
                        result = PresetRuleTransition(RuleTransitionAction.RETAINED, winner.id, preset.id, preset.name)
                        return@edit
                    }
                    validatePresetImpulse(preset)
                    val recovery = PresetRuleSessionCodec.encode(PresetRuleSession(
                        saved?.baseline ?: now, effective, winner.id, preset.id))
                    if (!PresetRuleEngine.mayCommit(expected, current())) return@edit
                    val applied = applyPresetSnapshot(p, preset, validateImpulse = false)
                    p[Keys.PRESET_RULE_SESSION] = recovery
                    result = PresetRuleTransition(RuleTransitionAction.APPLIED, winner.id, preset.id, preset.name, applied.restartRequired)
                }
            }
            result
        }
    }

    private fun captureProcessingState(p: Preferences): ProcessingPreset {
        val audio = readAudioPrefs(p)
        val playback = readPlaybackPrefs(p)
        val path = audio.dspConvIrPath
        val hash = if (path.isNotBlank() && File(path).isFile) editor.irHash(File(path)) else ""
        return ProcessingPreset("00000000-0000-0000-0000-000000000001", "Before preset rule", createdAtMs = 0,
            audio = audio, playback = ProcessingPlaybackPrefs.from(playback), activeEqProfile = p[Keys.AUTOEQ_PROFILE].orEmpty(),
            irSha256 = hash, rack = editor.readProcessingRack(p, audio, playback.monoAudio),
            rackImpulseAssets = editor.resolveRackAssets(p, editor.readProcessingRack(p, audio, playback.monoAudio)))
    }

    private fun sameProcessing(a: ProcessingPreset, b: ProcessingPreset): Boolean =
        a.audio == b.audio && a.playback == b.playback && a.rack == b.rack && a.activeEqProfile == b.activeEqProfile

    override suspend fun setRouteRulesEnabled(enabled: Boolean): Result<Unit> = editRouteRules { it.copy(enabled = enabled) }

    override suspend fun holdCurrentRoute(hold: Boolean): Result<Unit> {
        val key = processingRoutes.current.route.key ?: return Result.failure(IllegalStateException("Output identity is unavailable."))
        return editRouteRules { it.copy(manual = if (hold) it.manual + key else it.manual - key) }
    }

    override suspend fun chooseRouteHeadphones(key: String, headphones: String): Result<Unit> = editRouteRules { rules ->
        require(ProcessingRouteCodec.validKey(key)) { "Invalid output identity." }
        require(headphones.isEmpty() || rules.bindings.any { it.routeKey == key && it.headphones == headphones }) { "Headphones are not bound to this output." }
        rules.copy(headphones = rules.headphones + (key to headphones), manual = rules.manual - key)
    }

    override suspend fun bindCurrentRoute(presetId: String, headphones: String,
        expected: RouteObservation): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val route = expected.route
            val key = route.key ?: error("Output identity is unavailable.")
            require(route.kind == ProcessingRouteKind.ANDROID) { "This output bypasses local processing." }
            dataStore.edit { p ->
                require(processingRoutes.current == expected) { "The output changed. Try again." }
                require(presetList(p).any { it.id == presetId }) { "This preset no longer exists." }
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                val name = headphones.trim()
                val binding = RoutePresetBinding(key, route.label, name, presetId)
                p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(rules.copy(enabled = true,
                    bindings = rules.bindings.filterNot { it.routeKey == key && it.headphones == name } + binding,
                    headphones = rules.headphones + (key to name), manual = rules.manual - key))
            }
            Unit
        }
    }

    override suspend fun removeRouteBinding(binding: RoutePresetBinding): Result<Unit> = editRouteRules { rules ->
        rules.copy(bindings = rules.bindings.filterNot { it == binding }, manual = rules.manual + binding.routeKey)
    }

    private suspend fun editRouteRules(change: (ProcessingRouteRules) -> ProcessingRouteRules): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(change(rules))
            }
            Unit
        }
    }

    override suspend fun applyProcessingPreset(id: String): Result<ProcessingPresetApplyResult> = withContext(Dispatchers.IO) {
        impulseResult {
            var result: ProcessingPresetApplyResult? = null
            dataStore.edit { p ->
                val preset = presetList(p).firstOrNull { it.id == id } ?: error("This preset no longer exists.")
                result = applyPresetSnapshot(p, preset)
                editor.holdManualRoute(p)
            }
            requireNotNull(result)
        }
    }

    override suspend fun applyRoutePreset(expected: RouteObservation, presetId: String): Result<ProcessingPresetApplyResult?> = withContext(Dispatchers.IO) {
        impulseResult {
            var result: ProcessingPresetApplyResult? = null
            dataStore.edit { p ->
                if (editor.orderedRulesBlockFallback(p)) return@edit
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                if (!RouteRuleDecision.mayApply(expected, processingRoutes.current, rules, presetId)) return@edit
                val preset = presetList(p).firstOrNull { it.id == presetId } ?: error("The bound preset was deleted.")
                validatePresetImpulse(preset)
                if (editor.orderedRulesBlockFallback(p)) return@edit
                if (!RouteRuleDecision.mayApply(expected, processingRoutes.current, rules, presetId)) return@edit
                result = applyPresetSnapshot(p, preset, validateImpulse = false)
            }
            result
        }
    }

    private fun validatePresetImpulse(preset: ProcessingPreset) {
        ProcessingPresetAssets.validateFiles(preset.rackImpulseAssets)
        if (!preset.requiresImpulseResponse()) return
        require(preset.audio.dspConvIrPath.isNotBlank() && preset.irSha256.isNotBlank()) { "This preset needs its saved impulse response." }
        val file = File(preset.audio.dspConvIrPath)
        require(file.isFile && file.canRead() && editor.irHash(file) == preset.irSha256) { "The preset impulse response is missing or changed." }
    }

    private fun applyPresetSnapshot(p: MutablePreferences, preset: ProcessingPreset, validateImpulse: Boolean = true): ProcessingPresetApplyResult {
        if (validateImpulse) validatePresetImpulse(preset)
        val old = readPlaybackPrefs(p)
        val restart = old.preferHighRes != preset.playback.preferHighRes || old.bitPerfectUsb != preset.playback.bitPerfectUsb ||
            old.usbOutputMode != preset.playback.usbOutputMode || old.usbFallbackPolicy != preset.playback.usbFallbackPolicy
        writeProcessingAudio(p, if (preset.rack.enabled) preset.audio.copy(dspMode = DspMode.CUSTOM) else preset.audio)
        writeProcessingPlayback(p, preset.playback)
        p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(preset.rack)
        p[Keys.ACTIVE_RACK_IMPULSES] = ImpulseLibraryCodec.encodeLibrary(preset.rackImpulseAssets)
        p[Keys.AUTOEQ_PROFILE] = preset.activeEqProfile
        return ProcessingPresetApplyResult(preset.name, restart)
    }

    override suspend fun duplicateProcessingPreset(id: String, name: String): Result<ProcessingPreset> = withContext(Dispatchers.IO) {
        runCatching {
            val title = ProcessingPresetCodec.name(name)
            var duplicate: ProcessingPreset? = null
            dataStore.edit { p ->
                val previous = presetList(p)
                val original = previous.firstOrNull { it.id == id } ?: error("This preset no longer exists.")
                val copy = original.copy(id = UUID.randomUUID().toString(), name = title, createdAtMs = System.currentTimeMillis())
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous + copy)
                duplicate = copy
            }
            requireNotNull(duplicate)
        }
    }

    override suspend fun renameProcessingPreset(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val title = ProcessingPresetCodec.name(name)
            dataStore.edit { p ->
                val previous = presetList(p)
                require(previous.any { it.id == id }) { "This preset no longer exists." }
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous.map { if (it.id == id) it.copy(name = title) else it })
            }
            Unit
        }
    }

    override suspend fun deleteProcessingPreset(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            dataStore.edit { p ->
                val previous = presetList(p)
                require(previous.any { it.id == id }) { "This preset no longer exists." }
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous.filterNot { it.id == id })
            }
            // Retain immutable IRs: another preset, duplicate, or current processing can reference them.
            Unit
        }
    }

    /** Export one immutable snapshot. The caller owns and closes the destination stream. */
    override suspend fun exportProcessingPreset(id: String, output: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val preset = presetList(dataStore.data.first()).firstOrNull { it.id == id }
                ?: error("This preset no longer exists.")
            val asset = preset.audio.dspConvIrPath.takeIf { preset.irSha256.isNotBlank() }?.let(::File)
            ProcessingPresetBundle.write(preset, asset, output)
        }
    }

    /** Validate every dependency before changing the library. Import never applies audio settings. */
    override suspend fun importProcessingPreset(input: InputStream): Result<ProcessingPreset> = withContext(Dispatchers.IO) {
        runCatching {
            ProcessingPresetBundle.read(input, cacheDir).use { bundle ->
                var imported: ProcessingPreset? = null
                dataStore.edit { p ->
                    val previous = presetList(p)
                    require(previous.size < ProcessingPresetCodec.MAX_PRESETS) { "You can save up to 100 presets." }
                    var audio = bundle.preset.audio
                    bundle.impulseResponse?.let { source ->
                        val directory = File(filesDir, "processing-presets")
                        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preset storage." }
                        val destination = File(directory, "${UUID.randomUUID()}.wav")
                        source.copyTo(destination, overwrite = false)
                        // As with save, retain a durable asset if commit/cancellation status is
                        // uncertain. Live processing can still be loading an older reference.
                        audio = audio.copy(dspConvIrPath = destination.absolutePath)
                    }
                    val entries = ProcessingPresetAssets.remapEntries(bundle.preset.rackImpulseAssets) { _, hash ->
                        val source = bundle.assets[hash] ?: error("A preset impulse response is missing.")
                        val destination = editor.copyPresetAsset(source)
                        require(editor.irHash(destination) == hash) { "Preset impulse response checksum failed." }
                        destination.path to hash
                    }
                    val ids = entries.associate { it.id to UUID.randomUUID().toString() }
                    val preset = bundle.preset.copy(id = UUID.randomUUID().toString(),
                        createdAtMs = System.currentTimeMillis(), audio = audio,
                        rack = bundle.preset.rack.copy(nodes = bundle.preset.rack.nodes.map { it.copy(impulseId = it.impulseId?.let(ids::getValue)) }),
                        rackImpulseAssets = entries.map { it.copy(id = ids.getValue(it.id)) })
                    p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous + preset)
                    imported = preset
                }
                requireNotNull(imported)
            }
        }
    }
}
