package com.aurora.music.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Enriched, presentation-ready info about an artist, assembled from MusicBrainz + Wikipedia/Wikidata.
 * Cached in [com.aurora.music.data.ArtistInfoStore]; non-null fields (the cache is version-gated like
 * SonicStore so a schema change invalidates it, sidestepping the Gson missing-key trap). [found] is
 * false when nothing useful was located, so the miss can be cached too.
 */
data class ArtistInfo(
    val name: String = "",
    val bio: String = "",
    val imageUrl: String = "",
    val tags: List<String> = emptyList(),
    val country: String = "",
    val yearsActive: String = "",
    val wikipediaUrl: String = "",
    val mbid: String = "",
    val found: Boolean = false,
)

// --- MusicBrainz DTOs (nullable per the Gson rule) ---
private data class MbArtistSearch(val artists: List<MbArtistFull>? = emptyList())
private data class MbArtistFull(
    val id: String? = "",
    val name: String? = "",
    val country: String? = "",
    val disambiguation: String? = "",
    val type: String? = "",
    @SerializedName("life-span") val lifeSpan: MbLifeSpan? = null,
    val tags: List<MbTag>? = emptyList(),
    val relations: List<MbRelation>? = emptyList(),
)
private data class MbLifeSpan(val begin: String? = "", val end: String? = "", val ended: Boolean? = false)
private data class MbTag(val name: String? = "", val count: Int? = 0)
private data class MbRelation(val type: String? = "", val url: MbUrl? = null)
private data class MbUrl(val resource: String? = "")

// --- Wikipedia REST summary DTOs ---
private data class WikiSummary(
    val extract: String? = "",
    val thumbnail: WikiImage? = null,
    val originalimage: WikiImage? = null,
    @SerializedName("content_urls") val contentUrls: WikiContentUrls? = null,
)
private data class WikiImage(val source: String? = "")
private data class WikiContentUrls(val desktop: WikiPage? = null)
private data class WikiPage(val page: String? = "")

/**
 * Artist enrichment from keyless public APIs: MusicBrainz for identity (MBID, tags, country, active
 * years) + the Wikipedia/Wikidata relation, then the Wikipedia REST summary for a bio + lead image
 * (Wikidata's P18 Commons image as the fallback). MusicBrainz asks for a descriptive User-Agent and
 * rate-limits to ~1 req/s, so the two MB calls are spaced out; the whole thing runs off the UI thread
 * and is cached, so the latency is paid once per artist. All failures degrade to `found = false`.
 */
