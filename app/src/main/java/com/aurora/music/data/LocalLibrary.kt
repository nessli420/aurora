package com.aurora.music.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.playback.dsd.DsdMetadataReader
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class LocalLibrary(
    private val context: Context,
    // scanned replaygain overlaid by path since mediastore tags rarely carry it
    private val gainProvider: (String) -> Pair<Float, Float>? = { null },
    private val separatorsProvider: suspend () -> ArtistSeparators = { ArtistSeparators() },
) : LocalCatalog {

    @Volatile private var loaded = false
    private val mutex = Mutex()
    @Volatile private var lastAutoScanMs = -AUTO_SCAN_GAP_MS

    @Volatile override var songs: List<Song> = emptyList(); private set
    @Volatile override var albums: List<Album> = emptyList(); private set
    @Volatile override var artists: List<Artist> = emptyList(); private set
    private var byId: Map<String, Song> = emptyMap()
    private var rawSongs: List<Song> = emptyList()
    @Volatile private var artistIndex = LocalArtistIndex(emptyList(), ArtistSeparators())
    @Volatile private var appliedSeparators: ArtistSeparators? = null

    @Volatile private var matchIndex: Map<String, List<Song>> = emptyMap()

    @Volatile private var dirOf: Map<String, String> = emptyMap()

    @Volatile override var folderRoot: String = ""; private set

    override suspend fun ensureLoaded() {
        val separators = separatorsProvider()
        if (loaded && separators == appliedSeparators) return
        mutex.withLock {
            val current = separatorsProvider()
            if (!loaded) { scan(current); loaded = canReadAudio() }
            else if (current != appliedSeparators) withContext(Dispatchers.IO) { indexArtists(current) }
        }
    }

    val inUse: Boolean get() = loaded

    suspend fun refresh(): Boolean = mutex.withLock {
        val before = rawSongs
        scan(separatorsProvider())
        loaded = canReadAudio()
        rawSongs != before
    }

    suspend fun rescan(full: Boolean = false, folder: String? = null): LocalRescanResult {
        ensureLoaded()
        val before = songKeys()
        val targets = when {
            folder != null -> listOf(folder)
            full -> volumeRoots()
            else -> LocalRescan.roots(standardFolders() + dirOf.values)
        }
        requestMediaScan(targets, if (full) FULL_SCAN_TIMEOUT_MS else SCAN_TIMEOUT_MS)
        refresh()
        return LocalRescan.result(before, songKeys())
    }

    // files copied onto the device stay hidden from other apps until android scans them, so scan the music folders too
    suspend fun syncWithMediaStore(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoScanMs >= AUTO_SCAN_GAP_MS) {
            lastAutoScanMs = now
            requestMediaScan(LocalRescan.roots(standardFolders() + dirOf.values), SCAN_TIMEOUT_MS)
            lastAutoScanMs = SystemClock.elapsedRealtime()
        }
        return refresh()
    }

    private fun songKeys(): Set<String> = songs.mapTo(HashSet()) { it.path.ifBlank { it.id } }

    private fun volumeRoots(): List<String> = context.getExternalFilesDirs(null).filterNotNull()
        .map { it.absolutePath.substringBefore("/Android/data/") }.distinct()

    @Suppress("DEPRECATION")
    private fun standardFolders(): List<String> = listOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS)
        .map { Environment.getExternalStoragePublicDirectory(it).absolutePath }

    // before android 10 the system scanner does not descend into folders
    private fun audioFilesUnder(folder: String): List<String> = File(folder).walkTopDown().maxDepth(12)
        .filter { it.isFile && it.extension.lowercase() in LocalRescan.AUDIO_EXTENSIONS }
        .take(MAX_LEGACY_SCAN_FILES).map { it.absolutePath }.toList()

    private suspend fun requestMediaScan(paths: List<String>, timeoutMs: Long) {
        if (!canReadAudio()) return
        val targets = if (Build.VERSION.SDK_INT >= 29) paths
            else withContext(Dispatchers.IO) { paths.flatMap { runCatching { audioFilesUnder(it) }.getOrDefault(emptyList()) } }
        if (targets.isEmpty()) return
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val remaining = AtomicInteger(targets.size)
                MediaScannerConnection.scanFile(context, targets.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    private fun canReadAudio(): Boolean = context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    override fun song(id: String): Song? = byId[id]

    fun findMatch(artist: String, title: String, durationSec: Int): Song? =
        LocalCatalogIndex.findMatch(matchIndex, artist, title, durationSec)

    override fun browse(path: String): Pair<List<String>, List<Song>> = LocalCatalogIndex.browse(path, folderRoot, songs, dirOf)

    fun songsIn(album: Album): List<Song> = songs.filter { it.albumId == album.id }
    override fun songsByAlbumId(albumId: String): List<Song> = songs.filter { it.albumId == albumId }
    override fun artist(id: String): Artist? = artistIndex.artist(id)
    override fun songsByArtistId(artistId: String): List<Song> = artistIndex.songsBy(artistId)
    override fun albumsByArtistId(artistId: String): List<Album> =
        songsByArtistId(artistId).map { it.albumId }.distinct()
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
            m.contains("dsf") -> "dsf"
            m.contains("dff") || m.contains("dsdiff") -> "dff"
            else -> ""
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun scan(separators: ArtistSeparators) = withContext(Dispatchers.IO) {
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
        if (Build.VERSION.SDK_INT >= 30) {
            cols.add(MediaStore.Audio.Media.BITRATE) // columns absent pre-30
            cols.add(MediaStore.Audio.Media.GENRE)
        }
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
                val genreCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
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
                        accentArgb = accentArgbFor(id.toString()),
                        streamUrl = uri,
                        albumId = sidAlbum,
                        artistId = artistId.toString(),
                        suffix = suffix,
                        bitrateKbps = bitrateKbps,
                        path = data,
                        replayGainTrack = rg?.first ?: 0f,
                        replayGainAlbum = rg?.second ?: 0f,
                        genre = if (genreCol >= 0) c.getString(genreCol).orEmpty() else "",
                        dateAddedSec = added,
                    )
                }
            }
        }
        out += visibleDsdFiles(out.mapTo(hashSetOf()) { it.id }, dirs)
        for (index in out.indices) {
            currentCoroutineContext().ensureActive()
            val song = out[index]
            if (song.suffix != "dsf" && song.suffix != "dff") continue
            val file = DsdMetadataReader.read(context, Uri.parse(song.streamUrl)) ?: continue
            val tags = file.metadata
            val artist = tags.artist?.toString()?.takeIf(String::isNotBlank) ?: song.artist
            val album = tags.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: song.album
            val albumId = if (!tags.albumTitle.isNullOrBlank()) {
                "local-dsd-album-" + UUID.nameUUIDFromBytes("${tags.albumArtist ?: artist}\u0000$album".toByteArray(Charsets.UTF_8))
            } else song.albumId
            out[index] = song.copy(
                title = tags.title?.toString()?.takeIf(String::isNotBlank) ?: song.title,
                artist = artist,
                album = album,
                albumId = albumId,
                durationSec = (file.durationUs / 1_000_000).toInt(),
                sampleRateHz = file.source.bitRate,
                bitDepth = 1,
                bitrateKbps = file.source.bitRate * file.source.channels / 1000,
                genre = tags.genre?.toString()?.takeIf(String::isNotBlank) ?: song.genre,
            )
            albumDateAdded[albumId] = maxOf(albumDateAdded[albumId] ?: 0, song.dateAddedSec)
            (tags.releaseYear ?: tags.recordingYear)?.takeIf { it > 0 }?.let { albumYear[albumId] = it }
        }
        out += SacdLibrary(context).songs()
        rawSongs = out
        indexArtists(separators)
        matchIndex = LocalCatalogIndex.matchIndex(out)
        dirOf = dirs
        folderRoot = LocalCatalogIndex.commonDir(dirs.values)
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
                    durationSec = tracks.sumOf { it.durationSec },
                )
            }
            .sortedByDescending { albumDateAdded[it.id] ?: 0L }
    }

    private fun visibleDsdFiles(indexed: Set<String>, dirs: MutableMap<String, String>): List<Song> {
        val collection = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val files = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(collection, columns, selection, arrayOf("%.dsf", "%.dff"), null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                @Suppress("DEPRECATION") val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    if (mediaId.toString() in indexed) continue
                    val name = cursor.getString(nameColumn).orEmpty()
                    val suffix = suffixFrom(name, cursor.getString(mimeColumn))
                    if (suffix != "dsf" && suffix != "dff") continue
                    val id = "local-dsd-$mediaId"
                    val path = cursor.getString(pathColumn).orEmpty()
                    val folder = path.substringBeforeLast('/', "")
                    if (folder.isNotBlank()) dirs[id] = folder
                    val gain = path.takeIf(String::isNotBlank)?.let(gainProvider)
                    files += Song(
                        id = id, title = name.substringBeforeLast('.'), artist = "Unknown artist", album = "Unknown album",
                        artworkUrl = "", durationSec = 0, accentArgb = accentArgbFor(id),
                        streamUrl = ContentUris.withAppendedId(collection, mediaId).toString(),
                        albumId = "local-dsd-folder-" + UUID.nameUUIDFromBytes(folder.ifBlank { id }.toByteArray(Charsets.UTF_8)),
                        suffix = suffix, path = path, replayGainTrack = gain?.first ?: 0f, replayGainAlbum = gain?.second ?: 0f,
                        dateAddedSec = cursor.getLong(addedColumn),
                    )
                }
            }
        }
        return files
    }

    private fun indexArtists(separators: ArtistSeparators) {
        val index = LocalArtistIndex(rawSongs, separators)
        artistIndex = index
        songs = index.songs
        artists = index.artists
        byId = index.songs.associateBy { it.id }
        appliedSeparators = separators
    }

    private companion object {
        val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
        const val SCAN_TIMEOUT_MS = 60_000L
        const val FULL_SCAN_TIMEOUT_MS = 300_000L
        const val MAX_LEGACY_SCAN_FILES = 20_000
        const val AUTO_SCAN_GAP_MS = 15_000L
    }
}
