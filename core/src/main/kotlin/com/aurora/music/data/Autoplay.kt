package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Spotify-style autoplay: once the queue is down to its last song, songs like what has been playing follow it,
 * shown in the queue ahead of time so the music never stops.
 */
object Autoplay {
    // songs added per top-up; the next top-up comes when the last of them starts
    const val BATCH = 10
    // recent plays the picks are built from, so they follow the session rather than only its last song
    const val SEEDS = 2
    // the most picks one artist gets per batch
    private const val PER_ARTIST = 2

    /** A song autoplay can follow: stations, episodes, mixes and dj speech have nothing to be similar to. */
    fun follows(song: Song): Boolean = song.id.isNotEmpty() && !song.isRadio() && !song.isPodcast() &&
        !song.id.startsWith("aurora-mix:") && !song.id.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX)

    // shuffle with autoplay: one pick after every few of your own songs, up to a cap for long queues
    const val SMART_STRIDE = 3
    const val SMART_MAX = 30
    const val SMART_SEEDS = 3

    /** The latest plays to build from, newest first, one per artist so the picks don't all orbit one act. */
    fun seeds(played: List<Song>, count: Int = SEEDS): List<Song> {
        val artists = HashSet<String>()
        return played.asReversed().filter { follows(it) && artists.add(it.artist.lowercase()) }.take(count)
    }

    /** How many picks shuffle with autoplay mixes into [upcoming] songs of your own. */
    fun smartCount(upcoming: Int): Int = if (upcoming <= 0) 0 else ((upcoming + SMART_STRIDE - 1) / SMART_STRIDE).coerceAtMost(SMART_MAX)

    /**
     * Mixes [picks] into [upcoming], one after every [SMART_STRIDE] of your songs; picks left over once your songs
     * run out follow them.
     */
    fun <T> interleave(upcoming: List<T>, picks: List<T>): List<T> {
        val out = ArrayList<T>(upcoming.size + picks.size)
        var next = 0
        upcoming.forEachIndexed { i, item ->
            out += item
            if ((i + 1) % SMART_STRIDE == 0 && next < picks.size) out += picks[next++]
        }
        while (next < picks.size) out += picks[next++]
        return out
    }

    /**
     * Fetches similar songs for each seed and keeps [BATCH] of them: nothing already queued or just played, no
     * second copy of a recording, at most [PER_ARTIST] per artist, and the seeds' lists taken in turn so the
     * newest seed leads. [fallback] answers for a seed its source has no radio for.
     */
    suspend fun next(
        seeds: List<Song>,
        avoid: Collection<Song>,
        radio: suspend (Song) -> List<Song>,
        fallback: suspend (Song) -> List<Song> = { emptyList() },
        limit: Int = BATCH,
        hides: (Song) -> Boolean = { false },
    ): List<Song> = coroutineScope {
        val lists = seeds.map { seed ->
            async { quietly { radio(seed) }.ifEmpty { quietly { fallback(seed) } } }
        }.awaitAll()
        pick(lists.map { list -> list.filterNot(hides) }, avoid, limit)
    }

    fun pick(lists: List<List<Song>>, avoid: Collection<Song>, limit: Int = BATCH): List<Song> {
        val ids = avoid.mapTo(HashSet()) { it.id }
        val recordings = avoid.mapTo(HashSet(), ::recording)
        val perArtist = HashMap<String, Int>()
        val picked = ArrayList<Song>(limit)
        val cursors = IntArray(lists.size)
        while (picked.size < limit && lists.indices.any { cursors[it] < lists[it].size }) {
            for (i in lists.indices) {
                if (picked.size >= limit) break
                val list = lists[i]
                while (cursors[i] < list.size) {
                    val song = list[cursors[i]++]
                    val artist = song.artist.lowercase()
                    if (!follows(song) || song.id in ids || !recordings.add(recording(song)) || (perArtist[artist] ?: 0) >= PER_ARTIST) continue
                    ids += song.id
                    perArtist[artist] = (perArtist[artist] ?: 0) + 1
                    picked += song
                    break
                }
            }
        }
        return picked
    }

    // a remaster, live take or radio edit is still the same song to the listener
    private val VERSION_TAG = Regex(
        "\\s*(?:[\\[(][^)\\]]*\\b(?:remaster(?:ed)?|live|edit|version|mono|stereo|deluxe|anniversary)\\b[^)\\]]*[)\\]]" +
            "|\\s[-–]\\s.*\\b(?:remaster(?:ed)?|live|edit|version|mono|stereo)\\b.*$)",
        RegexOption.IGNORE_CASE,
    )

    private fun recording(song: Song) =
        song.artist.lowercase() + "\u0000" + recordingKey(song.title.replace(VERSION_TAG, "").ifBlank { song.title })

    private suspend fun quietly(block: suspend () -> List<Song>): List<Song> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
