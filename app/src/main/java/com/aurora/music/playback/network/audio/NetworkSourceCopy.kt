package com.aurora.music.playback.network.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File

@OptIn(UnstableApi::class)
internal object NetworkSourceCopy {
    fun validate(item: MediaItem) {
        val configuration = item.localConfiguration ?: throw NetworkRenderingException("The source is unavailable.")
        if (configuration.drmConfiguration != null) throw NetworkRenderingException("Protected tracks cannot be shared.")
        if (configuration.mimeType in setOf("application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml")) {
            throw NetworkRenderingException("Live and segmented sources cannot be shared.")
        }
    }

    fun copy(item: MediaItem, factory: DataSource.Factory, destination: File, checkCancelled: () -> Unit,
        onProgress: (Long) -> Unit = {}) {
        validate(item)
        val configuration = checkNotNull(item.localConfiguration)
        val source = factory.createDataSource()
        try {
            val length = source.open(DataSpec.Builder().setUri(configuration.uri).build())
            if (length > NetworkWaveWriter.MAX_BYTES) throw NetworkRenderingException("The source exceeds the 512 MB limit.")
            destination.outputStream().use { output ->
                val bytes = ByteArray(64 * 1024)
                var copied = 0L
                var lastProgress = System.nanoTime()
                while (true) {
                    checkCancelled()
                    val count = source.read(bytes, 0, bytes.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (count == 0) {
                        if (System.nanoTime() - lastProgress > 15_000_000_000L) throw NetworkRenderingException("The source stopped responding.")
                        continue
                    }
                    copied += count
                    if (copied > NetworkWaveWriter.MAX_BYTES) throw NetworkRenderingException("The source exceeds the 512 MB limit.")
                    output.write(bytes, 0, count)
                    lastProgress = System.nanoTime()
                    onProgress(copied)
                }
            }
        } finally { runCatching { source.close() } }
    }
}
