package com.aurora.music.data.merged

import com.aurora.music.data.Lyrics
import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.data.recordingTitle
import com.aurora.music.data.twinMatches
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

    suspend fun catalogueLyrics(song: Song): Lyrics? {
        val (i, _) = router.unwrap(song.id) ?: return null
        if (!borrows(router.spotifyIndex, i, song)) return null
        return twinOn(router.spotifyIndex, song)?.let { router.sources[router.spotifyIndex].serverLyrics(it) }
    }

    fun supportsComments(song: Song): Boolean {
        val (i, oid) = router.unwrap(song.id) ?: return false
        return router.sources[i].supportsComments(song.copy(id = oid)) || borrows(router.soundcloudIndex, i, song)
    }

    fun canPostComments(song: Song): Boolean {
        val (i, oid) = router.unwrap(song.id) ?: return false
        val original = song.copy(id = oid)
        if (router.sources[i].supportsComments(original)) return router.sources[i].canPostComments(original)
        return borrows(router.soundcloudIndex, i, song) && router.sources[router.soundcloudIndex].signedIn
    }

    suspend fun comments(song: Song): List<TrackComment>? {
        val (i, oid) = router.unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        if (router.sources[i].supportsComments(original)) return router.sources[i].comments(original)
        if (!borrows(router.soundcloudIndex, i, song)) return null
        return twinOn(router.soundcloudIndex, song)?.let { router.sources[router.soundcloudIndex].comments(it) }
    }

    suspend fun postComment(song: Song, body: String, timestampMs: Long): TrackComment? {
        val (i, oid) = router.unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        if (router.sources[i].supportsComments(original)) return router.sources[i].postComment(original, body, timestampMs)
        if (!borrows(router.soundcloudIndex, i, song)) return null
        return twinOn(router.soundcloudIndex, song)?.let { router.sources[router.soundcloudIndex].postComment(it, body, timestampMs) }
    }
}
