package com.aurora.music.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.aurora.music.data.QueueOrigin
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.theme.AuroraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPlayButtonTest {
    @Test fun onlyThePageThatStartedTheQueuePausesAndResumesIt() {
        val events = mutableListOf<String>()
        val queue = mutableStateOf(QueuePlayback(QueueOrigin.of("playlist", "p1"), playing = true) { events += "toggle" })
        EdtScene(240, 60) {
            AuroraTheme(UiPrefs()) {
                CompositionLocalProvider(LocalQueuePlayback provides queue.value) {
                    CollectionPlayButton(QueueOrigin.of("album", "a1"), { events += "play" })
                }
            }
        }.use { scene ->
            val elsewhere = scene.frame(600)
            scene.click(40f, 24f)
            queue.value = queue.value.copy(origin = QueueOrigin.of("album", "a1"))
            val here = scene.frame(600)
            scene.click(40f, 24f)
            queue.value = queue.value.copy(playing = false)
            val paused = scene.frame(600)
            scene.click(40f, 24f)
            assertEquals(listOf("play", "toggle", "toggle"), events)
            assertTrue(here.differsFrom(elsewhere))
            assertTrue(paused.differsFrom(here))
        }
    }
}
