package com.aurora.music.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.ui.screens.auth.SignInScreen
import com.aurora.music.viewmodel.AuthStep
import com.aurora.music.viewmodel.AuthViewModel
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SignInScreenTest {
    private var vm: AuthViewModel? = null

    @Composable
    private fun SignIn() {
        val container = LocalDesktopContainer.current
        val model: AuthViewModel = viewModel { AuthViewModel(container) }
        SideEffect { vm = model }
        val state by model.state.collectAsState()
        val folders by model.musicFolders.collectAsState()
        val saved by container.settingsStore.savedSessions.collectAsState(emptyList())
        SignInScreen(
            state = state, onSelectType = model::selectType, onScheme = model::onScheme, onHost = model::onHost,
            onUsername = model::onUsername, onPassword = model::onPassword, onBack = model::back,
            canContinueServer = model.canContinueServer, onContinueServer = model::continueToCredentials,
            canSubmit = model.canSubmit, onSignIn = { model.signIn {} }, onLocal = model::signInLocal,
            musicFolders = folders, onAddFolder = model::addMusicFolder, onRemoveFolder = model::removeMusicFolder,
            savedSessions = saved, onUseSaved = { model.useSaved(it) },
        )
    }

    @Test fun probe() {
        val music = createTempDirectory("aurora-music").toFile()
        AccountScene("sign-in", width = 1100, seed = {
            desktopSettings.setMusicFolders(listOf(music.path, File(music, "Albums").path))
            settingsStore.addSavedSession(Session("http://nas:4533", "mara", "s", "t"))
            settingsStore.addSavedSession(Session("spotify", "mara", "", "t", ServerType.SPOTIFY))
        }) { SignIn() }.use { scene ->
            scene.shot("-type")
            vm!!.selectType(ServerType.LOCAL)
            scene.shot("-folders")
            vm!!.back()
            vm!!.selectType(ServerType.SUBSONIC)
            scene.shot("-server")
            vm!!.onHost("192.168.1.10:4533")
            vm!!.continueToCredentials()
            scene.shot("-credentials")
            vm!!.back(); vm!!.back()
            vm!!.selectType(ServerType.PLEX)
            vm!!.onHost("https://plex.local:32400")
            vm!!.continueToCredentials()
            scene.shot("-plex")
            check(vm!!.state.value.step == AuthStep.CREDENTIALS)
        }
        music.deleteRecursively()
    }
}
