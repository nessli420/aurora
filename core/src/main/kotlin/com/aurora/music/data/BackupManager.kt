package com.aurora.music.data

import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.rules.PresetRuleSessionCodec
import com.google.gson.Gson
import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PrefsBackup(
    val strings: Map<String, String> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val stringSets: Map<String, List<String>> = emptyMap(),
)

data class AuroraBackup(
    val version: Int = 1,
    val createdAt: Long = 0L,
    val prefs: PrefsBackup = PrefsBackup(),
    val localStore: String = "",
    val playHistory: List<PlayEvent> = emptyList(),
    val listeningProfiles: String? = null,
)

class BackupManager(
    private val settingsStore: SettingsStore,
    private val localStore: LocalStore,
    private val playHistory: PlayHistoryStore,
    private val context: Context,
    private val listeningLevels: com.aurora.music.data.listening.ListeningLevelStore? = null,
) {
    private val gson = Gson()
    private val restoreMutex = Mutex()

    suspend fun export(nowMs: Long): String {
        val backup = AuroraBackup(
            version = 1,
            createdAt = nowMs,
            prefs = settingsStore.exportPrefs(),
            localStore = localStore.exportJson(),
            playHistory = playHistory.snapshot(),
            listeningProfiles = listeningLevels?.exportProfiles(),
        )
        return gson.toJson(backup)
    }

    suspend fun import(json: String): Boolean = backupResult {
        restoreValidated(withoutExternalIr(BackupArchive.decodeJson(json)))
    }.isSuccess

    suspend fun exportArchive(nowMs: Long, output: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        backupResult {
            val snapshot = AuroraBackup(2, nowMs, settingsStore.exportPrefs(), localStore.exportJson(), playHistory.snapshot(), listeningLevels?.exportProfiles())
            BackupArchive.write(snapshot, output)
        }
    }

    suspend fun importArchive(input: InputStream): Result<String> = withContext(Dispatchers.IO) {
        backupResult {
            val source = PushbackInputStream(input, 2)
            val signature = ByteArray(2)
            var count = 0
            while (count < signature.size) {
                val next = source.read(signature, count, signature.size - count)
                if (next < 0) break
                if (next == 0) continue
                count += next
            }
            require(count > 0) { "Backup is empty." }
            source.unread(signature, 0, count)
            if (count == 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()) {
                BackupArchive.read(source, context.cacheDir).use { imported ->
                    val directory = File(context.filesDir, "processing-presets")
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create impulse-response storage." }
                    val installed = imported.assets.mapValues { (_, file) ->
                        val destination = File(directory, "backup-${UUID.randomUUID()}.wav")
                        file.copyTo(destination, overwrite = false)
                        // retain assets if cancellation follows the preference commit.
                        destination
                    }
                    val resolved = BackupArchive.remap(imported.backup) { reference, hash ->
                        val key = BackupArchive.assetHash(reference)
                        require(hash.isEmpty() || hash == key)
                        requireNotNull(installed[key]).absolutePath to key
                    }
                    restoreValidated(resolved)
                    "Backup restored with ${installed.size} impulse responses. Restart Aurora."
                }
            } else {
                val backup = BackupArchive.readJsonStream(source)
                val hadIr = backup.prefs.strings[BackupArchive.IR_PATH_KEY].orEmpty().isNotEmpty() ||
                    ProcessingPresetCodec.decode(backup.prefs.strings[BackupArchive.PRESETS_KEY]).presets.any { it.audio.dspConvIrPath.isNotEmpty() || it.rackImpulseAssets.isNotEmpty() } ||
                    ImpulseLibraryCodec.decodeLibrary(backup.prefs.strings[BackupArchive.ACTIVE_RACK_IR_KEY]).getOrThrow().isNotEmpty() ||
                    ImpulseLibraryCodec.decodeLibrary(backup.prefs.strings[ImpulseLibraryCodec.PREFERENCE_KEY]).getOrThrow().isNotEmpty()
                restoreValidated(withoutExternalIr(backup))
                if (hadIr) "Legacy backup restored without impulse responses. Import them again and restart Aurora."
                else "Backup restored. Restart Aurora."
            }
        }
    }

    // cancellation waits for commit or rollback; process loss is not covered.
    private suspend fun restoreValidated(backup: AuroraBackup) = restoreMutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + Dispatchers.IO) {
            var localRollback: LocalStore.BackupRollback? = null
            var historyRollback: PlayHistoryStore.BackupRollback? = null
            var listeningRollback: com.aurora.music.data.listening.ListeningLevelStore.BackupRollback? = null
            try {
                if (backup.localStore.isNotBlank()) {
                    localRollback = localStore.replaceBackupJson(backup.localStore)
                }
                historyRollback = playHistory.replaceBackup(backup.playHistory)
                backup.listeningProfiles?.let { listeningRollback = listeningLevels?.replaceBackupProfiles(it) }
                // atomic preference replacement is last and cannot be cancelled
                settingsStore.restoreBackupPrefs(backup.prefs).getOrThrow()
            } catch (failure: Exception) {
                val rollbackFailures = mutableListOf<Exception>()
                var retainedNewerChanges = false
                listeningRollback?.let { token ->
                    try { if (listeningLevels?.rollbackBackup(token) == false) retainedNewerChanges = true }
                    catch (rollback: Exception) { rollbackFailures += rollback }
                }
                historyRollback?.let { token ->
                    try { if (!playHistory.rollbackBackup(token)) retainedNewerChanges = true }
                    catch (rollback: Exception) { rollbackFailures += rollback }
                }
                localRollback?.let { token ->
                    try { if (!localStore.rollbackBackup(token)) retainedNewerChanges = true }
                    catch (rollback: Exception) { rollbackFailures += rollback }
                }
                if (failure is CancellationException) {
                    rollbackFailures.forEach(failure::addSuppressed)
                    throw failure
                }
                val message = when {
                    rollbackFailures.isNotEmpty() -> "Backup restore failed. Some previous data could not be restored."
                    retainedNewerChanges -> "Backup restore failed. Newer changes were kept; previous data was not fully restored."
                    else -> "Backup restore failed. Previous settings, playlists and history were preserved."
                }
                throw IOException(message, failure).also { problem -> rollbackFailures.forEach(problem::addSuppressed) }
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private inline fun <T> backupResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun withoutExternalIr(backup: AuroraBackup): AuroraBackup {
        val strings = backup.prefs.strings.toMutableMap()
        strings.remove(ImpulseLibraryCodec.PREFERENCE_KEY)
        strings.remove(BackupArchive.ACTIVE_RACK_IR_KEY)
        strings[BackupArchive.IR_PATH_KEY] = ""
        strings["dsp_conv_name"] = ""
        fun dry(rack: ProcessingRack) = rack.copy(nodes = rack.nodes.map {
            if (it.kind == RackNodeKind.CONVOLUTION) it.copy(bypass = true, impulseId = null) else it
        })
        fun dryPreset(preset: ProcessingPreset) = preset.copy(
            audio = preset.audio.copy(dspConvEnabled = false, dspConvIrPath = "", dspConvIrName = ""),
            irSha256 = "", rack = dry(preset.rack), rackImpulseAssets = emptyList())
        PresetRuleSessionCodec.decode(strings[PresetRuleSessionCodec.PREFERENCE_KEY]).getOrThrow()?.let { session ->
            strings[PresetRuleSessionCodec.PREFERENCE_KEY] = PresetRuleSessionCodec.encode(session.copy(
                baseline = dryPreset(session.baseline), applied = dryPreset(session.applied)))
        }
        strings[ProcessingRackCodec.PREFERENCE_KEY]?.let {
            strings[ProcessingRackCodec.PREFERENCE_KEY] = ProcessingRackCodec.encode(dry(ProcessingRackCodec.decode(it).getOrThrow()))
        }
        strings[BackupArchive.PRESETS_KEY]?.let {
            val library = ProcessingPresetCodec.decode(it)
            require(library.error == null) { library.error.orEmpty() }
            strings[BackupArchive.PRESETS_KEY] = ProcessingPresetCodec.encode(library.presets.map(::dryPreset))
        }
        strings[RackSubchainCodec.PREFERENCE_KEY]?.let { json ->
            strings[RackSubchainCodec.PREFERENCE_KEY] = RackSubchainCodec.encode(RackSubchainCodec.decode(json).getOrThrow().map { it.copy(rack = dry(it.rack), impulseAssets = emptyList()) })
        }
        return backup.copy(prefs = backup.prefs.copy(strings = strings,
            booleans = backup.prefs.booleans + ("dsp_conv_enabled" to false)))
    }
}
