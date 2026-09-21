package com.aurora.music.data.cache

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor

/** Rebuild the LRU policy under the cache lock when the user changes the quota. */
internal class ResizableCacheEvictor(bytes: Long, private val changed: () -> Unit) : CacheEvictor {
    private var delegate = LeastRecentlyUsedCacheEvictor(bytes)

    fun resize(cache: Cache, bytes: Long) = synchronized(cache) {
        val spans = cache.keys.flatMap { cache.getCachedSpans(it) }.sortedBy { it.lastTouchTimestamp }
        delegate = LeastRecentlyUsedCacheEvictor(bytes)
        spans.forEach { delegate.onSpanAdded(cache, it) }
        changed()
    }

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() { delegate.onCacheInitialized(); changed() }
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = delegate.onStartFile(cache, key, position, length)
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { delegate.onSpanAdded(cache, span); changed() }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { delegate.onSpanRemoved(cache, span); changed() }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = delegate.onSpanTouched(cache, oldSpan, newSpan)
}
