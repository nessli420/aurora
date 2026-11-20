package com.aurora.music.data

import java.util.UUID

data class InsightsPrefs(
    val enabled: Boolean = false,
    val device: Boolean = true,
    val audioPath: Boolean = true,
    val places: Boolean = false,
    val locationHistory: Boolean = false,
)

data class InsightsSummary(val plays: Int = 0, val since: Long? = null)

enum class PlayEnding { COMPLETED, SKIPPED, STOPPED }

// how a track was heard; device and audio-path fields stay null while those categories are off
data class PlayContext(
    val genre: String? = null,
    val source: String? = null,
    val provider: String? = null,
    val playlist: String? = null,
    val shuffle: Boolean? = null,
    val repeat: Int? = null,
    val output: String? = null,
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val lossless: Boolean? = null,
    val dspEngine: String? = null,
    val autoEq: Boolean? = null,
    val convolution: Boolean? = null,
    val replayGain: Int? = null,
    val crossfeed: Boolean? = null,
    val rack: Boolean? = null,
    val bitPerfect: Boolean? = null,
    val zone: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
) {
    // the route and decoder are often unknown on the first tick, so later snapshots fill the gaps
    fun fill(newer: PlayContext) = PlayContext(
        newer.genre ?: genre, newer.source ?: source, newer.provider ?: provider, newer.playlist ?: playlist,
        newer.shuffle ?: shuffle, newer.repeat ?: repeat, newer.output ?: output, newer.codec ?: codec,
        newer.sampleRateHz ?: sampleRateHz, newer.bitDepth ?: bitDepth, newer.lossless ?: lossless,
        newer.dspEngine ?: dspEngine, newer.autoEq ?: autoEq, newer.convolution ?: convolution,
        newer.replayGain ?: replayGain, newer.crossfeed ?: crossfeed, newer.rack ?: rack, newer.bitPerfect ?: bitPerfect,
        newer.zone ?: zone,
        newer.latitude ?: latitude, newer.longitude ?: longitude, newer.locationAccuracyM ?: locationAccuracyM,
    )

    companion object {
        private val LOSSLESS = listOf("flac", "alac", "pcm", "wav", "aiff", "dsd", "dsf", "dff", "ape", "wavpack", "tta", "raw")
        private val LOSSY = listOf("mp3", "mpeg", "aac", "mp4a", "opus", "vorbis", "ogg", "wma", "ac3")

        fun losslessCodec(codec: String?): Boolean? {
            val value = codec?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return when {
                LOSSLESS.any { it in value } -> true
                LOSSY.any { it in value } -> false
                else -> null
            }
        }
    }
}

data class PlayedTrack(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val durationSec: Int,
)

data class DetailedPlay(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val track: PlayedTrack,
    val context: PlayContext = PlayContext(),
    val ending: PlayEnding? = null,
    val platform: String = "android",
    val imported: Boolean = false,
)

fun interface DetailedPlaySink {
    fun save(play: DetailedPlay)
}

interface DetailedListeningRepository : DetailedPlaySink {
    suspend fun summary(): InsightsSummary
    suspend fun importHistory(events: List<PlayEvent>): Int
    suspend fun removeHistory(events: List<PlayEvent>)
    suspend fun clear()
    suspend fun plays(from: Long, to: Long): List<DetailedPlay>
    suspend fun firstArtistPlays(): Map<String, Long>
}

// imported rows reuse the history timestamp in their id so importing twice changes nothing
fun PlayEvent.toImportedPlay(platform: String): DetailedPlay? {
    val listened = listeningMillis
    if (listened < 1_000 || (songId as String?).isNullOrBlank()) return null
    return DetailedPlay(importedPlayId, timestamp, timestamp + listened, listened,
        PlayedTrack(songId, title.orEmpty(), artist.orEmpty(), album.orEmpty(), albumId.orEmpty(), artistId.orEmpty(), durationSec),
        PlayContext(shuffle = shuffle), ending, platform = platform, imported = true)
}

val PlayEvent.importedPlayId: String get() = "history:$timestamp:$songId"

// one row per uninterrupted run of a track, rewritten while it plays so a killed process loses only seconds
class DetailedListeningRecorder(private val sink: DetailedPlaySink, private val platform: String = "android",
    private val clock: () -> Long = System::currentTimeMillis, private val crossfadeMs: () -> Long = { 0L }) {
    private var play: DetailedPlay? = null
    private var key: String? = null
    private var lastPosition = 0L
    private var lastTick = 0L
    private var lastSave = 0L
    private var wasPlaying = false

    @get:Synchronized @set:Synchronized
    var allowed = false
        set(value) {
            if (!value) close(PlayEnding.STOPPED)
            field = value
        }

    @Synchronized
    fun track(track: PlayedTrack?, playing: Boolean, positionMs: Long, uptimeMs: Long, context: () -> PlayContext) {
        if (!allowed) return
        val id = track?.songId?.takeUnless { it.isBlank() || EXCLUDED.any(it::startsWith) }
        val restarted = id != null && id == key && lastPosition > 5_000 && positionMs < 1_500
        if (id != key || restarted) {
            close(PlayEnding.SKIPPED)
            key = id
            wasPlaying = false
        }
        if (id != null && track != null && playing) {
            val now = clock()
            val current = play ?: DetailedPlay(UUID.randomUUID().toString(), now, now, 0, track, context(), platform = platform).also { lastSave = uptimeMs }
            val delta = if (wasPlaying) (uptimeMs - lastTick).coerceIn(0, 2_000) else 0
            play = current.copy(endedAt = now, listenedMs = current.listenedMs + delta)
            if (uptimeMs - lastSave >= SAVE_EVERY_MS) {
                lastSave = uptimeMs
                play = play?.let { it.copy(context = it.context.fill(context())) }
                play?.takeIf { it.listenedMs >= MIN_MS }?.let(sink::save)
            }
        }
        lastPosition = positionMs
        lastTick = uptimeMs
        wasPlaying = playing && id != null
    }

    @Synchronized
    fun finish() {
        close(PlayEnding.STOPPED)
        key = null
        wasPlaying = false
    }

    private fun close(otherwise: PlayEnding) {
        val current = play ?: return
        play = null
        if (current.listenedMs < MIN_MS) return
        val duration = current.track.durationSec * 1000L
        // a crossfade hands over before the end, so reaching the fade or hearing nearly all of it counts as finished
        val finished = duration > 0 && (lastPosition >= duration - END_MS - crossfadeMs().coerceAtLeast(0) ||
            current.listenedMs >= duration * 9 / 10)
        val ending = if (finished) PlayEnding.COMPLETED else otherwise
        sink.save(current.copy(ending = ending))
    }

    private companion object {
        const val MIN_MS = 1_000L
        const val SAVE_EVERY_MS = 15_000L
        const val END_MS = 4_000L
        val EXCLUDED = listOf("radio:", "podcast:", "aurora-mix:")
    }
}
