package com.aurora.music.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object PreferredPlayback {
    private const val URI = "aurora.preferred.uri"
    private val gson = Gson()

    suspend fun resolve(context: Context, repository: MusicRepository, item: MediaItem): MediaItem {
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val song = Song(extras?.getString("aurora.songId") ?: item.mediaId,
            metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
            metadata.albumTitle?.toString().orEmpty(), metadata.artworkUri?.toString().orEmpty(),
            extras?.getInt("aurora.durationSec") ?: 0, explicit = extras?.getBoolean("aurora.explicit") ?: false,
            streamUrl = item.localConfiguration?.uri.toString())
        val candidates = try { repository.playbackCandidates(song) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return item }
        val deadline = System.nanoTime() + 6_000_000_000L
        for (candidate in candidates.take(6)) {
            coroutineContext.ensureActive()
            if (System.nanoTime() > deadline) break
            val source = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(2_000).setReadTimeoutMs(2_000)).createDataSource()
            val available = try {
                source.open(DataSpec.Builder().setUri(Uri.parse(candidate.streamUrl)).setLength(1).build())
                source.read(ByteArray(1), 0, 1) == 1
            } catch (_: Exception) { false }
            finally { runCatching { source.close() } }
            coroutineContext.ensureActive()
            if (!available) continue
            val updated = Bundle(extras ?: Bundle.EMPTY).apply {
                listOf("provider", "providerLabel", "source", "album", "container").forEach { remove("aurora.rules.$it") }
                putAll(PresetContextPublisher.extras(candidate))
                putString(URI, candidate.streamUrl)
                putString("aurora.preferred.original", item.localConfiguration?.uri.toString())
                putString("aurora.preferred.suffix", candidate.suffix)
                putInt("aurora.preferred.rate", candidate.sampleRateHz)
                putInt("aurora.preferred.depth", candidate.bitDepth)
                putInt("aurora.preferred.bitrate", candidate.bitrateKbps)
                putString("aurora.preferred.source", gson.toJson(candidate.playbackSource))
            }
            return item.buildUpon().setUri(candidate.streamUrl).setMimeType(null)
                .setMediaMetadata(metadata.buildUpon().setExtras(updated).build()).build()
        }
        return item
    }

    fun applyTo(song: Song, item: MediaItem?): Song {
        val extras = item?.mediaMetadata?.extras ?: return song
        val uri = extras.getString(URI) ?: return song
        val source = runCatching { gson.fromJson(extras.getString("aurora.preferred.source"), PlaybackSourceIdentity::class.java) }.getOrNull()
        return song.copy(streamUrl = uri, suffix = extras.getString("aurora.preferred.suffix").orEmpty(),
            durationSec = song.durationSec.takeIf { it > 0 } ?: extras.getInt("aurora.durationSec"),
            sampleRateHz = extras.getInt("aurora.preferred.rate"), bitDepth = extras.getInt("aurora.preferred.depth"),
            bitrateKbps = extras.getInt("aurora.preferred.bitrate"), playbackSource = source,
            replayGainTrack = extras.getFloat("rgTrack"), replayGainAlbum = extras.getFloat("rgAlbum"))
    }
}
