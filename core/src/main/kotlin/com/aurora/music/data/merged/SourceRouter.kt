package com.aurora.music.data.merged

import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.accountKey
import com.aurora.music.data.mergeNamespace
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal class SourceRouter(
    val sources: List<MediaBackend>,
    val session: Session,
    private val nested: Boolean,
    private val downUntil: ConcurrentHashMap<String, Long>,
) {

    private fun catalogueOf(type: ServerType) = sources.indexOfFirst { it.session.type == type && it.session.accountKey() == session.accountKey() }
        .takeIf { it >= 0 } ?: sources.indexOfFirst { it.session.type == type }
    val youtubeIndex = catalogueOf(ServerType.YOUTUBE_MUSIC)
    val spotifyIndex = catalogueOf(ServerType.SPOTIFY)
    val soundcloudIndex = catalogueOf(ServerType.SOUNDCLOUD)
    fun isCatalogue(source: MediaBackend) = source.session.type.isCatalogue

    fun catalogueFor(id: String): Int = when (id) {
        "discovery", "youtube" -> youtubeIndex
        "spotify" -> spotifyIndex
        "soundcloud" -> soundcloudIndex
        else -> -1
    }

    // library first, then each streaming catalogue on its own; a lone choice is no choice
    fun <T> choices(make: (String, String) -> T, libraryId: String, libraryLabel: String, youtubeId: String): List<T> =
        if (nested) emptyList() else buildList {
            add(make(libraryId, libraryLabel))
            if (youtubeIndex >= 0) add(make(youtubeId, "YouTube Music"))
            if (spotifyIndex >= 0) add(make("spotify", "Spotify"))
            if (soundcloudIndex >= 0) add(make("soundcloud", "SoundCloud"))
        }.takeIf { it.size > 1 }.orEmpty()

    // a missing catalogue falls back to the other one before the merged library
    fun searchIndex(source: String): Int = when (source) {
        "library" -> -1
        "spotify" -> spotifyIndex.takeIf { it >= 0 } ?: youtubeIndex
        "soundcloud" -> soundcloudIndex.takeIf { it >= 0 } ?: youtubeIndex
        else -> youtubeIndex.takeIf { it >= 0 } ?: spotifyIndex.takeIf { it >= 0 } ?: soundcloudIndex
    }

    // discovery comes from the youtube music source, whichever source the song itself plays from
    val explorer: Int get() = youtubeIndex.takeIf { it >= 0 && sources[it].supportsExplore } ?: -1

    val primary: MediaBackend? = sources.firstOrNull { it.session.accountKey() == session.accountKey() }
        ?: sources.firstOrNull { it.session.type != ServerType.LOCAL } ?: sources.firstOrNull()

    val namespaces = sources.map { mergeNamespace(it.session) }
    fun wrapId(idx: Int, id: String): String = if (id.isBlank()) "" else "${namespaces[idx]}$SEP$id"
    fun unwrap(wrapped: String): Pair<Int, String>? {
        val i = wrapped.indexOf(SEP)
        if (i <= 0) return null
        val namespace = wrapped.substring(0, i)
        val idx = namespace.toIntOrNull() ?: namespaces.indexOf(namespace)
        if (idx !in sources.indices) return null
        return idx to wrapped.substring(i + 1)
    }

    fun wrap(song: Song, idx: Int) = song.copy(
        id = wrapId(idx, song.id),
        albumId = wrapId(idx, song.albumId),
        artistId = wrapId(idx, song.artistId),
        playbackSource = song.playbackSource ?: sources[idx].playbackSourceIdentity(song),
    )
    fun wrap(album: Album, idx: Int) = album.copy(id = wrapId(idx, album.id))
    fun wrap(artist: Artist, idx: Int) = artist.copy(id = wrapId(idx, artist.id))
    fun wrap(playlist: Playlist, idx: Int) = playlist.copy(id = wrapId(idx, playlist.id))

    fun wrapHome(home: HomeData, i: Int) = home.copy(
        newReleases = home.newReleases.map { wrap(it, i) }, recentlyPlayed = home.recentlyPlayed.map { wrap(it, i) },
        mostPlayed = home.mostPlayed.map { wrap(it, i) }, random = home.random.map { wrap(it, i) },
        playlists = home.playlists.map { wrap(it, i) }, artists = home.artists.map { wrap(it, i) }, starred = home.starred.map { wrap(it, i) },
        sections = wrapSections(home.sections, i),
    )

    fun wrapSections(sections: List<HomeFeedSection>, i: Int) = sections.map { section -> section.copy(id = wrapId(i, section.id), items = section.items.map { entry -> when (entry) {
        is HomeFeedItem.Track -> HomeFeedItem.Track(wrap(entry.song, i))
        is HomeFeedItem.Record -> HomeFeedItem.Record(wrap(entry.album, i))
        is HomeFeedItem.Performer -> HomeFeedItem.Performer(wrap(entry.artist, i))
        is HomeFeedItem.Collection -> HomeFeedItem.Collection(wrap(entry.playlist, i))
    } }) }

    // songs another source lent a local playlist keep their ids; ones from a source no longer merged drop out
    fun mixed(entries: List<Song>, index: Int) =
        entries.mapNotNull { if (SEP in it.id) it.takeIf { song -> unwrap(song.id) != null } else wrap(it, index) }

    // a catalogue's own view pages in its whole library, so it gets the liked songs' budget
    suspend fun <T> fanOut(timeoutMs: (MediaBackend) -> Long = { if (nested && isCatalogue(it)) LIKED_TIMEOUT_MS else SOURCE_TIMEOUT_MS }, block: suspend (MediaBackend) -> List<T>): List<List<T>> = coroutineScope {
        sources.mapIndexed { i, src -> async { reach(i) { withTimeoutOrNull(timeoutMs(src)) { block(src) } } ?: emptyList() } }
            .awaitAll()
    }

    // a whole streaming library of likes takes a while to page in; servers keep the usual limit
    fun likedTimeout(source: MediaBackend) = if (isCatalogue(source)) LIKED_TIMEOUT_MS else SOURCE_TIMEOUT_MS

    // a source that just failed, timed out or answered empty only after a connect timeout is skipped for a while
    // instead of stalling every call; servers swallow connection errors into empty lists, hence the slow-empty rule
    fun isDown(index: Int) = (downUntil[namespaces[index]] ?: 0L) > System.currentTimeMillis()

    suspend fun <R> reach(index: Int, call: suspend () -> R?): R? {
        if (isDown(index)) return null
        val started = System.currentTimeMillis()
        val result = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // a search that finds nothing slowly is a normal answer, not a dead source
        val empty = (result as? Collection<*>)?.isEmpty() == true || result == HomeData()
        val slowEmpty = empty && System.currentTimeMillis() - started >= SLOW_EMPTY_MS
        if (result == null || slowEmpty) downUntil[namespaces[index]] = System.currentTimeMillis() + DOWN_MS else downUntil.remove(namespaces[index])
        return result
    }

    suspend fun <R> route(id: String, block: suspend (MediaBackend, Int, String) -> R): R? {
        return try {
            val (i, oid) = unwrap(id) ?: return if (SEP in id) null else primary?.let { p -> block(p, sources.indexOf(p), id) }
            val src = sources.getOrNull(i) ?: return null
            block(src, i, oid)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    companion object {
        const val SEP = '\u0001'   // control char that never appears in a real backend id
        const val SOURCE_TIMEOUT_MS = 20_000L
        private const val LIKED_TIMEOUT_MS = 90_000L
        const val DOWN_MS = 60_000L
        private const val SLOW_EMPTY_MS = 8_000L
    }
}
