package com.aurora.music.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

internal fun File.diskBytes(): Long =
    if (!exists()) 0L else walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }

data class StorageUsage(
    val ready: Boolean = false,
    val total: Long = 0,
    val free: Long = 0,
    val downloads: Long = 0,
    val downloadsByServer: Map<String, Long> = emptyMap(),
    val audioCache: Long = 0,
    val artwork: Long = 0,
    val images: Long = 0,
    val mapTiles: Long = 0,
    val vocals: Long = 0,
    val stems: Long = 0,
    val temporary: Long = 0,
    val artistInfo: Long = 0,
    val sonic: Long = 0,
    val stemModel: Long = 0,
    val aiDjModel: Long = 0,
    val languagePacks: List<String> = emptyList(),
    val languagePackBytes: Long = 0,
    val insightsPlays: Int = 0,
    val locatedPlays: Int = 0,
) {
    val caches: Long get() = audioCache + artwork + images + mapTiles + vocals + stems + temporary + artistInfo
    val analysis: Long get() = sonic + stemModel + aiDjModel + languagePackBytes
    val other: Long get() = (total - downloads - caches - analysis).coerceAtLeast(0)
}

// one place that measures and clears everything aurora keeps on the device
@OptIn(UnstableApi::class)
class StorageInventory(private val context: Context, private val container: AppContainer, private val downloadsDir: File) {
    private val _usage = MutableStateFlow(StorageUsage())
    val usage: StateFlow<StorageUsage> = _usage.asStateFlow()

    suspend fun refresh() {
        _usage.value = withContext(Dispatchers.IO) { measure() }
    }

    private suspend fun measure(): StorageUsage {
        val downloads = container.downloadManager.downloads.value.values
        val byServer = downloads.groupBy { it.serverId.orEmpty() }.mapValues { (_, songs) ->
            songs.sumOf { File(it.audioPath).length() + (if (it.coverPath.isNotBlank()) File(it.coverPath).length() else 0L) }
        }
        val packs = container.lyricsTranslator.packs()
        val insights = runCatching { container.listeningDetails.summary().plays }.getOrDefault(0)
        val located = runCatching { container.listeningDetails.locationCount() }.getOrDefault(0)
        val roots = listOfNotNull(context.dataDir, context.getExternalFilesDir(null)?.parentFile)
        return StorageUsage(
            ready = true,
            total = roots.sumOf { root -> root.listFiles().orEmpty().filter { it.name !in SYSTEM_DIRS }.sumOf { it.diskBytes() } },
            free = context.filesDir.usableSpace,
            downloads = downloadsDir.diskBytes(),
            downloadsByServer = byServer,
            audioCache = container.audioCache.status.value.bytes,
            artwork = container.artworkRepository.bytes.value,
            images = runCatching { context.imageLoader.diskCache?.size }.getOrNull() ?: 0L,
            mapTiles = mapTiles.diskBytes(),
            vocals = container.vocalAnalysis.bytes(),
            stems = container.stemSeparator.stemBytes(),
            temporary = temporaryFiles().sumOf { it.diskBytes() },
            artistInfo = container.artistInfoStore.bytes(),
            sonic = container.sonicStore.bytes() + container.mixAnalyzer.bytes(),
            stemModel = container.stemSeparator.modelBytes(),
            aiDjModel = container.aiDjModels.bytes(),
            languagePacks = packs,
            languagePackBytes = if (packs.isEmpty()) 0L else languagePackDirs().sumOf { it.diskBytes() },
            insightsPlays = insights,
            locatedPlays = located,
        )
    }

    private val mapTiles get() = File(context.cacheDir, MAP_TILES)

    private fun languagePackDirs(): List<File> = listOf(context.filesDir, context.noBackupFilesDir).flatMap { root ->
        root.listFiles().orEmpty().filter { it.isDirectory && "mlkit" in it.name && "translate" in it.name }
    }

    private fun temporaryFiles(): List<File> {
        val busy = container.youTubeDownloader.states.value.values.any { it !is YouTubeDownloader.State.Done && it !is YouTubeDownloader.State.Failed }
        val owned = if (busy) MANAGED_CACHE + YOUTUBE_WORK else MANAGED_CACHE
        return context.cacheDir.listFiles().orEmpty().filter { it.name !in owned } + listOfNotNull(context.externalCacheDir)
    }

    suspend fun removeDownloads(serverId: String? = null) = withContext(Dispatchers.IO) {
        val manager = container.downloadManager
        manager.collections.value.filter { serverId == null || it.serverId.orEmpty() == serverId }.forEach { manager.removeCollection(it) }
        manager.removeDownloads(manager.downloads.value.values.filter { serverId == null || it.serverId.orEmpty() == serverId }.map { it.id })
    }

    suspend fun clearArtwork() {
        container.artworkRepository.clear()
        context.imageLoader.memoryCache?.clear()
    }

    suspend fun clearImages() = withContext(Dispatchers.IO) {
        context.imageLoader.diskCache?.clear()
        context.imageLoader.memoryCache?.clear()
    }

    suspend fun clearMapTiles() = withContext(Dispatchers.IO) { mapTiles.listFiles()?.forEach(File::deleteRecursively) }

    suspend fun clearTemporary() = withContext(Dispatchers.IO) {
        temporaryFiles().forEach { if (it.isDirectory) it.listFiles()?.forEach(File::deleteRecursively) else it.delete() }
    }

    suspend fun clearSonic() = withContext(Dispatchers.IO) {
        container.sonicEngine.cancel()
        container.sonicStore.clear()
        container.mixAnalyzer.clear()
    }

    suspend fun clearVocals() = withContext(Dispatchers.IO) { container.vocalAnalysis.clear() }

    suspend fun clearArtistInfo() = withContext(Dispatchers.IO) { container.artistInfoStore.clear() }

    // returns false when vocal separation was running and its files were left alone
    suspend fun clearCaches(): Boolean {
        runCatching { container.audioCache.clear() }
        runCatching { clearArtwork() }
        runCatching { clearImages() }
        runCatching { clearMapTiles() }
        runCatching { clearVocals() }
        runCatching { clearArtistInfo() }
        runCatching { clearTemporary() }
        return container.stemSeparator.clearStems()
    }

    private companion object {
        val SYSTEM_DIRS = setOf("lib", "code_cache")
        const val YOUTUBE_WORK = "youtube-downloads"
        const val MAP_TILES = "osmdroid"
        // these have their own rows or are in use by playback and clean themselves up
        val MANAGED_CACHE = setOf("audio-streams", "metadata-artwork", "image_cache", "vocal-activity", "separated-stems-v1",
            "network-audio", "renderer-media", "local-profile", "connect-art", MAP_TILES)
    }
}
