package com.aurora.music.data

import com.aurora.music.data.remote.LastfmClient
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the Last.fm link: drives the browser auth flow, persists the session in [SettingsStore], and
 * submits now-playing + scrobbles when connected and enabled. All network is best-effort and
 * fire-and-forget — failures never affect playback. Needs an API key + secret (see [AppContainer]);
 * when unconfigured, [configured] is false and everything is a no-op.
 */
class LastfmScrobbler(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    // The API key + secret are the user's own (entered in Integrations), so the client is rebuilt
    // whenever they change rather than baked in at construction.
    @Volatile private var client: LastfmClient? = null

    val configured: Boolean get() = client?.configured == true

    @Volatile private var sessionKey: String? = null
    @Volatile private var enabled: Boolean = true

    init {
        scope.launch {
            store.lastfm.collect { acct ->
                sessionKey = acct.sessionKey.ifBlank { null }
                enabled = acct.enabled
            }
        }
        scope.launch {
            store.lastfmKeys.collect { (key, secret) ->
                client = if (key.isNotBlank() && secret.isNotBlank()) LastfmClient(key, secret) else null
            }
        }
    }

    val isConnected: Boolean get() = !sessionKey.isNullOrBlank()

    /** Auth step 1: get a token and the URL to open in the browser. Null if unreachable / unconfigured. */
    suspend fun beginLink(): String? = client?.getToken()
    fun authorizeUrl(token: String): String = client?.authorizeUrl(token) ?: ""

    /** Auth step 2: after the user allowed access, exchange the token and persist the session. */
    suspend fun finishLink(token: String): Boolean {
        val c = client ?: return false
        val session = c.getSession(token) ?: return false
        val info = c.userInfo(session.name)
        store.saveLastfm(session.key, session.name, info?.imageUrl ?: "")
        return true
    }

    suspend fun disconnect() = store.clearLastfm()

    fun nowPlaying(song: Song) {
        val c = client ?: return
        val sk = sessionKey ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch { c.updateNowPlaying(sk, song.artist, song.title, song.album.ifBlank { null }) }
    }

    fun scrobble(song: Song, startedAtMs: Long) {
        val c = client ?: return
        val sk = sessionKey ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch { c.scrobble(sk, song.artist, song.title, song.album.ifBlank { null }, startedAtMs / 1000) }
    }
}
