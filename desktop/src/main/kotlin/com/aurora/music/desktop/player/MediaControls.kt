package com.aurora.music.desktop.player

import com.aurora.music.data.artwork.ArtworkRepository
import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.desktop.natives.SmtcButton
import com.aurora.music.desktop.natives.SmtcRepeat
import com.aurora.music.desktop.natives.SmtcSession
import com.aurora.music.desktop.natives.SmtcStatus
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

class MediaControls internal constructor(
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
    private val artwork: suspend (String) -> ByteArray?,
) : AutoCloseable {
    @Volatile private var session: SmtcSession? = null
    private var job: Job? = null

    fun attach(hwnd: Long) {
        if (session != null) return
        val created = runCatching { SmtcSession.create(hwnd, Callbacks()) }
            .onFailure { AppLog.w(TAG, "Windows media controls are unavailable", it) }
            .getOrNull() ?: return
        session = created
        job = scope.launch(Dispatchers.IO) {
            created.buttons()
            launch { player.state.map { it.current }.distinctUntilChanged().collectLatest { publishMetadata(created, it) } }
            var status: SmtcStatus? = null
            var shuffle: Boolean? = null
            var repeat: SmtcRepeat? = null
            var track: Pair<String, Int>? = null
            var sentAt = 0L
            var sentPosition = 0L
            var sentSpeed = 1f
            while (isActive) {
                val s = player.state.value
                val now = System.nanoTime() / 1_000_000
                val nextStatus = when {
                    !s.hasTrack -> SmtcStatus.STOPPED
                    s.isPlaying -> SmtcStatus.PLAYING
                    else -> SmtcStatus.PAUSED
                }
                val position = (s.positionSec * 1000).toLong()
                val expected = sentPosition + if (status == SmtcStatus.PLAYING) ((now - sentAt) * sentSpeed).toLong() else 0L
                val key = s.current.id to s.durationSec
                if (nextStatus != status || key != track || now - sentAt >= 5_000 || abs(position - expected) > 1_500) {
                    created.timeline(position, s.durationSec * 1000L)
                    track = key
                    sentAt = now
                    sentPosition = position
                    sentSpeed = s.speed
                }
                if (nextStatus != status) created.status(nextStatus)
                status = nextStatus
                if (s.shuffle != shuffle) created.shuffle(s.shuffle)
                shuffle = s.shuffle
                val nextRepeat = when (s.repeat) {
                    RepeatMode.OFF -> SmtcRepeat.NONE
                    RepeatMode.ONE -> SmtcRepeat.TRACK
                    RepeatMode.ALL -> SmtcRepeat.LIST
                }
                if (nextRepeat != repeat) created.repeat(nextRepeat)
                repeat = nextRepeat
                delay(500)
            }
        }
    }

    private suspend fun publishMetadata(target: SmtcSession, song: Song) {
        if (song.id.isEmpty()) {
            target.metadata(null, null)
            return
        }
        target.metadata(song.title, song.artist, song.album)
        val thumbnail = song.artworkUrl.takeIf { it.isNotBlank() }?.let { artwork(it) } ?: return
        target.metadata(song.title, song.artist, song.album, thumbnail = thumbnail)
    }

    override fun close() {
        job?.cancel()
        session?.close()
        session = null
    }

    // native callbacks arrive on the aurora-native thread and must return quickly
    private inner class Callbacks : SmtcSession.Callbacks {
        override fun onButton(button: SmtcButton) {
            scope.launch {
                when (button) {
                    SmtcButton.PLAY -> player.play()
                    SmtcButton.PAUSE, SmtcButton.STOP -> player.pause()
                    SmtcButton.NEXT -> player.next()
                    SmtcButton.PREVIOUS -> player.previous()
                    else -> Unit
                }
            }
        }

        override fun onSeek(positionMs: Long) {
            scope.launch {
                val duration = player.state.value.durationSec
                if (duration > 0) player.seekTo(positionMs / (duration * 1000f))
            }
        }

        override fun onShuffle(enabled: Boolean) {
            scope.launch { if (player.state.value.shuffle != enabled) player.toggleShuffle() }
        }

        override fun onRepeat(mode: SmtcRepeat) {
            scope.launch {
                player.setRepeat(when (mode) {
                    SmtcRepeat.NONE -> RepeatMode.OFF
                    SmtcRepeat.TRACK -> RepeatMode.ONE
                    SmtcRepeat.LIST -> RepeatMode.ALL
                })
            }
        }
    }

    private companion object {
        const val TAG = "AuroraMediaControls"
    }
}

internal suspend fun artworkBytes(url: String, http: OkHttpClient, repository: ArtworkRepository): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        when {
            ArtworkUrls.isArtwork(url) -> ArtworkUrls.decode(url)?.let { request -> repository.open(request) { it.readBytes() } }
            url.startsWith("file:") -> Files.readAllBytes(Path.of(URI(url)))
            url.startsWith("http://") || url.startsWith("https://") ->
                http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            else -> null
        }
    }.getOrNull()
}
