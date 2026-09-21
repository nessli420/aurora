package com.aurora.music.data.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class AudioCacheStatus(val bytes: Long = 0, val tracks: Int = 0, val ready: Boolean = false, val failed: Boolean = false)

/** One cache shared by the player and its crossfade/mix decks. Initialization and scans stay off the UI thread. */
class AudioCache(context: Context, private val settings: SettingsStore?,
    private val offline: () -> Boolean = { false }, directory: File = File(context.cacheDir, "audio-streams")) {
    private val app = context.applicationContext
    private val root = directory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<String, CachedTrack>()
    private val gson = Gson()
    @Volatile private var prefs = AudioCachePrefs()
    private val evictor = ResizableCacheEvictor(prefs.limitBytes) { updates.trySend(Unit) }
    private val storage by lazy { SimpleCache(root, evictor, StandaloneDatabaseProvider(app)) }
    private val mutableStatus = MutableStateFlow(AudioCacheStatus())
    val status = mutableStatus.asStateFlow()
    private val mutableSongs = MutableStateFlow<List<Song>>(emptyList())
    val songs = mutableSongs.asStateFlow()

    init {
        scope.launch {
            try {
                settings?.audioCachePrefs?.first()?.let { prefs = it }
                storage.checkInitialization()
                evictor.resize(storage, prefs.limitBytes)
                refresh()
                launch {
                    for (event in updates) { delay(250); refresh() }
                }
                settings?.audioCachePrefs?.collect { configure(it) }
            } catch (_: Exception) { mutableStatus.value = AudioCacheStatus(failed = true) }
        }
    }

    suspend fun configure(value: AudioCachePrefs) = withContext(Dispatchers.IO) {
        prefs = value
        evictor.resize(storage, value.limitBytes)
        refresh()
    }

    /** Stable account + resource identity; credentials never become filenames. */
    fun prepare(item: MediaItem): MediaItem {
        val config = item.localConfiguration ?: return item
        if (config.uri.scheme == SCHEME) return item.buildUpon().setCustomCacheKey(config.uri.host).build()
        val extras = item.mediaMetadata.extras
        if (config.uri.scheme !in listOf("http", "https", "aurora-yt") ||
            extras?.getString("aurora.rules.source") == RuleSource.RADIO.name ||
            (extras?.getInt("aurora.durationSec") ?: 0) <= 0 || config.drmConfiguration != null ||
            Util.inferContentTypeForUriAndMimeType(config.uri, config.mimeType) != C.CONTENT_TYPE_OTHER) return item
        val key = keyFor(config.uri.toString(), extras?.getString("aurora.rules.provider").orEmpty())
        pending[key] = CachedTrack.from(item)
        // Queue rebuilding can register many tracks; only opened tracks need persistent metadata.
        if (pending.size > 4096) pending.keys.firstOrNull { it != key }?.let(pending::remove)
        val cached = if (offline()) mutableSongs.value.firstOrNull { it.streamUrl == "$SCHEME://$key" } else null
        return item.buildUpon().setCustomCacheKey(key).apply { if (cached != null) setUri(cached.streamUrl) }.build()
    }

    fun factory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private var source: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            override fun addTransferListener(listener: TransferListener) { listeners += listener; source?.addTransferListener(listener) }
            override fun open(dataSpec: DataSpec): Long {
                val cacheOnly = dataSpec.uri.scheme == SCHEME
                val key = if (cacheOnly) dataSpec.uri.host else dataSpec.key
                val registered = key != null && (cacheOnly ||
                    (dataSpec.uri.scheme in listOf("http", "https", "aurora-yt") && pending.containsKey(key)))
                if (!registered) {
                    if (cacheOnly) throw IOException("Cached audio is unavailable")
                    source = upstream.createDataSource()
                } else {
                    val cache = try { storage.also { it.checkInitialization() } } catch (error: Exception) {
                        if (cacheOnly || offline()) throw IOException("Cached audio is unavailable", error)
                        null
                    }
                    source = if (cache == null) upstream.createDataSource() else {
                        if (prefs.enabled) pending[key]?.let { record ->
                            runCatching { cache.applyContentMetadataMutations(key!!, ContentMetadataMutations().set(TRACK_METADATA, gson.toJson(record))) }
                        }
                        CacheDataSource.Factory().setCache(cache)
                            .setUpstreamDataSourceFactory(if (cacheOnly || offline()) null else upstream)
                            .setCacheWriteDataSinkFactory(if (cacheOnly || !prefs.enabled) null else DataSink.Factory { guardedSink(key!!) })
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                            .createDataSource()
                    }
                }
                listeners.forEach { source!!.addTransferListener(it) }
                return source!!.open(if (registered) dataSpec.buildUpon().setKey(key).build() else dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(source).read(buffer, offset, length)
            override fun getUri(): Uri? = source?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
            override fun close() { try { source?.close() } finally { source = null; updates.trySend(Unit) } }
        }
    }

    private fun guardedSink(key: String): DataSink = object : DataSink {
        private var sink: CacheDataSink? = null
        private var epoch = generation.get()
        private var bytesUntilSpaceCheck = 0
        override fun open(dataSpec: DataSpec) {
            epoch = generation.get()
            if (!prefs.enabled || root.usableSpace < FREE_SPACE_RESERVE || dataSpec.length > prefs.limitBytes) return
            val candidate = CacheDataSink(storage, 1024 * 1024L)
            try { candidate.open(dataSpec); sink = candidate }
            catch (_: IOException) { runCatching { candidate.close() } }
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytesUntilSpaceCheck -= length
            val lowSpace = if (bytesUntilSpaceCheck <= 0) {
                bytesUntilSpaceCheck = 1024 * 1024
                root.usableSpace < FREE_SPACE_RESERVE
            } else false
            if (epoch != generation.get() || !prefs.enabled || lowSpace) {
                close()
                return
            }
            try { sink?.write(buffer, offset, length) } catch (_: IOException) { close() }
        }
        override fun close() {
            val active = sink ?: return
            sink = null
            try { active.close() } catch (_: IOException) { /* Caching must not interrupt the stream. */ } finally {
                if (epoch != generation.get()) runCatching { storage.removeResource(key) }
                updates.trySend(Unit)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        generation.incrementAndGet()
        synchronized(storage) { storage.keys.toList().forEach(storage::removeResource) }
        refresh()
    }

    /** Promote a complete cache entry to an explicit download without fetching it again. */
    fun copyTo(uri: String, file: File, progress: (Float) -> Unit) {
        require(Uri.parse(uri).scheme == SCHEME)
        val source = factory(DataSource.Factory { throw IOException("Cached audio is unavailable") }).createDataSource()
        try {
            val length = source.open(DataSpec.Builder().setUri(uri).build())
            if (length <= 0) throw IOException("Cached audio is incomplete")
            var copied = 0L
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    progress((copied.toFloat() / length).coerceIn(0f, 1f))
                }
            }
            if (copied != length) throw IOException("Cached audio is incomplete")
        } finally { source.close() }
    }

    internal suspend fun refresh() = withContext(Dispatchers.IO) {
        val cache = storage
        synchronized(cache) {
            val available = cache.keys.mapNotNull { key ->
                val metadata = cache.getContentMetadata(key)
                val length = ContentMetadata.getContentLength(metadata)
                if (length <= 0 || !cache.isCached(key, 0, length)) return@mapNotNull null
                val record = runCatching { gson.fromJson(metadata.get(TRACK_METADATA, ""), CachedTrack::class.java) }.getOrNull()
                    ?: return@mapNotNull null
                record.toSong(key)
            }
            mutableSongs.value = available.sortedBy { it.title }
            mutableStatus.value = AudioCacheStatus(cache.cacheSpace, available.size, ready = true)
        }
    }

    internal fun release() { scope.cancel(); storage.release() }

    companion object {
        const val SCHEME = "aurora-cache"
        private const val TRACK_METADATA = "custom_aurora_track_v1"
        private const val FREE_SPACE_RESERVE = 64 * 1024 * 1024L
        internal fun keyFor(uri: String, provider: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$provider\u0000$uri".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private data class CachedTrack(
    val id: String? = null, val title: String? = null, val artist: String? = null, val album: String? = null,
    val albumId: String? = null, val artistId: String? = null, val artwork: String? = null,
    val duration: Int? = null, val suffix: String? = null, val provider: String? = null,
    val providerLabel: String? = null, val sourceAlbum: String? = null, val trackGain: Float? = null, val albumGain: Float? = null,
) {
    fun toSong(key: String): Song? {
        if (id.isNullOrBlank() || title.isNullOrBlank() || (duration ?: 0) <= 0) return null
        return Song(id, title, artist.orEmpty(), album.orEmpty(), artwork.orEmpty(), duration ?: 0,
            streamUrl = "${AudioCache.SCHEME}://$key", albumId = albumId.orEmpty(), artistId = artistId.orEmpty(),
            suffix = suffix.orEmpty(), replayGainTrack = trackGain ?: 0f, replayGainAlbum = albumGain ?: 0f,
            playbackSource = PlaybackSourceIdentity(provider, providerLabel, RuleSource.STREAM, sourceAlbum))
    }
    companion object {
        fun from(item: MediaItem): CachedTrack {
            val metadata = item.mediaMetadata
            val extras = metadata.extras
            return CachedTrack(extras?.getString("aurora.songId") ?: item.mediaId, metadata.title?.toString(),
                metadata.artist?.toString(), metadata.albumTitle?.toString(), extras?.getString("aurora.albumId"),
                extras?.getString("aurora.artistId"), metadata.artworkUri?.toString(), extras?.getInt("aurora.durationSec"),
                extras?.getString("aurora.rules.container"), extras?.getString("aurora.rules.provider"),
                extras?.getString("aurora.rules.providerLabel"), extras?.getString("aurora.rules.album"),
                extras?.getFloat("rgTrack"), extras?.getFloat("rgAlbum"))
        }
    }
}
