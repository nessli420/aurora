package com.aurora.music.data

import android.util.Log
import com.aurora.music.data.remote.DiscordGateway
import com.aurora.music.data.remote.ImgurUploader
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.core.graphics.drawable.toBitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class DiscordRpc(
    private val context: android.content.Context,
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
    @Volatile private var appId = ""
    @Volatile private var connectedToken: String? = null
    @Volatile private var showAlbum = true
    @Volatile private var activityName = "aurora"
    private val loader = coil.ImageLoader(context)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedImages = ConcurrentHashMap<String, Long>()
    private var positionAt = 0L

    private val imageCache = ConcurrentHashMap<String, String>()
    @Volatile private var lastSong: Song? = null
    @Volatile private var lastPlaying = false
    @Volatile private var lastPositionSec = 0f

    private val imagesPossible: Boolean get() = appId.isNotBlank()
    private fun imgurId(): String = imgurClientId

    init {
        scope.launch {
            store.discord.collect { acct ->
                synchronized(this@DiscordRpc) {
                    token = acct.token
                    enabled = acct.enabled
                    imgurClientId = acct.imgurClientId
                    appId = acct.appId
                    showAlbum = acct.showAlbum
                    activityName = acct.activityName
                    reconcile()
                    if (token.isNotBlank() && enabled) gateway.updateActivity(lastSong?.takeIf { lastPlaying }?.let { buildActivity(it, true, currentPosition()) })
                }
            }
        }
    }

    private fun reconcile() {
        if (token.isBlank() || !enabled) {
            if (connectedToken != null) { gateway.disconnect(); connectedToken = null }
            return
        }
        if (connectedToken != token) {
            val initial = lastSong?.takeIf { lastPlaying }?.let { buildActivity(it, true, currentPosition()) }
            gateway.connect(token, initial)
            connectedToken = token
        }
    }

    @Synchronized fun update(song: Song, isPlaying: Boolean, positionSec: Float) {
        val previous = lastSong
        val changed = previous?.id != song.id || previous?.artworkUrl != song.artworkUrl || previous?.title != song.title || previous?.album != song.album || previous?.artist != song.artist || previous?.durationSec != song.durationSec || lastPlaying != isPlaying || kotlin.math.abs(currentPosition() - positionSec) > 2f
        if (!changed) return
        lastSong = song; lastPlaying = isPlaying; lastPositionSec = positionSec
        positionAt = android.os.SystemClock.elapsedRealtime()
        if (token.isBlank() || !enabled) return
        gateway.updateActivity(if (isPlaying) buildActivity(song, true, positionSec) else null)
    }

    private fun currentPosition() = lastPositionSec + if (lastPlaying) (android.os.SystemClock.elapsedRealtime() - positionAt).coerceAtLeast(0) / 1000f else 0f
    private fun imageKey(url: String) = "$appId|$imgurClientId|$url"

    private fun buildActivity(song: Song, isPlaying: Boolean, positionSec: Float): JSONObject? {
        if (song.title.isBlank()) return null
        val a = discordActivity(song, showAlbum, activityName, positionSec)
        // discord must fetch the art so without imgur skip private server urls it cant reach
        val canHost = imgurId().isNotBlank() || isLikelyPublic(song.artworkUrl)
        if (imagesPossible && song.artworkUrl.isNotBlank() && canHost) {
            val mp = imageCache[imageKey(song.artworkUrl)]
            val assets = JSONObject().put("large_text", (if (showAlbum) song.album.ifBlank { song.title } else song.title).take(128))
            if (mp != null) assets.put("large_image", mp)
            a.put("assets", assets)
            a.put("application_id", appId)
            if (mp == null) resolveImage(song.artworkUrl)
        }
        return a
    }

    private fun resolveImage(artUrl: String) {
        val key = imageKey(artUrl)
        if (imageCache.containsKey(key) || (failedImages[key] ?: 0) > System.currentTimeMillis() - 60_000 || !inFlight.add(key)) return
        val uploadId = imgurId(); val applicationId = appId; val authorization = token
        scope.launch(Dispatchers.IO) {
            try {
                val link = if (uploadId.isBlank()) artUrl else {
                    val bytes = loadDiscordArtwork(context, loader, artUrl)
                    bytes?.let { imgur.uploadBytes(it, uploadId) }
                }
                val mp = link?.let { externalAsset(it, applicationId, authorization) }
                synchronized(this@DiscordRpc) {
                    if (mp != null) { if (imageCache.size >= 128) imageCache.clear(); imageCache[key] = mp }
                    else failedImages[key] = System.currentTimeMillis()
                    val current = lastSong
                    if (current != null && lastPlaying && enabled && token == authorization && imageKey(current.artworkUrl) == key) {
                        gateway.updateActivity(buildActivity(current, true, currentPosition()))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { failedImages[key] = System.currentTimeMillis() }
            finally { inFlight.remove(key) }
        }
    }

    private fun externalAsset(url: String, applicationId: String, authorization: String): String? = runCatching {
        val body = JSONObject().put("urls", JSONArray().put(url)).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://discord.com/api/v9/applications/$applicationId/external-assets")
            .addHeader("Authorization", authorization)
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

    private fun isLikelyPublic(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost") return false
        val o = host.split(".").mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return !host.contains(":")
        val a = o[0]; val b = o[1]
        return when {
            a == 10 -> false
            a == 127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 100 && b in 64..127 -> false
            else -> true
        }
    }

    private companion object { const val TAG = "DiscordRpc" }
}

internal fun discordActivity(song: Song, showAlbum: Boolean, activityName: String, positionSec: Float, now: Long = System.currentTimeMillis()): JSONObject {
    val album = if (showAlbum && song.album.isNotBlank()) " · ${song.album}" else ""
    val artist = song.artist.ifBlank { "Unknown artist" }
    val activity = JSONObject()
        .put("name", when (activityName) { "artist" -> artist; "song" -> song.title; else -> "Aurora" }.take(128))
        .put("type", 2)
        .put("status_display_type", when (activityName) { "artist" -> 1; "song" -> 2; else -> 0 })
        .put("details", (song.title + if (activityName == "artist") album else "").take(128))
        .put("state", (artist + if (activityName == "artist") "" else album).take(128))
    if (song.durationSec > 0) {
        val start = now - (positionSec.coerceIn(0f, song.durationSec.toFloat()) * 1000).toLong()
        activity.put("timestamps", JSONObject().put("start", start).put("end", start + song.durationSec * 1000L))
    }
    return activity
}

internal suspend fun loadDiscordArtwork(context: android.content.Context, loader: coil.ImageLoader, url: String): ByteArray? {
    val result = loader.execute(coil.request.ImageRequest.Builder(context).data(url).size(512).allowHardware(false).build())
    val drawable = (result as? coil.request.SuccessResult)?.drawable ?: return null
    val bitmap = drawable.toBitmap(512, 512)
    return java.io.ByteArrayOutputStream().use { output ->
        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)) return null
        output.toByteArray()
    }
}
