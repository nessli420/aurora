package com.aurora.music.playback

import android.os.Bundle
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.rules.PresetPlaybackContext
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import java.util.Locale

object PresetContextPublisher {
    private const val PREFIX = "aurora.rules."
    private val providerPattern = Regex("provider:[0-9a-f]{64}")
    private val albumPattern = Regex("album:[0-9a-f]{64}")
    private val playlistPattern = Regex("playlist:[0-9a-f]{64}")

    fun extras(song: Song, source: PlaybackSourceIdentity? = song.playbackSource): Bundle = Bundle().apply {
        putString("aurora.songId", song.id)
        putString("aurora.albumId", song.albumId)
        putString("aurora.artistId", song.artistId)
        putInt("aurora.durationSec", song.durationSec)
        putBoolean("aurora.explicit", song.explicit)
        putFloat("rgTrack", song.replayGainTrack); putFloat("rgAlbum", song.replayGainAlbum)
        source?.providerId?.takeIf(providerPattern::matches)?.let { putString(PREFIX + "provider", it) }
        source?.providerLabel?.let { putString(PREFIX + "providerLabel", it.take(512)) }
        source?.source?.let { putString(PREFIX + "source", it.name) }
        source?.albumId?.takeIf(albumPattern::matches)?.let { putString(PREFIX + "album", it) }
        song.genre.takeIf { it.isNotBlank() }?.let { putStringArrayList(PREFIX + "genres", arrayListOf(it.take(512))) }
        song.suffix.takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) }?.let { putString(PREFIX + "container", it) }
        song.playbackCollection?.id?.takeIf(playlistPattern::matches)?.let { putString(PREFIX + "playlist", it) }
        song.playbackCollection?.name?.takeIf { it.isNotBlank() }?.let { putString(PREFIX + "playlistName", it.take(512)) }
    }

    fun build(item: MediaItem?, active: Boolean, observedSourceFormat: Format?, androidAuto: Boolean?, cast: Boolean): PresetPlaybackContext {
        if (item == null || !active) return PresetPlaybackContext(androidAuto = androidAuto, cast = cast)
        val extras = item.mediaMetadata.extras
        fun text(key: String) = runCatching { extras?.getString(PREFIX + key) }.getOrNull()?.takeIf { it.isNotBlank() }?.take(512)
        val source = text("source")?.let { runCatching { RuleSource.valueOf(it) }.getOrNull() }
        val observedContainer = observedSourceFormat?.containerMimeType?.let(::container)
        val localContainer = text("container")?.takeIf { source == RuleSource.LOCAL_FILE || source == RuleSource.DOWNLOAD }?.let(::container)
        val genres = runCatching { extras?.getStringArrayList(PREFIX + "genres") }.getOrNull()
            ?.take(16)?.mapNotNull { it?.trim()?.take(512)?.takeIf(String::isNotEmpty) }?.toSet()
        return PresetPlaybackContext(active = true, mediaId = item.mediaId,
            source = source, providerId = text("provider")?.takeIf(providerPattern::matches), providerLabel = text("providerLabel"),
            albumId = text("album")?.takeIf(albumPattern::matches), albumName = item.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }?.take(512),
            genres = genres, playlistId = text("playlist")?.takeIf(playlistPattern::matches), playlistName = text("playlistName"),
            sampleRateHz = observedSourceFormat?.sampleRate?.takeIf { it > 0 },
            codec = observedSourceFormat?.sampleMimeType?.let(::codec), container = observedContainer ?: localContainer,
            androidAuto = androidAuto, cast = cast)
    }

    private fun codec(mime: String): String? = when (val value = mime.lowercase(Locale.ROOT)) {
        "audio/raw" -> "pcm"
        "audio/mpeg" -> "mp3"
        "audio/mp4a-latm" -> "aac"
        "audio/x-flac" -> "flac"
        else -> value.substringAfter('/').takeIf { it.matches(Regex("[a-z0-9._+-]{1,40}")) }
    }

    private fun container(mimeOrExtension: String): String? = when (val value = mimeOrExtension.lowercase(Locale.ROOT).substringAfter('/')) {
        "x-wav", "wave", "vnd.wave" -> "wav"
        "x-flac" -> "flac"
        "mpeg" -> "mp3"
        "m4a", "m4b", "m4p", "mp4a-latm" -> "mp4"
        "x-matroska" -> "matroska"
        else -> value.takeIf { it.matches(Regex("[a-z0-9._+-]{1,40}")) }
    }
}
