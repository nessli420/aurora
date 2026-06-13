package com.decent.usbaudio.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Composite [DataSource.Factory] with local cache for network streams.
 *
 * Routes between:
 * - [SftpDataSource] for `sftp://` URIs
 * - [DefaultDataSource] for everything else (`file://`, `content://`, `http://`, `https://`)
 *
 * All network streams (SFTP + HTTP/HTTPS) are cached locally. This means:
 * - First play: streams from network (SFTP ~4-6s seek, HTTP instant)
 * - Subsequent plays or seeks within cached data: instant (local disk)
 * - Cache size: 500MB LRU (oldest evicted first)
 *
 * Local file:// URIs bypass the cache entirely.
 *
 * Usage with ExoPlayer:
 * ```kotlin
 * val dataSourceFactory = DecentDataSourceFactory(context)
 * val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
 * val player = ExoPlayer.Builder(context)
 *     .setMediaSourceFactory(mediaSourceFactory)
 *     .build()
 * ```
 */
@OptIn(UnstableApi::class)
class DecentDataSourceFactory(context: Context) : DataSource.Factory {

    private val cache: SimpleCache
    private val upstreamFactory: DataSource.Factory
    private val cachedFactory: CacheDataSource.Factory

    init {
        // Local disk cache — 500MB, LRU eviction
        val cacheDir = File(context.cacheDir, "decent_stream_cache")
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024), // 500 MB
            StandaloneDatabaseProvider(context)
        )

        // Upstream: routes SFTP to SftpDataSource, everything else to default
        upstreamFactory = RoutingDataSourceFactory(
            DefaultDataSource.Factory(context),
            SftpDataSource.Factory()
        )

        // Cache wraps the upstream — all network reads are cached locally
        cachedFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    override fun createDataSource(): DataSource {
        return cachedFactory.createDataSource()
    }

    /**
     * Internal factory that routes based on URI scheme.
     */
    private class RoutingDataSourceFactory(
        private val defaultFactory: DataSource.Factory,
        private val sftpFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return RoutingDataSource(defaultFactory.createDataSource(), sftpFactory.createDataSource())
        }
    }

    /**
     * DataSource that inspects the URI on open() and delegates to the appropriate backend.
     */
    private class RoutingDataSource(
        private val defaultSource: DataSource,
        private val sftpSource: DataSource
    ) : DataSource by defaultSource {

        private var activeSource: DataSource? = null

        @OptIn(UnstableApi::class)
        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            val source = if (SftpDataSource.supportsUri(dataSpec.uri)) sftpSource else defaultSource
            activeSource = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeSource?.read(buffer, offset, length)
                ?: defaultSource.read(buffer, offset, length)
        }

        override fun getUri(): android.net.Uri? = activeSource?.uri

        override fun close() {
            activeSource?.close()
            activeSource = null
        }
    }
}
