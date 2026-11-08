package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.ir.ImpulseLibraryFiles
import com.aurora.music.data.ir.ImpulsePreparation
import com.aurora.music.data.tuning.TuningProject
import com.aurora.music.data.tuning.TuningProjectCodec
import com.aurora.music.data.tuning.TuningRackPlan
import com.aurora.music.data.tuning.TuningTarget
import com.aurora.music.data.tuning.TuningTargetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

interface RackLibrarySettings {
    val impulseLibrary: Flow<List<ImpulseLibraryEntry>>
    suspend fun importImpulse(input: InputStream, name: String): Result<ImpulseLibraryEntry>
    suspend fun importCurrentImpulse(): Result<ImpulseLibraryEntry>
    suspend fun renameImpulse(id: String, name: String): Result<Unit>
    suspend fun deleteImpulse(id: String): Result<Unit>
    suspend fun prepareImpulse(id: String, options: ImpulsePreparation): Result<Unit>
    suspend fun selectImpulse(id: String, prepared: Boolean): Result<Unit>
    suspend fun exportImpulse(id: String, prepared: Boolean, output: OutputStream): Result<Unit>
    val tuningTargets: Flow<List<TuningTarget>>
    suspend fun saveTuningTarget(target: TuningTarget): Result<Unit>
    suspend fun deleteTuningTarget(id: String): Result<Unit>
    suspend fun revertTuningAppend(expectedRack: ProcessingRack, previousRack: ProcessingRack): Result<Unit>
    val tuningProjects: Flow<List<TuningProject>>
    suspend fun saveTuningProject(project: TuningProject): Result<Unit>
    suspend fun deleteTuningProject(id: String): Result<Unit>
    suspend fun appendTuningProjectToRack(project: TuningProject): Result<Unit>
    suspend fun appendTuningProjectWithUndo(project: TuningProject): Result<Pair<ProcessingRack, ProcessingRack>>
    val rackSubchains: Flow<List<RackSubchain>>
    suspend fun saveRackSubchain(chain: RackSubchain): Result<Unit>
    suspend fun deleteRackSubchain(id: String): Result<Unit>
    suspend fun appendRackSubchain(chain: RackSubchain): Result<Unit>
}

internal class RackLibrarySettingsStore(private val dataStore: DataStore<Preferences>, private val editor: ProcessingEditor, private val filesDir: File) : RackLibrarySettings {
    override val impulseLibrary: Flow<List<ImpulseLibraryEntry>> = editor.impulseLibrary

