package com.aurora.music.data.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.ArtistSeparators
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

class ArtworkCacheDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
    private fun metadata(calls: AtomicInteger, found: Boolean = true) = CoverArtClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val body = if (found) """{"release-groups":[{"id":"12345678-1234-1234-1234-123456789abc","title":"Album","artist-credit":[{"name":"Artist"}]}]}""" else """{"release-groups":[]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build(), beforeRequest = {},
    )
    private fun images(calls: AtomicInteger, successful: Boolean = true) = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (successful) 200 else 503).message("Fixture")
            .body(png().toResponseBody()).build()
    }.build()
    private fun verify(descriptor: ParcelFileDescriptor) = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
        val bitmap = BitmapFactory.decodeStream(it)
        assertNotNull(bitmap)
        assertEquals(android.graphics.Color.MAGENTA, bitmap!!.getPixel(0, 0))
        bitmap.recycle()
    }

    @Test fun concurrentLoadsShareOneLookupPersistOfflineAndClearCleanly() = runBlocking {
        val directory = File(context.cacheDir, "artwork-device-test-${System.nanoTime()}")
        val metadataCalls = AtomicInteger(); val imageCalls = AtomicInteger()
        var offline = false
        fun repository() = ArtworkRepository(context, offline = { offline }, enabled = { true }, separators = { ArtistSeparators() },
            client = metadata(metadataCalls), http = images(imageCalls), root = directory)
        try {
            val repo = repository()
            val request = ArtworkRequest("Artist", "Album")
            coroutineScope { List(6) { async { verify(repo.open(request)) } }.awaitAll() }
            assertEquals(1, metadataCalls.get()); assertEquals(1, imageCalls.get())
            offline = true
            verify(repository().open(request))
            assertEquals(1, metadataCalls.get())
            repo.clear()
            assertEquals(0L, repo.bytes.value)
            try { repo.open(request); fail("Offline fetch was attempted") } catch (_: FileNotFoundException) { }
            assertEquals(1, metadataCalls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun missesAreCachedButTransientErrorsCanBeRetried() = runBlocking {
        val directory = File(context.cacheDir, "artwork-device-test-${System.nanoTime()}")
        val metadataCalls = AtomicInteger(); val imageCalls = AtomicInteger()
        try {
            val request = ArtworkRequest("Artist", "Album")
            val miss = ArtworkRepository(context, offline = { false }, enabled = { true }, separators = { ArtistSeparators() },
                client = metadata(metadataCalls, false), http = images(imageCalls), root = directory)
            repeat(2) { try { miss.open(request); fail("Expected no cover") } catch (_: FileNotFoundException) { } }
            assertEquals(1, metadataCalls.get()); assertEquals(0, imageCalls.get())
            miss.clear()
            val failed = ArtworkRepository(context, offline = { false }, enabled = { true }, separators = { ArtistSeparators() },
                client = metadata(metadataCalls), http = images(imageCalls, false), root = directory)
            repeat(2) { try { failed.open(request); fail("Expected network failure") } catch (_: java.io.IOException) { } }
            assertEquals(3, metadataCalls.get()); assertEquals(2, imageCalls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun disabledLookupNeverContactsProviders() = runBlocking {
        val directory = File(context.cacheDir, "artwork-device-test-${System.nanoTime()}")
        val calls = AtomicInteger()
        try {
            val repo = ArtworkRepository(context, offline = { false }, enabled = { false }, separators = { ArtistSeparators() },
                client = metadata(calls), http = images(calls), root = directory)
            try { repo.open(ArtworkRequest("Artist", "Album")); fail("Disabled lookup ran") } catch (_: FileNotFoundException) { }
            assertEquals(0, calls.get())
        } finally { directory.deleteRecursively() }
    }
}
