package com.aurora.music.playback.dsd

import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.LocalLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

@UnstableApi
class DsdLocalLibraryDeviceTest {
    @Test fun visibleDsdFilesAreDiscoveredOnceWithDecodedMetadata() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val channels = Array(2) { ByteArray(8193) { 0x69 } }
        val inserted = ArrayList<android.net.Uri>()
        try {
            for ((extension, rate) in listOf("dsf" to 11_289_600, "dff" to 22_579_200)) {
                val title = "Aurora DSD ${UUID.randomUUID()}"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.$extension")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-$extension")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AuroraDSDTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = requireNotNull(resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
                inserted += uri
                val bytes = if (extension == "dsf") DsdFixtures.dsf(channels, rate, title = title)
                    else DsdFixtures.dff(channels, rate, title = title)
                requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val read = DsdMetadataReader.read(context, uri)
                assertEquals(title, requireNotNull(read).metadata.title)
                val library = LocalLibrary(context)
                library.refresh()
                val mediaId = ContentUris.parseId(uri).toString()
                val songs = library.songs.filter { it.id == mediaId || it.id == "local-dsd-$mediaId" }
                assertEquals("One indexed row for $extension", 1, songs.size)
                val song = songs.single()
                assertEquals(title, song.title)
                assertEquals(rate, song.sampleRateHz)
                assertEquals(1, song.bitDepth)
                assertEquals(rate * 2 / 1000, song.bitrateKbps)
                assertEquals(extension, song.suffix)
                if (extension == "dff") assertEquals("Aurora tests", song.artist)
            }
        } finally { inserted.forEach { resolver.delete(it, null, null) } }
    }
}
