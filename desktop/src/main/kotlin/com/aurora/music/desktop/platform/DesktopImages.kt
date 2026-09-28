package com.aurora.music.desktop.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import com.aurora.music.data.artwork.ArtworkRepository
import com.aurora.music.data.artwork.ArtworkUrls
import okhttp3.OkHttpClient
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileNotFoundException

class ArtworkFetcher(private val repository: ArtworkRepository, private val uri: String) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val request = ArtworkUrls.decode(uri) ?: throw FileNotFoundException("Invalid artwork uri")
        val bytes = repository.open(request) { it.readBytes() }
        return SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    class Factory(private val repository: ArtworkRepository) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (ArtworkUrls.isArtwork(data.toString())) ArtworkFetcher(repository, data.toString()) else null
    }
}

fun desktopImageLoader(cacheDir: File, http: OkHttpClient, artwork: ArtworkRepository): ImageLoader =
    ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(ArtworkFetcher.Factory(artwork))
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(PlatformContext.INSTANCE, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        .build()
