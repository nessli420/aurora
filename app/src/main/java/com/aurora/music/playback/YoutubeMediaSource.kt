package com.aurora.music.playback

import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** Resolves before choosing the extractor, so live manifests reach HLS/DASH playback. */
class YoutubeMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val resolver: YoutubeResolver,
    private val preferred: suspend (MediaItem) -> MediaItem = { it },
    private val prepare: (MediaItem) -> MediaItem = { it },
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val prepared = prepare(mediaItem)
        return if (prepared.localConfiguration?.uri?.scheme == "aurora-yt") YoutubeMediaSource(prepared, delegate, resolver) { prepare(preferred(it)) }
        else delegate.createMediaSource(prepared)
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { delegate.setDrmSessionManagerProvider(provider) }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { delegate.setLoadErrorHandlingPolicy(policy) }
}

private class YoutubeMediaSource(
    private val item: MediaItem,
    private val factory: MediaSource.Factory,
    private val resolver: YoutubeResolver,
    private val preferred: suspend (MediaItem) -> MediaItem,
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
                val result = runCatching {
                    val selected = preferred(item)
                    val uri = selected.localConfiguration!!.uri
                    val stream = if (uri.scheme != "aurora-yt") YoutubeResolver.PlaybackStream(uri.toString())
                        else if (uri.host == "video") resolver.resolvePlayback(
                            uri.lastPathSegment.orEmpty(), uri.getQueryParameter("quality")?.toIntOrNull(),
                        )
                        else resolver.resolveSentinel(uri)?.let { YoutubeResolver.PlaybackStream(it) }
                    selected to stream
                }
                handler.post {
                    if (generation != attempt) return@post
                    val stream = result.getOrNull()?.second
                    if (stream == null) {
                        failure = IOException("This YouTube stream is unavailable. Please try again.", result.exceptionOrNull())
                    } else {
                        try {
                            publishedItem = result.getOrThrow().first
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
                            failure = IOException("This YouTube stream could not be opened.", error)
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
