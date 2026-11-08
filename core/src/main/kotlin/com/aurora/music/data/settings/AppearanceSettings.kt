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

interface AppearanceSettings {
    val uiPrefs: Flow<UiPrefs>
    val gesturePrefs: Flow<GesturePrefs>
    val haptics: Flow<Boolean>
    val musicHapticsPrefs: Flow<MusicHapticsPrefs>
    val visualizerPrefs: Flow<VisualizerPrefs>
    suspend fun setVisualizer(v: VisualizerPrefs): Preferences
    suspend fun setGestureSwipeArtwork(v: Boolean): Preferences
    suspend fun setGestureSwipeDismiss(v: Boolean): Preferences
    suspend fun setGestureDoubleTap(v: Boolean): Preferences
    suspend fun setHaptics(v: Boolean): Preferences
    suspend fun setMusicHaptics(v: Boolean): Preferences
    suspend fun setMusicHapticsIntensity(v: Float): Preferences
    suspend fun setMusicHapticsDetail(v: Int): Preferences
    suspend fun setMusicHapticsTexture(v: Boolean): Preferences
    suspend fun setMusicHapticsOffset(ms: Int): Preferences
    suspend fun setThemeMode(v: Int): Preferences
    suspend fun setThemeStyle(v: Int): Preferences
    suspend fun setAccentMode(v: Int): Preferences
    suspend fun setAccentPreset(v: Int): Preferences
    suspend fun setAccentColor(v: Long): Preferences
    suspend fun setFontScale(v: Float): Preferences
    suspend fun setTypeface(v: Int): Preferences
    suspend fun setCornerStyle(v: Int): Preferences
    suspend fun setPlayerSeekStyle(v: Int): Preferences
    suspend fun setLyricsSync(v: Int): Preferences
    suspend fun setLyricsTranslate(v: Boolean): Preferences
    suspend fun setPlayerWaveBars(v: Int): Preferences
    suspend fun setPlayerArtSize(v: Float): Preferences
    suspend fun setPlayerGradient(v: Float): Preferences
    suspend fun setPlayerShowUtilities(v: Boolean): Preferences
    suspend fun setMiniStyle(v: Int): Preferences
    suspend fun setMiniProgress(v: Int): Preferences
    suspend fun setLibraryColumns(v: Int): Preferences
    suspend fun setLibraryTabs(v: LibraryTabs): Preferences
    suspend fun setTabletSetting(setting: TabletSetting, v: Float): Preferences
    suspend fun resetTabletSettings(): Preferences
    suspend fun setNavLayout(v: String): Preferences
    suspend fun setHomeSectionHidden(id: String, hidden: Boolean): Preferences
}