class ArtistInfoClient {
    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun lookup(artistName: String): ArtistInfo = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isBlank()) return@withContext ArtistInfo(name = artistName, found = false)

        val searchUrl = "https://musicbrainz.org/ws/2/artist?query=artist:%22${enc(name)}%22&fmt=json&limit=1"
        val search = getJson(searchUrl, MbArtistSearch::class.java)
        val artist = search?.artists?.firstOrNull()
            ?: return@withContext ArtistInfo(name = name, found = false)
        val mbid = artist.id?.takeIf { it.isNotBlank() }
            ?: return@withContext ArtistInfo(name = name, found = false)

        val tags = artist.tags.orEmpty()
            .filter { (it.count ?: 0) > 0 && !it.name.isNullOrBlank() }
            .sortedByDescending { it.count ?: 0 }
            .mapNotNull { it.name }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .distinct()
            .take(6)
        val years = formatYears(artist.lifeSpan)

        // Second MusicBrainz call (url relations). Space it out for the ~1 req/s limit.
        delay(1100)
        val full = getJson("https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json", MbArtistFull::class.java)
        val rels = full?.relations.orEmpty()
        val wikipediaUrl = rels.firstOrNull { it.type.equals("wikipedia", true) }?.url?.resource?.takeIf { it.isNotBlank() }
        val wikidataUrl = rels.firstOrNull { it.type.equals("wikidata", true) }?.url?.resource?.takeIf { it.isNotBlank() }

        var bio = ""
        var image = ""
        var pageUrl = ""

        val wikiSummary = wikipediaUrl?.let { summaryFromWikipediaUrl(it) }
        if (wikiSummary != null) {
            bio = wikiSummary.extract.orEmpty()
            image = wikiSummary.originalimage?.source?.takeIf { it.isNotBlank() } ?: wikiSummary.thumbnail?.source.orEmpty()
            pageUrl = wikiSummary.contentUrls?.desktop?.page ?: wikipediaUrl.orEmpty()
        }
        if (bio.isBlank() && wikidataUrl != null) {
            val (wTitle, p18) = resolveWikidata(wikidataUrl)
            if (!wTitle.isNullOrBlank()) {
                summaryFromWikipediaTitle("en", wTitle)?.let { s ->
                    bio = s.extract.orEmpty()
                    if (image.isBlank()) image = s.originalimage?.source?.takeIf { it.isNotBlank() } ?: s.thumbnail?.source.orEmpty()
                    pageUrl = s.contentUrls?.desktop?.page ?: pageUrl
                }
            }
            if (image.isBlank() && !p18.isNullOrBlank()) image = commonsImageUrl(p18)
        }

        val found = bio.isNotBlank() || image.isNotBlank() || tags.isNotEmpty()
        ArtistInfo(
            name = artist.name?.takeIf { it.isNotBlank() } ?: name,
            bio = bio.trim(),
            imageUrl = image,
            tags = tags,
            country = artist.country.orEmpty(),
            yearsActive = years,
            wikipediaUrl = pageUrl,
            mbid = mbid,
            found = found,
        )
    }

    private fun <T> getJson(url: String, type: Class<T>): T? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            gson.fromJson(resp.body?.string(), type)
        }
    }.getOrNull()

    /** Wikipedia summary from a full article URL like https://en.wikipedia.org/wiki/Radiohead. */
    private fun summaryFromWikipediaUrl(url: String): WikiSummary? {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return null
        val lang = host.substringBefore(".wikipedia.org").ifBlank { "en" }
        // Pull the raw path after /wiki/ (lastPathSegment would drop a slash in titles like "AC/DC")
        // and decode it to a plain title; summaryFromWikipediaTitle re-encodes it correctly.
        val rawPath = url.substringAfter("/wiki/", "").substringBefore("?").substringBefore("#")
        if (rawPath.isBlank()) return null
        return summaryFromWikipediaTitle(lang, android.net.Uri.decode(rawPath))
    }

    /** [title] must be a PLAIN (decoded) article title; this percent-encodes it as a path segment. */
    private fun summaryFromWikipediaTitle(lang: String, title: String): WikiSummary? {
        if (title.isBlank()) return null
        val seg = android.net.Uri.encode(title.replace(" ", "_"))
        return getJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/$seg", WikiSummary::class.java)
    }

    /** Returns the enwiki article title + the P18 Commons image filename for a Wikidata entity. */
    private fun resolveWikidata(wikidataUrl: String): Pair<String?, String?> {
        val qid = android.net.Uri.parse(wikidataUrl).lastPathSegment ?: return null to null
        val json = getJson("https://www.wikidata.org/wiki/Special:EntityData/$qid.json", JsonObject::class.java)
            ?: return null to null
        return runCatching {
            val entity = json.getAsJsonObject("entities").getAsJsonObject(qid)
            val title = entity.getAsJsonObject("sitelinks")?.getAsJsonObject("enwiki")?.get("title")?.asString
            val p18 = entity.getAsJsonObject("claims")?.getAsJsonArray("P18")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")?.get("value")?.asString
            title to p18
        }.getOrDefault(null to null)
    }

    private fun commonsImageUrl(filename: String): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/${enc(filename.replace(" ", "_"))}?width=600"

    private fun formatYears(span: MbLifeSpan?): String {
        val begin = span?.begin?.take(4).orEmpty()
        if (begin.isBlank()) return ""
        val end = span?.end?.take(4).orEmpty()
        return when {
            end.isNotBlank() -> "$begin–$end"
            span?.ended == true -> begin
            else -> "$begin–present"
        }
    }

    private fun enc(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

    private companion object {
        const val USER_AGENT = "Aurora/1.0 ( https://github.com/aurora-music/aurora )"
    }
}
