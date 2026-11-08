package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface PlaybackSettings {
    val audioCachePrefs: Flow<com.aurora.music.data.cache.AudioCachePrefs>
    suspend fun setAudioCacheEnabled(enabled: Boolean): Preferences
    suspend fun setAudioCacheLimit(mb: Int)
    val alarmPrefs: Flow<AlarmPrefs>
    val playbackPrefs: Flow<PlaybackPrefs>
    val aiDjSpoken: Flow<Boolean>
    suspend fun setAiDjSpoken(v: Boolean): Preferences
    val connectSwitch: Flow<Int>
    suspend fun setConnectSwitch(v: Int): Preferences
    suspend fun setSkipSilence(v: Boolean): Preferences
    suspend fun setCrossfade(sec: Int): Preferences
    suspend fun setCrossfadeCurve(curve: String): Preferences
    suspend fun setCrossfadeHeadroom(on: Boolean): Preferences
    suspend fun setGapless(v: Boolean): Preferences
    suspend fun setDefaultSpeed(v: Float): Preferences
    suspend fun setMono(v: Boolean): Preferences
    suspend fun setStreamWifi(v: Int): Preferences
    suspend fun setStreamCellular(v: Int): Preferences
    suspend fun setDownloadBitrate(v: Int): Preferences
    val youTubeDownloadPrefs: Flow<YouTubeDownloadPrefs>
    suspend fun setYouTubeDownloadFormat(format: YouTubeDownloadFormat): Preferences
    suspend fun setYouTubeDownloadBitrate(kbps: Int)
    suspend fun setYouTubeDownloadAnonymous(enabled: Boolean): Preferences
    suspend fun setYouTubeDownloadArtwork(enabled: Boolean): Preferences
    suspend fun setPreferHighRes(v: Boolean): Preferences
    suspend fun setBitPerfectUsb(v: Boolean): Preferences
    suspend fun setUsbOutputMode(v: UsbOutputMode): Preferences
    suspend fun setUsbDsdMode(v: UsbDsdMode)
    suspend fun setUsbDsdExperimental(v: Boolean)
    suspend fun setUsbHardwareVolume(v: Boolean)
    suspend fun setUsbFallbackPolicy(v: UsbFallbackPolicy): Preferences
    suspend fun setIndependentOutput(v: Boolean): Preferences
    suspend fun setScrobble(v: Boolean): Preferences
    suspend fun setAutoplayRadio(v: Boolean): Preferences
    suspend fun setResumePodcasts(v: Boolean): Preferences
    suspend fun setResumeLongTracks(v: Boolean): Preferences
    suspend fun setLongFormSkipButtons(v: Boolean): Preferences
    suspend fun setAlarm(enabled: Boolean, hour: Int, minute: Int): Preferences
}

internal class PlaybackSettingsStore(private val dataStore: DataStore<Preferences>, private val editManualProcessing: suspend ((MutablePreferences) -> Unit) -> Preferences) : PlaybackSettings {
    override val audioCachePrefs: Flow<com.aurora.music.data.cache.AudioCachePrefs> = dataStore.data.map {
        com.aurora.music.data.cache.AudioCachePrefs(it[Keys.AUDIO_CACHE_ENABLED] ?: true,
            (it[Keys.AUDIO_CACHE_LIMIT] ?: 1024).takeIf { mb -> mb in com.aurora.music.data.cache.AudioCachePrefs.limitsMb } ?: 1024)
    }.distinctUntilChanged()

    override suspend fun setAudioCacheEnabled(enabled: Boolean) = dataStore.edit { it[Keys.AUDIO_CACHE_ENABLED] = enabled }
    override suspend fun setAudioCacheLimit(mb: Int) {
        require(mb in com.aurora.music.data.cache.AudioCachePrefs.limitsMb)
        dataStore.edit { it[Keys.AUDIO_CACHE_LIMIT] = mb }
    }

    override val alarmPrefs: Flow<AlarmPrefs> = dataStore.data.map { p ->
        AlarmPrefs(
            enabled = p[Keys.ALARM_ENABLED] ?: false,
            hour = p[Keys.ALARM_HOUR] ?: 7,
            minute = p[Keys.ALARM_MINUTE] ?: 0,
        )
    }.distinctUntilChanged()

    override val playbackPrefs: Flow<PlaybackPrefs> = dataStore.data.map(::readPlaybackPrefs).distinctUntilChanged().flowOn(Dispatchers.Default)

