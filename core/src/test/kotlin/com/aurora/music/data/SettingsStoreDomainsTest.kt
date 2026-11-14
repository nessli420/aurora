package com.aurora.music.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.routes.ProcessingRoute
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.extensions.ExtensionCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsStoreDomainsTest {
    private val directory = Files.createTempDirectory("aurora-settings-domains-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(name: String) =
        SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "$name.preferences_pb") }, directory, directory)

    @After fun clean() { scope.cancel(); directory.deleteRecursively() }

    @Test fun processingStoresShareOneRouteMonitor() = runBlocking {
        val store = store("a")
        val key = "android:1:" + "a".repeat(64)
        store.processingRoutes.publish(ProcessingRoute(ProcessingRouteKind.ANDROID, key, "DAC"))
        store.bindEqToCurrentOutput("HD 600", -3f, listOf(ParamBand(1000f, 2f, 1f))).getOrThrow()
        store.setCrossfade(3)
        assertEquals(setOf(key), store.processingRouteRules.first().manual)
        assertEquals(3, store.playbackPrefs.first().crossfadeSec)
    }

    @Test fun backupRestoresEveryDomain() = runBlocking {
        val a = store("a")
        val session = Session("https://music.example", "alice", "salt", "token")
        a.saveSession(session)
        a.setExtensionGrants(ExtensionCodec.encode(emptyList()))
        a.addSavedSession(session)
        a.setLocalProfile(LocalProfile("Maren"))
        a.saveLastfm("sk", "u", "img")
        a.setSquigTarget("T")
        a.setThemeMode(ThemeMode.AMOLED)
        a.setTabletSetting(TabletSetting.HERO, 0.7f)
        a.setMusicHapticsIntensity(0.5f)
        a.setCrossfade(6)
        a.setStreamWifi(320)
        a.setAlarm(true, 6, 30)
        a.setAiDjSpoken(false)
        a.setUnifiedLibrary(true)
        a.setSourcePriority(listOf("stream", "local"))
        a.setMergeFeatures(MergeFeatures(canvas = false))
        a.togglePin(Pin("1", "album", "Title"))
        a.saveRadioStation(RadioStation("u", "Station"))
        a.addRecentSearch("query")
        a.setPlaylistLiked("p", true)
        a.setSongHidden(HiddenSong("nd1", "Song", "Artist", "Album", 200), true)
        a.setSimpleMode(true)
        a.setMobileDataDownloads(true)
        a.setInsightsEnabled(true)
        a.setWhatsNewSeen("1.0")
        a.setDspPreamp(-3f)
        a.setEqBands(listOf(1, 2, 3))
        a.setReverb(ReverbPrefs(enabled = true))
        a.migrateProcessingRack().getOrThrow()
        a.saveProcessingPreset("Preset").getOrThrow()
        a.setRouteRulesEnabled(true).getOrThrow()
        a.deleteRackSubchain("none").getOrThrow()

        val backup = a.exportPrefs()
        val b = store("b")
        b.restoreBackupPrefs(backup).getOrThrow()
        assertEquals(backup, b.exportPrefs())
        val flows = listOf<(SettingsStore) -> Flow<*>>(SettingsStore::session, SettingsStore::savedSessions, SettingsStore::localProfile,
            SettingsStore::extensionGrants, SettingsStore::lastfm, SettingsStore::squigTarget, SettingsStore::uiPrefs, SettingsStore::musicHapticsPrefs,
            SettingsStore::playbackPrefs, SettingsStore::alarmPrefs, SettingsStore::aiDjSpoken, SettingsStore::unifiedLibrary,
            SettingsStore::sourcePriority, SettingsStore::mergeFeatures, SettingsStore::pins, SettingsStore::radioFavorites,
            SettingsStore::recentSearches, SettingsStore::likedPlaylists, SettingsStore::hiddenSongs, SettingsStore::simpleMode, SettingsStore::mobileDataDownloads, SettingsStore::insightsPrefs,
            SettingsStore::whatsNewSeen, SettingsStore::audioPrefs, SettingsStore::reverbPrefs, SettingsStore::processingRack,
            SettingsStore::processingPresetLibrary, SettingsStore::processingRouteRules, SettingsStore::rackSubchains)
        for (flow in flows) assertEquals(flow(a).first(), flow(b).first())
        assertEquals(session, b.session.first())
        assertEquals(6, b.playbackPrefs.first().crossfadeSec)
        assertEquals(1, b.processingPresetLibrary.first().presets.size)
        assertEquals(listOf("nd1"), b.hiddenSongs.first().map { it.id })
    }

}
