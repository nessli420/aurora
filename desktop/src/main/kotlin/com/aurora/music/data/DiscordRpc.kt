package com.aurora.music.data

import com.aurora.music.util.AppLog
import com.aurora.music.data.remote.DiscordGateway
import com.aurora.music.data.remote.ImgurUploader
import com.aurora.music.model.Song
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

class DiscordRpc(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val localUser: MutableStateFlow<String?>,
    private val artwork: suspend (String) -> ByteArray?,
) : AutoCloseable {
    private val imgur = ImgurUploader()
    private val http get() = com.aurora.music.data.remote.AuroraHttp.base
    private val ipc = DiscordIpc(onUser = { name ->
        localUser.value = name
        synchronized(this@DiscordRpc) { reconcile() }
    })
    // the token gateway is only a fallback for when the local discord client isn't running
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
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedImages = ConcurrentHashMap<String, Long>()
    private var positionAt = 0L

    private val imageCache = ConcurrentHashMap<String, String>()
    private val uploads = ConcurrentHashMap<String, String>()
    @Volatile private var lastSong: Song? = null
    @Volatile private var lastPlaying = false
    @Volatile private var lastPositionSec = 0f
    // the gateway stays up only while playing plus a grace period after pause or stop
    @Volatile private var active = false
    private var idleJob: Job? = null

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
                    if (enabled) ipc.start(appId.ifBlank { DEFAULT_APP_ID }) else ipc.stop()
                    ipc.update(lastSong?.takeIf { lastPlaying }?.let { ipcActivity(it, currentPosition()) })
                    if (gateway.clearFatal()) connectedToken = null
                    // a fresh login connects once so ready can fill in the username
                    if (token.isNotBlank() && enabled && acct.username.isBlank() && !active) { active = true; scheduleIdle() }
                    reconcile()
                    if (connectedToken != null) gateway.updateActivity(lastSong?.takeIf { lastPlaying }?.let { buildActivity(it, true, currentPosition()) })
                }
            }
        }
    }

    private fun reconcile() {
        if (token.isBlank() || !enabled || !active || localUser.value != null) {
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
        positionAt = (System.nanoTime() / 1_000_000L)
        if (isPlaying) {
            idleJob?.cancel(); idleJob = null
            active = true
        } else if (active) scheduleIdle()
        ipc.update(if (isPlaying) ipcActivity(song, positionSec) else null)
        if (token.isBlank() || !enabled) return
        reconcile()
        gateway.updateActivity(if (isPlaying) buildActivity(song, true, positionSec) else null)
    }

    override fun close() {
        synchronized(this) { enabled = false; reconcile() }
        ipc.close()
    }

    private fun scheduleIdle() {
        if (idleJob != null) return
        idleJob = scope.launch {
            delay(IDLE_DISCONNECT_MS)
            synchronized(this@DiscordRpc) {
                if (!isActive) return@synchronized
                idleJob = null
                if (!lastPlaying) { active = false; reconcile() }
            }
        }
    }

    private fun currentPosition() = lastPositionSec + if (lastPlaying) ((System.nanoTime() / 1_000_000L) - positionAt).coerceAtLeast(0) / 1000f else 0f
    private fun imageKey(url: String) = "$appId|$imgurClientId|$url"

    // the local client fetches https art itself, so only private covers need imgur
    private fun ipcActivity(song: Song, positionSec: Float): JsonObject? {
        if (song.title.isBlank()) return null
        val a = discordActivity(song, showAlbum, activityName, positionSec)
        val art = song.artworkUrl
        val image = when {
            art.isBlank() -> null
            isPublicArt(art) -> art
            else -> uploads["$imgurClientId|$art"].also { if (it == null) uploadForIpc(art) }
        }
        if (image != null) a.add("assets", JsonObject().apply {
            addProperty("large_image", image)
            if (showAlbum && song.album.isNotBlank()) addProperty("large_text", song.album.take(128))
        })
        return a
    }

    private fun uploadForIpc(art: String) {
        val uploadId = imgurId()
        val key = "ipc|$uploadId|$art"
        if (uploadId.isBlank() || (failedImages[key] ?: 0) > System.currentTimeMillis() - 60_000 || !inFlight.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                val link = publicLink(art, uploadId)
                synchronized(this@DiscordRpc) {
                    if (link == null) failedImages[key] = System.currentTimeMillis()
                    val current = lastSong
                    if (link != null && current != null && lastPlaying && current.artworkUrl == art) ipc.update(ipcActivity(current, currentPosition()))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { failedImages[key] = System.currentTimeMillis() }
            finally { inFlight.remove(key) }
        }
    }

    private suspend fun publicLink(art: String, uploadId: String): String? {
        if (isPublicArt(art)) return art
        if (uploadId.isBlank()) return null
        val key = "$uploadId|$art"
        uploads[key]?.let { return it }
        val link = loadDiscordArtwork(artwork, art)?.let { imgur.uploadBytes(it, uploadId) } ?: return null
        if (uploads.size >= 128) uploads.clear()
        uploads[key] = link
        return link
    }

    private fun buildActivity(song: Song, isPlaying: Boolean, positionSec: Float): JsonObject? {
        if (song.title.isBlank()) return null
        val a = discordActivity(song, showAlbum, activityName, positionSec)
        val canHost = imgurId().isNotBlank() || isPublicArt(song.artworkUrl)
        if (imagesPossible && song.artworkUrl.isNotBlank() && canHost) {
            val mp = imageCache[imageKey(song.artworkUrl)]
            val assets = JsonObject()
            if (showAlbum && song.album.isNotBlank()) assets.addProperty("large_text", song.album.take(128))
            if (mp != null) assets.addProperty("large_image", mp)
            a.add("assets", assets)
            a.addProperty("application_id", appId)
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
                val mp = publicLink(artUrl, uploadId)?.let { externalAsset(it, applicationId, authorization) }
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
        val body = com.google.gson.JsonObject().apply { add("urls", com.google.gson.JsonArray().apply { add(url) }) }.toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://discord.com/api/v9/applications/$applicationId/external-assets")
            .addHeader("Authorization", authorization)
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string()
            if (!resp.isSuccessful) { AppLog.w(TAG, "external-assets HTTP ${resp.code}: ${bodyStr?.take(300)}"); return@use null }
            val arr = runCatching { com.google.gson.JsonParser.parseString(bodyStr ?: return@use null).asJsonArray }.getOrNull() ?: return@use null
            val path = arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("external_asset_path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (path.isBlank()) { AppLog.w(TAG, "external-assets no path: ${bodyStr?.take(300)}"); null } else "mp:$path"
        }
    }.getOrNull()

    private companion object {
        const val TAG = "DiscordRpc"
        const val IDLE_DISCONNECT_MS = 120_000L
        const val DEFAULT_APP_ID = "1513658991513964826"
    }
}

// discord embeds the cover url in a public mp:external path, so only cdns that never carry a server token qualify
private val PUBLIC_ART_HOSTS = listOf("ytimg.com", "googleusercontent.com", "ggpht.com", "scdn.co", "spotifycdn.com", "sndcdn.com", "mzstatic.com", "imgur.com")

internal fun isPublicArt(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && url.length <= 256 && PUBLIC_ART_HOSTS.any { host == it || host.endsWith(".$it") }
}

internal fun discordActivity(song: Song, showAlbum: Boolean, activityName: String, positionSec: Float, now: Long = System.currentTimeMillis()): JsonObject {
    val album = if (showAlbum && song.album.isNotBlank()) " · ${song.album}" else ""
    val artist = song.artist.ifBlank { "Unknown artist" }
    val activity = JsonObject().apply {
        addProperty("name", when (activityName) { "artist" -> artist; "song" -> song.title; else -> "Aurora" }.take(128))
        addProperty("type", 2)
        addProperty("status_display_type", when (activityName) { "artist" -> 1; "song" -> 2; else -> 0 })
        addProperty("details", (song.title + if (activityName == "artist") album else "").take(128))
        addProperty("state", (artist + if (activityName == "artist") "" else album).take(128))
    }
    if (song.durationSec > 0) {
        val start = now - (positionSec.coerceIn(0f, song.durationSec.toFloat()) * 1000).toLong()
        activity.add("timestamps", JsonObject().apply {
            addProperty("start", start)
            addProperty("end", start + song.durationSec * 1000L)
        })
    }
    return activity
}

internal suspend fun loadDiscordArtwork(artwork: suspend (String) -> ByteArray?, url: String): ByteArray? {
    val bytes = artwork(url) ?: return null
    val full = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    // centre square instead of squashing wide video thumbnails into 512x512
    val side = minOf(full.width, full.height)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(512, 512)
    surface.canvas.drawImageRect(full, org.jetbrains.skia.Rect.makeXYWH(((full.width - side) / 2).toFloat(), ((full.height - side) / 2).toFloat(), side.toFloat(), side.toFloat()),
        org.jetbrains.skia.Rect.makeWH(512f, 512f), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
    return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.bytes
}