    override val aiDjSpoken: Flow<Boolean> = dataStore.data.map { it[Keys.AI_DJ_SPOKEN] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override suspend fun setAiDjSpoken(v: Boolean) = dataStore.edit { it[Keys.AI_DJ_SPOKEN] = v }

    override val connectSwitch: Flow<Int> = dataStore.data.map { it[Keys.CONNECT_SWITCH]?.coerceIn(0, 2) ?: com.aurora.music.playback.network.endpoint.ConnectSwitch.PAUSE }.distinctUntilChanged()
    override suspend fun setConnectSwitch(v: Int) = dataStore.edit { it[Keys.CONNECT_SWITCH] = v.coerceIn(0, 2) }

    override suspend fun setSkipSilence(v: Boolean) = editManualProcessing { it[Keys.SKIP_SILENCE] = v }
    override suspend fun setCrossfade(sec: Int) = editManualProcessing { it[Keys.CROSSFADE] = sec.coerceIn(0, 30) }
    override suspend fun setCrossfadeCurve(curve: String) = editManualProcessing { it[Keys.CROSSFADE_CURVE] = curve }
    override suspend fun setCrossfadeHeadroom(on: Boolean) = editManualProcessing { it[Keys.CROSSFADE_HEADROOM] = on }
    override suspend fun setGapless(v: Boolean) = editManualProcessing { it[Keys.GAPLESS] = v }
    override suspend fun setDefaultSpeed(v: Float) = editManualProcessing { it[Keys.DEFAULT_SPEED] = v }
    override suspend fun setMono(v: Boolean) = editManualProcessing { it[Keys.MONO] = v }
    override suspend fun setStreamWifi(v: Int) = dataStore.edit { it[Keys.STREAM_WIFI] = v }
    override suspend fun setStreamCellular(v: Int) = dataStore.edit { it[Keys.STREAM_CELLULAR] = v }
    override suspend fun setDownloadBitrate(v: Int) = dataStore.edit { it[Keys.DOWNLOAD_BITRATE] = v }

    override val youTubeDownloadPrefs: Flow<YouTubeDownloadPrefs> = dataStore.data.map { p ->
        YouTubeDownloadPrefs(
            format = YouTubeDownloadFormat.entries.firstOrNull { it.name == p[Keys.YOUTUBE_DOWNLOAD_FORMAT] } ?: YouTubeDownloadFormat.OPUS,
            maxBitrateKbps = (p[Keys.YOUTUBE_DOWNLOAD_BITRATE] ?: 0).takeIf { it in YouTubeDownloadPrefs.bitrates } ?: 0,
            anonymous = p[Keys.YOUTUBE_DOWNLOAD_ANONYMOUS] ?: true,
            embedArtwork = p[Keys.YOUTUBE_DOWNLOAD_ARTWORK] ?: true,
        )
    }.distinctUntilChanged()

    override suspend fun setYouTubeDownloadFormat(format: YouTubeDownloadFormat) = dataStore.edit { it[Keys.YOUTUBE_DOWNLOAD_FORMAT] = format.name }
    override suspend fun setYouTubeDownloadBitrate(kbps: Int) {
        require(kbps in YouTubeDownloadPrefs.bitrates)
        dataStore.edit { it[Keys.YOUTUBE_DOWNLOAD_BITRATE] = kbps }
    }
    override suspend fun setYouTubeDownloadAnonymous(enabled: Boolean) = dataStore.edit { it[Keys.YOUTUBE_DOWNLOAD_ANONYMOUS] = enabled }
    override suspend fun setYouTubeDownloadArtwork(enabled: Boolean) = dataStore.edit { it[Keys.YOUTUBE_DOWNLOAD_ARTWORK] = enabled }
    override suspend fun setPreferHighRes(v: Boolean) = editManualProcessing { it[Keys.PREFER_HIRES] = v }
    override suspend fun setBitPerfectUsb(v: Boolean) = editManualProcessing { it[Keys.BIT_PERFECT_USB] = v }
    override suspend fun setUsbOutputMode(v: UsbOutputMode) = editManualProcessing { it[Keys.USB_OUTPUT_MODE] = v.name }
    override suspend fun setUsbDsdMode(v: UsbDsdMode) { dataStore.edit { it[Keys.USB_DSD_MODE] = v.name } }
    override suspend fun setUsbDsdExperimental(v: Boolean) { dataStore.edit { it[Keys.USB_DSD_EXPERIMENTAL] = v } }
    override suspend fun setUsbHardwareVolume(v: Boolean) { dataStore.edit { it[Keys.USB_HARDWARE_VOLUME] = v } }
    override suspend fun setUsbFallbackPolicy(v: UsbFallbackPolicy) = editManualProcessing { it[Keys.USB_FALLBACK_POLICY] = v.name }
    override suspend fun setIndependentOutput(v: Boolean) = editManualProcessing { it[Keys.INDEPENDENT_OUTPUT] = v }
    override suspend fun setScrobble(v: Boolean) = dataStore.edit { it[Keys.SCROBBLE] = v }
    override suspend fun setAutoplayRadio(v: Boolean) = dataStore.edit { it[Keys.AUTOPLAY_RADIO] = v }
    override suspend fun setResumePodcasts(v: Boolean) = dataStore.edit { it[Keys.RESUME_PODCASTS] = v }
    override suspend fun setResumeLongTracks(v: Boolean) = dataStore.edit { it[Keys.RESUME_LONG_TRACKS] = v }
    override suspend fun setLongFormSkipButtons(v: Boolean) = dataStore.edit { it[Keys.LONG_FORM_SKIP_BUTTONS] = v }

    override suspend fun setAlarm(enabled: Boolean, hour: Int, minute: Int) = dataStore.edit {
        it[Keys.ALARM_ENABLED] = enabled; it[Keys.ALARM_HOUR] = hour; it[Keys.ALARM_MINUTE] = minute
    }
}
