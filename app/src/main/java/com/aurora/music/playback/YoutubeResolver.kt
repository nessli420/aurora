package com.aurora.music.playback

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

// blocks on network must run off main thread
class YoutubeResolver {

    private val http = OkHttpClient()
    private data class CachedStream(val url: String, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CachedStream>()
    data class PlaybackStream(val url: String, val mimeType: String? = null, val videoUrl: String? = null)
    private data class CachedPlayback(val stream: PlaybackStream, val expiresAt: Long)
    private val playbackCache = ConcurrentHashMap<String, CachedPlayback>()
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

    fun resolveVideo(videoId: String, maxHeight: Int? = null): String? {
        return resolvePlayback(videoId, maxHeight)?.url
    }

    fun findMusicVideo(artist: String, title: String, durationSec: Int): String? {
        if (artist.isBlank() || title.isBlank()) return null
        return runCatching {
            ensureInit()
            val yt = ServiceList.YouTube
            val artistName = artist.substringBefore(',').substringBefore(" feat.").trim()
            val titleKey = videoSearchText(title.substringBefore(" ("))
            val sourceTitle = videoSearchText(title)
            val artistKey = videoSearchText(artistName)
            val titleWords = titleKey.split(' ').filter { it.length > 1 }.toSet()
            if (titleWords.isEmpty()) return@runCatching null
            data class Candidate(val item: StreamInfoItem, val score: Double, val preferred: Boolean,
                val artistUploaded: Boolean, val exactTitle: Boolean)
            val found = LinkedHashMap<String, Candidate>()
            val queries = listOf("$artistName $title official music video", "$artistName $title visualizer", "$artistName $title")
            for (query in queries) {
                val search = yt.getSearchExtractor(query, emptyList(), "")
                search.fetchPage()
                search.initialPage.items.filterIsInstance<StreamInfoItem>().take(25).forEach { item ->
                    val name = videoSearchText(item.name)
                    val uploader = videoSearchText(item.uploaderName.orEmpty())
                    val overlap = titleWords.count { it in name.split(' ') }.toDouble() / titleWords.size
                    val artistMatch = hasVideoPhrase(name, artistKey) || hasVideoPhrase(uploader, artistKey)
                    if (overlap < 0.7 || !artistMatch || item.duration <= 0) return@forEach
                    val durationGap = if (durationSec > 0) abs(item.duration - durationSec).toDouble() else 0.0
                    if (durationSec > 0 && durationGap > maxOf(120.0, durationSec * 0.6)) return@forEach
                    val preferred = name.contains("music video") || name.contains("official video") ||
                        name.contains("musikvideo") || name.contains("visualizer") || name.contains("visualiser")
                    val artistUploader = uploader == artistKey || uploader == "$artistKey vevo" || uploader == "$artistKey official"
                    val unwanted = listOf("cover", "reaction", "tutorial", "karaoke", "sped up", "slowed", "nightcore", "remaster", "remastered", "live", "version")
                        .any { hasVideoPhrase(name, it) && !hasVideoPhrase(sourceTitle, it) }
                    val exactTitle = hasVideoPhrase(name, titleKey)
                    val score = overlap * 100 + (if (exactTitle) 25 else 0) +
                        (if (artistMatch) 25 else 0) + (if (artistUploader) 20 else 0) +
                        log10(item.viewCount.coerceAtLeast(0) + 1.0) * 5 -
                        durationGap / 12 - (if (unwanted) 70 else 0)
                    if (!unwanted) found[item.url] = Candidate(item, score, preferred, artistUploader, exactTitle)
                }
            }
            val ranked = found.values.sortedWith(compareByDescending<Candidate> { it.artistUploaded && it.preferred }
                .thenByDescending { it.artistUploaded && it.exactTitle }
                .thenByDescending { it.preferred }.thenByDescending { it.score })
            ranked.take(5).firstNotNullOfOrNull { candidate ->
                val id = candidate.item.url.substringAfter("v=", "").take(11)
                if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) runCatching { resolveVisualStream(id) }.getOrNull() else null
            }
        }.getOrElse { Log.w(TAG, "Video search failed: ${it.javaClass.simpleName}"); null }
    }

