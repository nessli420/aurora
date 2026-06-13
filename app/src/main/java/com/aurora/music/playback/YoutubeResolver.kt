package com.aurora.music.playback

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

/**
 * Resolves a Spotify track (by search query) to a playable YouTube audio stream URL via
 * NewPipeExtractor. Results are cached per Spotify id. Calls block on the network, so they must run
 * off the main thread; the playback layer calls this on ExoPlayer's loader thread via a
 * ResolvingDataSource.
 */
class YoutubeResolver {

    private val http = OkHttpClient()
    private val cache = ConcurrentHashMap<String, String>()
    @Volatile private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader(http))
                initialized = true
            }
        }
    }

    /** Best-effort: returns a direct audio URL for [query] (title + artist), or null. Cached by [spotifyId]. */
    fun resolve(spotifyId: String, query: String, durationSec: Int): String? {
        cache[spotifyId]?.let { return it }
        if (query.isBlank()) return null
        return runCatching {
            ensureInit()
            val yt = ServiceList.YouTube
            val search = yt.getSearchExtractor(query, listOf("music_songs"), "")
            search.fetchPage()
            val items = search.initialPage.items.filterIsInstance<StreamInfoItem>()
            if (items.isEmpty()) return@runCatching null
            // Prefer the result whose duration is closest to the Spotify track (when known).
            val best = if (durationSec > 0) items.minByOrNull { abs(it.duration - durationSec) } else null
            val pick = best ?: items.first()
            val info = StreamInfo.getInfo(yt, pick.url)
            // Prefer an adaptive audio-only stream; if YouTube withholds those (po_token), fall back
            // to a progressive (muxed) stream — it carries audio and ExoPlayer plays it audio-only.
            val url = info.audioStreams
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?.content
                ?: info.videoStreams
                    .filter { !it.content.isNullOrBlank() }
                    .minByOrNull { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 9999 }
                    ?.content
            if (!url.isNullOrBlank()) {
                cache[spotifyId] = url
                Log.d(TAG, "resolved '$query' -> ${pick.name} (${pick.duration}s)")
            } else {
                Log.d(TAG, "no audio stream for '$query'")
            }
            url
        }.getOrElse { Log.d(TAG, "resolve failed '$query': ${it.message}"); null }
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: NpRequest): NpResponse {
            val builder = okhttp3.Request.Builder().url(request.url())
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            if (request.headers()["User-Agent"].isNullOrEmpty()) builder.header("User-Agent", USER_AGENT)
            val data = request.dataToSend()
            val body = data?.toRequestBody(null, 0, data.size)
            builder.method(request.httpMethod(), body)
            client.newCall(builder.build()).execute().use { response ->
                return NpResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string(),
                    response.request.url.toString(),
                )
            }
        }

        companion object {
            const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    private companion object { const val TAG = "YtResolver" }
}
