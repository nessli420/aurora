package com.aurora.music.desktop.linux

import com.aurora.music.desktop.natives.MediaSession
import com.aurora.music.desktop.natives.SmtcButton
import com.aurora.music.desktop.natives.SmtcRepeat
import com.aurora.music.desktop.natives.SmtcStatus
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class MprisSessionTest {
    private class Recorder : MediaSession.Callbacks {
        val events = CopyOnWriteArrayList<String>()
        override fun onButton(button: SmtcButton) { events += "button:$button" }
        override fun onSeek(positionMs: Long) { events += "seek:$positionMs" }
        override fun onShuffle(enabled: Boolean) { events += "shuffle:$enabled" }
        override fun onRepeat(mode: SmtcRepeat) { events += "repeat:$mode" }
    }

    private fun MediaSession.playing() {
        metadata("Song", "Artist", "Album", "Album Artist", byteArrayOf(1, 2, 3))
        timeline(1_000, 200_000)
        status(SmtcStatus.PLAYING)
        shuffle(true)
        repeat(SmtcRepeat.LIST)
    }

    @Test fun stateIsPublishedAndControlsComeBack() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val recorder = Recorder()
            MprisSession.create(recorder, bus.connect()).use { session ->
                session.playing()
                val client = bus.connect()
                val properties = client.getRemoteObject(NAME, PATH, Properties::class.java)
                val player = properties.GetAll(PLAYER).mapValues { it.value.value }
                assertEquals("Playing", player["PlaybackStatus"])
                assertEquals("Playlist", player["LoopStatus"])
                assertEquals(true, player["Shuffle"])
                assertEquals(true, player["CanSeek"])
                assertTrue((player["Position"] as Long) in 1_000_000L..3_000_000L)
                @Suppress("UNCHECKED_CAST")
                val metadata = (player["Metadata"] as Map<String, Variant<*>>).mapValues { it.value.value }
                assertEquals("Song", metadata["xesam:title"])
                assertEquals(listOf("Artist"), metadata["xesam:artist"])
                assertEquals("Album", metadata["xesam:album"])
                assertEquals(200_000_000L, metadata["mpris:length"])
                val art = File(URI(metadata["mpris:artUrl"] as String))
                assertTrue(art.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
                assertEquals("Aurora", properties.GetAll(ROOT)["Identity"]?.value)

                val remote = client.getRemoteObject(NAME, PATH, MediaPlayer2Player::class.java)
                remote.Next()
                remote.PlayPause()
                remote.SetPosition(metadata["mpris:trackid"] as DBusPath, 30_000_000)
                remote.SetPosition(DBusPath("/com/aurora/music/track/other"), 10_000_000)
                properties.Set(PLAYER, "Shuffle", false)
                properties.Set(PLAYER, "LoopStatus", "Track")
                assertEquals(listOf("button:NEXT", "button:PAUSE", "seek:30000", "shuffle:false", "repeat:TRACK"), recorder.events.toList())

                val changed = CompletableFuture<Properties.PropertiesChanged>()
                client.addSigHandler(Properties.PropertiesChanged::class.java) { if ("PlaybackStatus" in it.propertiesChanged) changed.complete(it) }
                session.status(SmtcStatus.PAUSED)
                assertEquals("Paused", changed.get(5, TimeUnit.SECONDS).propertiesChanged["PlaybackStatus"]?.value)
                remote.PlayPause()
                assertEquals("button:PLAY", recorder.events.last())
            }
        }
    }

    @Test fun playerctlSeesTheSession() {
        PrivateBus.assumeAvailable("playerctl")
        PrivateBus().use { bus ->
            val recorder = Recorder()
            MprisSession.create(recorder, bus.connect()).use { session ->
                session.playing()
                fun playerctl(vararg args: String) = bus.run("playerctl", "-p", "aurora", *args).inputStream.bufferedReader().readText().trim()
                assertEquals("Song|Artist|Album|200000000", playerctl("metadata", "--format", "{{title}}|{{artist}}|{{album}}|{{mpris:length}}"))
                assertEquals("Playing", playerctl("status"))
                assertEquals("Playlist", playerctl("loop"))
                assertEquals("On", playerctl("shuffle"))
                playerctl("next")
                playerctl("previous")
                playerctl("pause")
                playerctl("position", "42")
                assertEquals(listOf("button:NEXT", "button:PREVIOUS", "button:PAUSE", "seek:42000"), recorder.events.toList())
            }
        }
    }

    private companion object {
        const val NAME = "org.mpris.MediaPlayer2.aurora"
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"
    }
}
