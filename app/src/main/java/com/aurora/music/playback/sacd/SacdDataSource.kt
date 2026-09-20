package com.aurora.music.playback.sacd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.aurora.music.playback.dsd.DstDecoder
import java.io.IOException
import java.nio.ByteBuffer

class AndroidSacdInput(context: Context, uri: Uri) : SacdInput, AutoCloseable {
    private val file = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")))
    override val length: Long
    init {
        try { length = file.channel.size(); require(length > 0) { "Select a local, seekable SACD image." } }
        catch (failure: Exception) { file.close(); throw failure }
    }
    override fun read(position: Long, size: Int): ByteArray {
        require(position >= 0 && size >= 0 && position <= length - size)
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) {
            val count = file.channel.read(buffer, position + buffer.position())
            if (count <= 0) throw IOException("SACD image is no longer readable.")
        }
        return buffer.array()
    }
    override fun close() = file.close()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SacdDataSource(private val context: Context) : BaseDataSource(false) {
    private var input: AndroidSacdInput? = null
    private var decoder: DstDecoder? = null
    private var stream: SacdTrackStream? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            require(dataSpec.uri.scheme == SCHEME)
            val source = Uri.parse(requireNotNull(dataSpec.uri.getQueryParameter("image")))
            require(source.scheme == "content") { "SACD images require a local document." }
            val track = requireNotNull(dataSpec.uri.getQueryParameter("track")?.toIntOrNull())
            val opened = AndroidSacdInput(context, source).also { input = it }
            val image = SacdImage(opened)
            val dst = if (image.disc.frameFormat == 0) DstDecoder(2).also { decoder = it } else null
            val trackStream = SacdTrackStream(image, track, dst?.let { d -> { bytes -> ByteArray(9408).also { d.decode(bytes, it) } } })
            require(dataSpec.position in 0..trackStream.length)
            stream = trackStream; uri = dataSpec.uri; position = dataSpec.position
            remaining = trackStream.length - position
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                require(dataSpec.length <= remaining); remaining = dataSpec.length
            }
            transferStarted(dataSpec); started = true
            return remaining
        } catch (failure: Exception) {
            close()
            throw IOException(failure.message ?: "SACD image could not open.", failure)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val count = checkNotNull(stream).read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count <= 0) throw IOException("Truncated SACD track.")
            position += count; remaining -= count; bytesTransferred(count)
            return count
        } catch (failure: IOException) { throw failure }
        catch (failure: Exception) { throw IOException(failure.message ?: "SACD decoding failed.", failure) }
    }

    override fun getUri() = uri
    override fun close() {
        try { decoder?.close() } finally {
            decoder = null; stream = null; uri = null
            try { input?.close() } finally { input = null; if (started) { started = false; transferEnded() } }
        }
    }

    class Factory(private val context: Context, private val delegate: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            private val listeners = ArrayList<TransferListener>()
            private var active: DataSource? = null
            override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; active?.addTransferListener(transferListener) }
            override fun open(dataSpec: DataSpec): Long {
                check(active == null)
                val source = if (dataSpec.uri.scheme == SCHEME) SacdDataSource(context) else delegate.createDataSource()
                active = source; listeners.forEach(source::addTransferListener)
                return try { source.open(dataSpec) } catch (failure: Exception) { close(); throw failure }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(active).read(buffer, offset, length)
            override fun getUri() = active?.uri
            override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()
            override fun close() { try { active?.close() } finally { active = null } }
        }
    }

    companion object {
        const val SCHEME = "aurora-sacd"
        fun trackUri(image: Uri, track: Int): Uri = Uri.Builder().scheme(SCHEME).authority("track")
            .appendQueryParameter("image", image.toString()).appendQueryParameter("track", track.toString()).build()
    }
}
