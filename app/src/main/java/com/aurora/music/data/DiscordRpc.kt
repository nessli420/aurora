package com.aurora.music.data

import com.aurora.music.data.remote.DiscordGateway
import com.aurora.music.data.remote.ImgurUploader
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Discord Rich Presence for the now-playing track. Connects the user's account to the gateway (see
 * [DiscordGateway]) and pushes a "Listening to Aurora" activity with the song title, artist, and a
 * progress bar. Album art is uploaded to Imgur ([ImgurUploader]) then proxied through Discord's
 * external-assets endpoint — requires a Discord [appId] and an Imgur client id; without them the
 * presence is text-only (still works). Everything is best-effort and never affects playback.
 */
class DiscordRpc(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val imgur = ImgurUploader()
    private val http = OkHttpClient()
    private val gateway = DiscordGateway(
        onUsername = { name -> if (token.isNotBlank()) scope.launch { store.saveDiscord(token, name) } },
        onConnected = { },
    )

    @Volatile private var token = ""
    @Volatile private var enabled = true
    @Volatile private var imgurClientId = ""
    @Volatile private var appId = ""   // user's own Discord application id (from Integrations)
    @Volatile private var connectedToken: String? = null

    private val imageCache = ConcurrentHashMap<String, String>()  // artUrl -> "mp:external/..."
    @Volatile private var lastSong: Song? = null
    @Volatile private var lastPlaying = false
    @Volatile private var lastPositionSec = 0f

    private val imagesPossible: Boolean get() = appId.isNotBlank()
    private fun imgurId(): String = imgurClientId

    init {
        scope.launch {
            store.discord.collect { acct ->
                token = acct.token
                enabled = acct.enabled
                imgurClientId = acct.imgurClientId
                appId = acct.appId
                reconcile()
            }
        }
    }

    private fun reconcile() {
        if (token.isBlank() || !enabled) {
            if (connectedToken != null) { gateway.disconnect(); connectedToken = null }
            return
        }
        if (connectedToken != token) {
            val initial = lastSong?.takeIf { lastPlaying }?.let { buildActivity(it, true, lastPositionSec) }
            gateway.connect(token, initial)
            connectedToken = token
        }
    }

    /** Push the current track as the presence. Called on track change / play-pause.
     *  Paused → clear the presence entirely (only show while actually playing). */
    fun update(song: Song, isPlaying: Boolean, positionSec: Float) {
        lastSong = song; lastPlaying = isPlaying; lastPositionSec = positionSec
        if (token.isBlank() || !enabled) return
        gateway.updateActivity(if (isPlaying) buildActivity(song, true, positionSec) else null)
    }

    private fun buildActivity(song: Song, isPlaying: Boolean, positionSec: Float): JSONObject? {
        if (song.title.isBlank()) return null
        val a = JSONObject()
            .put("name", "Aurora")
            .put("type", 2) // Listening → "Listening to Aurora"
            .put("details", song.title)
            .put("state", song.artist.ifBlank { "Unknown artist" })
        if (isPlaying && song.durationSec > 0) {
            val start = System.currentTimeMillis() - (positionSec * 1000).toLong()
            a.put("timestamps", JSONObject().put("start", start).put("end", start + song.durationSec * 1000L))
        }
        if (imagesPossible && imgurId().isNotBlank() && song.artworkUrl.isNotBlank()) {
            val mp = imageCache[song.artworkUrl]
            val assets = JSONObject().put("large_text", song.album.ifBlank { song.title })
            if (mp != null) assets.put("large_image", mp)
            a.put("assets", assets)
            a.put("application_id", appId)
            if (mp == null) resolveImage(song.artworkUrl)
        }
        return a
    }

    /** Upload art to Imgur → register as a Discord external asset → cache + re-push presence. */
    private fun resolveImage(artUrl: String) {
        if (imageCache.containsKey(artUrl)) return
        scope.launch {
            val link = imgur.uploadFromUrl(artUrl, imgurId()) ?: return@launch
            val mp = externalAsset(link) ?: return@launch
            imageCache[artUrl] = mp
            lastSong?.let { if (it.artworkUrl == artUrl) gateway.updateActivity(buildActivity(it, lastPlaying, lastPositionSec)) }
        }
    }

    private fun externalAsset(url: String): String? = runCatching {
        val body = JSONObject().put("urls", JSONArray().put(url)).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://discord.com/api/v9/applications/$appId/external-assets")
            .addHeader("Authorization", token)
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val arr = JSONArray(resp.body?.string() ?: return@use null)
            val path = arr.optJSONObject(0)?.optString("external_asset_path").orEmpty()
            if (path.isBlank()) null else "mp:$path"
        }
    }.getOrNull()
}
