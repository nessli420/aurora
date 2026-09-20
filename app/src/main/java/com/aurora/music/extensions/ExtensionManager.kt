package com.aurora.music.extensions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.aurora.extension.ExtensionContract
import com.aurora.music.data.*
import com.aurora.music.data.remote.MetadataMatch
import com.aurora.music.model.Song
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ExtensionManager(private val context: Context, private val settings: SettingsStore, scope: CoroutineScope) {
    private val client = ExtensionClient(context)
    private val mutation = Mutex()
    private val gson = Gson()
    private val indexes = ConcurrentHashMap<String, List<ExtensionTrack>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val refreshed = ConcurrentHashMap<String, Long>()
    private val mutableEntries = MutableStateFlow<List<ExtensionEntry>>(emptyList())
    val entries: StateFlow<List<ExtensionEntry>> = mutableEntries.asStateFlow()
    private var grants = emptyList<ExtensionGrant>()
    private val manifests = ConcurrentHashMap<String, ExtensionManifest>()

    init {
        scope.launch { settings.extensionGrants.collect { json ->
            mutation.withLock { grants = runCatching { ExtensionCodec.decode(json) }.getOrDefault(emptyList()); refreshLocked() }
        } }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) { mutation.withLock { refreshLocked() } }

    @Suppress("DEPRECATION")
    private fun discover(): List<ExtensionDescriptor> = context.packageManager
        .queryIntentServices(Intent(ExtensionContract.ACTION), PackageManager.GET_META_DATA).mapNotNull { result ->
            runCatching {
                val service = result.serviceInfo
                if (!service.exported || !service.enabled || !service.applicationInfo.enabled) return@mapNotNull null
                val pkg = service.packageName
                val signatures = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(pkg,
                    PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
                else context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                val signer = digest(requireNotNull(signatures).sortedBy { it.toCharsString() }.flatMap { it.toByteArray().toList() }.toByteArray())
                ExtensionDescriptor(android.content.ComponentName(pkg, service.name).flattenToString(), pkg,
                    service.applicationInfo.uid, signer, service.loadLabel(context.packageManager).toString().take(80),
                    service.metaData?.getInt("aurora.extension.api", 0) ?: 0,
                    service.metaData?.getString("aurora.extension.capabilities").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                    service.metaData?.getString("aurora.extension.license").orEmpty().take(500))
            }.getOrNull()
        }.distinctBy { it.component }.take(32)

    private fun refreshLocked() {
        val available = discover().associateBy { it.component }
        mutableEntries.value = (available.keys + grants.map { it.component }).distinct().map { component ->
            val descriptor = available[component]; val grant = grants.firstOrNull { it.component == component }
            ExtensionEntry(component, descriptor, grant, manifests[component], when {
                descriptor == null -> "Extension is not installed."
                !descriptor.compatible -> "Unsupported extension API or capability."
                grant?.enabled == true && (grant.signer != descriptor.signer || grant.capabilities != descriptor.capabilities) -> "Extension changed. Enable it again to review access."
                else -> null
            })
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun setEnabled(component: String, enabled: Boolean, expected: ExtensionDescriptor? = null): Result<Unit> = result {
        mutation.withLock {
            refreshLocked()
            val entry = requireNotNull(entries.value.find { it.component == component }) { "Extension is unavailable." }
            val grant = if (enabled) {
                val descriptor = requireNotNull(entry.descriptor)
                require(expected == null || descriptor == expected) { "Extension changed. Review its access and enable it again." }
                require(descriptor.compatible) { "Unsupported extension API." }
                val manifest = ExtensionCodec.manifest(withTimeout(6_000) { client.call(descriptor,
                    JsonObject().apply { addProperty("method", ExtensionContract.DESCRIBE) }) }["data"].asJsonObject)
                require(manifest.capabilities == descriptor.capabilities) { "Extension capabilities do not match its declaration." }
                val current = discover().find { it.component == component }
                require(current == descriptor) { "Extension changed. Refresh and retry." }
                manifests[component] = manifest
                val values = manifest.settings.associate { field -> field.id to
                    (entry.grant?.settings?.get(field.id)?.takeIf { it.isFinite() && it in field.minimum..field.maximum } ?: field.default) }
                ExtensionGrant(component, descriptor.signer, descriptor.capabilities, true, values)
            } else requireNotNull(entry.grant).copy(enabled = false)
            grants = grants.filterNot { it.component == component } + grant
            settings.setExtensionGrants(ExtensionCodec.encode(grants))
            refreshLocked()
        }
    }

    suspend fun loadManifest(component: String): Result<ExtensionManifest> = result {
        val response = request(component, ExtensionContract.DESCRIBE)
        val manifest = ExtensionCodec.manifest(response["data"].asJsonObject)
        val descriptor = requireNotNull(entries.value.find { it.component == component }?.descriptor)
        require(manifest.capabilities == descriptor.capabilities) { "Extension capabilities do not match its declaration." }
        manifests[component] = manifest
        mutation.withLock { refreshLocked() }
        manifest
    }

    suspend fun updateSetting(component: String, id: String, value: Double): Result<Unit> = result {
        val manifest = manifests[component] ?: loadManifest(component).getOrThrow()
        val field = requireNotNull(manifest.settings.find { it.id == id })
        require(value.isFinite() && value in field.minimum..field.maximum)
        mutation.withLock {
            grants = grants.map { if (it.component == component) it.copy(settings = it.settings + (id to value)) else it }
            settings.setExtensionGrants(ExtensionCodec.encode(grants)); refreshLocked()
        }
        refreshed.remove(component)
    }

    suspend fun audio(component: String): Result<ProcessingRack> = result {
        val entry = authorized(component, "audio")
        ExtensionCodec.compileAudio(request(component, "audio")["data"], entry.name)
    }

    suspend fun useLibrary(component: String): Session {
        val entry = authorized(component, "media")
        loadTracks(component, force = true)
        val old = settings.savedSessions.first().find { it.type == ServerType.EXTENSION && it.userId == component }
        val session = old ?: Session("extension://${key(component)}", entry.name, "", "extension", ServerType.EXTENSION, component)
        settings.addSavedSession(session)
        return session
    }

    fun backend(session: Session, localize: (Song) -> Song = { it }): MediaBackend = ExtensionBackend(this, session, localize)

    private suspend fun authorized(component: String, capability: String? = null): ExtensionEntry = withContext(Dispatchers.IO) {
        mutation.withLock {
            refreshLocked()
            val entry = requireNotNull(entries.value.find { it.component == component }) { "Extension is not installed." }
            require(entry.enabled) { entry.error ?: "Enable this extension in Advanced audio → Extensions." }
            require(capability == null || capability in requireNotNull(entry.grant).capabilities) { "Extension access is not enabled." }
            entry
        }
    }

    private suspend fun request(component: String, method: String, extra: JsonObject = JsonObject(), expected: ExtensionEntry? = null): JsonObject {
        val entry = authorized(component, method.takeUnless { it == ExtensionContract.DESCRIBE })
        if (expected != null) requireUnchanged(expected, entry)
        val request = extra.deepCopy().apply {
            addProperty("method", method)
            add("settings", gson.toJsonTree(entry.grant?.settings.orEmpty()))
        }
        val response = withTimeout(6_000) { client.call(requireNotNull(entry.descriptor), request) }
        val current = authorized(component, method.takeUnless { it == ExtensionContract.DESCRIBE })
        requireUnchanged(entry, current)
        return response
    }

    private fun requireUnchanged(expected: ExtensionEntry, current: ExtensionEntry) {
        require(current.enabled && current.grant == expected.grant && current.descriptor == expected.descriptor) {
            "Extension access or settings changed. Retry."
        }
    }

    suspend fun metadata(title: String, artist: String, album: String): List<MetadataMatch> = supervisorScope {
        refresh()
        entries.value.filter { it.enabled && "metadata" in it.descriptor!!.capabilities }.take(4).map { entry -> async {
            try {
                val query = JsonObject().apply { addProperty("title", title.take(200)); addProperty("artist", artist.take(200)); addProperty("album", album.take(200)) }
                val data = request(entry.component, "metadata", JsonObject().apply { add("query", query) })["data"]
                require(data is JsonArray && data.size() <= 20)
                data.map { element ->
                    val o = element.asJsonObject
                    MetadataMatch(ExtensionCodec.text(o, "title"), ExtensionCodec.text(o, "artist"), ExtensionCodec.text(o, "album"),
                        ExtensionCodec.text(o, "year", 10, true), ExtensionCodec.text(o, "trackNumber", 10, true), "",
                        ExtensionCodec.integer(o, "score", 0, 100), entry.name)
                }
            } catch (_: TimeoutCancellationException) { emptyList() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        } }.awaitAll().flatten()
    }

    internal suspend fun loadTracks(component: String, force: Boolean = false): List<ExtensionTrack> = withContext(Dispatchers.IO) {
        locks.getOrPut(component) { Mutex() }.withLock {
            val cached = indexes[component] ?: readIndex(component).also { indexes[component] = it }
            val entry = try { authorized(component, "media") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (entry == null) return@withLock cached
            if (!force && cached.isNotEmpty() && System.currentTimeMillis() - (refreshed[component] ?: 0L) < 60_000) return@withLock cached
            try {
                withTimeout(20_000) {
                    val tracks = mutableListOf<ExtensionTrack>()
                    var total = -1
                    do {
                        val page = request(component, "media", JsonObject().apply { addProperty("offset", tracks.size); addProperty("count", 100) }, entry)["data"].asJsonObject
                        val reported = ExtensionCodec.integer(page, "total", 0, 10_000)
                        require(total < 0 || total == reported) { "Extension library changed during loading. Retry." }
                        total = reported
                        val songs = page["songs"]
                        require(songs is JsonArray && songs.size() <= 100 && (songs.size() > 0 || total == tracks.size)) { "Incomplete extension library." }
                        tracks += songs.map { parseTrack(it.asJsonObject, entry.descriptor!!) }
                        require(tracks.size <= total && tracks.map { it.id }.distinct().size == tracks.size) { "Duplicate or excess extension tracks." }
                    } while (tracks.size < total)
                    val file = indexFile(component)
                    val json = gson.toJson(tracks)
                    require(json.toByteArray().size <= 8 * 1024 * 1024) { "Extension library is too large." }
                    mutation.withLock {
                        refreshLocked()
                        requireUnchanged(entry, requireNotNull(entries.value.find { it.component == component }))
                        val atomic = android.util.AtomicFile(file)
                        val output = atomic.startWrite()
                        try { output.write(json.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
                        catch (failure: Exception) { atomic.failWrite(output); throw failure }
                        indexes[component] = tracks.toList(); refreshed[component] = System.currentTimeMillis()
                    }
                    tracks.toList()
                }
            } catch (timeout: TimeoutCancellationException) { if (cached.isNotEmpty() && !force) cached else throw timeout }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (cached.isNotEmpty() && !force) cached else throw failure }
        }
    }

    fun resolve(uri: Uri): Uri = runBlocking(Dispatchers.IO) {
        val component = entries.value.firstOrNull { key(it.component) == uri.host }?.component ?: throw IOException("Extension is unavailable.")
        val entry = authorized(component, "media")
        val id = uri.lastPathSegment ?: throw IOException("Missing extension track.")
        val track = loadTracks(component).find { it.id == id } ?: throw IOException("Extension track is unavailable.")
        val current = authorized(component, "media")
        requireUnchanged(entry, current)
        ownUri(track.uri, requireNotNull(current.descriptor))
    }

    internal fun song(component: String, track: ExtensionTrack): Song = Song("ext-${key(component)}-${track.id}", track.title, track.artist, track.album, "", track.durationSec,
        streamUrl = Uri.Builder().scheme("aurora-extension").authority(key(component)).appendPath(track.id).build().toString(),
        albumId = key(track.album + "\u0000" + track.artist), artistId = key(track.artist), suffix = track.suffix,
        sampleRateHz = track.sampleRate, bitDepth = track.bitDepth)

    fun copyAudio(url: String, destination: File, onProgress: (Float) -> Unit) {
        val uri = resolve(Uri.parse(url))
        val deadline = System.nanoTime() + 60_000_000_000L
        context.contentResolver.openAssetFileDescriptor(uri, "r").use { descriptor ->
            requireNotNull(descriptor) { "Extension file is unavailable." }
            val size = descriptor.length
            require(size in 1..(512L * 1024 * 1024)) { "Extension downloads require a file smaller than 512 MB with a known length." }
            descriptor.createInputStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (copied < size) {
                    check(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "Extension download timed out." }
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
                    check(count > 0) { "Extension file ended early." }
                    output.write(buffer, 0, count); copied += count
                    onProgress(copied.toFloat() / size)
                }
            } }
        }
    }

    private fun parseTrack(o: JsonObject, descriptor: ExtensionDescriptor): ExtensionTrack {
        val id = ExtensionCodec.text(o, "id", 160)
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) { "Invalid extension track ID." }
        val uri = ownUri(ExtensionCodec.text(o, "uri", 2048), descriptor).toString()
        return ExtensionTrack(id, ExtensionCodec.text(o, "title"), ExtensionCodec.text(o, "artist"), ExtensionCodec.text(o, "album"), uri,
            ExtensionCodec.integer(o, "durationSec", 0, 86_400), ExtensionCodec.text(o, "suffix", 10).lowercase(),
            if (o.has("sampleRate")) ExtensionCodec.integer(o, "sampleRate", 0, 768_000) else 0,
            if (o.has("bitDepth")) ExtensionCodec.integer(o, "bitDepth", 0, 64) else 0)
    }
    private fun ownUri(value: String, descriptor: ExtensionDescriptor): Uri {
        val uri = Uri.parse(value)
        require(uri.scheme == "content" && uri.authority?.matches(Regex("[A-Za-z0-9_.]+")) == true && uri.fragment == null) { "Extensions must serve their own content URIs." }
        val owner = context.packageManager.resolveContentProvider(requireNotNull(uri.authority), 0)
        require(owner?.packageName == descriptor.packageName && owner.applicationInfo.uid == descriptor.uid) { "Extension URI belongs to another app." }
        return uri
    }
    private fun indexFile(component: String) = File(context.filesDir, "extension-index").apply { mkdirs() }.resolve("${key(component)}.json")
    private fun readIndex(component: String): List<ExtensionTrack> = runCatching {
        val file = indexFile(component)
        if (!file.exists() || file.length() > 8 * 1024 * 1024) return@runCatching emptyList()
        val array = JsonParser.parseString(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() }).asJsonArray
        require(array.size() <= 10_000)
        array.map { gson.fromJson(it, ExtensionTrack::class.java) }.filter {
            it.id.isNotBlank() && it.title.isNotBlank() && it.uri.startsWith("content://")
        }
    }.getOrDefault(emptyList())
    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (_: TimeoutCancellationException) { Result.failure(IOException("Extension did not respond in time.")) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    companion object {
        internal fun key(value: String) = digest(value.toByteArray(Charsets.UTF_8))
        private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}

internal data class ExtensionTrack(val id: String, val title: String, val artist: String, val album: String,
    val uri: String, val durationSec: Int, val suffix: String, val sampleRate: Int, val bitDepth: Int)
