package com.aurora.music.ui.settings.general

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.aurora.music.data.BackupManager
import com.aurora.music.data.LocalStore
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.UiPrefs
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
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeneralSettingsTest {
    private val padding = PaddingValues(bottom = 24.dp)
    private val navidrome = Session("https://music.example.com", "mara", "salt", "token")
    private val jellyfin = Session("http://192.168.1.20:8096", "mara", "", "jf", ServerType.JELLYFIN, userId = "u1")

    @Test fun settingsRootRendersEveryDesktopSection() {
        GeneralSettingsScene("root", width = 380, height = 1500, seed = { settingsStore.saveSession(navidrome) }) {
            SettingsScreen(
                contentPadding = padding, username = "Mara", server = navidrome.server, onBack = {}, onOpenPlayback = {}, onOpenOutput = {},
                onOpenLoudness = {}, onOpenAdvancedAudio = {}, onOpenSignalPath = {}, onOpenEq = {}, onOpenVisualizer = {}, onOpenSonic = {},
                onOpenSources = {}, onOpenDownloads = {}, onOpenAppearance = {}, onOpenLanguage = {}, onOpenIntegrations = {}, onOpenAbout = {},
                onOpenProfile = {}, onOpenAccounts = {}, onOpenBackup = {}, onLogout = {},
            )
        }.use { scene ->
            val image = scene.shot()
            assertTrue(image.distinctColors() > 20)
        }
    }

    @Test fun playbackRenders() {
        GeneralSettingsScene("playback", seed = { settingsStore.setCrossfade(6) }) {
            PlaybackSettingsScreen(padding, onBack = {}, onOpenOutput = {}, onOpenLoudness = {}, onOpenEq = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun appearanceRenders() {
        GeneralSettingsScene("appearance", height = 1600) { AppearanceScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun accountsRender() {
        GeneralSettingsScene("accounts", seed = {
            settingsStore.addSavedSession(jellyfin)
            settingsStore.saveSession(navidrome)
        }) { AccountsScreen(padding, onBack = {}, onSwitch = {}, onForget = {}, onAddAccount = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun aboutRenders() {
        GeneralSettingsScene("about") { AboutSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun languageRenders() {
        GeneralSettingsScene("language") { LanguageSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 10) }
    }

    @Test fun navigationMenuRenders() {
        GeneralSettingsScene("nav-menu", height = 1300) { NavigationMenuScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun storageRenders() {
        GeneralSettingsScene("storage") { StorageSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun integrationsRender() {
        GeneralSettingsScene("integrations") {
            IntegrationsSettingsScreen(padding, onBack = {}, onOpenLyrics = {}, onOpenLastfm = {}, onOpenListenBrainz = {}, onOpenArtistInfo = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
        GeneralSettingsScene("integrations-lastfm") { LastfmIntegrationScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 10) }
        GeneralSettingsScene("integrations-listenbrainz") { ListenBrainzIntegrationScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 10) }
    }

    @Test fun sourcesRender() {
        val music = kotlin.io.path.createTempDirectory("aurora-music").toFile()
        try {
            GeneralSettingsScene("sources", height = 1200, seed = {
                desktopSettings.setMusicFolders(listOf(music.path, File(music, "missing").path))
                settingsStore.addSavedSession(jellyfin)
                settingsStore.saveSession(navidrome)
                settingsStore.setUnifiedLibrary(true)
            }) { SourcesSettingsScreen(padding, onBack = {}, onArtistSeparators = {}) }.use { scene ->
                assertTrue(scene.shot().distinctColors() > 20)
                assertTrue(scene.await(read = { desktopSettings.musicFolders.first() }) { it.size == 2 }.size == 2)
            }
        } finally { music.deleteRecursively() }
    }

    @Test fun audioOutputRenders() {
        GeneralSettingsScene("output", height = 1500) { AudioOutputSettingsScreen(padding, onBack = {}, onOpenSignalPath = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun backupRenders() {
        GeneralSettingsScene("backup") {
            val manager = androidx.compose.runtime.remember {
                BackupManager(container.settingsStore, LocalStore(File(root, "b")), PlayHistoryStore(File(root, "b")), File(root, "b"), File(root, "c"))
            }
            BackupScreen(padding, manager, onBack = {}, confirm = {})
        }.use { assertTrue(it.shot().distinctColors() > 10) }
    }

    @Test fun lightThemeRenders() {
        GeneralSettingsScene("output-light", prefs = UiPrefs(themeMode = ThemeMode.LIGHT)) {
            AudioOutputSettingsScreen(padding, onBack = {}, onOpenSignalPath = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }
}
