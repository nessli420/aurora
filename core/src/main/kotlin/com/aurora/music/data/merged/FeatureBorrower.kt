package com.aurora.music.data.merged

import com.aurora.music.data.Lyrics
import com.aurora.music.data.ServerType
import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.data.recordingTitle
import com.aurora.music.data.twinMatches
import com.aurora.music.data.CommentPage
import com.aurora.music.model.Song
import com.aurora.music.model.TrackComment
import kotlinx.coroutines.CancellationException

internal class FeatureBorrower(private val router: SourceRouter) {

    // a song from another source borrows the canvas or comments of its twin on a merged catalogue
    private val twins = object : LinkedHashMap<String, Song?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>?) = size > 128
    }

    private suspend fun twinOn(index: Int, song: Song): Song? {
        val source = router.sources.getOrNull(index) ?: return null
        val key = "$index\u0000${song.id}"
        synchronized(twins) { if (twins.containsKey(key)) return twins[key] }
        // a failed search isn't remembered, so the next try asks again
        val results = try { source.search("${recordingTitle(song.title)} ${song.artist}".trim()).songs }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        if (results.isEmpty()) return null
        val twin = results.firstOrNull { twinMatches(song, it) }
        synchronized(twins) { twins[key] = twin }
        return twin
    }

    fun borrows(catalogue: Int, owner: Int, song: Song) = catalogue >= 0 && owner != catalogue && !song.isRadio() && !song.isPodcast()

    fun supportsCanvas(song: Song): Boolean {
        val (i, oid) = router.unwrap(song.id) ?: return false
        return router.sources[i].supportsCanvas(song.copy(id = oid)) || borrows(router.spotifyIndex, i, song)
    }

    suspend fun canvasUrl(song: Song): String? {
        val (i, oid) = router.unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        if (router.sources[i].supportsCanvas(original)) return router.sources[i].canvasUrl(original)
        if (!borrows(router.spotifyIndex, i, song)) return null
        val twin = twinOn(router.spotifyIndex, song) ?: return null
        return router.sources[router.spotifyIndex].takeIf { it.supportsCanvas(twin) }?.canvasUrl(twin)
    }

    suspend fun catalogueLyrics(song: Song, from: ServerType): Lyrics? {
        val (i, _) = router.unwrap(song.id) ?: return null
        val catalogue = if (from == ServerType.APPLE_MUSIC) router.appleIndex else router.spotifyIndex
        if (!borrows(catalogue, i, song)) return null
        return twinOn(catalogue, song)?.let { router.sources[catalogue].serverLyrics(it) }
    }

    // soundcloud's timed comments first, then youtube's; a song's own service answers for itself, the others through its twin
    private fun commentIndices(owner: Int, song: Song, original: Song): List<Int> {
        val ownType = router.sources[owner].session.type
        return listOf(router.soundcloudIndex, router.youtubeIndex, owner).distinct().filter { c ->
            if (c == owner) router.sources[c].supportsComments(original)
            else router.sources.getOrNull(c)?.session?.type != ownType && borrows(c, owner, song)
        }.sortedBy { !router.sources[it].session.type.timedComments }
    }

    private fun commentIndex(song: Song, source: ServerType): Triple<Int, Int, Song>? {
        val (i, oid) = router.unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        val c = commentIndices(i, song, original).firstOrNull { router.sources[it].session.type == source } ?: return null
        return Triple(c, i, original)
    }

    fun supportsComments(song: Song): Boolean = commentSources(song).isNotEmpty()

    fun commentSources(song: Song): List<ServerType> {
        val (i, oid) = router.unwrap(song.id) ?: return emptyList()
        return commentIndices(i, song, song.copy(id = oid)).map { router.sources[it].session.type }
    }

    suspend fun comments(song: Song, source: ServerType, page: Any?): CommentPage? {
        val (c, i, original) = commentIndex(song, source) ?: return null
        if (c == i) return router.sources[i].comments(original, source, page)
        return twinOn(c, song)?.let { router.sources[c].comments(it, source, page) }
    }

    // a borrowed service takes posts and likes once its account is signed in
    fun canPostComments(song: Song, source: ServerType): Boolean {
        val (c, i, original) = commentIndex(song, source) ?: return false
        return if (c == i) router.sources[i].canPostComments(original, source) else router.sources[c].signedIn
    }

    fun canLikeComments(song: Song, source: ServerType): Boolean = source.likesComments && canPostComments(song, source)

    suspend fun postComment(song: Song, source: ServerType, body: String, timestampMs: Long): TrackComment? {
        val (c, i, original) = commentIndex(song, source) ?: return null
        if (c == i) return router.sources[i].postComment(original, source, body, timestampMs)
        return twinOn(c, song)?.let { router.sources[c].postComment(it, source, body, timestampMs) }
    }

    suspend fun likeComment(song: Song, source: ServerType, commentId: String, liked: Boolean): Boolean {
        val (c, i, original) = commentIndex(song, source) ?: return false
        if (c == i) return router.sources[i].likeComment(original, source, commentId, liked)
        return twinOn(c, song)?.let { router.sources[c].likeComment(it, source, commentId, liked) } ?: false
    }
}
