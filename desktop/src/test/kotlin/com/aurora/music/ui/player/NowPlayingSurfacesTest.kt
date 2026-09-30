package com.aurora.music.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.ui.screens.player.LyricsScreen
import com.aurora.music.ui.screens.player.OutputDeviceSheet
import com.aurora.music.ui.screens.player.QueueActions
import com.aurora.music.ui.screens.player.QueueContent
import com.aurora.music.ui.screens.player.SleepTimerSheet
import com.aurora.music.ui.screens.player.SpeedPitchSheet
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.ui.testing.pixel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSurfacesTest {
    private val devices = listOf(
        AudioDevice("{0.0.0.00000000}.{speakers}", "Speakers (Realtek(R) Audio)", DeviceKind.SPEAKERS, isDefault = true),
        AudioDevice("{0.0.0.00000000}.{dac}", "Headphones (FiiO K7)", DeviceKind.HEADPHONES, isDefault = false),
        AudioDevice("{0.0.0.00000000}.{hdmi}", "LG ULTRAGEAR (NVIDIA High Definition Audio)", DeviceKind.DIGITAL_DISPLAY, isDefault = false),
        AudioDevice("{0.0.0.00000000}.{spdif}", "Digital Output (S/PDIF)", DeviceKind.SPDIF, isDefault = false),
    )

    @Test fun lyricsScreenSeeksByLineOverAnArtworkBackdrop() {
        val events = mutableListOf<String>()
        val seeks = mutableListOf<Float>()
        PlayerScene("lyrics-full", 1440, 900) {
            LyricsScreen(PlayerFixtures.playing, onClose = { events += "close" }, onTogglePlay = { events += "play" },
                onPrevious = {}, onNext = {}, onSeek = { seeks += it }, loadLyrics = { PlayerFixtures.lyrics })
        }.use { scene ->
            val image = scene.shot(realMillis = 60)
            assertTrue(image.distinctColors(6) > 40)
            val glow = image.pixel(1400, 850)
            assertTrue("backdrop %08x".format(glow), (glow shr 16 and 0xFF) > (glow shr 8 and 0xFF) + 8)
            scene.click(890f, 346f)
            assertEquals(75f / 214f, seeks.single(), 0.0001f)
            scene.click(324f, 738f)
            scene.click(36f, 36f)
            assertEquals(listOf("play", "close"), events)
        }
    }

    @Test fun queueJumpsRemovesAndReordersByHandle() {
        val events = mutableListOf<String>()
        PlayerScene("queue-full", 1440, 900) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 880.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        QueueActions(PlayerFixtures.queue, 1, true, onClear = { events += "clear" }, onSaveAsPlaylist = { events += "save:$it" })
                    }
                    QueueContent(PlayerFixtures.queue, 1, true, onJump = { events += "jump:$it" }, onRemove = { events += "remove:$it" },
                        onMove = { from, to -> events += "move:$from:$to" }, editable = true, modifier = Modifier.weight(1f))
                }
            }
        }.use { scene ->
            val collapsed = scene.shot()
            assertTrue(collapsed.distinctColors(6) > 40)
            scene.click(600f, 248f)
            scene.click(1054f, 312f)
            scene.drag(1102f, 248f, 0f, 128f)
            scene.click(1120f, 32f)
            assertEquals(listOf("jump:2", "remove:3", "move:2:4", "clear"), events)
            scene.click(700f, 166f)
            assertTrue(scene.shot("-history").differsFrom(collapsed))
        }
    }

    @Test fun outputSheetListsEndpointsWithExclusiveAndVolume() {
        val events = mutableListOf<String>()
        PlayerScene("sheet-output", 1440, 900) {
            OutputDeviceSheet(devices, currentId = devices[1].id, exclusive = true, volume = 0.72f,
                onSelect = { events += "select:$it" }, onExclusiveChange = { events += "exclusive:$it" },
                onVolumeChange = { events += "volume" }, onToggleMute = { events += "mute" }, onDismiss = { events += "dismiss" })
        }.use { scene ->
            assertTrue(scene.shot().distinctColors(6) > 20)
            listOf(527f to 425f, 628f to 629f, 600f to 784f, 444f to 848f, 720f to 120f).forEach { (x, y) -> scene.click(x, y) }
            scene.frames(8)
            assertEquals(listOf("select:null", "select:${devices[2].id}", "exclusive:false", "mute", "dismiss"), events)
        }
    }

    @Test fun outputSheetKeepsADisconnectedPreferenceSelected() {
        val events = mutableListOf<String>()
        PlayerScene("sheet-output-disconnected", 1440, 900) {
            OutputDeviceSheet(devices, currentId = "{0.0.0.00000000}.{unplugged}", exclusive = false, volume = 0.5f,
                onSelect = { events += "select:$it" }, onExclusiveChange = {}, onVolumeChange = {}, onToggleMute = {}, onDismiss = {})
        }.use { scene ->
            val image = scene.shot()
            val idle = image.pixel(430, 425)
            assertEquals(idle, image.pixel(430, 357))
            assertNotEquals(idle, image.pixel(430, 697))
            scene.click(527f, 357f)
            assertEquals(listOf("select:null"), events)
        }
    }

    @Test fun sleepAndSpeedSheetsRouteChoices() {
        val sleep = mutableListOf<String>()
        PlayerScene("sheet-sleep", 1440, 900) {
            SleepTimerSheet(currentMinutes = 30, endOfTrack = false, onSelect = { sleep += "min:$it" },
                onEndOfTrack = { sleep += "end" }, onDismiss = { sleep += "dismiss" })
        }.use { scene ->
            scene.shot()
            scene.click(617f, 799f)
            scene.click(477f, 851f)
            assertEquals(listOf("min:15", "dismiss", "end", "dismiss"), sleep)
        }
        val speed = mutableListOf<String>()
        PlayerScene("sheet-speed", 1440, 900) {
            SpeedPitchSheet(speed = 1.25f, onSpeed = { speed += "speed:$it" }, onReset = { speed += "reset" }, onDismiss = { speed += "dismiss" })
        }.use { scene ->
            scene.shot()
            scene.click(869f, 783f)
            scene.click(720f, 845f)
            assertEquals(listOf("speed:1.5", "reset"), speed)
        }
    }
}
