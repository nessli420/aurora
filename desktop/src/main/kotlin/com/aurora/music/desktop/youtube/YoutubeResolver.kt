package com.aurora.music.desktop.youtube

import com.aurora.music.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

// youtube audio for spotify, youtube music and soundcloud fallbacks; every call blocks on the network
data class MusicVideo(val videoId: String, val streamUrl: String)

class YoutubeResolver(http: OkHttpClient, private val chosenVideo: (String) -> String? = { null }) {
    private val http = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private data class Cached(val url: String, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    @Volatile private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(Fetcher(http))
                initialized = true
            }
        }
    }

    fun signatureTimestamp(videoId: String): Int? {
        ensureInit()
        return runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
    }

    // aurora-yt://video/<id> plays that video; aurora-yt://<key>?q=..&dur=.. plays the best search match
    fun resolveSentinel(sentinel: String): String? {
        val uri = runCatching { URI(sentinel) }.getOrNull() ?: return null
        if (uri.scheme != "aurora-yt") return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val name = part.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }.toMap()
        return if (uri.host == "video") resolveVideo(uri.path.orEmpty().trim('/').substringAfterLast('/'))
        else chosenVideo(uri.host.orEmpty())?.let { resolveVideo(it) }
            ?: resolveSearch(uri.host.orEmpty(), query["q"].orEmpty(), query["dur"]?.toIntOrNull() ?: 0)
    }

    fun resolveSearch(key: String, query: String, durationSec: Int): String? {
        cached("search:$key")?.let { return it }
        if (query.isBlank()) return null
        return runCatching {
            ensureInit()
            val search = ServiceList.YouTube.getSearchExtractor(query, listOf("music_songs"), "").apply { fetchPage() }
            val items = search.initialPage.items.filterIsInstance<StreamInfoItem>()
            val pick = (if (durationSec > 0) items.minByOrNull { abs(it.duration - durationSec) } else null) ?: items.firstOrNull()
                ?: return@runCatching null
            val id = pick.url.substringAfter("v=", "").take(11)
            resolveVideo(id)?.also { remember("search:$key", it) }
        }.getOrElse { AppLog.d(TAG, "search failed: ${it.javaClass.simpleName}"); null }
    }

    fun resolveVideo(videoId: String): String? {
        if (!videoId.matches(VIDEO_ID)) return null
        cached("video:$videoId")?.let { return it }
        return runCatching {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val live = info.streamType in setOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM)
            val audio = info.audioStreams.filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedByDescending { it.averageBitrate }.distinctBy { it.content }.take(6)
                .firstOrNull { canOpen(it.content) }?.content
            // when the audio-only streams are withheld, the smallest muxed stream still carries the sound
            val muxed = if (audio != null || live) null else info.videoStreams
                .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedBy { it.resolution.orEmpty().takeWhile(Char::isDigit).toIntOrNull() ?: 9999 }
                .distinctBy { it.content }.take(6).firstOrNull { canOpen(it.content) }?.content
            val url = audio ?: muxed ?: info.hlsUrl.takeIf { it.isNotBlank() } ?: info.dashMpdUrl.takeIf { it.isNotBlank() }
            url?.also { if (!live) remember("video:$videoId", it) }
        }.getOrElse { AppLog.w(TAG, "stream extraction failed for $videoId: ${it.javaClass.simpleName}"); null }
    }

    data class SearchResult(val videoId: String, val title: String, val artist: String, val durationSec: Int, val thumbnailUrl: String)
    data class DownloadInfo(val tags: com.aurora.music.data.YouTubeTrackTags, val thumbnailUrl: String,
        val options: List<com.aurora.music.data.YouTubeAudioOption>)

    fun searchSongs(query: String): List<SearchResult> {
        ensureInit()
        fun search(filter: List<String>) = ServiceList.YouTube.getSearchExtractor(query, filter, "").apply { fetchPage() }
            .initialPage.items.filterIsInstance<StreamInfoItem>()
        return search(listOf("music_songs")).ifEmpty { search(listOf("videos")) }.mapNotNull { item ->
            val id = item.url.substringAfter("v=", "").take(11)
            if (!id.matches(VIDEO_ID) || item.duration <= 0) null
            else SearchResult(id, item.name, item.uploaderName.orEmpty(), item.duration.toInt(),
                item.thumbnails.maxByOrNull { it.height }?.url.orEmpty())
        }.distinctBy { it.videoId }
    }

    fun downloadInfo(videoId: String): DownloadInfo {
        require(videoId.matches(VIDEO_ID))
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val options = info.audioStreams
            .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .mapNotNull { stream ->
                val format = when {
                    stream.format == org.schabi.newpipe.extractor.MediaFormat.M4A -> com.aurora.music.data.YouTubeDownloadFormat.M4A
                    stream.format == org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS || stream.format == org.schabi.newpipe.extractor.MediaFormat.OPUS ||
                        stream.codec.orEmpty().contains("opus", ignoreCase = true) -> com.aurora.music.data.YouTubeDownloadFormat.OPUS
                    else -> null
                }
                format?.let { com.aurora.music.data.YouTubeAudioOption(stream.content, it, stream.averageBitrate) }
            }
        val year = runCatching { info.uploadDate?.offsetDateTime()?.year }.getOrNull()
        val description = info.description?.let {
            if (it.type == org.schabi.newpipe.extractor.stream.Description.HTML) htmlText(it.content.orEmpty()) else it.content.orEmpty()
        }.orEmpty()
        return DownloadInfo(com.aurora.music.data.YouTubeDownloads.tags(info.name.orEmpty(), info.uploaderName.orEmpty(), description, year),
            info.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), options)
    }

    private fun htmlText(html: String): String = html.replace(Regex("""(?i)<br\s*/?>"""), "\n").replace(Regex("<[^>]+>"), "")
        .replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")) { match ->
            when (val name = match.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> runCatching { String(Character.toChars(if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt())) }
                    .getOrDefault(match.value)
            }
        }

    fun findMusicVideo(artist: String, title: String, durationSec: Int): MusicVideo? {
        if (artist.isBlank() || title.isBlank()) return null
        return runCatching {
            ensureInit()
            val yt = org.schabi.newpipe.extractor.ServiceList.YouTube
            val artistName = artist.substringBefore(',').substringBefore(" feat.").trim()
            val titleKey = videoSearchText(title.substringBefore(" ("))
            val sourceTitle = videoSearchText(title)
            val artistKey = videoSearchText(artistName)
            val titleWords = titleKey.split(' ').filter { it.length > 1 }.toSet()
            if (titleWords.isEmpty()) return@runCatching null
            data class Candidate(val item: org.schabi.newpipe.extractor.stream.StreamInfoItem, val score: Double, val preferred: Boolean,
                val artistUploaded: Boolean, val exactTitle: Boolean)
            val found = LinkedHashMap<String, Candidate>()
            val queries = listOf("$artistName $title official music video", "$artistName $title visualizer", "$artistName $title")
            for (query in queries) {
                val search = yt.getSearchExtractor(query, emptyList(), "")
                search.fetchPage()
                search.initialPage.items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>().take(25).forEach { item ->
                    val name = videoSearchText(item.name)
                    val uploader = videoSearchText(item.uploaderName.orEmpty())
                    val overlap = titleWords.count { it in name.split(' ') }.toDouble() / titleWords.size
                    val artistMatch = hasVideoPhrase(name, artistKey) || hasVideoPhrase(uploader, artistKey)
                    if (overlap < 0.7 || !artistMatch || item.duration <= 0) return@forEach
                    val durationGap = if (durationSec > 0) kotlin.math.abs(item.duration - durationSec).toDouble() else 0.0
                    if (durationSec > 0 && durationGap > maxOf(120.0, durationSec * 0.6)) return@forEach
                    val preferred = name.contains("music video") || name.contains("official video") ||
                        name.contains("musikvideo") || name.contains("visualizer") || name.contains("visualiser")
                    val artistUploader = uploader == artistKey || uploader == "$artistKey vevo" || uploader == "$artistKey official"
                    val unwanted = listOf("cover", "reaction", "tutorial", "karaoke", "sped up", "slowed", "nightcore", "remaster", "remastered", "live", "version")
                        .any { hasVideoPhrase(name, it) && !hasVideoPhrase(sourceTitle, it) }
                    val exactTitle = hasVideoPhrase(name, titleKey)
                    val score = overlap * 100 + (if (exactTitle) 25 else 0) +
                        (if (artistMatch) 25 else 0) + (if (artistUploader) 20 else 0) +
                        kotlin.math.log10(item.viewCount.coerceAtLeast(0) + 1.0) * 5 -
                        durationGap / 12 - (if (unwanted) 70 else 0)
                    if (!unwanted) found[item.url] = Candidate(item, score, preferred, artistUploader, exactTitle)
                }
            }
            val ranked = found.values.sortedWith(compareByDescending<Candidate> { it.artistUploaded && it.preferred }
                .thenByDescending { it.artistUploaded && it.exactTitle }
                .thenByDescending { it.preferred }.thenByDescending { it.score })
            ranked.take(5).firstNotNullOfOrNull { candidate ->
                val id = candidate.item.url.substringAfter("v=", "").take(11)
                if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                    runCatching { resolveVisualStream(id, 720)?.let { MusicVideo(id, it) } }.getOrNull()
                } else null
            }
        }.getOrElse { com.aurora.music.util.AppLog.w("YoutubeResolver", "Video search failed: ${it.javaClass.simpleName}"); null }
    }

    fun resolveVisualStream(videoId: String, maxHeight: Int?): String? {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        ensureInit()
        val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = (info.videoOnlyStreams + info.videoStreams)
            .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
        fun height(stream: org.schabi.newpipe.extractor.stream.VideoStream) =
            stream.resolution.orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        val candidates = streams.filter { height(it) in 144..(maxHeight ?: Int.MAX_VALUE) }
            .ifEmpty { streams.filter { height(it) > 0 }.sortedBy(::height).take(1) }
        return candidates.sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream>(::height)
            .thenByDescending { it.codec.orEmpty().contains("avc", ignoreCase = true) || it.codec.orEmpty().contains("h264", ignoreCase = true) }
            .thenByDescending { it.fps })
            .distinctBy { it.content }.take(12)
            .firstOrNull { canOpen(it.content) }?.content
    }

    private fun videoSearchText(value: String): String = value.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun hasVideoPhrase(value: String, phrase: String): Boolean =
        phrase.isNotBlank() && " $value ".contains(" $phrase ")

    private fun canOpen(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", USER_AGENT).build()).execute()
            .use { it.code in 200..299 }
    }.getOrDefault(false)

    private fun cached(key: String): String? = cache[key]?.let {
        if (it.expiresAt > System.currentTimeMillis()) it.url else { cache.remove(key, it); null }
    }

    private fun remember(key: String, url: String) {
        val now = System.currentTimeMillis()
        val expires = runCatching {
            URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()?.times(1000)
        }.getOrNull()
        cache.entries.removeIf { it.value.expiresAt <= now }
        if (cache.size >= 256) cache.clear()
        cache[key] = Cached(url, minOf(expires?.minus(60_000) ?: Long.MAX_VALUE, now + 10 * 60_000))
    }

    private class Fetcher(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: NpRequest): NpResponse {
            val builder = Request.Builder().url(request.url())
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            if (request.headers()["User-Agent"].isNullOrEmpty()) builder.header("User-Agent", USER_AGENT)
            val data = request.dataToSend()
            builder.method(request.httpMethod(), data?.toRequestBody(null, 0, data.size))
            client.newCall(builder.build()).execute().use { response ->
                return NpResponse(response.code, response.message, response.headers.toMultimap(), response.body?.string(), response.request.url.toString())
            }
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "YtResolver"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
