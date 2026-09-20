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

// blocks on network must run off main thread
class YoutubeResolver {

    private val http = OkHttpClient()
    private data class CachedStream(val url: String, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CachedStream>()
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

    fun resolve(spotifyId: String, query: String, durationSec: Int): String? {
        cached("spotify:$spotifyId")?.let { return it }
        if (query.isBlank()) return null
        return runCatching {
            ensureInit()
            val yt = ServiceList.YouTube
            val search = yt.getSearchExtractor(query, listOf("music_songs"), "")
            search.fetchPage()
            val items = search.initialPage.items.filterIsInstance<StreamInfoItem>()
            if (items.isEmpty()) return@runCatching null
            val best = if (durationSec > 0) items.minByOrNull { abs(it.duration - durationSec) } else null
            val pick = best ?: items.first()
            val info = StreamInfo.getInfo(yt, pick.url)
            // fall back to progressive muxed stream when audio-only withheld po_token
            val url = info.audioStreams
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?.content
                ?: info.videoStreams
                    .filter { !it.content.isNullOrBlank() }
                    .minByOrNull { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 9999 }
                    ?.content
            if (!url.isNullOrBlank()) {
                remember("spotify:$spotifyId", url)
                Log.d(TAG, "resolved '$query' -> ${pick.name} (${pick.duration}s)")
            } else {
                Log.d(TAG, "no audio stream for '$query'")
            }
            url
        }.getOrElse { Log.d(TAG, "resolve failed '$query': ${it.message}"); null }
    }

    fun resolveVideo(videoId: String): String? {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        cached("video:$videoId")?.let { return it }
        return runCatching {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val url = info.audioStreams.filter { !it.content.isNullOrBlank() }.maxByOrNull { it.averageBitrate }?.content
                ?: info.videoStreams.filter { !it.content.isNullOrBlank() }
                    .minByOrNull { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 9999 }?.content
            url?.takeIf { it.isNotBlank() }?.also { remember("video:$videoId", it) }
        }.getOrNull()
    }

    fun resolveSentinel(uri: android.net.Uri): String? {
        if (uri.scheme != "aurora-yt") return null
        return if (uri.host == "video") resolveVideo(uri.lastPathSegment.orEmpty())
        else resolve(uri.host.orEmpty(), uri.getQueryParameter("q").orEmpty(), uri.getQueryParameter("dur")?.toIntOrNull() ?: 0)
    }

    private fun cached(key: String): String? = cache[key]?.let {
        if (it.expiresAt > System.currentTimeMillis()) it.url else { cache.remove(key, it); null }
    }

    private fun remember(key: String, url: String) {
        val now = System.currentTimeMillis()
        val expires = runCatching { java.net.URI(url).rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()?.times(1000) }.getOrNull()
        cache.entries.removeAll { it.value.expiresAt <= now }
        if (cache.size >= 256) cache.clear()
        cache[key] = CachedStream(url, minOf(expires?.minus(60_000) ?: Long.MAX_VALUE, now + 10 * 60_000))
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
