package com.aurora.music.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.UUID

class LocalLibraryProfileDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = (context.applicationContext as AuroraApplication).container.settingsStore

    @Test fun importedImagesSurviveSourceDeletionAndBackupRestore() = runBlocking {
        val original = settings.exportPrefs()
        val root = File(context.cacheDir, "profile-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = root }
        try {
            val source = File(root, "source.png")
            val bitmap = Bitmap.createBitmap(1800, 900, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff3579ba.toInt())
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val images = ProfileImages(isolated)
            val avatar = images.import(Uri.fromFile(source), false)
            val banner = images.import(Uri.fromFile(source), true)
            assertTrue(source.delete())
            val profile = LocalProfile("Test listener", avatar, banner)
            settings.setLocalProfile(profile)
            val backup = BackupArchive.decodeJson(Gson().toJson(AuroraBackup(prefs = settings.exportPrefs())))
            settings.setLocalProfile(LocalProfile())
            assertEquals("Local Library", images.appearance(settings.localProfile.first()).name)
            settings.restoreBackupPrefs(backup.prefs).getOrThrow()
            assertEquals(profile, settings.localProfile.first())
            val appearance = ProfileImages(isolated).appearance(settings.localProfile.first())
            assertEquals("Test listener", appearance.name)
            listOf(appearance.avatarUrl to 512, appearance.bannerUrl to 1280).forEach { (url, edge) ->
                val image = File(URI(url))
                val decoded = requireNotNull(BitmapFactory.decodeFile(image.path))
                assertEquals(edge, decoded.width)
                assertEquals(edge / 2, decoded.height)
                decoded.recycle()
                assertTrue(image.delete())
            }
            val rebuilt = images.appearance(settings.localProfile.first())
            assertTrue(File(URI(rebuilt.avatarUrl)).isFile)
            assertTrue(File(URI(rebuilt.bannerUrl)).isFile)
            settings.setLocalProfile(profile.copy(avatar = "", banner = ""))
            assertEquals(ProfileAppearance("Test listener"), images.appearance(settings.localProfile.first()))
        } finally {
            settings.restoreBackupPrefs(original).getOrThrow()
            root.deleteRecursively()
        }
    }

    @Test fun invalidImageLeavesSavedProfileUnchanged() = runBlocking {
        val before = settings.localProfile.first()
        val source = File(context.cacheDir, "invalid-profile-${UUID.randomUUID()}.png")
        try {
            source.writeText("not an image")
            assertTrue(runCatching { ProfileImages(context).import(Uri.fromFile(source), false) }.isFailure)
            assertEquals(before, settings.localProfile.first())
        } finally { source.delete() }
    }

    @Test fun importedPhotoHonorsExifRotation() = runBlocking {
        val source = File(context.cacheDir, "rotated-profile-${UUID.randomUUID()}.jpg")
        try {
            val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val bytes = java.util.Base64.getDecoder().decode(ProfileImages(context).import(Uri.fromFile(source), false))
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(256, decoded.width)
            assertEquals(512, decoded.height)
            decoded.recycle()
        } finally { source.delete() }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun scannedCollaborationAppearsOnBothArtistPagesAndRulesReindexWithoutRescan() = runBlocking {
        var queries = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "vnd.android.cursor.dir/audio"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
                queries++
                val columns = requireNotNull(projection)
                return MatrixCursor(columns).apply {
                    listOf("Cynthoni, Sewerslvt", "Cynthoni", "AC/DC").forEachIndexed { i, credit ->
                        val row = mapOf("_id" to i + 1L, "title" to "Track $i", "artist" to credit,
                            "artist_id" to i + 10L, "album" to "Split EP", "album_id" to 1L,
                            "duration" to 120000L, "year" to 2026, "track" to i + 1, "date_added" to 1L,
                            "_display_name" to "$i.flac", "mime_type" to "audio/flac",
                            "_data" to "/storage/emulated/0/Music/$i.flac", "bitrate" to 900000, "genre" to "Electronic")
                        addRow(columns.map { row[it] }.toTypedArray())
                    }
                }
            }
        }
        val resolver = ContentResolver.wrap(provider)
        val isolated = object : ContextWrapper(context) { override fun getContentResolver() = resolver }
        var rules = ArtistSeparators()
        val library = LocalLibrary(isolated, separatorsProvider = { rules })
        val backend = LocalBackend(library, LocalStore(context), Session("On this device", "Local Library", "", "local", ServerType.LOCAL))
        val artists = backend.allArtists().associateBy { it.name }
        assertEquals(setOf("Cynthoni", "Sewerslvt", "AC/DC"), artists.keys)
        val cynthoni = requireNotNull(artists["Cynthoni"])
        val sewerslvt = requireNotNull(artists["Sewerslvt"])
        assertEquals(listOf("1", "2"), backend.collectionTracks("artist", cynthoni.id).map { it.id })
        val detail = requireNotNull(backend.detail("artist", sewerslvt.id))
        assertEquals("Cynthoni, Sewerslvt", detail.tracks.single().artist)
        assertEquals("1", detail.albums.single().id)
        assertEquals(listOf(sewerslvt), backend.search("Sewerslvt").artists)
        assertEquals(3, backend.allSongs().size)
        assertEquals("1", library.findMatch("Cynthoni, Sewerslvt", "Track 0", 120)?.id)
        assertEquals(1, queries)
        rules = ArtistSeparators(rules.rules!!.map { if (it.text == ",") it.copy(match = SeparatorMatch.OFF) else it })
        assertEquals(setOf("Cynthoni, Sewerslvt", "Cynthoni", "AC/DC"), backend.allArtists().map { it.name }.toSet())
        assertEquals(1, queries)
        rules = ArtistSeparators()
        assertEquals(cynthoni.id, backend.allArtists().single { it.name == "Cynthoni" }.id)
        library.refresh()
        assertEquals(2, queries)
        assertEquals(2, backend.collectionTracks("artist", cynthoni.id).size)
    }
}
