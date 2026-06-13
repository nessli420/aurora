package com.aurora.music.data

import com.aurora.music.data.remote.ListenBrainzClient
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ListenBrainz scrobbling, mirroring [LastfmScrobbler] but token-only (no browser auth). The
 * user pastes their ListenBrainz user token in Integrations; submissions are fire-and-forget and
 * never affect playback. A no-op until a token is set.
 */
class ListenBrainzScrobbler(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val client = ListenBrainzClient()

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

    /** Validate a token and persist it (with the resolved username) if valid. Returns success. */
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
        scope.launch { client.playingNow(t, song.artist, song.title, song.album.ifBlank { null }) }
    }

    fun scrobble(song: Song, startedAtMs: Long) {
        val t = token ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch { client.listen(t, song.artist, song.title, song.album.ifBlank { null }, startedAtMs / 1000) }
    }
}
