package com.aurora.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.aurora.music.util.accentFor
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalLibrary(
    private val context: Context,
    // scanned replaygain overlaid by path since mediastore tags rarely carry it
    private val gainProvider: (String) -> Pair<Float, Float>? = { null },
) {

    @Volatile private var loaded = false
    private val mutex = Mutex()

    @Volatile var songs: List<Song> = emptyList(); private set
    @Volatile var albums: List<Album> = emptyList(); private set
    @Volatile var artists: List<Artist> = emptyList(); private set
    private var byId: Map<String, Song> = emptyMap()

    @Volatile private var matchIndex: Map<String, List<Song>> = emptyMap()

    @Volatile private var dirOf: Map<String, String> = emptyMap()

    @Volatile var folderRoot: String = ""; private set

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (!loaded) { scan(); loaded = true }
        }
    }

    suspend fun refresh() {
        mutex.withLock { scan(); loaded = true }
    }

    fun song(id: String): Song? = byId[id]

    // only substitute on a single unambiguous match so a different version is never swapped in
    fun findMatch(artist: String, title: String, durationSec: Int): Song? {
        if (title.isBlank()) return null
        val candidates = matchIndex[TrackMatch.key(artist, title)] ?: return null
        val byDuration = candidates.filter { durationSec > 0 && it.durationSec > 0 && abs(it.durationSec - durationSec) <= TrackMatch.DURATION_TOLERANCE_SEC }
        if (byDuration.isNotEmpty()) return byDuration.minByOrNull { abs(it.durationSec - durationSec) }
        return candidates.singleOrNull()
    }

    fun browse(path: String): Pair<List<String>, List<Song>> {
        val base = path.ifBlank { folderRoot }
        if (base.isBlank()) return emptyList<String>() to emptyList()
        val here = songs.filter { dirOf[it.id] == base }.sortedBy { it.title.lowercase() }
        val subdirs = dirOf.values.asSequence()
            .filter { it != base && it.startsWith("$base/") }
            .map { it.removePrefix("$base/").substringBefore('/') }
            .distinct().sortedBy { it.lowercase() }.toList()
        return subdirs to here
    }

    private fun commonDir(dirs: Collection<String>): String {
        if (dirs.isEmpty()) return ""
        var prefix = dirs.first().split('/')
        for (d in dirs) {
            val seg = d.split('/')
            var i = 0
            while (i < prefix.size && i < seg.size && prefix[i] == seg[i]) i++
            prefix = prefix.subList(0, i)
        }
        return prefix.joinToString("/")
    }
    fun songsIn(album: Album): List<Song> = songs.filter { it.albumId == album.id }
    fun songsByAlbumId(albumId: String): List<Song> = songs.filter { it.albumId == albumId }
    fun songsByArtistId(artistId: String): List<Song> = songs.filter { it.artistId == artistId }
    fun albumsByArtistId(artistId: String): List<Album> =
        songs.filter { it.artistId == artistId }.map { it.albumId }.distinct()
            .mapNotNull { aid -> albums.firstOrNull { it.id == aid } }

    private fun albumArtUri(albumId: Long): String =
        if (albumId <= 0) "" else ContentUris.withAppendedId(ALBUM_ART_BASE, albumId).toString()

    private fun suffixFrom(displayName: String?, mime: String?): String {
        displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length in 2..4 }?.let { return it.lowercase() }
        val m = mime?.lowercase() ?: return ""
        return when {
            m.contains("flac") -> "flac"
            m.contains("mpeg") || m.contains("mp3") -> "mp3"
            m.contains("aac") || m.contains("mp4") || m.contains("m4a") -> "m4a"
            m.contains("opus") -> "opus"
            m.contains("ogg") || m.contains("vorbis") -> "ogg"
            m.contains("wav") -> "wav"
            m.contains("aiff") || m.contains("aif") -> "aiff"
            else -> ""
        }
    }

    private suspend fun scan() = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.BITRATE) // column absent pre-30
        val projection = cols.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<Song>()
        val albumDateAdded = HashMap<String, Long>()
        val albumYear = HashMap<String, Int>()
        val dirs = HashMap<String, String>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val bitrateCol = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                @Suppress("DEPRECATION") val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val albumId = c.getLong(albumIdCol)
                    val artistId = c.getLong(artistIdCol)
                    val title = c.getString(titleCol) ?: continue
                    val artistName = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                    val albumName = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown album"
                    val durSec = (c.getLong(durCol) / 1000L).toInt()
                    val year = runCatching { c.getInt(yearCol) }.getOrDefault(0)
                    val added = runCatching { c.getLong(addedCol) }.getOrDefault(0L)
                    val display = if (nameCol >= 0) c.getString(nameCol) else null
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val suffix = suffixFrom(display, mime)
                    val bitrateKbps = if (bitrateCol >= 0) (runCatching { c.getInt(bitrateCol) }.getOrDefault(0) / 1000) else 0
                    val art = albumArtUri(albumId)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val data = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    if (data.contains('/')) dirs[id.toString()] = data.substringBeforeLast('/')
                    val rg = if (data.isNotBlank()) gainProvider(data) else null
                    val sidAlbum = albumId.toString()
                    if (added > (albumDateAdded[sidAlbum] ?: 0L)) albumDateAdded[sidAlbum] = added
                    if (year > 0 && albumYear[sidAlbum] == null) albumYear[sidAlbum] = year
                    out += Song(
                        id = id.toString(),
                        title = title,
                        artist = artistName,
                        album = albumName,
                        artworkUrl = art,
                        durationSec = durSec,
                        accent = accentFor(id.toString()),
                        streamUrl = uri,
                        albumId = sidAlbum,
                        artistId = artistId.toString(),
                        suffix = suffix,
                        bitrateKbps = bitrateKbps,
                        path = data,
                        replayGainTrack = rg?.first ?: 0f,
                        replayGainAlbum = rg?.second ?: 0f,
                    )
                }
            }
        }
        songs = out
        byId = out.associateBy { it.id }
        matchIndex = out.groupBy { TrackMatch.key(it.artist, it.title) }
        dirOf = dirs
        folderRoot = commonDir(dirs.values)
        albums = out.groupBy { it.albumId }
            .map { (aid, tracks) ->
                val f = tracks.first()
                Album(
                    id = aid,
                    title = f.album,
                    artist = tracks.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
                    artworkUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "",
                    year = albumYear[aid] ?: 0,
                    songCount = tracks.size,
                )
            }
            .sortedByDescending { albumDateAdded[it.id] ?: 0L }
        artists = out.groupBy { it.artistId }
            .map { (aid, tracks) ->
                Artist(
                    id = aid,
                    name = tracks.first().artist,
                    imageUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "",
                    monthlyListeners = 0,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private companion object {
        val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
