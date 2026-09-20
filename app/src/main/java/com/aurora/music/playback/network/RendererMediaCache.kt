package com.aurora.music.playback.network

import android.content.Context
import com.aurora.music.playback.network.endpoint.EndpointTrack
import com.aurora.music.playback.network.endpoint.RendererException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

class RendererMediaCache(context: Context) {
    private val directory = File(context.cacheDir, "renderer-media").apply { mkdirs() }
    private val filesLock = Any()
    private val pending = mutableSetOf<File>()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()

    @Synchronized
    fun receive(tracks: List<EndpointTrack>, sourceHost: String?, authorized: () -> Boolean = { true }): List<File> {
        if (tracks.size !in 1..100) throw RendererException("invalid_request", "Queue must contain 1 to 100 tracks.")
        if (sourceHost == null) throw RendererException("source_unavailable", "The controller address is missing.")
        val sender = InetAddress.getByName(sourceHost)
        val files = mutableListOf<File>()
        val retainedBytes = directory.listFiles()?.sumOf { it.length() } ?: 0L
        if (retainedBytes > MAX_BYTES) throw RendererException("source_too_large", "Queue exceeds the 512 MB limit.")
        var total = 0L
        val deadline = System.nanoTime() + 105_000_000_000L
        try {
            for (track in tracks) {
                if (!authorized()) throw RendererException("unauthorized", "Controller access was revoked.", 401)
                val uri = URI(track.sourceUrl)
                if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.rawQuery != null || uri.fragment != null ||
                    !uri.path.matches(Regex("/media/[A-Za-z0-9_-]{43}")) ||
                    InetAddress.getAllByName(uri.host).any { it != sender }) {
                    throw RendererException("invalid_source", "Audio must be shared by the paired controller.")
                }
                val file = File(directory, "${UUID.randomUUID()}.wav")
                files += file
                synchronized(filesLock) { pending += file }
                val sourceClient = client.newBuilder().dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != uri.host) throw java.net.UnknownHostException("Unexpected media host.")
                        return listOf(sender)
                    }
                }).callTimeout((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS).build()
                sourceClient.newCall(Request.Builder().url(track.sourceUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw RendererException("source_unavailable", "Shared audio is unavailable.")
                    val body = response.body ?: throw RendererException("source_unavailable", "Shared audio is empty.")
                    if (body.contentLength() > MAX_BYTES - total) throw RendererException("source_too_large", "Queue exceeds the 512 MB limit.")
                    if (body.contentLength() > directory.usableSpace) throw RendererException("storage_full", "Not enough storage for this queue.")
                    body.byteStream().use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            if (!authorized()) throw RendererException("unauthorized", "Controller access was revoked.", 401)
                            if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw RendererException("source_timeout", "Audio transfer timed out.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_BYTES || total + retainedBytes > 2 * MAX_BYTES)
                                throw RendererException("source_too_large", "Queue exceeds the 512 MB limit.")
                            output.write(buffer, 0, count)
                        }
                    } }
                }
                if (file.length() < 44) throw RendererException("invalid_source", "Shared audio is empty.")
            }
            return files
        } catch (e: Exception) {
            discard(files)
            if (e is RendererException) throw e
            throw RendererException("source_unavailable", "Audio transfer failed.")
        }
    }

    fun retain(files: List<File>) {
        synchronized(filesLock) {
            pending.removeAll(files.toSet())
            val keep = (files + pending).map { it.name }.toSet()
            directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
    }

    fun discard(files: List<File>) {
        synchronized(filesLock) {
            pending.removeAll(files.toSet())
            files.forEach { it.delete() }
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object { const val MAX_BYTES = 512L * 1024 * 1024 }
}
