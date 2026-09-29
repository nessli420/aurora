package com.aurora.music.ui.player

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.ui.screens.player.LyricsScreen
import com.aurora.music.ui.screens.player.OutputDeviceSheet
import com.aurora.music.ui.screens.player.QueueScreen
import com.aurora.music.ui.screens.player.SleepTimerSheet
import com.aurora.music.ui.screens.player.SpeedPitchSheet
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSurfacesTest {
    private val devices = listOf(
        AudioDevice("{0.0.0.00000000}.{speakers}", "Speakers (Realtek(R) Audio)", DeviceKind.SPEAKERS, isDefault = true),
        AudioDevice("{0.0.0.00000000}.{dac}", "Headphones (FiiO K7)", DeviceKind.HEADPHONES, isDefault = false),
        AudioDevice("{0.0.0.00000000}.{hdmi}", "LG ULTRAGEAR (NVIDIA High Definition Audio)", DeviceKind.DIGITAL_DISPLAY, isDefault = false),
        AudioDevice("{0.0.0.00000000}.{spdif}", "Digital Output (S/PDIF)", DeviceKind.SPDIF, isDefault = false),
    )

    @Test fun lyricsScreenRenders() {
        val events = mutableListOf<String>()
        PlayerScene("lyrics-full", 1440, 900) {
            LyricsScreen(PlayerFixtures.playing, onClose = { events += "close" }, onTogglePlay = {}, onPrevious = {}, onNext = {},
                onSeek = { events += "seek:%.3f".format(it) }, loadLyrics = { PlayerFixtures.lyrics })
        }.use { assertTrue(it.shot(realMillis = 60).distinctColors() > 40) }
    }

    @Test fun queueScreenRenders() {
        val events = mutableListOf<String>()
        PlayerScene("queue-full", 1440, 900) {
            QueueScreen(PlayerFixtures.queue, 1, true, onJump = { events += "jump:$it" }, onRemove = { events += "remove:$it" },
                onMove = { from, to -> events += "move:$from:$to" }, onClear = { events += "clear" },
                onSaveAsPlaylist = { events += "save:$it" }, onClose = { events += "close" })
        }.use { assertTrue(it.shot().distinctColors() > 40) }
    }

    @Test fun sheetsRender() {
        PlayerScene("sheet-output", 1440, 900) {
            OutputDeviceSheet(devices, currentId = devices[1].id, exclusive = true, volume = 0.72f, onSelect = {}, onExclusiveChange = {},
                onVolumeChange = {}, onDismiss = {})
        }.use { it.shot() }
        PlayerScene("sheet-sleep", 1440, 900) {
            SleepTimerSheet(currentMinutes = 30, endOfTrack = false, onSelect = {}, onEndOfTrack = {}, onDismiss = {})
        }.use { it.shot() }
        PlayerScene("sheet-speed", 1440, 900) {
            SpeedPitchSheet(speed = 1.25f, pitch = 2f, matchPitch = false, onSpeed = {}, onPitch = {}, onMatchPitch = {}, onReset = {}, onDismiss = {})
        }.use { it.shot() }
    }
}
