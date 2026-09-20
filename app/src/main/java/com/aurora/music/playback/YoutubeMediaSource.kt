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
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.uri?.scheme == "aurora-yt") YoutubeMediaSource(mediaItem, delegate, resolver)
        else delegate.createMediaSource(mediaItem)

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { delegate.setDrmSessionManagerProvider(provider) }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { delegate.setLoadErrorHandlingPolicy(policy) }
}

private class YoutubeMediaSource(
    private val item: MediaItem,
    private val factory: MediaSource.Factory,
    private val resolver: YoutubeResolver,
) : CompositeMediaSource<Unit>() {
    private var child: MediaSource? = null
    private var scope: CoroutineScope? = null
    private var failure: IOException? = null
    private var generation = 0

    override fun getMediaItem() = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(Looper.myLooper()!!)
        val attempt = ++generation
        failure = null
        scope = CoroutineScope(Dispatchers.IO + Job()).also { work ->
            work.launch {
                val result = runCatching {
                    val uri = item.localConfiguration!!.uri
                    if (uri.host == "video") resolver.resolvePlayback(uri.lastPathSegment.orEmpty())
                    else resolver.resolveSentinel(uri)?.let { YoutubeResolver.PlaybackStream(it) }
                }
                handler.post {
                    if (generation != attempt) return@post
                    val stream = result.getOrNull()
                    if (stream == null) {
                        failure = IOException("This YouTube stream is unavailable. Please try again.", result.exceptionOrNull())
                    } else {
                        try {
                            val audio = factory.createMediaSource(item.buildUpon().setUri(stream.url).setMimeType(stream.mimeType).build())
                            child = stream.videoUrl?.let { video ->
                                MergingMediaSource(true, audio, factory.createMediaSource(item.buildUpon().setUri(video).setMimeType(null).build()))
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
                super.getWindow(index, window, defaultPositionProjectionUs).also { it.mediaItem = item }
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
