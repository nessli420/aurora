package com.aurora.music.ui.shell

import com.aurora.music.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

class AuroraShellTest {
    @Test fun signedOutStartsOnSignIn() {
        ShellScene("signed-out").use { scene ->
            scene.awaitRoute(Routes.SIGN_IN)
            scene.shot("sign-in")
            assertEquals(false, scene.container.sessionReady.value)
        }
    }

    @Test fun signedInLocalShowsHomeAndDock() {
        ShellScene("local", ShellPlayer(ShellFixtures.playing), seed = { applySession(authenticator.local()) }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.shot("home")
            scene.nav.navigate(Routes.LIBRARY)
            scene.awaitRoute(Routes.LIBRARY)
            scene.shot("library")
            scene.nav.navigate(Routes.SETTINGS)
            scene.awaitRoute(Routes.SETTINGS)
            scene.shot("settings")
            scene.player.setExpanded(true)
            scene.shot("player")
        }
    }
}
