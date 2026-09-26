package com.aurora.music.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** A YouTube item that could not be resolved or opened; the service skips it instead of stopping. */
class YoutubeStreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Resolves before choosing the extractor, so live manifests reach HLS/DASH playback. */
class YoutubeMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val resolver: YoutubeResolver,
    private val preferred: suspend (MediaItem) -> MediaItem = { it },
    private val prepare: (MediaItem) -> MediaItem = { it },
    private val resolveStream: suspend (MediaItem) -> YoutubeResolver.PlaybackStream? = { item ->
        val uri = item.localConfiguration!!.uri
        if (uri.host == "video") resolver.resolvePlayback(uri.lastPathSegment.orEmpty(), uri.getQueryParameter("quality")?.toIntOrNull())
        else resolver.resolveSentinel(uri)?.let { YoutubeResolver.PlaybackStream(it) }
    },
) : MediaSource.Factory {
    private class Prefetch(val result: Deferred<YoutubeResolution>, val startedAt: Long)
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetched = ConcurrentHashMap<String, Prefetch>()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val prepared = prepare(mediaItem)
        return if (prepared.isYoutube()) YoutubeMediaSource(prepared, delegate) { resolution(prepared) }
        else delegate.createMediaSource(prepared)
    }

    /**
     * Starts resolving an upcoming YouTube item while the current track still plays (and keeps the
     * device awake), so the transition doesn't resolve from scratch in the silent gap where a
     * backgrounded device may sleep.
     */
    fun prefetch(mediaItem: MediaItem) {
        val prepared = prepare(mediaItem)
        if (!prepared.isYoutube()) return
        val now = SystemClock.elapsedRealtime()
        prefetched.entries.removeAll { now - it.value.startedAt > PREFETCH_TTL_MS }
        while (prefetched.size >= MAX_PREFETCHED) {
            val oldest = prefetched.entries.minByOrNull { it.value.startedAt } ?: break
            prefetched.remove(oldest.key, oldest.value)
        }
        prefetched.computeIfAbsent(key(prepared)) { Prefetch(prefetchScope.async { resolve(prepared) }, now) }
    }

    private suspend fun resolution(item: MediaItem): YoutubeResolution {
        val key = key(item)
        prefetched[key]?.takeIf { SystemClock.elapsedRealtime() - it.startedAt <= PREFETCH_TTL_MS }?.let { pending ->
            val result = try { pending.result.await() } catch (e: CancellationException) { coroutineContext.ensureActive(); null }
            if (result?.stream != null) return result
            prefetched.remove(key, pending)
        }
        return resolve(item)
    }

    private suspend fun resolve(item: MediaItem): YoutubeResolution {
        val selected = prepare(preferred(item))
        val uri = selected.localConfiguration!!.uri
        if (uri.scheme != "aurora-yt") return YoutubeResolution(selected, YoutubeResolver.PlaybackStream(uri.toString()))
        var error: Throwable? = null
        // Extraction and stream probes fail transiently on flaky or just-woken networks; a later attempt usually succeeds.
        for (attempt in 0 until RESOLVE_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_BACKOFF_MS * attempt)
            val stream = try { resolveStream(selected) } catch (e: Exception) { error = e; null }
            coroutineContext.ensureActive()
            if (stream != null) return YoutubeResolution(selected, stream)
            Log.w(TAG, "YouTube resolve attempt ${attempt + 1}/$RESOLVE_ATTEMPTS failed")
        }
        return YoutubeResolution(selected, null, error)
    }

    private fun MediaItem.isYoutube() = localConfiguration?.uri?.scheme == "aurora-yt"
    private fun key(item: MediaItem) = item.mediaId + "\u0000" + item.localConfiguration?.uri

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { delegate.setDrmSessionManagerProvider(provider) }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { delegate.setLoadErrorHandlingPolicy(policy) }

    private companion object {
        const val TAG = "YtMediaSource"
        const val RESOLVE_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 1_500L
        const val MAX_PREFETCHED = 8
        const val PREFETCH_TTL_MS = 20 * 60_000L
    }
}

private class YoutubeResolution(val item: MediaItem, val stream: YoutubeResolver.PlaybackStream?, val error: Throwable? = null)

private class YoutubeMediaSource(
    private val item: MediaItem,
    private val factory: MediaSource.Factory,
    private val resolve: suspend () -> YoutubeResolution,
) : CompositeMediaSource<Unit>() {
    private var publishedItem = item
    private var child: MediaSource? = null
    private var scope: CoroutineScope? = null
    private var failure: IOException? = null
    private var generation = 0

    override fun getMediaItem() = publishedItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(Looper.myLooper()!!)
        val attempt = ++generation
        failure = null
        scope = CoroutineScope(Dispatchers.IO + Job()).also { work ->
            work.launch {
                val result = runCatching { resolve() }
                handler.post {
                    if (generation != attempt) return@post
                    val stream = result.getOrNull()?.stream
                    if (stream == null) {
                        failure = YoutubeStreamException("This YouTube stream is unavailable. Please try again.",
                            result.exceptionOrNull() ?: result.getOrNull()?.error)
                    } else {
                        try {
                            publishedItem = result.getOrThrow().item
                            val audioItem = publishedItem.buildUpon().setUri(stream.url).setMimeType(stream.mimeType).apply {
                                // HLS/DASH segments and video use separate resources, never the progressive audio key.
                                if (androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(android.net.Uri.parse(stream.url), stream.mimeType)
                                    != androidx.media3.common.C.CONTENT_TYPE_OTHER) setCustomCacheKey(null)
                            }.build()
                            val audio = factory.createMediaSource(audioItem)
                            child = stream.videoUrl?.let { video ->
                                MergingMediaSource(true, audio, factory.createMediaSource(item.buildUpon().setUri(video).setMimeType(null).setCustomCacheKey(null).build()))
                            } ?: audio
                            prepareChildSource(Unit, child!!)
                        } catch (error: Exception) {
                            failure = YoutubeStreamException("This YouTube stream could not be opened.", error)
                        }
                    }
                }
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, timeline: Timeline) {
        refreshSourceInfo(object : ForwardingTimeline(timeline) {
            override fun getWindow(index: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window =
                super.getWindow(index, window, defaultPositionProjectionUs).also { it.mediaItem = publishedItem }
        })
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) = checkNotNull(child).releasePeriod(mediaPeriod)

    override fun releaseSourceInternal() {
        generation++
        scope?.cancel()
        scope = null
        super.releaseSourceInternal()
        child = null
        failure = null
    }
}
