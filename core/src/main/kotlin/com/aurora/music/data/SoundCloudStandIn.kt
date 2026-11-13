package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

// youtube refuses some songs (age gate, region block, removed video); the same recording on soundcloud plays instead
class SoundCloudStandIn(private val soundcloud: suspend () -> SoundCloudBackend?, private val relaxed: () -> Boolean = { false }) {
    private val found = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 256
    }

    suspend fun <T : Any> play(song: Song, youtube: suspend () -> T?, standIn: (String) -> T): T? = youtube() ?: stream(song)?.let(standIn)

    suspend fun stream(song: Song): String? {
        if (song.title.isBlank() || song.durationSec <= 0) return null
        val source = try { soundcloud() } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        val relaxed = relaxed()
        val key = "${recordingKey(song.title)}\u0000${song.artist.lowercase()}\u0000${song.durationSec}\u0000$relaxed"
        val known = synchronized(found) { if (found.containsKey(key)) found[key] ?: return null else null }
        known?.let { id -> open(source, id)?.let { return it } }
        val results = try { source.fullTracks("${recordingTitle(song.title)} ${song.artist}") }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        val matches = matches(song, results, relaxed)
        val pick = matches.take(3).firstNotNullOfOrNull { match -> open(source, match.id)?.let { match.id to it } }
        // a match that failed to open may open next time; only a search without one is remembered as a miss
        if (pick != null || matches.isEmpty()) synchronized(found) { found[key] = pick?.first }
        return pick?.second
    }

    private suspend fun open(source: SoundCloudBackend, id: String): String? =
        try { source.playbackUrl(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        // the artist's own upload first, then "artist - title" reposts, then (relaxed) title and length
        fun matches(song: Song, candidates: List<Song>, relaxed: Boolean): List<Song> {
            if (song.durationSec <= 0) return emptyList()
            fun gap(candidate: Song) = abs(song.durationSec - candidate.durationSec)
            fun rank(candidate: Song): Int? = when {
                candidate.durationSec <= 0 || gap(candidate) > TrackMatch.DURATION_TOLERANCE_SEC -> null
                recordingMatches(song, candidate) -> 0
                recordingVariants(candidate).any { recordingMatches(song, it) } -> 1
                relaxed && recordingVariants(candidate).any { sameLength(song, it) } -> 2
                else -> null
            }
            return candidates.mapNotNull { candidate -> rank(candidate)?.let { it to candidate } }
                .sortedWith(compareBy({ it.first }, { gap(it.second) })).map { it.second }
        }
    }
}
