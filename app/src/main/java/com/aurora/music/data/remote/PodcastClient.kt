package com.aurora.music.data.remote

import com.aurora.music.data.Podcast
import com.aurora.music.data.PodcastEpisode
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class ItunesResult(val results: List<ItunesPodcast>? = emptyList())
private data class ItunesPodcast(
    @SerializedName("collectionName") val collectionName: String? = "",
    @SerializedName("artistName") val artistName: String? = "",
    @SerializedName("feedUrl") val feedUrl: String? = "",
    @SerializedName("artworkUrl600") val artworkUrl600: String? = "",
    @SerializedName("artworkUrl100") val artworkUrl100: String? = "",
)

class PodcastClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // null on transport failure empty on no hits
    suspend fun search(query: String, limit: Int = 40): List<Podcast>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://itunes.apple.com/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("term", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = getString(url.toString()) ?: return@withContext null
        runCatching {
            gson.fromJson(body, ItunesResult::class.java).results.orEmpty().mapNotNull { it.toPodcast() }
        }.getOrDefault(emptyList())
    }

    suspend fun episodes(feedUrl: String): FeedContent = withContext(Dispatchers.IO) {
        if (feedUrl.isBlank()) return@withContext FeedContent()
        val xml = getString(feedUrl) ?: return@withContext FeedContent()
        runCatching { parseFeed(xml) }.getOrDefault(FeedContent())
    }

    private fun getString(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun ItunesPodcast.toPodcast(): Podcast? {
        val feed = feedUrl?.takeIf { it.isNotBlank() } ?: return null
        return Podcast(
            feedUrl = feed,
            title = collectionName,
            author = artistName,
            imageUrl = artworkUrl600?.takeIf { it.isNotBlank() } ?: artworkUrl100,
        )
    }

    data class FeedContent(
        val title: String = "",
        val author: String = "",
        val imageUrl: String = "",
        val description: String = "",
        val episodes: List<PodcastEpisode> = emptyList(),
    )

    private fun parseFeed(xml: String): FeedContent {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(xml.reader())

        var channelTitle = ""
        var channelAuthor = ""
        var channelImage = ""
        var channelDesc = ""
        val episodes = mutableListOf<PodcastEpisode>()
        var inItem = false

        var title = ""; var audioUrl = ""; var guid = ""; var pubDate = ""
        var duration = ""; var image = ""; var desc = ""
        // distinguish channel-level <image><url> from inside an item
        var inChannelImage = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty()
                    when {
                        name.equals("item", true) -> {
                            inItem = true
                            title = ""; audioUrl = ""; guid = ""; pubDate = ""; duration = ""; image = ""; desc = ""
                        }
                        name.equals("image", true) && !inItem -> inChannelImage = true
                        name.equals("enclosure", true) && inItem -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            // take first audio or type-less enclosure some feeds ship a video enclosure first
                            if (url.isNotBlank() && (type.isBlank() || type.startsWith("audio")) && audioUrl.isBlank()) audioUrl = url
                        }
                        name.equals("itunes:image", true) -> {
                            val href = parser.getAttributeValue(null, "href").orEmpty()
                            if (href.isNotBlank()) { if (inItem) image = href else if (channelImage.isBlank()) channelImage = href }
                        }
                        name.equals("media:thumbnail", true) && inItem -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            if (url.isNotBlank() && image.isBlank()) image = url
                        }
                        name.equals("media:content", true) && inItem && audioUrl.isBlank() -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            if (url.isNotBlank() && type.startsWith("audio")) audioUrl = url
                        }
                        name.equals("title", true) -> { val t = parser.nextText().trim(); if (inItem) title = t else if (!inChannelImage && channelTitle.isBlank()) channelTitle = t }
                        name.equals("itunes:author", true) && !inItem -> { val a = parser.nextText().trim(); if (channelAuthor.isBlank()) channelAuthor = a }
                        name.equals("url", true) && inChannelImage -> { val u = parser.nextText().trim(); if (channelImage.isBlank()) channelImage = u }
                        name.equals("guid", true) && inItem -> guid = parser.nextText().trim()
                        name.equals("pubDate", true) && inItem -> pubDate = parser.nextText().trim()
                        name.equals("itunes:duration", true) && inItem -> duration = parser.nextText().trim()
                        name.equals("description", true) -> { val d = parser.nextText().trim(); if (inItem) { if (desc.isBlank()) desc = d } else if (channelDesc.isBlank()) channelDesc = d }
                        name.equals("itunes:summary", true) && inItem && desc.isBlank() -> desc = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty()
                    when {
                        name.equals("image", true) && inChannelImage -> inChannelImage = false
                        name.equals("item", true) -> {
                            inItem = false
                            if (audioUrl.isNotBlank()) {
                                episodes += PodcastEpisode(
                                    id = guid.ifBlank { audioUrl },
                                    title = title,
                                    audioUrl = audioUrl,
                                    imageUrl = image,
                                    durationSec = parseDuration(duration),
                                    pubDateMs = parsePubDate(pubDate),
                                    description = desc,
                                    podcastTitle = channelTitle,
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        // backfill show title onto episodes parsed before channel title appeared
        val fixed = if (channelTitle.isNotBlank()) episodes.map { if (it.podcastTitle.isBlank()) it.copy(podcastTitle = channelTitle) else it } else episodes
        return FeedContent(channelTitle, channelAuthor, channelImage, channelDesc, fixed)
    }

    // itunes:duration may be raw seconds MM:SS or HH:MM:SS
    private fun parseDuration(raw: String): Int {
        if (raw.isBlank()) return 0
        if (!raw.contains(":")) return raw.toFloatOrNull()?.toInt() ?: 0
        val parts = raw.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun parsePubDate(raw: String): Long {
        if (raw.isBlank()) return 0
        for (fmt in PUB_DATE_FORMATS) {
            val ms = runCatching { SimpleDateFormat(fmt, Locale.US).parse(raw)?.time }.getOrNull()
            if (ms != null) return ms
        }
        return 0
    }

    private companion object {
        const val USER_AGENT = "Aurora/1.0 ( https://github.com/aurora-music/aurora )"
        val PUB_DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z",
        )
    }
}
