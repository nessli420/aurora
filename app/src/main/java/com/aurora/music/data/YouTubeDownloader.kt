package com.aurora.music.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.aurora.music.data.remote.YouTubeMusicWebSession
import com.aurora.music.playback.YoutubeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Saves YouTube audio as tagged files in the shared Music folder so they join the local library. */
class YouTubeDownloader(
    private val context: Context,
    private val resolver: YoutubeResolver,
    private val prefs: suspend () -> YouTubeDownloadPrefs,
    private val account: suspend () -> YouTubeMusicWebSession?,
) {
    sealed interface State {
        data object Queued : State
        data class Downloading(val progress: Float) : State
        data object Saving : State
        data object Done : State
        data object Failed : State
    }

    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val work = File(context.cacheDir, "youtube-downloads")

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    @Synchronized
    fun download(videoId: String) {
        when (_states.value[videoId]) {
            State.Queued, State.Saving, is State.Downloading -> return
            else -> setState(videoId, State.Queued)
        }
        scope.launch { gate.withPermit { run(videoId) } }
    }

    private suspend fun run(id: String) {
        work.mkdirs()
        val source = File(work, "$id.src")
        var target: File? = null
        try {
            val settings = prefs()
            val info = resolver.downloadInfo(id, if (settings.anonymous) null else account())
            val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) YouTubeDownloadFormat.entries.toSet()
                else setOf(YouTubeDownloadFormat.M4A)
            // some stream urls answer 403, so fall back to the next best candidate
            var remaining = info.options
            var option = YouTubeDownloads.pick(remaining, settings, supported) ?: throw IOException("No downloadable audio")
            setState(id, State.Downloading(0f))
            while (true) {
                try {
                    fetch(option.url, source) { setState(id, State.Downloading(it)) }
                    break
                } catch (e: IOException) {
                    remaining = remaining - option
                    option = YouTubeDownloads.pick(remaining, settings, supported) ?: throw e
                }
            }
            setState(id, State.Saving)
            val file = File(work, "$id.${option.format.extension}").also { target = it }
            remux(source, file, option.format)
            val cover = if (settings.embedArtwork) runCatching { cover(info.thumbnailUrl) }.getOrNull() else null
            runCatching { writeTags(file, info.tags, cover) }.onFailure { Log.w(TAG, "Tagging failed: ${it.javaClass.simpleName}") }
            publish(file, YouTubeDownloads.fileName(info.tags, option.format), option.format)
            setState(id, State.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.javaClass.simpleName}: ${e.message}")
            setState(id, State.Failed)
        } finally {
            source.delete()
            target?.delete()
        }
    }

    // ranged requests keep youtube from throttling the transfer to playback speed
    private fun fetch(url: String, file: File, onProgress: (Float) -> Unit) {
        file.outputStream().use { output ->
            var position = 0L
            var total = -1L
            while (total < 0 || position < total) {
                val request = Request.Builder().url(url).header("Range", "bytes=$position-${position + CHUNK - 1}").build()
                http.newCall(request).execute().use { response ->
                    val body = response.body ?: throw IOException("Empty body")
                    when (response.code) {
                        206 -> total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: throw IOException("Missing length")
                        200 -> total = 0
                        else -> throw IOException("HTTP ${response.code}")
                    }
                    val copied = body.byteStream().copyTo(output)
                    if (copied == 0L && total > 0) throw IOException("Truncated download")
                    position += copied
                }
                if (total > 0) onProgress((position.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        if (file.length() == 0L) throw IOException("Empty download")
    }

    private fun remux(source: File, target: File, format: YouTubeDownloadFormat) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val container = if (format == YouTubeDownloadFormat.OPUS) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = MediaMuxer(target.absolutePath, container)
            val output = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER)
            val sample = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                sample.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(output, buffer, sample)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun cover(url: String): ByteArray? {
        if (url.isBlank()) return null
        val sized = url.replace(Regex("=w\\d+-h\\d+.*$"), "=w$COVER_SIZE-h$COVER_SIZE")
        val bytes = http.newCall(Request.Builder().url(sized).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = if (side > COVER_SIZE) Bitmap.createScaledBitmap(square, COVER_SIZE, COVER_SIZE, true) else square
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun writeTags(file: File, tags: YouTubeTrackTags, cover: ByteArray?) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        listOf(FieldKey.TITLE to tags.title, FieldKey.ARTIST to tags.artist, FieldKey.ALBUM to tags.album.ifBlank { tags.title }, FieldKey.YEAR to tags.year)
            .filter { it.second.isNotBlank() }.forEach { (key, value) -> runCatching { tag.setField(key, value) } }
        if (cover != null) runCatching {
            tag.setField(AndroidArtwork().apply {
                binaryData = cover
                mimeType = "image/jpeg"
                pictureType = PictureTypes.DEFAULT_ID
            })
        }
        audio.commit()
    }

    private fun publish(file: File, name: String, format: YouTubeDownloadFormat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            var target = File(directory, name)
            var copy = 1
            while (target.exists()) target = File(directory, "${name.substringBeforeLast('.')} (${copy++}).${format.extension}")
            file.copyTo(target)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(format.mimeType), null)
            return
        }
        val content = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = content.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the file")
        try {
            (content.openOutputStream(uri) ?: throw IOException("Could not open the file")).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            content.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { content.delete(uri, null, null) }
            throw e
        }
    }

    private fun setState(id: String, state: State) = _states.update { it + (id to state) }

    companion object {
        const val FOLDER = "Aurora"
        private const val TAG = "YouTubeDownloader"
        private const val CHUNK = 1_048_576L
        private const val SAMPLE_BUFFER = 1_048_576
        private const val COVER_SIZE = 800
    }
}
