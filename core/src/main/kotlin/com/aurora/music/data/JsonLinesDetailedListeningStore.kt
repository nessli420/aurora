package com.aurora.music.data

import com.google.gson.Gson
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

// append-only lines, newest line per id wins; compacted once stale checkpoints outnumber live rows
class JsonLinesDetailedListeningStore(private val file: File, private val platform: String) : DetailedListeningRepository {
    private val gson = Gson()
    private val lock = Any()
    private var rows: LinkedHashMap<String, DetailedPlay>? = null
    private var lines = 0
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aurora-insights").apply { isDaemon = true } }
    private val dispatcher = worker.asCoroutineDispatcher()

    override fun save(play: DetailedPlay) {
        worker.execute { runCatching { synchronized(lock) { write(listOf(play)) } } }
    }

    override suspend fun summary(): InsightsSummary = withContext(dispatcher) {
        synchronized(lock) { loaded().values.let { InsightsSummary(it.size, it.minOfOrNull { p -> p.startedAt }) } }
    }

    override suspend fun importHistory(events: List<PlayEvent>): Int = withContext(dispatcher) {
        val plays = events.mapNotNull { it.toImportedPlay(platform) }
        synchronized(lock) { write(plays) }
        plays.size
    }

    override suspend fun removeHistory(events: List<PlayEvent>) = withContext(dispatcher) {
        synchronized(lock) {
            val map = loaded()
            val before = map.size
            events.forEach { map.remove(it.importedPlayId) }
            if (map.size != before) {
                file.parentFile?.mkdirs()
                persistBackupFileAtomically(file, map.values.joinToString("") { gson.toJson(it) + "\n" }.toByteArray(Charsets.UTF_8))
                lines = map.size
            }
        }
    }

    override suspend fun clear() = withContext(dispatcher) {
        synchronized(lock) {
            rows = LinkedHashMap()
            lines = 0
            file.delete()
            Unit
        }
    }

    override suspend fun plays(from: Long, to: Long): List<DetailedPlay> = withContext(dispatcher) {
        synchronized(lock) { loaded().values.filter { it.startedAt in from until to } }
    }

    override suspend fun firstArtistPlays(): Map<String, Long> = withContext(dispatcher) {
        synchronized(lock) {
            val first = HashMap<String, Long>()
            loaded().values.forEach { first.merge(Insights.artistKey(it.track.artist), it.startedAt, ::minOf) }
            first
        }
    }

    suspend fun located(from: Long = 0, to: Long = Long.MAX_VALUE, songId: String? = null): List<DetailedPlay> = withContext(dispatcher) {
        synchronized(lock) {
            loaded().values.filter { it.context.latitude != null && it.context.longitude != null && it.startedAt >= from && it.startedAt < to &&
                (songId == null || it.track.songId == songId) }.sortedBy { it.startedAt }
        }
    }

    suspend fun locationCount(): Int = withContext(dispatcher) {
        synchronized(lock) { loaded().values.count { it.context.latitude != null } }
    }

    // the plays and their statistics stay, only the coordinates go
    suspend fun clearLocations() = withContext(dispatcher) {
        synchronized(lock) {
            val map = loaded()
            map.replaceAll { _, play -> if (play.context.latitude == null) play else play.copy(context = play.context.copy(latitude = null, longitude = null, locationAccuracyM = null)) }
            file.parentFile?.mkdirs()
            persistBackupFileAtomically(file, map.values.joinToString("") { gson.toJson(it) + "\n" }.toByteArray(Charsets.UTF_8))
            lines = map.size
        }
    }

    private fun loaded(): LinkedHashMap<String, DetailedPlay> = rows ?: LinkedHashMap<String, DetailedPlay>().also { map ->
        if (file.exists()) file.forEachLine { line ->
            lines++
            runCatching { gson.fromJson(line, DetailedPlay::class.java) }.getOrNull()
                ?.takeIf { (it.id as String?) != null && (it.track as PlayedTrack?) != null }
                ?.let { map[it.id] = it }
        }
        rows = map
    }

    private fun write(plays: List<DetailedPlay>) {
        if (plays.isEmpty()) return
        val map = loaded()
        plays.forEach { map[it.id] = it }
        file.parentFile?.mkdirs()
        file.appendText(plays.joinToString("") { gson.toJson(it) + "\n" })
        lines += plays.size
        if (lines > 1_000 && lines > map.size * 2) {
            persistBackupFileAtomically(file, map.values.joinToString("") { gson.toJson(it) + "\n" }.toByteArray(Charsets.UTF_8))
            lines = map.size
        }
    }
}
