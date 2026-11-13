package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface GeneralSettings {
    val offlineMode: Flow<Boolean>
    val dataSaver: Flow<Boolean>
    val mobileDataDownloads: Flow<Boolean>
    val simpleMode: Flow<Boolean>
    val insightsPrefs: Flow<InsightsPrefs>
    val onboardingSeen: Flow<Boolean?>
    suspend fun setOnboardingSeen(): Preferences
    val whatsNewSeen: Flow<String?>
    suspend fun setWhatsNewSeen(version: String): Preferences
    val whatsNewEnabled: Flow<Boolean>
    suspend fun setWhatsNewEnabled(enabled: Boolean): Preferences
    // the release tag the launch update toast last named
    val updateAnnounced: Flow<String?>
    suspend fun setUpdateAnnounced(tag: String): Preferences
    val privateSession: Flow<Boolean>
    // listening time brought from another player, only added to the all-time total
    val historicalListeningMs: Flow<Long>
    suspend fun setHistoricalListeningMs(ms: Long): Preferences
    suspend fun setOfflineMode(v: Boolean): Preferences
    suspend fun setDataSaver(v: Boolean): Preferences
    suspend fun setMobileDataDownloads(v: Boolean): Preferences
    suspend fun setInsightsEnabled(v: Boolean): Preferences
    suspend fun setInsightsDevice(v: Boolean): Preferences
    suspend fun setInsightsAudioPath(v: Boolean): Preferences
    suspend fun setInsightsPlaces(v: Boolean): Preferences
    suspend fun setInsightsLocationHistory(v: Boolean): Preferences
    suspend fun setSimpleMode(v: Boolean): Preferences
    suspend fun setPrivateSession(v: Boolean): Preferences
}

internal class GeneralSettingsStore(private val dataStore: DataStore<Preferences>) : GeneralSettings {
    override val offlineMode: Flow<Boolean> = dataStore.data.map { it[Keys.OFFLINE] ?: false }.distinctUntilChanged()

    override val dataSaver: Flow<Boolean> = dataStore.data.map { it[Keys.DATA_SAVER] ?: false }.distinctUntilChanged()
    override val mobileDataDownloads: Flow<Boolean> = dataStore.data.map { it[Keys.MOBILE_DATA_DOWNLOADS] ?: false }.distinctUntilChanged()
    override val simpleMode: Flow<Boolean> = dataStore.data.map { it[Keys.SIMPLE_MODE] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override val insightsPrefs: Flow<InsightsPrefs> = dataStore.data.map {
        InsightsPrefs(it[Keys.INSIGHTS_ENABLED] ?: false, it[Keys.INSIGHTS_DEVICE] ?: true, it[Keys.INSIGHTS_AUDIO_PATH] ?: true,
            it[Keys.INSIGHTS_PLACES] ?: false, it[Keys.INSIGHTS_LOCATION_HISTORY] ?: false)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override val onboardingSeen: Flow<Boolean?> = dataStore.data.map { it[Keys.ONBOARDING_SEEN] }.distinctUntilChanged()
    override suspend fun setOnboardingSeen() = dataStore.edit { it[Keys.ONBOARDING_SEEN] = true }
    // the last version whose what's new tour was shown or skipped
    override val whatsNewSeen: Flow<String?> = dataStore.data.map { it[Keys.WHATS_NEW_SEEN] }.distinctUntilChanged()
    override suspend fun setWhatsNewSeen(version: String) = dataStore.edit { it[Keys.WHATS_NEW_SEEN] = version }
    override val whatsNewEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.WHATS_NEW_ENABLED] ?: true }.distinctUntilChanged()
    override suspend fun setWhatsNewEnabled(enabled: Boolean) = dataStore.edit { it[Keys.WHATS_NEW_ENABLED] = enabled }
    override val updateAnnounced: Flow<String?> = dataStore.data.map { it[Keys.UPDATE_ANNOUNCED] }.distinctUntilChanged()
    override suspend fun setUpdateAnnounced(tag: String) = dataStore.edit { it[Keys.UPDATE_ANNOUNCED] = tag }
    override val privateSession: Flow<Boolean> = dataStore.data.map { it[Keys.PRIVATE_SESSION] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override val historicalListeningMs: Flow<Long> = dataStore.data.map { it[Keys.HISTORICAL_LISTENING_MS] ?: 0L }.distinctUntilChanged()
    override suspend fun setHistoricalListeningMs(ms: Long) = dataStore.edit {
        if (ms > 0) it[Keys.HISTORICAL_LISTENING_MS] = ms else it.remove(Keys.HISTORICAL_LISTENING_MS)
    }

    override suspend fun setOfflineMode(v: Boolean) = dataStore.edit { it[Keys.OFFLINE] = v }

    override suspend fun setDataSaver(v: Boolean) = dataStore.edit { it[Keys.DATA_SAVER] = v }
    override suspend fun setMobileDataDownloads(v: Boolean) = dataStore.edit { it[Keys.MOBILE_DATA_DOWNLOADS] = v }
    override suspend fun setInsightsEnabled(v: Boolean) = dataStore.edit { it[Keys.INSIGHTS_ENABLED] = v }
    override suspend fun setInsightsDevice(v: Boolean) = dataStore.edit { it[Keys.INSIGHTS_DEVICE] = v }
    override suspend fun setInsightsAudioPath(v: Boolean) = dataStore.edit { it[Keys.INSIGHTS_AUDIO_PATH] = v }
    override suspend fun setInsightsPlaces(v: Boolean) = dataStore.edit { it[Keys.INSIGHTS_PLACES] = v }
    override suspend fun setInsightsLocationHistory(v: Boolean) = dataStore.edit { it[Keys.INSIGHTS_LOCATION_HISTORY] = v }
    override suspend fun setSimpleMode(v: Boolean) = dataStore.edit { it[Keys.SIMPLE_MODE] = v }
    override suspend fun setPrivateSession(v: Boolean) = dataStore.edit { it[Keys.PRIVATE_SESSION] = v }
}
