package com.aurora.music.data

import com.google.gson.Gson

/** Typed snapshot of all DataStore prefs (so a JSON backup round-trips without losing value types). */
data class PrefsBackup(
    val strings: Map<String, String> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val stringSets: Map<String, List<String>> = emptyMap(),
)

/** The whole backup bundle (5.3): settings + on-device playlists/likes + listening history. */
data class AuroraBackup(
    val version: Int = 1,
    val createdAt: Long = 0L,
    val prefs: PrefsBackup = PrefsBackup(),
    val localStore: String = "",            // raw JSON of LocalStore state
    val playHistory: List<PlayEvent> = emptyList(),
)

/**
 * Backup & restore (5.3). Exports the user's settings (theme, DSP/EQ, smart playlists, pins, saved
 * logins, integrations…), their on-device playlists/likes and listening history into one JSON
 * document; import overwrites the current state with it. Downloaded audio files are intentionally
 * excluded — they're large and re-downloadable.
 */
class BackupManager(
    private val settingsStore: SettingsStore,
    private val localStore: LocalStore,
    private val playHistory: PlayHistoryStore,
) {
    private val gson = Gson()

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

    /** Restore from a backup document. Returns false if it can't be parsed. */
    suspend fun import(json: String): Boolean {
        val backup = runCatching { gson.fromJson(json, AuroraBackup::class.java) }.getOrNull() ?: return false
        settingsStore.importPrefs(backup.prefs)
        if (backup.localStore.isNotBlank()) localStore.importJson(backup.localStore)
        playHistory.restore(backup.playHistory)
        return true
    }
}