internal class AppearanceSettingsStore(private val dataStore: DataStore<Preferences>) : AppearanceSettings {
    override val uiPrefs: Flow<UiPrefs> = dataStore.data.map { p ->
        UiPrefs(
            themeMode = p[Keys.UI_THEME_MODE] ?: ThemeMode.DARK,
            themeStyle = (p[Keys.UI_THEME_STYLE] ?: ThemeStyle.AURORA).coerceIn(ThemeStyle.AURORA, ThemeStyle.GLASS),
            accentMode = p[Keys.UI_ACCENT_MODE] ?: AccentMode.PRESET,
            accentPreset = p[Keys.UI_ACCENT_PRESET] ?: 0,
            accentColor = p[Keys.UI_ACCENT_COLOR] ?: 0xFFFF2E7EL,
            fontScale = p[Keys.UI_FONT_SCALE] ?: 1f,
            typeface = AppTypeface.normalize(p[Keys.UI_TYPEFACE] ?: AppTypeface.THEME_DEFAULT),
            cornerStyle = p[Keys.UI_CORNER_STYLE] ?: CornerStyle.DEFAULT,
            playerSeekStyle = p[Keys.UI_PLAYER_SEEK] ?: SeekStyle.WAVEFORM,
            playerWaveBars = p[Keys.UI_PLAYER_WAVE_BARS] ?: 60,
            playerArtSize = p[Keys.UI_PLAYER_ART] ?: 0.86f,
            playerGradient = p[Keys.UI_PLAYER_GRADIENT] ?: 1f,
            playerShowUtilities = p[Keys.UI_PLAYER_UTILITIES] ?: true,
            miniStyle = p[Keys.UI_MINI_STYLE] ?: MiniStyle.STANDARD,
            miniProgress = p[Keys.UI_MINI_PROGRESS] ?: MiniProgress.LINE,
            libraryColumns = p[Keys.UI_LIBRARY_COLUMNS] ?: 2,
            libraryTabs = LibraryTabs.decode(p[Keys.UI_LIBRARY_TABS]),
            hiddenHomeSections = p[Keys.UI_HIDDEN_HOME] ?: emptySet(),
            tabletDockScale = tabletValue(p, TabletSetting.DOCK),
            tabletPanelScale = tabletValue(p, TabletSetting.PANEL),
            tabletPanelSpacing = tabletValue(p, TabletSetting.PANEL_SPACING),
            tabletNavGap = tabletValue(p, TabletSetting.NAV_GAP),
            tabletPageMargin = tabletValue(p, TabletSetting.PAGE_MARGIN),
            tabletHeroScale = tabletValue(p, TabletSetting.HERO),
            tabletFavouriteScale = tabletValue(p, TabletSetting.FAVOURITE),
            tabletPlayerSplit = tabletValue(p, TabletSetting.PLAYER_SPLIT),
            navLayout = p[Keys.UI_NAV_LAYOUT] ?: "",
            lyricsSync = (p[Keys.UI_LYRICS_SYNC] ?: LyricsSync.WORD).coerceIn(LyricsSync.LINE, LyricsSync.VOCALS),
            lyricsTranslate = p[Keys.UI_LYRICS_TRANSLATE] ?: false,
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val gesturePrefs: Flow<GesturePrefs> = dataStore.data.map { p ->
        GesturePrefs(
            swipeArtwork = p[Keys.GESTURE_SWIPE_ART] ?: true,
            swipeDownDismiss = p[Keys.GESTURE_SWIPE_DISMISS] ?: true,
            doubleTapPause = p[Keys.GESTURE_DOUBLE_TAP] ?: true,
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override val haptics: Flow<Boolean> = dataStore.data.map { it[Keys.HAPTICS] ?: false }.distinctUntilChanged()
    override val musicHapticsPrefs: Flow<MusicHapticsPrefs> = dataStore.data.map { p ->
        MusicHapticsPrefs(
            enabled = p[Keys.MUSIC_HAPTICS] ?: false,
            intensity = (p[Keys.MUSIC_HAPTICS_INTENSITY] ?: 0.8f).coerceIn(0.2f, 1f),
            detail = (p[Keys.MUSIC_HAPTICS_DETAIL] ?: 1).coerceIn(0, 2),
            texture = p[Keys.MUSIC_HAPTICS_TEXTURE] ?: true,
            offsetMs = (p[Keys.MUSIC_HAPTICS_OFFSET_MS] ?: 0).coerceIn(-150, 150),
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val visualizerPrefs: Flow<VisualizerPrefs> = dataStore.data.map(::readVisualizerPrefs).distinctUntilChanged()

    override suspend fun setVisualizer(v: VisualizerPrefs) = dataStore.edit { p ->
        p[Keys.VIZ_STYLE] = v.style
        p[Keys.VIZ_COLOR_SOURCE] = v.colorSource
        p[Keys.VIZ_PRIMARY] = v.primaryColor
        p[Keys.VIZ_SECONDARY] = v.secondaryColor
        p[Keys.VIZ_BACKGROUND] = v.background
        p[Keys.VIZ_BAR_COUNT] = v.barCount
        p[Keys.VIZ_SMOOTHING] = v.smoothing
        p[Keys.VIZ_SENSITIVITY] = v.sensitivity
        p[Keys.VIZ_MIN_HZ] = v.minHz
        p[Keys.VIZ_MAX_HZ] = v.maxHz
        p[Keys.VIZ_PEAK_HOLD] = v.peakHold
        p[Keys.VIZ_MIRROR] = v.mirror
        p[Keys.VIZ_FFT_SIZE] = v.fftSize
        p[Keys.VIZ_FPS] = v.fpsCap
        p[Keys.VIZ_ROTATE] = v.rotate
        p[Keys.VIZ_PARTICLES] = v.particleCount
        p[Keys.VIZ_ALBUM_ART] = v.showAlbumArt
        p[Keys.VIZ_TRACK_INFO] = v.showTrackInfo
    }

    override suspend fun setGestureSwipeArtwork(v: Boolean) = dataStore.edit { it[Keys.GESTURE_SWIPE_ART] = v }
    override suspend fun setGestureSwipeDismiss(v: Boolean) = dataStore.edit { it[Keys.GESTURE_SWIPE_DISMISS] = v }
    override suspend fun setGestureDoubleTap(v: Boolean) = dataStore.edit { it[Keys.GESTURE_DOUBLE_TAP] = v }
    override suspend fun setHaptics(v: Boolean) = dataStore.edit { it[Keys.HAPTICS] = v }
    override suspend fun setMusicHaptics(v: Boolean) = dataStore.edit { it[Keys.MUSIC_HAPTICS] = v }
    override suspend fun setMusicHapticsIntensity(v: Float) = dataStore.edit { it[Keys.MUSIC_HAPTICS_INTENSITY] = v.coerceIn(0.2f, 1f) }
    override suspend fun setMusicHapticsDetail(v: Int) = dataStore.edit { it[Keys.MUSIC_HAPTICS_DETAIL] = v.coerceIn(0, 2) }
    override suspend fun setMusicHapticsTexture(v: Boolean) = dataStore.edit { it[Keys.MUSIC_HAPTICS_TEXTURE] = v }
    override suspend fun setMusicHapticsOffset(ms: Int) = dataStore.edit { it[Keys.MUSIC_HAPTICS_OFFSET_MS] = ms.coerceIn(-150, 150) }

    override suspend fun setThemeMode(v: Int) = dataStore.edit { it[Keys.UI_THEME_MODE] = v }
    override suspend fun setThemeStyle(v: Int) = dataStore.edit { it[Keys.UI_THEME_STYLE] = v.coerceIn(ThemeStyle.AURORA, ThemeStyle.GLASS) }
    override suspend fun setAccentMode(v: Int) = dataStore.edit { it[Keys.UI_ACCENT_MODE] = v }
    override suspend fun setAccentPreset(v: Int) = dataStore.edit { it[Keys.UI_ACCENT_PRESET] = v }
    override suspend fun setAccentColor(v: Long) = dataStore.edit { it[Keys.UI_ACCENT_COLOR] = v }
    override suspend fun setFontScale(v: Float) = dataStore.edit { it[Keys.UI_FONT_SCALE] = v }
    override suspend fun setTypeface(v: Int) = dataStore.edit { it[Keys.UI_TYPEFACE] = AppTypeface.normalize(v) }
    override suspend fun setCornerStyle(v: Int) = dataStore.edit { it[Keys.UI_CORNER_STYLE] = v }
    override suspend fun setPlayerSeekStyle(v: Int) = dataStore.edit { it[Keys.UI_PLAYER_SEEK] = v }
    override suspend fun setLyricsSync(v: Int) = dataStore.edit { it[Keys.UI_LYRICS_SYNC] = v }
    override suspend fun setLyricsTranslate(v: Boolean) = dataStore.edit { it[Keys.UI_LYRICS_TRANSLATE] = v }
    override suspend fun setPlayerWaveBars(v: Int) = dataStore.edit { it[Keys.UI_PLAYER_WAVE_BARS] = v }
    override suspend fun setPlayerArtSize(v: Float) = dataStore.edit { it[Keys.UI_PLAYER_ART] = v }
    override suspend fun setPlayerGradient(v: Float) = dataStore.edit { it[Keys.UI_PLAYER_GRADIENT] = v }
    override suspend fun setPlayerShowUtilities(v: Boolean) = dataStore.edit { it[Keys.UI_PLAYER_UTILITIES] = v }
    override suspend fun setMiniStyle(v: Int) = dataStore.edit { it[Keys.UI_MINI_STYLE] = v }
    override suspend fun setMiniProgress(v: Int) = dataStore.edit { it[Keys.UI_MINI_PROGRESS] = v }
    override suspend fun setLibraryColumns(v: Int) = dataStore.edit { it[Keys.UI_LIBRARY_COLUMNS] = v }
    override suspend fun setLibraryTabs(v: LibraryTabs) = dataStore.edit { it[Keys.UI_LIBRARY_TABS] = v.encode() }
    override suspend fun setTabletSetting(setting: TabletSetting, v: Float) =
        dataStore.edit { it[Keys.tablet(setting)] = v.coerceIn(setting.range) }
    private fun tabletValue(p: Preferences, setting: TabletSetting): Float =
        (p[Keys.tablet(setting)] ?: setting.default).coerceIn(setting.range)
    override suspend fun resetTabletSettings() = dataStore.edit { p -> TabletSetting.entries.forEach { p.remove(Keys.tablet(it)) } }
    override suspend fun setNavLayout(v: String) = dataStore.edit { it[Keys.UI_NAV_LAYOUT] = v }
    override suspend fun setHomeSectionHidden(id: String, hidden: Boolean) = dataStore.edit { p ->
        val set = (p[Keys.UI_HIDDEN_HOME] ?: emptySet()).toMutableSet()
        if (hidden) set.add(id) else set.remove(id)
        p[Keys.UI_HIDDEN_HOME] = set
    }
}
