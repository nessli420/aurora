package com.aurora.music.ui.settings.general

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.aurora.music.data.AccentMode
import com.aurora.music.data.BackupManager
import com.aurora.music.data.LocalStore
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SignalPath
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.data.accountKey
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalTrayAvailable
import com.aurora.music.localization.AppStrings
import com.aurora.music.ui.screens.settings.AboutSettingsScreen
import com.aurora.music.ui.screens.settings.AccountsScreen
import com.aurora.music.ui.screens.settings.AppearanceScreen
import com.aurora.music.ui.screens.settings.AudioOutputSettingsScreen
import com.aurora.music.ui.screens.settings.BackupScreen
import com.aurora.music.ui.screens.settings.IntegrationsSettingsScreen
import com.aurora.music.ui.screens.settings.LanguageSettingsScreen
import com.aurora.music.ui.screens.settings.LastfmIntegrationScreen
import com.aurora.music.ui.screens.settings.ListenBrainzIntegrationScreen
import com.aurora.music.ui.screens.settings.NavigationMenuScreen
import com.aurora.music.ui.screens.settings.PlaybackSettingsScreen
import com.aurora.music.ui.screens.settings.SettingsScreen
import com.aurora.music.ui.screens.settings.SourcesSettingsScreen
import com.aurora.music.ui.screens.settings.StorageSettingsScreen
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.ui.testing.inkRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GeneralSettingsTest {
    private val padding = PaddingValues(bottom = 24.dp)
    private val navidrome = Session("https://music.example.com", "mara", "salt", "token")
    private val jellyfin = Session("http://192.168.1.20:8096", "mara", "", "jf", ServerType.JELLYFIN, userId = "u1")
    private val local = AccountAuthenticator.LOCAL_SESSION

    private fun root(name: String, simpleMode: Boolean = false, backup: Boolean = true, tray: Boolean = true, height: Int = 1800, opened: MutableList<String>) =
        GeneralSettingsScene(name, width = 380, height = height, seed = {
            settingsStore.saveSession(navidrome)
            settingsStore.setSimpleMode(simpleMode)
        }) {
            CompositionLocalProvider(LocalTrayAvailable provides tray) {
                SettingsScreen(
                    contentPadding = padding, username = "Mara", server = navidrome.server, onBack = {},
                    onOpenPlayback = { opened += "playback" }, onOpenOutput = { opened += "output" }, onOpenLoudness = { opened += "loudness" },
                    onOpenAdvancedAudio = { opened += "advanced" }, onOpenSignalPath = { opened += "signal" }, onOpenEq = { opened += "eq" },
                    onOpenVisualizer = { opened += "visualizer" }, onOpenSonic = { opened += "sonic" }, onOpenSources = { opened += "sources" },
                    onOpenDownloads = { opened += "storage" }, onOpenAppearance = { opened += "appearance" }, onOpenLanguage = { opened += "language" },
                    onOpenIntegrations = { opened += "integrations" }, onOpenAbout = { opened += "about" }, onOpenProfile = { opened += "profile" },
                    onOpenAccounts = { opened += "accounts" }, onOpenBackup = if (backup) ({ opened += "backup" }) else null, onLogout = { opened += "logout" },
                )
            }
        }

    @Test fun settingsRootOpensDesktopDestinations() {
        val opened = mutableListOf<String>()
        val full = root("root", opened = opened).use { scene ->
            val image = scene.shot()
            scene.click(190f, 220f)
            scene.click(190f, 610f)
            scene.click(190f, 756f)
            scene.click(190f, 1244f)
            assertEquals(listOf("accounts", "output", "loudness", "integrations"), opened)
            image
        }
        assertTrue(full.distinctColors() > 20)

        opened.clear()
        root("root-simple", simpleMode = true, opened = opened).use { scene ->
            assertTrue(scene.shot().differsFrom(full))
            scene.click(190f, 756f)
            assertFalse("loudness" in opened)
        }
        val withoutBackup = root("root-no-backup", backup = false, opened = mutableListOf()).use { it.shot() }
        assertTrue(withoutBackup.differsFrom(full))
    }

    @Test fun closeToTrayIsOnlyOfferedWhenThereIsATray() {
        val withTray = root("root-tray", height = 2600, opened = mutableListOf()).use { it.shot() }
        val withoutTray = root("root-no-tray", tray = false, height = 2600, opened = mutableListOf()).use { it.shot() }
        assertTrue(withoutTray.inkRows() < withTray.inkRows())
    }

    @Test fun signalPathRowSummarisesTheLivePath() {
        root("root-signal", opened = mutableListOf()).use { scene ->
            val idle = scene.shot()
            assertFalse(scene.shot().differsFrom(idle))
            scene.player.signalPath.value = SignalPath(active = true, codec = "FLAC", sampleRateHz = 44_100, output = "WASAPI exclusive")
            assertTrue(scene.shot("-playing").differsFrom(idle))
        }
    }

    @Test fun playbackHidesStreamingForLocalAndRevealsFadeControls() {
        val plain = GeneralSettingsScene("playback-plain", height = 1400) {
            PlaybackSettingsScreen(padding, onBack = {}, onOpenOutput = {}, onOpenLoudness = {}, onOpenEq = {})
        }.use { it.shot() }
        val crossfade = GeneralSettingsScene("playback", height = 1400, seed = { settingsStore.setCrossfade(6) }) {
            PlaybackSettingsScreen(padding, onBack = {}, onOpenOutput = {}, onOpenLoudness = {}, onOpenEq = {})
        }.use { it.shot() }
        val localOnly = GeneralSettingsScene("playback-local", height = 1400, seed = {
            settingsStore.saveSession(local)
            withTimeout(10_000) { while (!isLocal) delay(10) }
        }) {
            PlaybackSettingsScreen(padding, onBack = {}, onOpenOutput = {}, onOpenLoudness = {}, onOpenEq = {})
        }.use { it.shot() }
        assertTrue(crossfade.inkRows() > plain.inkRows())
        assertTrue(localOnly.inkRows() < plain.inkRows())
    }

    @Test fun appearancePicksAccentAndThemeStyle() {
        GeneralSettingsScene("appearance", height = 1600) { AppearanceScreen(padding, onBack = {}) }.use { scene ->
            val aurora = scene.shot()
            assertTrue(aurora.distinctColors() > 20)
            scene.click(419f, 751f)
            assertEquals(AccentMode.CUSTOM, scene.await(read = { settingsStore.uiPrefs.first().accentMode }) { it == AccentMode.CUSTOM })
            scene.shot("-custom")
            scene.click(617f, 220f)
            assertEquals(ThemeStyle.RETRO, scene.await(read = { settingsStore.uiPrefs.first().themeStyle }) { it == ThemeStyle.RETRO })
            assertTrue(scene.shot("-retro").differsFrom(aurora))
        }
    }

    @Test fun accountsSwitchAndForgetSavedLogins() {
        val switched = mutableListOf<Session>()
        val forgotten = mutableListOf<Session>()
        GeneralSettingsScene("accounts", seed = {
            settingsStore.addSavedSession(jellyfin)
            settingsStore.saveSession(navidrome)
        }) { AccountsScreen(padding, onBack = {}, onSwitch = { switched += it }, onForget = { forgotten += it }, onAddAccount = {}) }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(400f, 160f)
            scene.click(400f, 225f)
            scene.click(783f, 160f)
            scene.click(400f, 290f)
        }
        assertEquals(listOf(jellyfin.accountKey(), local.accountKey()), switched.map { it.accountKey() })
        assertEquals(listOf(jellyfin), forgotten)
    }

    @Test fun aboutShowsNoticesInADialog() {
        GeneralSettingsScene("about") { AboutSettingsScreen(padding, onBack = {}) }.use { scene ->
            val page = scene.shot()
            assertTrue(page.distinctColors() > 20)
            scene.click(93f, 360f)
            assertTrue(scene.shot("-ffmpeg").differsFrom(page))
        }
    }

    @Test fun languageChoiceAppliesAndPersists() {
        try {
            GeneralSettingsScene("language") { LanguageSettingsScreen(padding, onBack = {}) }.use { scene ->
                val english = scene.shot()
                scene.click(112f, 263f)
                assertEquals("ru", AppStrings.languageTag.value)
                assertEquals("ru", scene.await(read = { desktopSettings.languageTag.first() }) { it == "ru" })
                assertTrue(scene.shot("-ru").differsFrom(english))
            }
        } finally { AppStrings.setLocale("") }
    }

    @Test fun navigationMenuReordersAndPins() {
        GeneralSettingsScene("nav-menu", height = 1300) { NavigationMenuScreen(padding, onBack = {}) }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(691f, 508f)
            scene.await(read = { settingsStore.uiPrefs.first().navLayout }) { it.contains("more=stats,history,duplicates") }
            scene.click(697f, 690f)
            scene.await(read = { settingsStore.uiPrefs.first().navLayout }) { it.startsWith("main=home,search,library,playback;") }
        }
    }

    @Test fun storageListsDesktopFolders() {
        GeneralSettingsScene("storage") { StorageSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun integrationsOpenDetailPagesAndToggleArtwork() {
        val opened = mutableListOf<String>()
        GeneralSettingsScene("integrations") {
            IntegrationsSettingsScreen(padding, onBack = {}, onOpenLyrics = { opened += "lyrics" }, onOpenLastfm = { opened += "lastfm" },
                onOpenListenBrainz = { opened += "listenbrainz" }, onOpenArtistInfo = { opened += "artist" })
        }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(400f, 131f)
            scene.click(400f, 308f)
            scene.click(400f, 490f)
            assertFalse(scene.await(read = { settingsStore.artworkLookupEnabled.first() }) { !it })
        }
        assertEquals(listOf("lastfm", "lyrics"), opened)
        GeneralSettingsScene("integrations-lastfm") { LastfmIntegrationScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 10) }
        GeneralSettingsScene("integrations-listenbrainz") { ListenBrainzIntegrationScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 10) }
    }

    @Test fun sourcesManageFoldersAndMergedServers() {
        val music = createTempDirectory("aurora-music").toFile()
        val missing = File(music, "missing")
        try {
            GeneralSettingsScene("sources", height = 1200, seed = {
                desktopSettings.setMusicFolders(listOf(music.path, missing.path))
                settingsStore.addSavedSession(jellyfin)
                settingsStore.saveSession(navidrome)
                settingsStore.setUnifiedLibrary(true)
            }) { SourcesSettingsScreen(padding, onBack = {}, onArtistSeparators = {}) }.use { scene ->
                assertTrue(scene.shot().distinctColors() > 20)
                scene.click(771f, 1070f)
                assertEquals(setOf(navidrome.accountKey()), scene.await(read = { settingsStore.mergeSources.first() }) { it.isNotEmpty() })
                scene.click(785f, 219f)
                assertEquals(listOf(music.absolutePath), scene.await(read = { desktopSettings.musicFolders.first() }) { it.size == 1 })
                scene.shot("-edited")
            }
        } finally { music.deleteRecursively() }
    }

    @Test fun audioOutputDrivesDeviceExclusiveAndBuffer() {
        val player = FakePlayer()
        GeneralSettingsScene("output", height = 1500, player = player) { AudioOutputSettingsScreen(padding, onBack = {}, onOpenSignalPath = {}) }.use { scene ->
            val shared = scene.shot()
            scene.click(400f, 285f)
            assertEquals("dac", player.preferredOutput.value)
            scene.click(400f, 159f)
            assertNull(player.preferredOutput.value)
            scene.click(231f, 644f)
            assertEquals(100, scene.await(read = { desktopSettings.outputBufferMs.first() }) { it == 100 })
            scene.click(400f, 497f)
            assertTrue(player.exclusiveOutput.value)
            assertTrue(scene.shot("-exclusive").inkRows() > shared.inkRows())
            player.preferredOutput.value = "unplugged"
            scene.shot("-disconnected")
            assertTrue(scene.settle().inkRows() > shared.inkRows())
        }
        GeneralSettingsScene("output-light", prefs = UiPrefs(themeMode = ThemeMode.LIGHT)) {
            AudioOutputSettingsScreen(padding, onBack = {}, onOpenSignalPath = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun backupRenders() {
        val dir = createTempDirectory("aurora-backup").toFile()
        try {
            GeneralSettingsScene("backup") {
                val store = LocalDesktopContainer.current.settingsStore
                val manager = remember { BackupManager(store, LocalStore(dir), PlayHistoryStore(dir), dir, dir) }
                BackupScreen(padding, manager, onBack = {}, confirm = {})
            }.use { assertTrue(it.shot().distinctColors() > 10) }
        } finally { dir.deleteRecursively() }
    }
}