    private fun resolveVisualStream(videoId: String): String? {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = (info.videoOnlyStreams + info.videoStreams)
            .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        fun height(stream: org.schabi.newpipe.extractor.stream.VideoStream) =
            stream.resolution.orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        return streams.filter { height(it) in 144..720 }.ifEmpty { streams }
            .sortedByDescending(::height).distinctBy { it.content }.take(6)
            .firstOrNull { canOpenStream(it.content) }?.content
    }

    private fun videoSearchText(value: String): String = value.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun hasVideoPhrase(value: String, phrase: String): Boolean =
        phrase.isNotBlank() && " $value ".contains(" $phrase ")

    fun resolvePlayback(videoId: String, maxHeight: Int? = null): PlaybackStream? {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val qualityKey = maxHeight?.coerceAtLeast(144) ?: Int.MAX_VALUE
        val cacheKey = "$videoId:${maxHeight?.coerceAtLeast(144) ?: "max"}"
        playbackCache[cacheKey]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.stream }
        return runCatching {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val live = info.streamType in setOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM)
            val audio = info.audioStreams.filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedByDescending { it.averageBitrate }.distinctBy { it.content }.take(6)
                .firstOrNull { canOpenStream(it.content) }
            val videos = (info.videoOnlyStreams + info.videoStreams)
                .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            fun height(stream: org.schabi.newpipe.extractor.stream.VideoStream) = stream.getResolution().orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            val candidates = videos.filter { height(it) in 1..qualityKey }
                .ifEmpty { videos.sortedBy(::height) }
                .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream>(::height)
                    .thenByDescending { it.fps })
            val video = if (live) null else candidates.distinctBy { it.content }.take(6).firstOrNull { canOpenStream(it.content) }
            val muxed = if (audio != null || live) null else info.videoStreams
                .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedBy(::height).distinctBy { it.content }.take(6).firstOrNull { canOpenStream(it.content) }
            val stream = when {
                live && info.hlsUrl.isNotBlank() -> PlaybackStream(info.hlsUrl, "application/x-mpegURL")
                live && info.dashMpdUrl.isNotBlank() -> PlaybackStream(info.dashMpdUrl, "application/dash+xml")
                live -> null
                audio != null -> PlaybackStream(audio.content, videoUrl = video?.content)
                muxed != null -> PlaybackStream(muxed.content)
                info.hlsUrl.isNotBlank() -> PlaybackStream(info.hlsUrl, "application/x-mpegURL")
                info.dashMpdUrl.isNotBlank() -> PlaybackStream(info.dashMpdUrl, "application/dash+xml")
                else -> null
            }
            stream?.also {
                remember("video:$videoId", it.url)
                val now = System.currentTimeMillis()
                playbackCache.entries.removeAll { entry -> entry.value.expiresAt <= now }
                if (playbackCache.size >= 256) playbackCache.clear()
                playbackCache[cacheKey] = CachedPlayback(it, minOf(cache["video:$videoId"]!!.expiresAt, now + if (live) 60_000 else 600_000))
            }
        }.getOrElse { Log.w(TAG, "Stream extraction failed for $videoId: ${it.javaClass.simpleName}"); null }
    }

    private fun canOpenStream(url: String): Boolean {
        val source = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000).createDataSource()
        return try {
            source.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).build())
            true
        } catch (_: java.io.IOException) {
            false
        } finally {
            runCatching { source.close() }
        }
    }

    fun resolveSentinel(uri: android.net.Uri): String? {
        if (uri.scheme != "aurora-yt") return null
        return if (uri.host == "video") resolveVideo(
            uri.lastPathSegment.orEmpty(),
            uri.getQueryParameter("quality")?.toIntOrNull(),
        )
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
