package com.aurora.music.data

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
)

class BackupManager(
    private val settingsStore: SettingsStore,
    private val localStore: LocalStore,
    private val playHistory: PlayHistoryStore,
    private val context: Context,
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
        )
        return gson.toJson(backup)
    }

    suspend fun import(json: String): Boolean = backupResult {
        restoreValidated(withoutExternalIr(BackupArchive.decodeJson(json)))
    }.isSuccess

    suspend fun exportArchive(nowMs: Long, output: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        backupResult {
            val snapshot = AuroraBackup(2, nowMs, settingsStore.exportPrefs(), localStore.exportJson(), playHistory.snapshot())
            BackupArchive.write(snapshot, output)
        }
    }

    /** All metadata and dependencies are validated before the first preference changes. */
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
                        // Immutable files stay available if DataStore commits before a cancelled
                        // caller observes success. Existing/active asset references are never deleted.
                        destination
                    }
                    val resolved = BackupArchive.remap(imported.backup) { reference, hash ->
                        val key = BackupArchive.assetHash(reference)
                        require(hash.isEmpty() || hash == key)
                        requireNotNull(installed[key]).absolutePath to key
                    }
                    restoreValidated(resolved)
                    "Backup restored, including ${installed.size} impulse response(s). Restart Aurora to apply output settings."
                }
            } else {
                val backup = BackupArchive.readJsonStream(source)
                val hadIr = backup.prefs.strings[BackupArchive.IR_PATH_KEY].orEmpty().isNotEmpty() ||
                    ProcessingPresetCodec.decode(backup.prefs.strings[BackupArchive.PRESETS_KEY]).presets.any { it.audio.dspConvIrPath.isNotEmpty() }
                restoreValidated(withoutExternalIr(backup))
                if (hadIr) "Legacy backup restored. Select impulse responses again; JSON backups do not include them. Restart Aurora to apply output settings."
                else "Backup restored. Restart Aurora to apply output settings."
            }
        }
    }

    /**
     * Await all three stores and roll back recoverable failures. Once writes begin, cancellation waits
     * for commit or rollback; it cannot strand a half-restored session. This is not a crash-atomic
     * cross-store transaction: process/device loss between the individual commits still needs a journal.
     */
    private suspend fun restoreValidated(backup: AuroraBackup) = restoreMutex.withLock {
        val oldPrefs = settingsStore.exportPrefs()
        val oldLocal = localStore.exportJson()
        val oldHistory = playHistory.snapshot()
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + Dispatchers.IO) {
            var localChanged = false
            var historyChanged = false
            var prefsAttempted = false
            try {
                if (backup.localStore.isNotBlank()) {
                    localStore.restoreBackupJson(backup.localStore)
                    localChanged = true
                }
                playHistory.restoreBackup(backup.playHistory)
                historyChanged = true
                // Commit preferences last: an unavailable local/history destination cannot change them.
                prefsAttempted = true
                settingsStore.restoreBackupPrefs(backup.prefs).getOrThrow()
            } catch (failure: Exception) {
                val rollbackFailures = mutableListOf<Exception>()
                if (prefsAttempted) try { settingsStore.restoreBackupPrefs(oldPrefs).getOrThrow() }
                    catch (rollback: Exception) { rollbackFailures += rollback }
                if (historyChanged) try { playHistory.restoreBackup(oldHistory) }
                    catch (rollback: Exception) { rollbackFailures += rollback }
                if (localChanged) try { localStore.restoreBackupJson(oldLocal) }
                    catch (rollback: Exception) { rollbackFailures += rollback }
                if (failure is CancellationException) {
                    rollbackFailures.forEach(failure::addSuppressed)
                    throw failure
                }
                val message = if (rollbackFailures.isEmpty())
                    "Backup restore failed. Previous settings, playlists and history were preserved."
                else "Backup restore failed, and some previous data could not be restored."
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
        strings[BackupArchive.IR_PATH_KEY] = ""
        strings["dsp_conv_name"] = ""
        fun dry(rack: ProcessingRack) = rack.copy(nodes = rack.nodes.map {
            if (it.kind == RackNodeKind.CONVOLUTION) it.copy(bypass = true) else it
        })
        strings[ProcessingRackCodec.PREFERENCE_KEY]?.let {
            strings[ProcessingRackCodec.PREFERENCE_KEY] = ProcessingRackCodec.encode(dry(ProcessingRackCodec.decode(it).getOrThrow()))
        }
        strings[BackupArchive.PRESETS_KEY]?.let {
            val library = ProcessingPresetCodec.decode(it)
            require(library.error == null) { library.error.orEmpty() }
            strings[BackupArchive.PRESETS_KEY] = ProcessingPresetCodec.encode(library.presets.map { p ->
                p.copy(audio = p.audio.copy(dspConvEnabled = false, dspConvIrPath = "", dspConvIrName = ""),
                    irSha256 = "", rack = dry(p.rack))
            })
        }
        return backup.copy(prefs = backup.prefs.copy(strings = strings,
            booleans = backup.prefs.booleans + ("dsp_conv_enabled" to false)))
    }
}
