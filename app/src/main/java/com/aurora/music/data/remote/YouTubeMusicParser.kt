package com.aurora.music.data.remote

import com.aurora.music.data.SearchResults
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Only content renderers become items; menu endpoints are never interpreted as tracks. */
object YouTubeMusicParser {
    fun home(root: JsonObject): HomeData {
        val sections = mutableListOf<HomeFeedSection>()
        fun visit(node: JsonElement) {
            if (node.isJsonArray) { node.asJsonArray.forEach(::visit); return }
            if (!node.isJsonObject) return
            node.asJsonObject.entrySet().forEach { (key, value) ->
                if (key in setOf("musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer", "musicShelfRenderer", "musicGridRenderer") && value.isJsonObject) {
                    val shelf = value.asJsonObject
                    val header = shelf.obj("header").entrySet().firstOrNull { it.value.isJsonObject }?.value?.asJsonObject ?: JsonObject()
                    val title = header.obj("title").label().ifBlank { shelf.obj("title").label() }
                    val contents = shelf.array("contents").takeIf { it.size() > 0 } ?: shelf.array("items")
                    val items = contents.flatMap { child ->
                        val parsed = results(child)
                        parsed.songs.map { HomeFeedItem.Track(it) } + parsed.albums.map { HomeFeedItem.Record(it) } +
                            parsed.playlists.map { HomeFeedItem.Collection(it) } + parsed.artists.map { HomeFeedItem.Performer(it) }
                    }.distinctBy { it.key }
                    if (title.isNotBlank() && items.isNotEmpty()) sections += HomeFeedSection(
                        "${shelf.string("shelfId")}:$title", title, header.obj("strapline").label(), items)
                } else if (key !in setOf("menu", "header", "overlay", "buttons", "trackingParams")) visit(value)
            }
        }
        visit(root)
        // A carousel can have its own continuation; only follow the page's section list.
        val list = root.objects("sectionListRenderer").firstOrNull()
            ?: root.obj("continuationContents").obj("sectionListContinuation")
        val token = list.array("continuations").firstOrNull()?.let { continuation(it) }
            ?: list.array("contents").lastOrNull()?.takeIf { it.isJsonObject && it.asJsonObject.has("continuationItemRenderer") }?.let { continuation(it) }
        return HomeData(sections = sections.distinctBy { it.id }, continuation = token)
    }

