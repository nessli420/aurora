package com.aurora.music.data.artwork

import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

internal data class ArtworkRequest(
    val artist: String? = null, val album: String? = null, val title: String? = null,
    val original: String? = null, val duration: Int? = null,
) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(
        listOf("2", artist, album, title, original, duration?.toString()).joinToString("\u0000").toByteArray()
    ).joinToString("") { "%02x".format(it) }

    val matchKey: String get() = copy(artist = artworkKey(artist.orEmpty()), album = artworkKey(album.orEmpty()),
        title = artworkKey(title.orEmpty()), original = "").key
}

/** Content URIs let the player, widgets and image loader share the same lazy cover lookup. */
object ArtworkUrls {
    const val AUTHORITY = "com.aurora.music.artwork"
    private const val PREFIX = "content://$AUTHORITY/v1/"
    private val gson = Gson()
    private val unknown = setOf("", "unknown", "unknown artist", "unknown album", "<unknown>")

    fun song(song: Song): Song = song.copy(artworkUrl = cover(song.artworkUrl, song.artist, song.album, song.title, song.durationSec))
    fun album(album: Album): Album = album.copy(artworkUrl = cover(album.artworkUrl, album.artist, album.title))
    fun isArtwork(uri: String): Boolean = uri.startsWith(PREFIX)

    fun cover(original: String, artist: String, album: String, title: String = "", duration: Int = 0): String {
        if (original.isNotBlank() && !isMediaStoreArt(original)) return original
        return requestUrl(original, artist, album, title, duration)
    }

    internal fun subsonicCover(original: String, artist: String, album: String, title: String = "", duration: Int = 0): String =
        if (original.isBlank() || isServerArt(original)) requestUrl(original, artist, album, title, duration) else original

    private fun requestUrl(original: String, artist: String, album: String, title: String, duration: Int): String {
        if (artworkKey(artist) in unknown) return original
        val knownAlbum = album.takeUnless { artworkKey(it) in unknown }.orEmpty()
        if (knownAlbum.isBlank() && title.isBlank()) return original
        val request = ArtworkRequest(artist.trim(), knownAlbum.trim(), if (knownAlbum.isBlank()) title.trim() else "",
            original, if (knownAlbum.isBlank()) duration else 0)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(gson.toJson(request).toByteArray(StandardCharsets.UTF_8))
    }

    internal fun decode(uri: String): ArtworkRequest? = runCatching {
        if (!isArtwork(uri) || uri.length > 8192) return null
        val request = gson.fromJson(String(Base64.getUrlDecoder().decode(uri.removePrefix(PREFIX)), StandardCharsets.UTF_8), ArtworkRequest::class.java)
        if (request.artist.isNullOrBlank() || listOf(request.artist, request.album, request.title).any { (it?.length ?: 0) > 1024 }) return null
        if (!request.original.isNullOrBlank() && !isMediaStoreArt(request.original) && !isServerArt(request.original)) return null
        if (request.album.isNullOrBlank() && request.title.isNullOrBlank()) return null
        request
    }.getOrNull()

    private fun isMediaStoreArt(uri: String): Boolean =
        Regex("content://media/(external|internal|external_primary)/audio/albumart/[0-9]+$").matches(uri)

    internal fun isServerArt(uri: String): Boolean = runCatching {
        val parsed = java.net.URI(uri)
        parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() &&
            parsed.userInfo == null && parsed.path.endsWith("/rest/getCoverArt.view")
    }.getOrDefault(false)
}

internal fun artworkKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
