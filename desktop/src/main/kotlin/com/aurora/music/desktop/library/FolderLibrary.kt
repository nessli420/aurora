package com.aurora.music.desktop.library

import com.aurora.music.data.ArtistSeparators
import com.aurora.music.data.LocalArtistIndex
import com.aurora.music.data.LocalCatalog
import com.aurora.music.data.LocalCatalogIndex
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.ProbeResult
import com.aurora.music.desktop.audio.decode.SampleKind
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class LibraryScan(val running: Boolean = false, val scanned: Int = 0, val total: Int = 0)

class FolderLibrary(
    dir: File,
    private val folders: suspend () -> List<String>,
    private val fileUri: (String) -> String,
    private val scope: CoroutineScope,
    private val gainProvider: (String) -> Pair<Float, Float>? = { null },
    private val separatorsProvider: suspend () -> ArtistSeparators = { ArtistSeparators() },
    private val probe: (File) -> ProbeResult = { FfmpegDecoder.probe(it) },
) : LocalCatalog {
    private val index = LibraryIndex(File(dir, "index.json"))
    private val covers = File(dir, "covers")
    private val mutex = Mutex()
    private val prober = Dispatchers.IO.limitedParallelism(4)

    @Volatile private var loaded = false
    @Volatile private var tracks: List<IndexedTrack> = emptyList()
    @Volatile override var songs: List<Song> = emptyList(); private set
    @Volatile override var albums: List<Album> = emptyList(); private set
    @Volatile override var artists: List<Artist> = emptyList(); private set
    @Volatile override var folderRoot: String = ""; private set
    @Volatile private var rawSongs: List<Song> = emptyList()
    @Volatile private var byId: Map<String, Song> = emptyMap()
    @Volatile private var byAlbum: Map<String, List<Song>> = emptyMap()
    @Volatile private var positions: Map<String, Int> = emptyMap()
    @Volatile private var dirOf: Map<String, String> = emptyMap()
    @Volatile private var matchIndex: Map<String, List<Song>> = emptyMap()
    @Volatile private var artistIndex = LocalArtistIndex(emptyList(), ArtistSeparators())
    @Volatile private var appliedSeparators: ArtistSeparators? = null

    private val _scan = MutableStateFlow(LibraryScan())
    val scan: StateFlow<LibraryScan> = _scan.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    override suspend fun ensureLoaded() {
        val separators = separatorsProvider()
        if (loaded && separators == appliedSeparators) return
        mutex.withLock {
            val current = separatorsProvider()
            if (!loaded) {
                val cached = withContext(Dispatchers.IO) { index.read() }
                if (cached != null && cached.folders == configured()) {
                    withContext(Dispatchers.IO) { publish(cached.tracks, current) }
                    loaded = true
                    scope.launch { runCatching { refresh() }.onFailure { AppLog.e(TAG, "Library refresh failed", it) } }
                } else {
                    scan(cached?.tracks.orEmpty(), current)
                    loaded = true
                }
            } else if (current != appliedSeparators) withContext(Dispatchers.Default) { indexArtists(current) }
        }
    }

    suspend fun refresh() = mutex.withLock {
        val previous = if (loaded) tracks else withContext(Dispatchers.IO) { index.read()?.tracks.orEmpty() }
        scan(previous, separatorsProvider())
        loaded = true
    }

    override fun song(id: String): Song? = byId[id]

    fun findMatch(artist: String, title: String, durationSec: Int): Song? =
        LocalCatalogIndex.findMatch(matchIndex, artist, title, durationSec)

    override fun browse(path: String): Pair<List<String>, List<Song>> = LocalCatalogIndex.browse(path, folderRoot, songs, dirOf)

    override fun songsByAlbumId(albumId: String): List<Song> = byAlbum[albumId].orEmpty()
    override fun artist(id: String): Artist? = artistIndex.artist(id)
    override fun songsByArtistId(artistId: String): List<Song> = artistIndex.songsBy(artistId)
    override fun albumsByArtistId(artistId: String): List<Album> =
        songsByArtistId(artistId).map { it.albumId }.distinct().mapNotNull { id -> albums.firstOrNull { it.id == id } }

    private suspend fun configured(): List<String> =
        folders().map { File(it).absoluteFile.normalize().path }.distinctBy { it.lowercase(Locale.ROOT) }

    private suspend fun scan(previous: List<IndexedTrack>, separators: ArtistSeparators) {
        val roots = configured()
        val known = previous.associateBy { key(it.path) }
        _scan.value = LibraryScan(running = true)
        try {
            val (available, missing) = roots.partition { File(it).isDirectory }
            val files = withContext(Dispatchers.IO) { available.flatMap(::audioFiles).distinctBy { key(it.first.path) } }
            _scan.value = LibraryScan(true, 0, files.size)
            val found = coroutineScope {
                files.map { (file, attributes) ->
                    async(prober) {
                        entry(file, attributes, known[key(file.path)]).also { _scan.update { it.copy(scanned = it.scanned + 1) } }
                    }
                }.awaitAll()
            }.filterNotNull()
            // keep tracks from unplugged drives until the drive returns
            val next = found + previous.filter { track -> missing.any { isUnder(track.path, it) } }
            val changed = next.toSet() != tracks.toSet()
            withContext(Dispatchers.IO) {
                index.write(roots, next)
                val used = next.mapTo(HashSet()) { it.cover }
                covers.listFiles()?.filter { it.isFile && it.name !in used }?.forEach(File::delete)
                publish(next, separators)
            }
            if (changed) _revision.value++
        } finally {
            _scan.value = LibraryScan()
        }
    }

    private fun audioFiles(root: String): List<Pair<File, BasicFileAttributes>> {
        val out = ArrayList<Pair<File, BasicFileAttributes>>()
        Files.walkFileTree(Path.of(root), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (attrs.isRegularFile && !name.startsWith("._") && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS) {
                    out += file.toFile() to attrs
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return out
    }

    private fun entry(file: File, attributes: BasicFileAttributes, previous: IndexedTrack?): IndexedTrack? {
        val size = attributes.size()
        val modified = attributes.lastModifiedTime().toMillis()
        if (previous != null && previous.size == size && previous.modifiedMs == modified &&
            (previous.cover.isEmpty() || File(covers, previous.cover).isFile)) return previous.copy(path = file.path)
        val result = try { probe(file) } catch (e: Exception) {
            AppLog.w(TAG, "Skipping ${file.path}", e)
            return null
        }
        val tags = result.tags
        val info = result.info
        return IndexedTrack(
            path = file.path,
            size = size,
            modifiedMs = modified,
            addedSec = previous?.addedSec ?: (attributes.creationTime().toMillis() / 1000),
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            albumArtist = tags.albumArtist,
            track = tags.track ?: 0,
            disc = tags.disc ?: 0,
            year = tags.date?.let { YEAR.find(it)?.value?.toInt() } ?: 0,
            genre = tags.genre,
            durationMs = info.durationMs.coerceAtLeast(0),
            codec = info.codec,
            sampleRate = info.sampleRate,
            channels = info.channels,
            bitDepth = if (info.sampleFormat.kind == SampleKind.LOSSY) 0 else info.sampleFormat.bits,
            bitrate = info.bitrate,
            trackGainDb = tags.trackGainDb ?: tags.r128TrackGainDb?.plus(R128_TO_REPLAYGAIN_DB),
            albumGainDb = tags.albumGainDb ?: tags.r128AlbumGainDb?.plus(R128_TO_REPLAYGAIN_DB),
            cover = result.cover?.let(::saveCover).orEmpty(),
        )
    }

    private fun saveCover(bytes: ByteArray): String? {
        val name = sha1(bytes) + when {
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> ".png"
            bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ".jpg"
            else -> ".img"
        }
        val target = File(covers, name)
        if (!target.isFile) runCatching {
            covers.mkdirs()
            val temporary = File(covers, ".$name-${UUID.randomUUID()}.tmp")
            try {
                temporary.writeBytes(bytes)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
        return name.takeIf { target.isFile }
    }

    private fun publish(entries: List<IndexedTrack>, separators: ArtistSeparators) {
        val folderArt = HashMap<String, String>()
        val built = entries.map { it to song(it, folderArt) }.sortedBy { it.second.title.lowercase() }
        tracks = entries
        rawSongs = built.map { it.second }
        positions = built.associate { (track, song) -> song.id to track.disc * 10_000 + track.track }
        dirOf = built.associate { (_, song) -> song.id to dirKey(File(song.path).parent.orEmpty()) }
        folderRoot = LocalCatalogIndex.commonDir(dirOf.values)
        matchIndex = LocalCatalogIndex.matchIndex(rawSongs)
        albums = built.groupBy { it.second.albumId }
            .map { (id, group) -> album(id, group) to group.maxOf { it.second.dateAddedSec } }
            .sortedByDescending { it.second }
            .map { it.first }
        indexArtists(separators)
    }

    private fun album(id: String, group: List<Pair<IndexedTrack, Song>>): Album {
        val tracks = group.map { it.second }
        val albumArtist = group.mapNotNull { it.first.albumArtist.clean() }.distinct().singleOrNull()
        return Album(
            id = id,
            title = tracks.first().album,
            artist = albumArtist ?: tracks.map { it.artist }.distinct().let { if (it.size == 1) it.first() else VARIOUS_ARTISTS },
            artworkUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
            year = group.firstOrNull { it.first.year > 0 }?.first?.year ?: 0,
            songCount = tracks.size,
            durationSec = tracks.sumOf { it.durationSec },
        )
    }

    private fun song(track: IndexedTrack, folderArt: MutableMap<String, String>): Song {
        val file = File(track.path)
        val folder = file.parent.orEmpty()
        val id = "local-" + sha1(key(track.path).toByteArray())
        val album = track.album.clean() ?: file.parentFile?.name.clean() ?: UNKNOWN_ALBUM
        val owner = track.albumArtist.clean()?.lowercase(Locale.ROOT) ?: key(albumFolder(file))
        val dsd = track.codec.startsWith("dsd_")
        val sampleRate = if (dsd) track.sampleRate * 8 else track.sampleRate
        val gains = gainProvider(track.path)
        return Song(
            id = id,
            title = track.title.clean() ?: file.nameWithoutExtension,
            artist = track.artist.clean() ?: track.albumArtist.clean() ?: UNKNOWN_ARTIST,
            album = album,
            artworkUrl = if (track.cover.isNotEmpty()) fileUri(File(covers, track.cover).path) else folderArt.getOrPut(folder) { folderImage(folder) },
            durationSec = ((track.durationMs + 500) / 1000).toInt(),
            accentArgb = accentArgbFor(id),
            streamUrl = fileUri(track.path),
            albumId = "local-album-" + sha1("${album.lowercase(Locale.ROOT)}\u0000$owner".toByteArray()),
            suffix = file.extension.lowercase(Locale.ROOT),
            bitrateKbps = when {
                dsd -> sampleRate * track.channels / 1000
                track.bitrate > 0 -> (track.bitrate / 1000).toInt()
                track.durationMs > 0 -> (track.size * 8 / track.durationMs).toInt()
                else -> 0
            },
            sampleRateHz = sampleRate,
            bitDepth = if (dsd) 1 else track.bitDepth,
            replayGainTrack = gains?.first ?: track.trackGainDb ?: 0f,
            replayGainAlbum = gains?.second ?: track.albumGainDb ?: 0f,
            path = track.path,
            genre = track.genre.clean().orEmpty(),
            dateAddedSec = track.addedSec,
        )
    }

    private fun folderImage(folder: String): String =
        FOLDER_IMAGES.map { File(folder, it) }.firstOrNull { it.isFile }?.let { fileUri(it.path) }.orEmpty()

    private fun albumFolder(file: File): String {
        val parent = file.absoluteFile.parentFile ?: return ""
        return (if (DISC_FOLDER.matches(parent.name)) parent.parentFile ?: parent else parent).path
    }

    private fun indexArtists(separators: ArtistSeparators) {
        val index = LocalArtistIndex(rawSongs, separators)
        artistIndex = index
        songs = index.songs
        artists = index.artists
        byId = index.songs.associateBy { it.id }
        byAlbum = index.songs.groupBy { it.albumId }
            .mapValues { (_, list) -> list.sortedWith(compareBy<Song>({ positions[it.id] ?: 0 }, { it.title.lowercase() })) }
        appliedSeparators = separators
    }

    private companion object {
        const val TAG = "FolderLibrary"
        // virtual root so folders on different drives still share a parent
        const val ROOT = "Computer"
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
        const val VARIOUS_ARTISTS = "Various artists"
        const val R128_TO_REPLAYGAIN_DB = 5f
        val EXTENSIONS = setOf(
            "flac", "mp3", "m4a", "aac", "alac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "aifc",
            "wv", "ape", "dsf", "dff", "wma", "mpc", "tta", "mka",
        )
        val FOLDER_IMAGES = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png", "album.jpg", "album.png")
        val YEAR = Regex("\\d{4}")
        val DISC_FOLDER = Regex("(?i)(cd|dis[ck])\\s*\\d+")

        fun key(path: String): String = File(path).absoluteFile.normalize().path.replace('\\', '/').lowercase(Locale.ROOT)

        fun dirKey(dir: String): String = "$ROOT/" + dir.replace('\\', '/').trim('/')

        fun isUnder(path: String, root: String): Boolean = key(path).startsWith(key(root).trimEnd('/') + "/")

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
