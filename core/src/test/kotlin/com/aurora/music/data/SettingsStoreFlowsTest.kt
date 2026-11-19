package com.aurora.music.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.aurora.music.data.routes.ProcessingRouteRules
import com.aurora.music.data.rules.PresetRuleInput
import com.aurora.music.data.rules.PresetRuleSet
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class SettingsStoreFlowsTest {
    private val directory = Files.createTempDirectory("aurora-settings-flows-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val edits = AtomicInteger()
    private val real = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "aurora_settings.preferences_pb") }
    private val counted = object : DataStore<Preferences> by real {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            edits.incrementAndGet()
            return real.updateData(transform)
        }
    }
    private val store = SettingsStore(counted, directory, directory)

    @After fun clean() { scope.cancel(); directory.deleteRecursively() }

    private fun input() = PresetRuleInput(store.presetRuleContext.current, store.processingRoutes.current, PresetRuleSet(), ProcessingRouteRules())

    @Test fun savedListsRoundTripWithTheirElementTypes() = runBlocking {
        val pin = Pin("1", "album", "Title")
        store.togglePin(pin)
        assertEquals(listOf(pin), store.pins.first())
        val binding = EqBinding("usb", "USB", "HD 600", -3f, listOf(ParamBand(1000f, 2f, 1f)))
        store.upsertEqBinding(binding)
        assertEquals(listOf(binding), store.eqBindings.first())
        val smart = SmartPlaylist("s", "Smart")
        store.saveSmartPlaylist(smart)
        assertEquals(listOf(smart), store.smartPlaylists.first())
        val station = RadioStation("u", "Station")
        store.saveRadioStation(station)
        assertEquals(listOf(station), store.radioFavorites.first())
        val podcast = Podcast("https://feed.example", "Show")
        store.savePodcast(podcast)
        assertEquals(listOf(podcast), store.podcastSubs.first())
        store.addRecentSearch("query")
        assertEquals(listOf("query"), store.recentSearches.first())
    }

    @Test fun pageLayoutDefaultsToClassicAndClampsUnknownValues() = runBlocking {
        assertEquals(PageLayout.CLASSIC, store.uiPrefs.first().pageLayout)
        store.setPageLayout(PageLayout.SPOTLIGHT)
        assertEquals(PageLayout.SPOTLIGHT, store.uiPrefs.first().pageLayout)
        store.setPageLayout(99)
        assertEquals(PageLayout.DRIFT, store.uiPrefs.first().pageLayout)
    }

    @Test fun barsFollowTheLayoutOnlyWhileMatching() = runBlocking {
        assertEquals(BarStyle.CLASSIC, store.uiPrefs.first().bars)
        store.setPageLayout(PageLayout.CONSOLE)
        assertEquals(BarStyle.CLASSIC, store.uiPrefs.first().bars)
        store.setBarsMatchLayout(true)
        assertEquals(BarStyle.CONSOLE, store.uiPrefs.first().bars)
        store.setBarStyle(BarStyle.DRIFT)
        assertEquals(false, store.uiPrefs.first().barsMatchLayout)
        assertEquals(BarStyle.DRIFT, store.uiPrefs.first().bars)
    }

    @Test fun searchFollowsTheLayoutUntilItGetsItsOwn() = runBlocking {
        store.setPageLayout(PageLayout.DRIFT)
        store.setSearchStyle(PageLayout.CONSOLE)
        assertEquals(PageLayout.DRIFT, store.uiPrefs.first().searchLayout)
        store.setSearchOwnLayout(true)
        assertEquals(PageLayout.CONSOLE, store.uiPrefs.first().searchLayout)
    }

    @Test fun playerStyleDefaultsToClassicAndClampsUnknownValues() = runBlocking {
        assertEquals(PlayerStyle.CLASSIC, store.uiPrefs.first().playerStyle)
        store.setPlayerStyle(PlayerStyle.CONSOLE)
        assertEquals(PlayerStyle.CONSOLE, store.uiPrefs.first().playerStyle)
        store.setPlayerStyle(99)
        assertEquals(PlayerStyle.DRIFT, store.uiPrefs.first().playerStyle)
    }

    @Test fun savedSessionsKeepTheValidityFilter() = runBlocking {
        val valid = Session("https://music.example", "alice", "salt", "token")
        store.importPrefs(PrefsBackup(strings = mapOf("saved_sessions" to Gson().toJson(listOf(valid, valid.copy(token = ""))))))
        assertEquals(listOf(valid), store.savedSessions.first())
    }

    @Test fun aBlankSoundCloudClientIdKeepsTheSavedOne() = runBlocking {
        store.setSoundcloudClientId("abc")
        store.setSoundcloudClientId(" ")
        assertEquals("abc", store.soundcloudClientId.first())
    }

    @Test fun noRulesAndNoSessionSkipTheEdit() = runBlocking {
        assertNull(store.transitionPresetRule(input()).getOrThrow())
        assertEquals(0, edits.get())
    }

    @Test fun aSavedSessionStillReachesTheEdit() = runBlocking {
        store.importPrefs(PrefsBackup(strings = mapOf("processing_rule_session_v1" to "{broken")))
        val before = edits.get()
        store.transitionPresetRule(input())
        assertEquals(before + 1, edits.get())
    }
}
