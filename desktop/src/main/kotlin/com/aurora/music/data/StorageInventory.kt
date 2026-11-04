package com.aurora.music.data

import com.aurora.music.desktop.DesktopContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

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
    val stems: Long = 0,
    val temporary: Long = 0,
    val artistInfo: Long = 0,
    val sonic: Long = 0,
    val stemModel: Long = 0,
    val aiDjModel: Long = 0,
    val aiDjVoiceModel: Long = 0,
    val aiDjSpeakerModel: Long = 0,
    val logs: Long = 0,
    val insightsPlays: Int = 0,
    val locatedPlays: Int = 0,
) {
    val caches: Long get() = audioCache + artwork + images + mapTiles + stems + temporary + artistInfo
    val analysis: Long get() = sonic + stemModel + aiDjModel + aiDjVoiceModel + aiDjSpeakerModel
    val other: Long get() = (total - downloads - caches - analysis).coerceAtLeast(0)
}

// one place that measures and clears everything aurora keeps on this computer
class StorageInventory(private val container: DesktopContainer) {
    private val paths = container.paths
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
        val roots = listOf(paths.local, paths.roaming).map { it.absoluteFile }.distinct()
            .filterNot { root -> listOf(paths.local, paths.roaming).any { other -> other.absoluteFile != root && root.startsWith(other.absoluteFile) } }
        return StorageUsage(
            ready = true,
            total = roots.sumOf { it.diskBytes() },
            free = paths.local.usableSpace,
            downloads = paths.downloads.diskBytes(),
            downloadsByServer = byServer,
            audioCache = container.audioCache.status.value.bytes,
            artwork = container.artworkRepository.bytes.value,
            images = paths.images.diskBytes(),
            mapTiles = mapTiles.diskBytes(),
            stems = container.stemSeparator.stemBytes(),
            temporary = temporaryFiles().sumOf { it.diskBytes() },
            artistInfo = container.artistInfoStore.bytes(),
            sonic = container.sonicStore.bytes() + container.mixAnalyzer.bytes(),
            stemModel = container.stemSeparator.modelBytes(),
            aiDjModel = container.aiDjModels.bytes(),
            aiDjVoiceModel = container.aiDjVoiceModels.bytes(),
            aiDjSpeakerModel = container.aiDjSpeakerModels.bytes(),
            logs = paths.logs.diskBytes(),
            insightsPlays = runCatching { container.listeningDetails.summary().plays }.getOrDefault(0),
            locatedPlays = runCatching { container.listeningDetails.locationCount() }.getOrDefault(0),
        )
    }

    private val mapTiles get() = File(paths.cache, MAP_TILES)

    // the youtube work folder is only temporary while nothing is downloading into it
    private fun temporaryFiles(): List<File> {
        val youTube = File(paths.cache, YOUTUBE_WORK).takeUnless { container.youTubeDownloader.busy }
        return listOfNotNull(paths.staging, youTube)
    }

    suspend fun removeDownloads(serverId: String? = null) = withContext(Dispatchers.IO) {
        val manager = container.downloadManager
        manager.collections.value.filter { serverId == null || it.serverId.orEmpty() == serverId }.forEach { manager.removeCollection(it) }
        manager.removeDownloads(manager.downloads.value.values.filter { serverId == null || it.serverId.orEmpty() == serverId }.map { it.id })
    }

    suspend fun clearArtwork() {
        container.artworkRepository.clear()
        container.imageLoader.memoryCache?.clear()
    }

    suspend fun clearImages() = withContext(Dispatchers.IO) {
        container.imageLoader.diskCache?.clear()
        container.imageLoader.memoryCache?.clear()
    }

    suspend fun clearMapTiles() = withContext(Dispatchers.IO) { mapTiles.listFiles()?.forEach(File::deleteRecursively) }

    suspend fun clearTemporary() = withContext(Dispatchers.IO) {
        temporaryFiles().forEach { dir -> dir.listFiles()?.forEach(File::deleteRecursively) }
    }

    suspend fun clearSonic() = withContext(Dispatchers.IO) {
        container.sonicEngine.cancel()
        container.sonicStore.clear()
        container.mixAnalyzer.clear()
    }

    suspend fun clearArtistInfo() = withContext(Dispatchers.IO) { container.artistInfoStore.clear() }

    // returns false when vocal separation was running and its files were left alone
    suspend fun clearCaches(): Boolean {
        runCatching { container.audioCache.clear() }
        runCatching { clearArtwork() }
        runCatching { clearImages() }
        runCatching { clearMapTiles() }
        runCatching { clearArtistInfo() }
        runCatching { clearTemporary() }
        return container.stemSeparator.clearStems()
    }

    private companion object {
        const val YOUTUBE_WORK = "youtube-downloads"
        const val MAP_TILES = "map-tiles"
    }
}