    fun results(root: JsonElement): SearchResults {
        val songs = mutableListOf<Song>()
        val albums = mutableListOf<Album>()
        val artists = mutableListOf<Artist>()
        val playlists = mutableListOf<Playlist>()
        fun visit(node: JsonElement) {
            if (node.isJsonArray) { node.asJsonArray.forEach(::visit); return }
            if (!node.isJsonObject) return
            node.asJsonObject.entrySet().forEach { (key, value) ->
                if (key in ITEM_RENDERERS && value.isJsonObject) {
                    val item = value.asJsonObject
                    val title = item.obj("title").label().ifBlank { item.obj("headline").label() }
                        .ifBlank { columns(item).firstOrNull()?.label().orEmpty() }
                    val endpoint = item.obj("navigationEndpoint").takeIf { it.size() > 0 }
                        ?: item.obj("onTap").takeIf { it.size() > 0 }
                        ?: item.obj("title").objects("navigationEndpoint").firstOrNull() ?: JsonObject()
                    val videoId = item.string("videoId").ifBlank { item.obj("playlistItemData").string("videoId") }
                        .ifBlank { endpoint.obj("watchEndpoint").string("videoId") }
                        .ifBlank { item.obj("onTap").obj("watchEndpoint").string("videoId") }
                        .ifBlank { columns(item).firstOrNull()?.array("runs")?.firstOrNull()?.let {
                            it.asJsonObject.obj("navigationEndpoint").obj("watchEndpoint").string("videoId")
                        }.orEmpty() }
                        .ifBlank { item.objects("musicPlayButtonRenderer").firstOrNull()
                            ?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.string("videoId").orEmpty() }
                    val browse = endpoint.obj("browseEndpoint")
                    val browseId = browse.string("browseId").ifBlank {
                        columns(item).firstOrNull()?.objects("browseEndpoint")?.firstOrNull()?.string("browseId").orEmpty()
                    }.ifBlank { endpoint.obj("watchPlaylistEndpoint").string("playlistId").let { if (it.isBlank()) "" else "VL$it" } }
                    val art = artwork(item)
                    val metadata = columns(item).drop(1) + listOf(item.obj("subtitle"), item.obj("longBylineText"))
                    val runs = metadata.flatMap { it.array("runs").toList() }.filter { it.isJsonObject }.map { it.asJsonObject }
                    val artistRuns = runs.filter { it.obj("navigationEndpoint").obj("browseEndpoint").string("browseId").startsWith("UC") }
                    val albumRun = runs.firstOrNull { it.obj("navigationEndpoint").obj("browseEndpoint").string("browseId").startsWith("MPRE") }
                    val subtitle = metadata.map { it.label() }.filter { it.isNotBlank() }.joinToString(" · ")
                    val artist = artistRuns.joinToString(", ") { it.string("text") }.ifBlank {
                        item.obj("longBylineText").label().ifBlank {
                            val labels = metadata.firstOrNull()?.label().orEmpty().split(" • ")
                            labels.dropWhile { it in setOf("Song", "Video", "Album", "Single", "EP") }.firstOrNull().orEmpty()
                        }
                    }
                    if (title.isBlank()) return@forEach
                    when {
                        !browseId.startsWith("MPRE") && !browseId.startsWith("UC") && !browseId.startsWith("VL") && videoId.matches(Regex("[A-Za-z0-9_-]{11}")) -> {
                            if (item.string("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT" ||
                                item.get("isPlayable")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return@forEach
                            val durations = item.objects("musicResponsiveListItemFixedColumnRenderer").map { it.obj("text").label() } +
                                listOf(item.obj("lengthText").label()) + runs.map { it.string("text") }
                            songs += Song(videoId, title, artist, albumRun?.string("text").orEmpty(), art,
                                durations.firstNotNullOfOrNull { duration(it) } ?: 0,
                                liked = item.objects("likeButtonRenderer").any { it.string("likeStatus") == "LIKE" },
                                explicit = item.objects("icon").any { it.string("iconType") == "MUSIC_EXPLICIT_BADGE" },
                                accent = accentFor(videoId), streamUrl = sentinel(videoId),
                                albumId = albumRun?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId").orEmpty(),
                                artistId = artistRuns.firstOrNull()?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId").orEmpty())
                        }
                        browseId.startsWith("MPRE") -> albums += Album(browseId, title, artist, art,
                            runs.mapNotNull { it.string("text").toIntOrNull()?.takeIf { y -> y in 1900..2200 } }.firstOrNull() ?: 0, 0)
                        browseId.startsWith("UC") -> artists += Artist(browseId, title, art, 0)
                        browseId.startsWith("VL") -> playlists += Playlist(browseId.removePrefix("VL"), title, subtitle, art, 0, accentFor(browseId))
                    }
                    if (key == "musicCardShelfRenderer") visit(item.array("contents"))
                } else if (key !in setOf("menu", "overlay", "buttons", "trackingParams")) visit(value)
            }
        }
        visit(root)
        return SearchResults(songs, albums.distinctBy { it.id }, artists.distinctBy { it.id }, playlists.distinctBy { it.id })
    }

    private fun columns(item: JsonObject): List<JsonObject> = item.array("flexColumns").mapNotNull {
        it.takeIf { v -> v.isJsonObject }?.asJsonObject?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
    }

    fun artwork(root: JsonElement): String = root.objects("musicThumbnailRenderer").firstOrNull()?.let { thumbnail(it.obj("thumbnail")) }
        ?.takeIf { it.isNotBlank() } ?: root.takeIf { it.isJsonObject }?.asJsonObject?.let {
            thumbnail(it.obj("thumbnail")).ifBlank { thumbnail(it.obj("foregroundThumbnail")) }
        }.orEmpty()

    fun thumbnail(root: JsonObject): String = root.array("thumbnails").lastOrNull()?.takeIf { it.isJsonObject }
        ?.asJsonObject?.string("url").orEmpty()

    fun duration(value: String): Int? {
        if (!value.matches(Regex("\\d{1,3}:[0-5]\\d(?::[0-5]\\d)?"))) return null
        return value.split(':').fold(0) { total, part -> total * 60 + part.toInt() }
    }

    fun sentinel(videoId: String): String {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        return "aurora-yt://video/$videoId"
    }

    fun continuation(root: JsonElement): String? = root.objects("nextContinuationData").firstOrNull()?.string("continuation")
        ?.takeIf { it.isNotBlank() } ?: root.objects("continuationItemRenderer").firstOrNull()
        ?.obj("continuationEndpoint")?.obj("continuationCommand")?.string("token")?.takeIf { it.isNotBlank() }

    fun trackShelf(root: JsonElement): JsonElement = listOf("musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "musicShelfContinuation", "musicShelfRenderer")
        .firstNotNullOfOrNull { root.objects(it).firstOrNull() } ?: root

    private val ITEM_RENDERERS = setOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "playlistPanelVideoRenderer", "musicCardShelfRenderer")
}
