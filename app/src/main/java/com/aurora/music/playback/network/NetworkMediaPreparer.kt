package com.aurora.music.playback.network

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.ProcessingSettings
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.network.audio.ProcessedNetworkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@UnstableApi
internal class NetworkMediaPreparer(
    private val context: Context,
    private val dataSource: androidx.media3.datasource.DataSource.Factory,
    private val processingSettings: Flow<ProcessingSettings>,
    private val impulse: () -> ImpulseResponse?,
    private val rackImpulses: () -> Map<String, ImpulseResponse>,
    private val disposed: () -> Boolean,
) : java.io.Closeable {
    private var networkServer: ScopedMediaServer? = null

    suspend fun prepare(item: MediaItem, receiver: NetworkReceiver, processed: Boolean): NetworkMedia {
        if (!processed || receiver.kind == "Aurora") {
            val direct = try { com.aurora.music.playback.network.audio.DirectNetworkPreparer.prepare(context, item, dataSource) }
            catch (e: com.aurora.music.playback.network.audio.NetworkRenderingException) { throw NetworkOutputException(e.message ?: "Audio preparation failed.") }
            return grant(receiver, direct.file, direct.mimeType, direct.durationMs,
                if (receiver.kind == "Aurora") "Original audio file; the Aurora receiver applies its own settings" else direct.summary)
        }
        val settings = processingSettings.first()
        val snapshot = com.aurora.music.data.ProcessingSnapshot(
            settings.audio, com.aurora.music.data.ProcessingPlaybackPrefs.from(settings.playback), rack = settings.rack)
        val ir = impulse()
        val impulses = rackImpulses().toMap()
        val extras = item.mediaMetadata.extras
        val db = if (settings.audio.replayGain == 2) extras?.getFloat("rgAlbum", 0f) ?: 0f else extras?.getFloat("rgTrack", 0f) ?: 0f
        val gain = if (settings.audio.replayGain == 0) 1.0 else Math.pow(10.0, db.coerceAtMost(0f) / 20.0)
        val relativeVolume = try { receiver.status().volume?.toDouble() ?: 1.0 }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { 1.0 }
        val rendered = try { ProcessedNetworkRenderer.render(context, item, dataSource, snapshot,
            impulses = impulses, legacyImpulse = ir, outputGain = gain, relativeVolume = relativeVolume) }
        catch (e: com.aurora.music.playback.network.audio.NetworkRenderingException) { throw NetworkOutputException(e.message ?: "Audio preparation failed.") }
        return grant(receiver, rendered.file, rendered.mimeType, rendered.durationMs, rendered.processingSummary)
    }

    private suspend fun grant(receiver: NetworkReceiver, file: java.io.File, mime: String, duration: Long, summary: String): NetworkMedia {
        var release: (() -> Unit)? = null
        try {
            val negotiatedMime = receiver.contentType(mime)
            return withContext(Dispatchers.IO) {
                val address = java.net.InetAddress.getByName(receiver.host)
                val localAddress = java.net.DatagramSocket().use { socket -> socket.connect(address, 9); socket.localAddress.hostAddress!! }
                synchronized(this@NetworkMediaPreparer) {
                    check(!disposed())
                    val server = networkServer ?: ScopedMediaServer().also { it.start(); networkServer = it }
                    val grant = server.grant(file, negotiatedMime, 4 * 60 * 60 * 1000L, allowedClient = address)
                    val cleanup = { server.revoke(grant.token); file.delete(); Unit }
                    release = cleanup
                    NetworkMedia(grant.url(localAddress, server.port), negotiatedMime, duration, summary,
                        sizeBytes = file.length(), release = cleanup)
                }
            }
        } catch (e: Exception) { release?.invoke(); file.delete(); throw e }
    }

    override fun close() { networkServer?.close(); networkServer = null }
}
