package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.SignalPathScreen
import com.aurora.music.ui.testing.differsFrom
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

class SignalPathScreenTest {
    @Test fun followsTheFedPathAndCopiesTheReport() {
        val path = MutableStateFlow(SignalPath())
        val events = mutableListOf<String>()
        AudioSettingsScene("signal-path") {
            SignalPathScreen(PaddingValues(), path, onBack = { events += "back" }, onOpenOutput = { events += "output" })
        }.use { scene ->
            val idle = scene.shot("-idle")
            scene.click(400f, 182f)
            assertEquals(listOf("output"), events)
            scene.click(400f, 249f)
            if (!GraphicsEnvironment.isHeadless()) {
                val copied = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
                assertEquals(SignalPath().toDiagnosticReport(), copied)
            }
            assertTrue(scene.shot("-copied").differsFrom(idle))

            path.value = AudioFixtures.activePath
            val active = scene.shot("-active")
            assertTrue(active.differsFrom(idle))
            scene.click(88f, 766f)
            assertTrue(scene.shot("-meters").differsFrom(active))
        }
    }
}
