package com.aurora.music.desktop.mac

import com.aurora.music.desktop.natives.MediaSession
import com.aurora.music.desktop.natives.SmtcRepeat
import com.aurora.music.desktop.natives.SmtcStatus
import com.aurora.music.desktop.platform.HostPlatform
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class MacNowPlayingTest {
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        ObjC.onMain { result = runCatching(block); done.countDown() }
        check(done.await(5, TimeUnit.SECONDS)) { "main thread did not answer" }
        return result!!.getOrThrow()
    }

    private fun text(obj: com.sun.jna.Pointer?): String? = obj?.let { ObjC.msg(it, "UTF8String")?.getString(0) }

    @Test fun publishesTheTrackToNowPlaying() {
        assumeTrue(HostPlatform.isMac)
        java.awt.Toolkit.getDefaultToolkit() // starts appkit, which runs the main queue
        val session = MacNowPlaying.create(object : MediaSession.Callbacks {})
        try {
            val cover = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            session.buttons()
            session.metadata("WHO DO U LUV", "Avenoir", "Album", thumbnail = cover)
            session.timeline(12_000, 248_000)
            session.status(SmtcStatus.PLAYING)
            session.shuffle(true)
            session.repeat(SmtcRepeat.LIST)
            val (title, artist, elapsed, state) = onMain {
                val center = ObjC.msg(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter")!!
                val info = ObjC.msg(center, "nowPlayingInfo")!!
                fun value(key: String) = ObjC.msg(info, "objectForKey:", ObjC.constant("MediaPlayer", key))
                listOf(text(value("MPMediaItemPropertyTitle")), text(value("MPMediaItemPropertyArtist")),
                    value("MPNowPlayingInfoPropertyElapsedPlaybackTime")?.let { ObjC.msgDouble(it, "doubleValue") }?.toString(),
                    ObjC.msgLong(center, "playbackState").toString())
            }
            assertEquals("WHO DO U LUV", title)
            assertEquals("Avenoir", artist)
            assertEquals("12.0", elapsed)
            assertEquals("1", state)
            val hasArtwork = onMain {
                val info = ObjC.msg(ObjC.msg(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter")!!, "nowPlayingInfo")!!
                ObjC.msg(info, "objectForKey:", ObjC.constant("MediaPlayer", "MPMediaItemPropertyArtwork")) != null
            }
            assertEquals(true, hasArtwork)
        } finally {
            session.close()
        }
    }
}
