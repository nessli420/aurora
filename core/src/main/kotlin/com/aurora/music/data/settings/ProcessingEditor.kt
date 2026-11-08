package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.routes.*
import com.aurora.music.data.rules.*
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class ProcessingEditor(private val dataStore: DataStore<Preferences>, private val filesDir: File) {
    val processingRoutes = ProcessingRouteMonitor()
    val presetRuleContext = PresetRuleContextMonitor()

    private val gson = Gson()

    val audioPrefs: Flow<AudioPrefs> = dataStore.data.map(::readAudioPrefs).distinctUntilChanged().flowOn(Dispatchers.Default)

    fun readProcessingRack(p: Preferences, audio: AudioPrefs, mono: Boolean): ProcessingRack =
        p[Keys.PROCESSING_RACK]?.let { decodeRack(it) } ?: ProcessingRack.legacy(audio, mono)

    @Volatile private var rackMemo: Pair<String, ProcessingRack?>? = null

    fun decodeRack(json: String): ProcessingRack? {
        rackMemo?.let { if (it.first == json) return it.second }
        return ProcessingRackCodec.decode(json).getOrNull().also { rackMemo = json to it }
    }

    val impulseLibrary: Flow<List<ImpulseLibraryEntry>> = dataStore.data
        .map { it[Keys.IMPULSE_LIBRARY] }.distinctUntilChanged()
        .map { ImpulseLibraryCodec.decodeLibrary(it).getOrThrow() }.flowOn(Dispatchers.IO)

    fun resolveRackAssets(p: Preferences, rack: ProcessingRack, strict: Boolean = true): List<ImpulseLibraryEntry> =
        resolveRackAssets(rack.nodes, p[Keys.ACTIVE_RACK_IMPULSES], p[Keys.RACK_SUBCHAINS], p[Keys.IMPULSE_LIBRARY], strict)

    fun resolveRackAssets(nodes: List<ProcessingRackNode>, active: String?, subchains: String?, library: String?,
        strict: Boolean): List<ImpulseLibraryEntry> {
        val ids = nodes.mapNotNull { it.impulseId }.distinct()
        if (ids.isEmpty()) return emptyList()
        val available = (ImpulseLibraryCodec.decodeLibrary(active).getOrThrow() +
            RackSubchainCodec.decode(subchains).getOrThrow().flatMap { it.impulseAssets } +
            ImpulseLibraryCodec.decodeLibrary(library).getOrThrow()).associateBy { it.id }
        return ids.mapNotNull { id -> available[id].also { require(it != null || !strict) { "An impulse response is missing." } } }
    }

    fun copyRackAssets(rack: ProcessingRack, entries: List<ImpulseLibraryEntry>): Pair<ProcessingRack, List<ImpulseLibraryEntry>> {
        ProcessingPresetAssets.validateFiles(entries)
        val ids = entries.associate { it.id to UUID.randomUUID().toString() }
        val copied = ProcessingPresetAssets.remapEntries(entries) { path, hash ->
            val destination = copyPresetAsset(File(path))
            require(irHash(destination) == hash) { "An impulse response changed while saving." }
            destination.path to hash
        }.map { it.copy(id = ids.getValue(it.id)) }
        return rack.copy(nodes = rack.nodes.map { it.copy(impulseId = it.impulseId?.let(ids::getValue)) }) to copied
    }

    fun copyPresetAsset(source: File, tooLarge: String = "Impulse response is too large."): File {
        require(source.isFile && source.length() in 1..MAX_PRESET_IR_BYTES) { "Impulse response is missing or too large." }
        val directory = File(filesDir, "processing-presets")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preset storage." }
        val destination = File(directory, "${UUID.randomUUID()}.wav")
        source.inputStream().use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= MAX_PRESET_IR_BYTES) { tooLarge }
                output.write(buffer, 0, read)
            }
        } }
        return destination
    }

    fun holdManualRoute(p: MutablePreferences) {
        val ordered = PresetRuleCodec.decode(p[Keys.PRESET_RULES]).getOrThrow()
        val hadRecovery = p[Keys.PRESET_RULE_SESSION] != null
        p.remove(Keys.PRESET_RULE_SESSION)
        if (hadRecovery || ordered.enabled && ordered.rules.isNotEmpty()) {
            p[Keys.PRESET_RULES] = PresetRuleCodec.encode(ordered.copy(manualHold = true))
        }
        val route = processingRoutes.current.route
        val key = route.key ?: return
        if (!route.switchesDeviceEq) return
        val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
        val fullBinding = rules.enabled && rules.bindings.any { it.routeKey == key }
        val legacyBinding = p[Keys.AUTOEQ_SWITCH] == true && parseBindings(p[Keys.EQ_BINDINGS]).any {
            it.deviceKey == key || it.deviceKey == route.legacyKey
        }
        if (fullBinding || legacyBinding) p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(rules.copy(manual = rules.manual + key))
    }

    suspend fun editManualProcessing(change: (MutablePreferences) -> Unit) = dataStore.edit { p ->
        change(p)
        holdManualRoute(p)
    }

    fun orderedRulesBlockFallback(p: Preferences): Boolean {
        val context = presetRuleContext.current
        val rules = PresetRuleCodec.decode(p[Keys.PRESET_RULES]).getOrThrow()
        return context.frozen || rules.manualHold || PresetRuleEngine.winner(PresetRuleInput(context,
            processingRoutes.current, rules, ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow())) != null
    }

    fun irHash(file: File): String {
        require(file.length() in 1..MAX_PRESET_IR_BYTES) { "Impulse response must be between 1 byte and 64 MB." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PRESET_IR_BYTES) { "Impulse response exceeds 64 MB." }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun parseBindings(json: String?): List<EqBinding> = gson.parseList(json)
}

internal suspend fun <T> impulseResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

internal const val MAX_PRESET_IR_BYTES = 64L * 1024L * 1024L
