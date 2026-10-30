package com.aurora.music.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.playback.dsd.DsdFileMetadata
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class LocalLibrary(
    private val context: Context,
    // scanned replaygain overlaid by path since mediastore tags rarely carry it
    private val gainProvider: (String) -> Pair<Float, Float>? = { null },
    private val separatorsProvider: suspend () -> ArtistSeparators = { ArtistSeparators() },
    private val trackCoversProvider: suspend () -> Boolean = { false },
    // chosen folders (empty = whole device) and excluded folders
    private val foldersProvider: suspend () -> Pair<Set<String>, Set<String>> = { emptySet<String>() to emptySet() },
) : LocalCatalog {

    @Volatile private var loaded = false
    private val mutex = Mutex()
    @Volatile private var indexedSignature: List<Long>? = null
    @androidx.annotation.OptIn(UnstableApi::class)
    private val dsdCache = ConcurrentHashMap<String, Pair<String, DsdFileMetadata>>()

    @Volatile override var songs: List<Song> = emptyList(); private set
    @Volatile override var albums: List<Album> = emptyList(); private set
    @Volatile override var artists: List<Artist> = emptyList(); private set
    private var byId: Map<String, Song> = emptyMap()
    private var rawSongs: List<Song> = emptyList()
    @Volatile private var artistIndex = LocalArtistIndex(emptyList(), ArtistSeparators())
    @Volatile private var appliedSeparators: ArtistSeparators? = null
    @Volatile private var appliedTrackCovers = false

    @Volatile private var matchIndex: Map<String, List<Song>> = emptyMap()

    @Volatile private var dirOf: Map<String, String> = emptyMap()
    @Volatile private var positions: Map<String, Int> = emptyMap()
    // untagged files fall back to filename order, ties keep scan order
    private val trackOrder = compareBy<Song>({ positions[it.id] ?: 0 }, { it.path.substringAfterLast('/').lowercase() })

    @Volatile override var folderRoot: String = ""; private set

    override suspend fun ensureLoaded() {
        val separators = separatorsProvider()
        if (loaded && separators == appliedSeparators && trackCoversProvider() == appliedTrackCovers) return
        mutex.withLock {
            val current = separatorsProvider()
            val covers = trackCoversProvider()
            if (!loaded) { scan(current, covers); loaded = canReadAudio() }
            else if (current != appliedSeparators || covers != appliedTrackCovers) withContext(Dispatchers.IO) { indexArtists(current, covers) }
        }
    }

    val inUse: Boolean get() = loaded

    suspend fun refresh(): Boolean = mutex.withLock {
        val before = rawSongs
        scan(separatorsProvider(), trackCoversProvider())
        loaded = canReadAudio()
        rawSongs != before
    }

    suspend fun rescan(full: Boolean = false, folder: String? = null): LocalRescanResult {
        ensureLoaded()
        val before = songKeys()
        val chosen = foldersProvider().first
        val targets = when {
            folder != null -> listOf(folder)
            full -> LocalRescan.roots(chosen).ifEmpty { volumeRoots() }
            else -> LocalRescan.roots(standardFolders() + chosen + dirOf.values)
        }
        requestMediaScan(targets, if (full) FULL_SCAN_TIMEOUT_MS else SCAN_TIMEOUT_MS)
        refresh()
        return LocalRescan.result(before, songKeys())
    }

    // change notifications only rebuild when the indexed rows really changed the media scanner stays manual
    suspend fun syncWithMediaStore(): Boolean {
        val current = withContext(Dispatchers.IO) { mediaSignature() }
        if (current != null && current == indexedSignature) return false
        return refresh()
    }

    private fun mediaSignature(): List<Long>? = runCatching {
        rowSignature(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null) +
            rowSignature(MediaStore.Files.getContentUri("external"), DSD_SELECTION, DSD_ARGS)
    }.getOrNull()

    private fun rowSignature(uri: Uri, selection: String, args: Array<String>?): List<Long> {
        val generations = Build.VERSION.SDK_INT >= 30
        val columns = if (generations) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED)
            else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED)
        var rows = 0L
        var hash = 17L
        val cursor = context.contentResolver.query(uri, columns, selection, args, "${MediaStore.MediaColumns._ID} ASC") ?: return listOf(-1L)
        cursor.use { c ->
            while (c.moveToNext()) {
                rows++
                hash = hash * 31 + c.getLong(0)
                hash = hash * 31 + c.getLong(1)
                if (generations) hash = hash * 31 + c.getLong(2)
            }
        }
        return listOf(rows, hash)
    }

    private fun songKeys(): Set<String> = songs.mapTo(HashSet()) { it.path.ifBlank { it.id } }

    private fun volumeRoots(): List<String> = context.getExternalFilesDirs(null).filterNotNull()
        .map { it.absolutePath.substringBefore("/Android/data/") }.distinct()

    // songs aurora saved itself stay unless that folder is excluded
    @Suppress("DEPRECATION")
    private fun auroraFolder(): String =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), YouTubeDownloader.FOLDER).absolutePath

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

    override fun browse(path: String): Pair<List<String>, List<Song>> = LocalCatalogIndex.browse(path, folderRoot, songs, dirOf, positions)

    fun songsIn(album: Album): List<Song> = songsByAlbumId(album.id)
    override fun songsByAlbumId(albumId: String): List<Song> = songs.filter { it.albumId == albumId }.sortedWith(trackOrder)
    override fun artist(id: String): Artist? = artistIndex.artist(id)
    override fun songsByArtistId(artistId: String): List<Song> = artistIndex.songsBy(artistId)
    override fun albumsByArtistId(artistId: String): List<Album> =
        songsByArtistId(artistId).map { it.albumId }.distinct()
            .mapNotNull { aid -> albums.firstOrNull { it.id == aid } }

    private fun albumArtUri(albumId: Long): String =
        if (albumId <= 0) "" else ContentUris.withAppendedId(ALBUM_ART_BASE, albumId).toString()

    private fun leadingNumber(tag: String?): Int? = tag?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }

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
    private fun dsdMetadata(song: Song, stamp: String?): DsdFileMetadata? {
        if (stamp != null) dsdCache[song.streamUrl]?.takeIf { it.first == stamp }?.let { return it.second }
        val read = DsdMetadataReader.read(context, Uri.parse(song.streamUrl))
        if (stamp != null && read != null) dsdCache[song.streamUrl] = stamp to read
        return read
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun scan(separators: ArtistSeparators, trackCovers: Boolean) = withContext(Dispatchers.IO) {
        val signature = mediaSignature()
        val (folders, excluded) = foldersProvider()
        val kept = listOf(auroraFolder())
        val keep = { path: String -> LocalRescan.inScope(path, folders, excluded, kept) }
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
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 30) {
            cols.add(MediaStore.Audio.Media.BITRATE) // columns absent pre-30
            cols.add(MediaStore.Audio.Media.GENRE)
            cols.add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
            cols.add(MediaStore.Audio.Media.DISC_NUMBER)
        }
        val projection = cols.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<Song>()
        val albumDateAdded = HashMap<String, Long>()
        val albumYear = HashMap<String, Int>()
        val dirs = HashMap<String, String>()
        val order = HashMap<String, Int>()
        val stamps = HashMap<String, String>()
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
                val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val bitrateCol = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                val genreCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val cdTrackCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.CD_TRACK_NUMBER) else -1
                val discCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else -1
                @Suppress("DEPRECATION") val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val data = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    if (!keep(data)) continue
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
                    if ((suffix == "dsf" || suffix == "dff") && modifiedCol >= 0 && sizeCol >= 0)
                        stamps[id.toString()] = "${c.getLong(modifiedCol)}:${c.getLong(sizeCol)}"
                    val bitrateKbps = if (bitrateCol >= 0) (runCatching { c.getInt(bitrateCol) }.getOrDefault(0) / 1000) else 0
                    val art = albumArtUri(albumId)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    if (data.contains('/')) dirs[id.toString()] = data.substringBeforeLast('/')
                    // TRACK packs disc * 1000 + track; the raw tags are preferred when android exposes them
                    val packed = if (trackCol >= 0) runCatching { c.getInt(trackCol) }.getOrDefault(0) else 0
                    val track = leadingNumber(if (cdTrackCol >= 0) c.getString(cdTrackCol) else null) ?: (packed % 1000)
                    val disc = leadingNumber(if (discCol >= 0) c.getString(discCol) else null) ?: (packed / 1000)
                    if (track > 0 || disc > 0) order[id.toString()] = disc * 10_000 + track
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
        out += visibleDsdFiles(out.mapTo(hashSetOf()) { it.id }, dirs, stamps, keep)
        dsdCache.keys.retainAll(out.filter { it.suffix == "dsf" || it.suffix == "dff" }.mapTo(HashSet()) { it.streamUrl })
        for (index in out.indices) {
            currentCoroutineContext().ensureActive()
            val song = out[index]
            if (song.suffix != "dsf" && song.suffix != "dff") continue
            val file = dsdMetadata(song, stamps[song.id]) ?: continue
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
            tags.trackNumber?.let { order[song.id] = (tags.discNumber ?: 0) * 10_000 + it }
            albumDateAdded[albumId] = maxOf(albumDateAdded[albumId] ?: 0, song.dateAddedSec)
            (tags.releaseYear ?: tags.recordingYear)?.takeIf { it > 0 }?.let { albumYear[albumId] = it }
        }
        val albumFiles = out.filter { it.durationSec >= CUE_MIN_SEC }.mapNotNull { dirs[it.id] }
        val parts = if (albumFiles.isEmpty()) emptyList() else CueSheets.split(out, CueLibrary(context).sheets(albumFiles))
        if (parts.isNotEmpty()) {
            val wholes = parts.mapTo(HashSet()) { it.whole.id }
            out.removeAll { it.id in wholes }
            for (part in parts) {
                val song = part.song.copy(accentArgb = accentArgbFor(part.song.id))
                out += song
                order[song.id] = part.position
                dirs[part.whole.id]?.let { dirs[song.id] = it }
                albumDateAdded[song.albumId] = maxOf(albumDateAdded[song.albumId] ?: 0L, song.dateAddedSec)
                (part.year.takeIf { it > 0 } ?: albumYear[part.whole.albumId])?.let { albumYear[song.albumId] = it }
            }
            wholes.forEach(dirs::remove)
        }
        out += SacdLibrary(context).songs()
        indexedSignature = signature
        positions = order
        rawSongs = out
        indexArtists(separators, trackCovers)
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

    private fun visibleDsdFiles(indexed: Set<String>, dirs: MutableMap<String, String>, stamps: MutableMap<String, String>,
                                keep: (String) -> Boolean): List<Song> {
        val collection = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA)
        val files = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(collection, columns, DSD_SELECTION, DSD_ARGS, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                @Suppress("DEPRECATION") val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    if (mediaId.toString() in indexed) continue
                    val name = cursor.getString(nameColumn).orEmpty()
                    val suffix = suffixFrom(name, cursor.getString(mimeColumn))
                    if (suffix != "dsf" && suffix != "dff") continue
                    val path = cursor.getString(pathColumn).orEmpty()
                    if (!keep(path)) continue
                    val id = "local-dsd-$mediaId"
                    stamps[id] = "${cursor.getLong(modifiedColumn)}:${cursor.getLong(sizeColumn)}"
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

    // albums keep mediastore's one cover per album; with track covers on, songs show their own file's embedded art
    private fun indexArtists(separators: ArtistSeparators, trackCovers: Boolean) {
        val index = LocalArtistIndex(if (trackCovers) rawSongs.map(::ownCover) else rawSongs, separators)
        artistIndex = index
        songs = index.songs
        artists = index.artists
        byId = index.songs.associateBy { it.id }
        appliedSeparators = separators
        appliedTrackCovers = trackCovers
    }

    private fun ownCover(song: Song): Song =
        if (AUDIO_ROW.matches(song.streamUrl)) song.copy(artworkUrl = "${song.streamUrl}/albumart") else song

    private companion object {
        val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
        // cue parts (#t=) and dsd file rows have no per-row art
        val AUDIO_ROW = Regex("""content://media/external/audio/media/\d+""")
        const val SCAN_TIMEOUT_MS = 60_000L
        const val FULL_SCAN_TIMEOUT_MS = 300_000L
        const val MAX_LEGACY_SCAN_FILES = 20_000
        // a whole album in one file; shorter files are not worth a folder lookup
        const val CUE_MIN_SEC = 600
        val DSD_SELECTION = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val DSD_ARGS = arrayOf("%.dsf", "%.dff")
    }
}
