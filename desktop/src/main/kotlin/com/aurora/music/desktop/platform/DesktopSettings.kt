package com.aurora.music.desktop.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

data class OutputPrefs(
    val deviceId: String? = null,
    val exclusive: Boolean = false,
    val bufferMs: Int = DesktopSettings.DEFAULT_BUFFER_MS,
)

data class WindowPlacement(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

class DesktopSettings(private val dataStore: DataStore<Preferences>) {
    private val gson = Gson()

    val output: Flow<OutputPrefs> = dataStore.data.map { p ->
        OutputPrefs(p[OUTPUT_DEVICE], p[EXCLUSIVE] ?: false, p[BUFFER_MS] ?: DEFAULT_BUFFER_MS)
    }.distinctUntilChanged()
    val outputDeviceId: Flow<String?> = dataStore.data.map { it[OUTPUT_DEVICE] }.distinctUntilChanged()
    val exclusiveMode: Flow<Boolean> = dataStore.data.map { it[EXCLUSIVE] ?: false }.distinctUntilChanged()
    val outputBufferMs: Flow<Int> = dataStore.data.map { it[BUFFER_MS] ?: DEFAULT_BUFFER_MS }.distinctUntilChanged()
    val volume: Flow<Float> = dataStore.data.map { it[VOLUME] ?: 1f }.distinctUntilChanged()
    val unmuteVolume: Flow<Float> = dataStore.data.map { p ->
        p[UNMUTE_VOLUME] ?: p[VOLUME]?.takeIf { it > MIN_AUDIBLE_VOLUME } ?: DEFAULT_UNMUTE_VOLUME
    }.distinctUntilChanged()
    val languageTag: Flow<String> = dataStore.data.map { it[LANGUAGE] ?: "" }.distinctUntilChanged()
    val musicFolders: Flow<List<String>> = dataStore.data.map { parseFolders(it[MUSIC_FOLDERS]) }.distinctUntilChanged()
    val closeToTray: Flow<Boolean> = dataStore.data.map { it[CLOSE_TO_TRAY] ?: false }.distinctUntilChanged()
    val recapSeen: Flow<Set<String>> = dataStore.data.map { it[RECAP_SEEN].orEmpty() }.distinctUntilChanged()
    val window: Flow<WindowPlacement?> = dataStore.data.map { p ->
        val width = p[WINDOW_WIDTH]
        val height = p[WINDOW_HEIGHT]
        if (width == null || height == null) null
        else WindowPlacement(p[WINDOW_X] ?: 0, p[WINDOW_Y] ?: 0, width, height, p[WINDOW_MAXIMIZED] ?: false)
    }.distinctUntilChanged()
    val libraryListWidth: Flow<Float?> = dataStore.data.map { it[LIBRARY_LIST_WIDTH] }.distinctUntilChanged()
    val settingsListWidth: Flow<Float?> = dataStore.data.map { it[SETTINGS_LIST_WIDTH] }.distinctUntilChanged()
    val sidePanelWidth: Flow<Float?> = dataStore.data.map { it[SIDE_PANEL_WIDTH] }.distinctUntilChanged()

    suspend fun setOutputDevice(id: String?) = dataStore.edit { if (id.isNullOrBlank()) it.remove(OUTPUT_DEVICE) else it[OUTPUT_DEVICE] = id }
    suspend fun setExclusiveMode(enabled: Boolean) = dataStore.edit { it[EXCLUSIVE] = enabled }
    suspend fun setOutputBufferMs(ms: Int) = dataStore.edit { it[BUFFER_MS] = ms.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS) }
    suspend fun setVolume(volume: Float, unmute: Float? = null) = dataStore.edit {
        it[VOLUME] = volume.coerceIn(0f, 1f)
        if (unmute != null) it[UNMUTE_VOLUME] = unmute.coerceIn(MIN_AUDIBLE_VOLUME, 1f)
    }
    suspend fun setLanguageTag(tag: String) = dataStore.edit { it[LANGUAGE] = tag.trim() }
    suspend fun setCloseToTray(enabled: Boolean) = dataStore.edit { it[CLOSE_TO_TRAY] = enabled }
    suspend fun markRecapsSeen(keys: Set<String>) = dataStore.edit { it[RECAP_SEEN] = it[RECAP_SEEN].orEmpty() + keys }

    suspend fun setMusicFolders(folders: List<String>) = dataStore.edit { it[MUSIC_FOLDERS] = gson.toJson(normalize(folders)) }
    suspend fun addMusicFolder(folder: String) = dataStore.edit { it[MUSIC_FOLDERS] = gson.toJson(normalize(parseFolders(it[MUSIC_FOLDERS]) + folder)) }
    suspend fun removeMusicFolder(folder: String) = dataStore.edit { p ->
        val removed = normalize(listOf(folder)).firstOrNull()
        p[MUSIC_FOLDERS] = gson.toJson(parseFolders(p[MUSIC_FOLDERS]).filterNot { it.equals(removed, ignoreCase = true) })
    }

    suspend fun setWindow(placement: WindowPlacement) = dataStore.edit {
        it[WINDOW_X] = placement.x
        it[WINDOW_Y] = placement.y
        it[WINDOW_WIDTH] = placement.width
        it[WINDOW_HEIGHT] = placement.height
        it[WINDOW_MAXIMIZED] = placement.maximized
    }

    suspend fun setLibraryListWidth(dp: Float?) = setWidth(LIBRARY_LIST_WIDTH, dp)
    suspend fun setSettingsListWidth(dp: Float?) = setWidth(SETTINGS_LIST_WIDTH, dp)
    suspend fun setSidePanelWidth(dp: Float?) = setWidth(SIDE_PANEL_WIDTH, dp)

    private suspend fun setWidth(key: Preferences.Key<Float>, dp: Float?) = dataStore.edit {
        if (dp == null || !dp.isFinite() || dp <= 0f) it.remove(key) else it[key] = dp
    }

    private fun parseFolders(json: String?): List<String> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun normalize(folders: List<String>): List<String> = folders.map(String::trim).filter(String::isNotEmpty)
        .map { File(it).absoluteFile.normalize().path }.distinctBy { it.lowercase() }

    companion object {
        const val DEFAULT_BUFFER_MS = 400
        const val MIN_BUFFER_MS = 20
        const val MAX_BUFFER_MS = 5000
        const val MIN_AUDIBLE_VOLUME = 0.01f
        const val DEFAULT_UNMUTE_VOLUME = 0.5f
        private val OUTPUT_DEVICE = stringPreferencesKey("output_device_id")
        private val EXCLUSIVE = booleanPreferencesKey("exclusive_mode")
        private val BUFFER_MS = intPreferencesKey("output_buffer_ms")
        private val VOLUME = floatPreferencesKey("volume")
        private val UNMUTE_VOLUME = floatPreferencesKey("unmute_volume")
        private val LANGUAGE = stringPreferencesKey("language_tag")
        private val MUSIC_FOLDERS = stringPreferencesKey("music_folders")
        private val CLOSE_TO_TRAY = booleanPreferencesKey("close_to_tray")
        private val RECAP_SEEN = stringSetPreferencesKey("recap_seen")
        private val WINDOW_X = intPreferencesKey("window_x")
        private val WINDOW_Y = intPreferencesKey("window_y")
        private val WINDOW_WIDTH = intPreferencesKey("window_width")
        private val WINDOW_HEIGHT = intPreferencesKey("window_height")
        private val WINDOW_MAXIMIZED = booleanPreferencesKey("window_maximized")
        private val LIBRARY_LIST_WIDTH = floatPreferencesKey("library_list_width")
        private val SETTINGS_LIST_WIDTH = floatPreferencesKey("settings_list_width")
        private val SIDE_PANEL_WIDTH = floatPreferencesKey("side_panel_width")
    }
}