    override suspend fun importImpulse(input: InputStream, name: String): Result<ImpulseLibraryEntry> = withContext(Dispatchers.IO) {
        impulseResult {
            val file = newImpulseFile()
            var published = false
            try {
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytes += count
                        require(bytes <= 64L * 1024 * 1024) { "WAV exceeds 64 MiB." }
                        output.write(buffer, 0, count)
                    }
                }
                val entry = ImpulseLibraryFiles.importOriginal(file, name.substringBeforeLast('.').take(80).ifBlank { "Impulse response" },
                    name, System.currentTimeMillis()).getOrThrow()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    dataStore.edit { p ->
                        val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                        require(library.size < ImpulseLibraryCodec.MAX_ENTRIES) { "The library holds up to 32 impulse responses." }
                        p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library + entry)
                    }
                    published = true
                }
                entry
            } finally { if (!published) file.delete() }
        }
    }

    override suspend fun importCurrentImpulse(): Result<ImpulseLibraryEntry> = withContext(Dispatchers.IO) {
        impulseResult {
            val audio = editor.audioPrefs.first()
            require(audio.dspConvIrPath.isNotBlank()) { "No impulse response is selected." }
            File(audio.dspConvIrPath).inputStream().use {
                importImpulse(it, audio.dspConvIrName.ifBlank { "Impulse response.wav" }).getOrThrow()
            }
        }
    }

    override suspend fun renameImpulse(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                val current = library.firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
                val renamed = ImpulseLibraryCodec.validate(current.copy(name = name))
                p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library.map {
                    if (it.id == id) renamed else it
                })
                val selectedPath = p[Keys.DSP_CONV_PATH]
                when {
                    selectedPath == current.sourcePath -> p[Keys.DSP_CONV_NAME] = renamed.name
                    current.prepared != null && selectedPath == current.prepared?.path -> {
                        p[Keys.DSP_CONV_NAME] = "${renamed.name} · Variant"
                    }
                }
            }
            Unit
        }
    }

    override suspend fun deleteImpulse(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                require(library.any { it.id == id }) { "Impulse response no longer exists." }
                // active playback and presets may still reference these files
                p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library.filterNot { it.id == id })
            }
            Unit
        }
    }

    override suspend fun prepareImpulse(id: String, options: ImpulsePreparation): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val entry = impulseLibrary.first().firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
            val file = newImpulseFile()
            var published = false
            try {
                val prepared = ImpulseLibraryFiles.prepare(entry, file, options).getOrThrow()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    dataStore.edit { p ->
                        val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                        val current = library.firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
                        require(current.sourcePath == entry.sourcePath && current.sourceSha256 == entry.sourceSha256) {
                            "The source changed. Open it again."
                        }
                        p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library.map {
                            if (it.id == id) it.copy(prepared = prepared.prepared) else it
                        })
                    }
                    published = true
                }
                Unit
            } finally { if (!published) file.delete() }
        }
    }

    override suspend fun selectImpulse(id: String, prepared: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val entry = impulseLibrary.first().firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
            val file = ImpulseLibraryFiles.validateAsset(entry, prepared).getOrThrow()
            val metadata = if (prepared) requireNotNull(entry.prepared).metadata else entry.sourceMetadata
            require(ImpulseLibraryFiles.estimate(metadata, metadata.sampleRate).supported) { "Trim this response before selecting it." }
            dataStore.edit { p ->
                val current = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow().firstOrNull { it.id == id }
                    ?: error("Impulse response no longer exists.")
                require(if (prepared) current.prepared == entry.prepared else
                    current.sourcePath == entry.sourcePath && current.sourceSha256 == entry.sourceSha256) { "The response changed. Select it again." }
                p[Keys.DSP_CONV_PATH] = file.absolutePath
                p[Keys.DSP_CONV_NAME] = current.name + if (prepared) " · Variant" else ""
                editor.holdManualRoute(p)
            }
            Unit
        }
    }

    override suspend fun exportImpulse(id: String, prepared: Boolean, output: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val entry = impulseLibrary.first().firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
            val file = ImpulseLibraryFiles.validateAsset(entry, prepared).getOrThrow()
            file.inputStream().use { it.copyTo(output) }
            Unit
        }
    }

    private fun newImpulseFile(): File {
        val directory = File(filesDir, "impulse-library")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create impulse-response storage." }
        return File(directory, "${UUID.randomUUID()}.wav")
    }

    override val tuningTargets: Flow<List<TuningTarget>> = dataStore.data
        .map { it[Keys.TUNING_TARGETS] }.distinctUntilChanged()
        .map { TuningTargetCatalog.decodeLibrary(it).getOrThrow() }.flowOn(Dispatchers.Default)

    override suspend fun saveTuningTarget(target: TuningTarget): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val library = TuningTargetCatalog.decodeLibrary(p[Keys.TUNING_TARGETS]).getOrThrow()
                p[Keys.TUNING_TARGETS] = TuningTargetCatalog.encodeLibrary(TuningTargetCatalog.upsert(library, target).getOrThrow())
            }
            Unit
        }
    }

    override suspend fun deleteTuningTarget(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val library = TuningTargetCatalog.decodeLibrary(p[Keys.TUNING_TARGETS]).getOrThrow()
                p[Keys.TUNING_TARGETS] = TuningTargetCatalog.encodeLibrary(TuningTargetCatalog.delete(library, id).getOrThrow())
            }
            Unit
        }
    }

    override suspend fun revertTuningAppend(expectedRack: ProcessingRack, previousRack: ProcessingRack): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val encoded = ProcessingRackCodec.encode(previousRack)
            dataStore.edit { p ->
                val current = editor.readProcessingRack(p, readAudioPrefs(p), readPlaybackPrefs(p).monoAudio)
                require(current == expectedRack) { "The rack changed. Remove the correction stages manually." }
                p[Keys.PROCESSING_RACK] = encoded
                editor.holdManualRoute(p)
            }
            Unit
        }
    }

    override val tuningProjects: Flow<List<TuningProject>> = dataStore.data
        .map { it[Keys.TUNING_PROJECTS] }.distinctUntilChanged()
        .map { TuningProjectCodec.decodeLibrary(it).getOrThrow() }.flowOn(Dispatchers.Default)

    override suspend fun saveTuningProject(project: TuningProject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = TuningProjectCodec.validate(project)
            dataStore.edit { p ->
                p[Keys.TUNING_PROJECTS] = TuningProjectCodec.encodeLibrary(TuningProjectCodec.upsert(
                    TuningProjectCodec.decodeLibrary(p[Keys.TUNING_PROJECTS]).getOrThrow(), validated).getOrThrow())
            }
            Unit
        }
    }

    override suspend fun deleteTuningProject(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            dataStore.edit { p ->
                p[Keys.TUNING_PROJECTS] = TuningProjectCodec.encodeLibrary(TuningProjectCodec.delete(
                    TuningProjectCodec.decodeLibrary(p[Keys.TUNING_PROJECTS]).getOrThrow(), id).getOrThrow())
            }
            Unit
        }
    }

    override suspend fun appendTuningProjectToRack(project: TuningProject): Result<Unit> =
        appendTuningProjectWithUndo(project).map { Unit }

    override suspend fun appendTuningProjectWithUndo(project: TuningProject): Result<Pair<ProcessingRack, ProcessingRack>> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = TuningProjectCodec.validate(project)
            var snapshots: Pair<ProcessingRack, ProcessingRack>? = null
            dataStore.edit { p ->
                val rack = editor.readProcessingRack(p, readAudioPrefs(p), readPlaybackPrefs(p).monoAudio)
                val updatedRack = TuningRackPlan.append(rack, validated)
                snapshots = rack to updatedRack
                val library = TuningProjectCodec.upsert(TuningProjectCodec.decodeLibrary(p[Keys.TUNING_PROJECTS]).getOrThrow(), validated).getOrThrow()
                p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(updatedRack)
                p[Keys.TUNING_PROJECTS] = TuningProjectCodec.encodeLibrary(library)
                editor.holdManualRoute(p)
            }
            requireNotNull(snapshots)
        }
    }

    override val rackSubchains: Flow<List<RackSubchain>> = dataStore.data
        .map { it[Keys.RACK_SUBCHAINS] }.distinctUntilChanged()
        .map { RackSubchainCodec.decode(it).getOrThrow() }.distinctUntilChanged()

    override suspend fun saveRackSubchain(chain: RackSubchain): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val saved = RackSubchainCodec.decode(p[Keys.RACK_SUBCHAINS]).getOrThrow()
                var sourceRack = chain.rack
                val entries = editor.resolveRackAssets(p, sourceRack).toMutableList()
                if (sourceRack.nodes.any { it.kind == RackNodeKind.CONVOLUTION && it.impulseId == null }) {
                    val file = File(readAudioPrefs(p).dspConvIrPath)
                    val shared = ImpulseLibraryFiles.importOriginal(file, "Saved impulse", file.name, System.currentTimeMillis()).getOrThrow()
                    entries += shared
                    sourceRack = sourceRack.copy(nodes = sourceRack.nodes.map {
                        if (it.kind == RackNodeKind.CONVOLUTION && it.impulseId == null) it.copy(impulseId = shared.id) else it
                    })
                }
                val (rack, assets) = editor.copyRackAssets(sourceRack, entries)
                p[Keys.RACK_SUBCHAINS] = RackSubchainCodec.encode(saved.filterNot { it.id == chain.id } + chain.copy(rack = rack, impulseAssets = assets))
            }
            Unit
        }
    }

    override suspend fun deleteRackSubchain(id: String): Result<Unit> = impulseResult {
        dataStore.edit { p ->
            p[Keys.RACK_SUBCHAINS] = RackSubchainCodec.encode(RackSubchainCodec.decode(p[Keys.RACK_SUBCHAINS]).getOrThrow().filterNot { it.id == id })
        }
        Unit
    }

    override suspend fun appendRackSubchain(chain: RackSubchain): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            dataStore.edit { p ->
                val saved = RackSubchainCodec.decode(p[Keys.RACK_SUBCHAINS]).getOrThrow().firstOrNull { it.id == chain.id }
                    ?: error("The saved stages no longer exist.")
                ProcessingPresetAssets.validateFiles(saved.impulseAssets)
                val audio = readAudioPrefs(p)
                val rack = editor.readProcessingRack(p, audio, readPlaybackPrefs(p).monoAudio)
                val updated = RackSubchainCodec.append(rack, saved)
                p[Keys.ACTIVE_RACK_IMPULSES] = ImpulseLibraryCodec.encodeLibrary(editor.resolveRackAssets(p, updated))
                p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(updated)
                editor.holdManualRoute(p)
            }
            Unit
        }
    }
}
