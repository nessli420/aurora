package com.aurora.music.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.accountKey
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.auth.SignInScreen
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.viewmodel.AuthStep
import com.aurora.music.viewmodel.AuthViewModel
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SignInScreenTest {
    private val music = createTempDirectory("aurora-music").toFile()
    private val albums = File(music, "Albums")
    private val opened = mutableListOf<String>()
    private lateinit var vm: AuthViewModel

    @After fun tearDown() { music.deleteRecursively() }

    @Composable
    private fun SignIn() {
        val container = LocalDesktopContainer.current
        val model: AuthViewModel = viewModel { AuthViewModel(container) }
        SideEffect { vm = model }
        val state by model.state.collectAsState()
        val folders by model.musicFolders.collectAsState()
        val saved by container.settingsStore.savedSessions.collectAsState(emptyList())
        val uris = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        CompositionLocalProvider(LocalUriHandler provides uris) {
            SignInScreen(
                state = state, onSelectType = model::selectType, onScheme = model::onScheme, onHost = model::onHost,
                onUsername = model::onUsername, onPassword = model::onPassword, onBack = model::back,
                canContinueServer = model.canContinueServer, onContinueServer = model::continueToCredentials,
                canSubmit = model.canSubmit, onSignIn = { model.signIn {} }, onLocal = model::signInLocal,
                musicFolders = folders, onAddFolder = model::addMusicFolder, onRemoveFolder = model::removeMusicFolder,
                savedSessions = saved, onUseSaved = { model.useSaved(it) },
            )
        }
    }

    private fun scene(name: String, seed: suspend com.aurora.music.desktop.DesktopContainer.() -> Unit = {}) =
        AccountScene(name, width = 1100, seed = seed) { SignIn() }

    @Test fun localFoldersAreManagedAndSignInAsTheLocalLibrary() {
        scene("sign-in", seed = { desktopSettings.setMusicFolders(listOf(music.path, albums.path)) }).use { scene ->
            val type = scene.shot("-type")
            assertTrue(type.distinctColors() > 20)
            scene.click(549f, 430f)
            assertEquals(AuthStep.FOLDERS, vm.state.value.step)
            assertEquals(ServerType.LOCAL, vm.state.value.type)
            assertTrue(scene.shot("-folders").differsFrom(type))
            scene.click(842f, 507f)
            assertEquals(listOf(music.path), scene.await(read = { desktopSettings.musicFolders.first() }) { it.size == 1 })
            scene.shot("-folders-one")
            scene.click(254f, 270f)
            assertEquals(AuthStep.TYPE, vm.state.value.step)
            scene.click(549f, 430f)
            scene.click(549f, 628f)
            val session = scene.await(read = { settingsStore.session.first() }) { it != null }!!
            assertEquals(ServerType.LOCAL, session.type)
            scene.await(read = { sessionReady.value }) { it == true }
            assertTrue(scene.container.isLocal)
        }
    }

    @Test fun localContinueNeedsAFolder() {
        scene("sign-in-no-folders").use { scene ->
            scene.click(549f, 430f)
            assertEquals(AuthStep.FOLDERS, vm.state.value.step)
            scene.shot()
            scene.click(549f, 608f)
            scene.settle(300)
            assertNull(kotlinx.coroutines.runBlocking { scene.container.settingsStore.session.first() })
            assertFalse(vm.state.value.loading)
        }
    }

    @Test fun navidromeSignInShowsErrorsThenConnects() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
            val host = "${server.hostName}:${server.port}"
            scene("sign-in-navidrome").use { scene ->
                scene.click(398f, 566f)
                assertEquals(AuthStep.SERVER, vm.state.value.step)
                assertEquals(ServerType.SUBSONIC, vm.state.value.type)
                scene.click(549f, 606f)
                assertEquals(AuthStep.SERVER, vm.state.value.step)
                vm.onHost(host)
                scene.settle(200)
                scene.click(549f, 606f)
                assertEquals(AuthStep.CREDENTIALS, vm.state.value.step)
                val empty = scene.shot("-credentials")
                vm.onUsername("mara")
                vm.onPassword("wrong")
                scene.settle(200)
                scene.click(549f, 480f)
                scene.await(read = { vm.state.value }) { !it.loading && it.error != null }
                assertEquals(appString(R.string.text_wrong_username_or_password_85b465), vm.state.value.error)
                assertTrue(scene.shot("-error").differsFrom(empty))
                vm.onPassword("secret")
                scene.settle(200)
                scene.click(549f, 480f)
                val session = scene.await(read = { settingsStore.session.first() }) { it != null }!!
                assertEquals(ServerType.SUBSONIC, session.type)
                assertEquals("http://$host", session.server)
                assertEquals("mara", session.username)
                assertEquals("", vm.state.value.password)
                assertEquals("/rest/ping.view", server.takeRequest().requestUrl!!.encodedPath)
            }
        } finally { server.shutdown() }
    }

    @Test fun plexAsksOnlyForATokenAndLinksToHelp() {
        scene("sign-in-plex").use { scene ->
            scene.click(549f, 667f)
            assertEquals(ServerType.PLEX, vm.state.value.type)
            vm.onHost("https://plex.local:32400")
            assertEquals("https://", vm.state.value.scheme)
            assertEquals("plex.local:32400", vm.state.value.host)
            scene.settle(200)
            scene.click(549f, 627f)
            assertEquals(AuthStep.CREDENTIALS, vm.state.value.step)
            scene.shot()
            assertFalse(vm.canSubmit)
            vm.onPassword("token")
            assertTrue(vm.canSubmit)
            scene.click(355f, 374f)
            assertEquals(listOf("https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/"), opened)
            scene.click(801f, 223f)
            assertEquals(AuthStep.SERVER, vm.state.value.step)
        }
    }

    @Test fun savedAccountsExpandAndSwitch() {
        val navidrome = Session("http://nas:4533", "mara", "salt", "token")
        scene("sign-in-saved", seed = {
            settingsStore.addSavedSession(navidrome)
            settingsStore.addSavedSession(Session("spotify", "mara", "", "t", ServerType.SPOTIFY))
        }).use { scene ->
            val collapsed = scene.shot()
            scene.click(549f, 387f)
            assertTrue(scene.shot("-expanded").differsFrom(collapsed))
            scene.click(549f, 413f)
            val session = scene.await(read = { settingsStore.session.first() }) { it != null }!!
            assertEquals(navidrome.accountKey(), session.accountKey())
        }
    }
}
