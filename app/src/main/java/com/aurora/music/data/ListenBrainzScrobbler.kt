package com.aurora.music.data

import com.aurora.music.data.remote.ListenBrainzClient
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ListenBrainzScrobbler(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val client: ListenBrainzClient = ListenBrainzClient(),
) {
    @Volatile private var token: String? = null
    @Volatile private var enabled: Boolean = true

    init {
        scope.launch {
            store.listenBrainz.collect { acct ->
                token = acct.token.ifBlank { null }
                enabled = acct.enabled
            }
        }
    }

    val isConnected: Boolean get() = !token.isNullOrBlank()

    suspend fun connect(rawToken: String): Boolean {
        val t = rawToken.trim()
        if (t.isBlank()) return false
        val username = client.validate(t) ?: return false
        store.saveListenBrainz(t, username)
        return true
    }

    suspend fun disconnect() = store.clearListenBrainz()

    fun nowPlaying(song: Song) {
        val t = token ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch {
            val artist = store.artistSeparators.first().split(song.artist).first()
            client.playingNow(t, artist, song.title, song.album.ifBlank { null })
        }
    }

    fun scrobble(song: Song, startedAtMs: Long) {
        val t = token ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch {
            val artist = store.artistSeparators.first().split(song.artist).first()
            client.listen(t, artist, song.title, song.album.ifBlank { null }, startedAtMs / 1000)
        }
    }
}
