package com.aurora.music.data

import android.util.Log
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
 * progress bar. Album art is registered via Discord's external-assets endpoint: with an Imgur client
 * id the cover is uploaded to Imgur first (needed for private servers Discord can't reach), otherwise
 * the original URL is handed to Discord directly (works for public art, e.g. Spotify). Requires a
 * Discord [appId]; without it the presence is text-only. Best-effort; never affects playback.
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
        // Art needs a host Discord's servers can actually fetch: either Imgur (re-hosts the bytes) or
        // a publicly reachable original URL (e.g. Spotify). A private/LAN/Tailscale server URL can't
        // be reached by Discord, so without Imgur we skip art entirely rather than show a broken icon.
        val canHost = imgurId().isNotBlank() || isLikelyPublic(song.artworkUrl)
        if (imagesPossible && song.artworkUrl.isNotBlank() && canHost) {
            val mp = imageCache[song.artworkUrl]
            Log.i(TAG, "art: appId=${appId.isNotBlank()} imgur=${imgurId().isNotBlank()} public=${isLikelyPublic(song.artworkUrl)} cached=${mp != null} url=${song.artworkUrl.take(60)}")
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
            // With an Imgur id, host the art there (works even for private servers Discord can't
            // reach). Without one, hand the original URL straight to Discord — works for publicly
            // reachable art (e.g. Spotify's CDN).
            val link = if (imgurId().isNotBlank()) imgur.uploadFromUrl(artUrl, imgurId()) else artUrl
            if (link == null) { Log.w(TAG, "resolveImage: imgur upload failed (clientId set=${imgurId().isNotBlank()})"); return@launch }
            Log.i(TAG, "resolveImage: via=${if (imgurId().isNotBlank()) "imgur" else "direct"} link=${link.take(80)}")
            val mp = externalAsset(link)
            if (mp == null) { Log.w(TAG, "resolveImage: external-assets failed (appId=$appId) for $link"); return@launch }
            Log.i(TAG, "resolveImage: art ready -> $mp")
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
            val bodyStr = resp.body?.string()
            if (!resp.isSuccessful) { Log.w(TAG, "external-assets HTTP ${resp.code}: ${bodyStr?.take(300)}"); return@use null }
            val arr = JSONArray(bodyStr ?: return@use null)
            val path = arr.optJSONObject(0)?.optString("external_asset_path").orEmpty()
            if (path.isBlank()) { Log.w(TAG, "external-assets no path: ${bodyStr?.take(300)}"); null } else "mp:$path"
        }
    }.getOrNull()

    /** Whether Discord's servers can likely fetch this URL directly (public host), vs a private/LAN
     *  address (RFC1918 / loopback / link-local / CGNAT-Tailscale) that only Imgur re-hosting can bridge. */
    private fun isLikelyPublic(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost") return false
        val o = host.split(".").mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return !host.contains(":")  // hostname -> public; IPv6 -> treat as private
        val a = o[0]; val b = o[1]
        return when {
            a == 10 -> false
            a == 127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 100 && b in 64..127 -> false   // CGNAT / Tailscale
            else -> true
        }
    }

    private companion object { const val TAG = "DiscordRpc" }
}
