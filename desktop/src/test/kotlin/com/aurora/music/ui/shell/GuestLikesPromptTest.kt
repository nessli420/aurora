package com.aurora.music.ui.shell

import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.remote.SoundCloudClient
import com.aurora.music.model.Song
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.saveTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class GuestLikesPromptTest {
    private val shots = System.getenv("AURORA_SHOTS")?.let { File(it, "guest-likes") }

    @Test fun signingInOffersTheGuestLikes() {
        ShellScene("guest-likes", seed = {
            applySession(authenticator.local())
            guestLikes.add(ServerType.SOUNDCLOUD, Song("575003979", "molly", "iann dior", "", "", 180))
            settingsStore.addSavedSession(Session(SoundCloudClient.WEB, "listener", "", "reference", ServerType.SOUNDCLOUD, "42"))
        }).use { scene ->
            val prompt = scene.settle(2_000).saveTo(shots, "prompt")
            // a click beside the dialog leaves the offer up
            scene.click(1200f, 800f)
            assertFalse(scene.settle(800).saveTo(shots, "after-outside-click").differsFrom(prompt))
            scene.click(930f, 502f)
            scene.settle(3_000).saveTo(shots, "after-add")
            val account = Session(SoundCloudClient.WEB, "listener", "", "reference", ServerType.SOUNDCLOUD, "42")
            println("GUEST left=${scene.container.guestLikes.count(ServerType.SOUNDCLOUD)} waiting=${scene.container.soundCloudWaiting(account)}")
            assertEquals(0, scene.container.guestLikes.count(ServerType.SOUNDCLOUD))
            assertEquals(1, scene.container.soundCloudWaiting(account))
        }
    }
}
