package com.aurora.music.playback.sacd

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.LocalLibrary
import com.aurora.music.data.SacdLibrary
import com.aurora.music.playback.PrecisionPlaybackDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class SacdPlaybackDeviceTest {
    @Test fun importedImageRetainsTrackIdentityStreamsSeeksAndReportsMissingFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val imports = SacdLibrary(context)
        val inserted = ArrayList<Uri>()
        val helper = PrecisionPlaybackDeviceTest()
        var foreground = false
        try {
            helper.keepTargetForegroundForAudioFocus(); foreground = true
            for (coded in listOf(false, true)) {
                val uri = requireNotNull(resolver.insert(MediaStore.Files.getContentUri("external"), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Aurora-${UUID.randomUUID()}.iso")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AuroraSacdTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                inserted += uri
                requireNotNull(resolver.openOutputStream(uri)).use { it.write(image(coded)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                assertEquals(2, imports.add(uri))
                val songs = SacdLibrary(context).songs().filter { Uri.parse(it.streamUrl).getQueryParameter("image") == uri.toString() }
                assertEquals(2, songs.size); assertNotEquals(songs[0].id, songs[1].id)
                assertTrue(songs.all { it.durationSec == 3 && it.bitDepth == 1 && it.sampleRateHz == 2822400 })
                val library = LocalLibrary(context)
                library.refresh()
                assertTrue(library.songs.map { it.id }.containsAll(songs.map { it.id }))
                val source = SacdDataSource(context)
                try {
                    val spec = DataSpec(Uri.parse(songs[1].streamUrl))
                    val length = source.open(spec)
                    val header = ByteArray(4)
                    assertEquals(4, source.read(header, 0, 4)); assertEquals("FRM8", String(header))
                    source.close()
                    assertEquals(16, source.open(spec.buildUpon().setPosition(length - 16).build()).toInt())
                    val tail = ByteArray(16)
                    assertEquals(16, source.read(tail, 0, 16)); assertTrue(tail.all { it == 0x69.toByte() })
                    assertEquals(-1, source.read(tail, 0, 1))
                } finally { source.close() }
                helper.withProcessingFixture(0) { controller, _ ->
                    helper.main {
                        controller.setMediaItems(songs.map { MediaItem.Builder().setMediaId(it.id).setUri(it.streamUrl).build() })
                        controller.prepare(); controller.play()
                    }
                    helper.await("SACD first track plays", controller) { helper.main { controller.isPlaying && controller.currentPosition > 250 } }
                    val container = (context.applicationContext as AuroraApplication).container
                    helper.await("SACD source appears in Signal Path", controller) { container.signalPath.value.codec == "SACD ISO / DSD" }
                    helper.main { assertEquals(3000L, controller.duration); controller.pause(); controller.seekTo(1200); controller.play() }
                    helper.await("SACD seek resumes", controller) { helper.main { controller.isPlaying && controller.currentPosition in 1350..2400 } }
                    helper.main { controller.seekTo(2600) }
                    helper.await("SACD next track plays", controller) { helper.main { controller.currentMediaItemIndex == 1 && controller.isPlaying } }
                    helper.main { assertEquals(3000L, controller.duration); controller.seekTo(2600) }
                    helper.await("SACD reaches EOS", controller) { helper.main { controller.playbackState == Player.STATE_ENDED } }
                }
                resolver.delete(uri, null, null)
                assertTrue(SacdLibrary(context).songs().none { it.id in songs.map { s -> s.id } })
                assertNotNull(SacdLibrary(context).entries().single { it.uri == uri.toString() }.error)
                imports.remove(uri.toString())
            }
        } finally {
            inserted.forEach { imports.remove(it.toString()); resolver.delete(it, null, null) }
            if (foreground) helper.removeFixturesAndFinishActivity()
        }
    }

    private fun image(dst: Boolean): ByteArray {
        val frames = 450
        val start = 580
        val sectorsPerTrack = if (dst) 225 * 5 else 225 * 14 / 3
        val sectors = sectorsPerTrack * 2
        val bytes = ByteArray((start + sectors) * 2048)
        fun block(lsn: Int) = ByteBuffer.wrap(bytes, lsn * 2048, 2048).slice()
        fun ByteBuffer.signature(value: String) { put(value.toByteArray()) }
        block(510).apply { signature("SACDMTOC"); put(8, 1); put(9, 20); putInt(64, 540) }
        block(540).apply {
            signature("TWOCHTOC"); put(8, 1); put(9, 20); putShort(10, 3)
            put(20, 4); put(21, if (dst) 0 else 2); put(32, 2); put(69, 2)
            putInt(72, start); putInt(76, start + sectors - 1)
        }
        block(541).apply {
            signature("SACDTRL1"); putInt(8, start); putInt(12, start + sectorsPerTrack)
            putInt(1028, sectorsPerTrack); putInt(1032, sectorsPerTrack)
        }
        block(542).apply { signature("SACDTRL2"); put(13, 3); put(1029, 3); put(1033, 3) }
        if (!dst) repeat(sectors) { sector -> block(start + sector).apply {
            position(32); put(ByteArray(2016) { 0x69 })
            var at = sector * 2016
            var left = 2016
            val parts = ArrayList<Triple<Int, Boolean, Int>>()
            while (left > 0) {
                val size = minOf(left, 9408 - at % 9408)
                parts += Triple(size, at % 9408 == 0, at / 9408)
                at += size; left -= size
            }
            val starts = parts.filter { it.second }.map { it.third }
            val count = parts.size + 1
            put(0, ((count shl 5) or (starts.size shl 2)).toByte())
            putShort(1, (0x2000 or (32 - (1 + count * 2 + starts.size * 3))).toShort())
            parts.forEachIndexed { i, p -> putShort(3 + i * 2, (0x1000 or p.first or if (p.second) 0x8000 else 0).toShort()) }
            starts.forEachIndexed { i, frame -> put(1 + count * 2 + i * 3 + 1, (frame / 75).toByte()); put(1 + count * 2 + i * 3 + 2, (frame % 75).toByte()) }
        } }
        else repeat(frames) { frame ->
            val source = byteArrayOf(0) + ByteArray(9408) { 0x69 }
            var offset = 0
            repeat(5) { part ->
                val dataOffset = if (part == 0) 7 else 3
                val size = minOf(2048 - dataOffset, source.size - offset)
                block(start + frame * 5 + part).apply {
                    put(0, if (part == 0) 0x25 else 0x21)
                    putShort(1, (0x1000 or size or if (part == 0) 0x8000 else 0).toShort())
                    if (part == 0) { put(4, (frame / 75).toByte()); put(5, (frame % 75).toByte()); put(6, 20) }
                    position(dataOffset); put(source, offset, size)
                }
                offset += size
            }
        }
        return bytes
    }
}
