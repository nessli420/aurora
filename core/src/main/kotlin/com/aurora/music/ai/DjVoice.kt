package com.aurora.music.ai

import com.aurora.music.model.Song
import com.aurora.music.util.NativeLibraries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

// speech uses the radio prefix so history, scrobbling and resume points already skip it
const val DJ_SPEECH_PREFIX = "radio:aurora-dj:"

fun Song.isDjSpeech(): Boolean = id.startsWith(DJ_SPEECH_PREFIX)

data class DjStats(
    val hoursThisWeek: Int,
    val topArtist: String?,
    val topTrack: String?,
    val topTrackPlays: Int,
    val artistsThisWeek: Int = 0,
    val streakDays: Int = 0,
    val newArtist: String? = null,
    val monthArtist: String? = null,
) {
    fun facts(): List<DjFact> = listOfNotNull(
        DjFact.HOURS.takeIf { hoursThisWeek >= 2 && topArtist != null },
        DjFact.TRACK.takeIf { topTrackPlays >= 3 && topTrack != null },
        DjFact.ARTISTS.takeIf { artistsThisWeek >= 5 },
        DjFact.STREAK.takeIf { streakDays >= 3 },
        DjFact.NEW_ARTIST.takeIf { newArtist != null },
        DjFact.MONTH_ARTIST.takeIf { monthArtist != null && monthArtist != topArtist },
    )
}

enum class DjFact { HOURS, TRACK, ARTISTS, STREAK, NEW_ARTIST, MONTH_ARTIST }

sealed interface DjMoment {
    val turn: Int
    val stats: DjStats? get() = null
    val fact: DjFact? get() = null
    data class Welcome(val set: DjSet, override val stats: DjStats?, override val fact: DjFact?, override val turn: Int) : DjMoment
    data class NextSet(val set: DjSet, override val stats: DjStats?, override val fact: DjFact?, override val turn: Int) : DjMoment
    data class Switch(val set: DjSet, override val turn: Int) : DjMoment
    data class Steered(val request: String, override val turn: Int) : DjMoment
    data class More(val request: String, override val turn: Int) : DjMoment
}

data class DjScript(val text: String, val title: String, val label: String)

data class DjAudio(val uri: String, val seconds: Int)

data class DjSpeech(val text: String, val stats: DjStats?)

interface DjVoice {
    fun script(moment: DjMoment): DjScript
    suspend fun synthesize(text: String, id: String): DjAudio?
    fun clear(keep: String? = null)
}

interface DjMic {
    fun start(): Boolean
    fun stop(): FloatArray
}

interface DjEars {
    fun start(): Boolean
    suspend fun stop(): String
}

fun wavSeconds(file: File): Int = runCatching {
    RandomAccessFile(file, "r").use { wav ->
        wav.seek(28)
        val rate = Integer.reverseBytes(wav.readInt())
        if (rate > 0) ((file.length() - 44) / rate).toInt().coerceAtLeast(1) else 0
    }
}.getOrDefault(0)

internal object WhisperNative {
    val available: Boolean by lazy { runCatching { NativeLibraries.load("aurora_llm") }.isSuccess }
    @JvmStatic external fun load(path: ByteArray): Long
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, threads: Int, language: ByteArray): ByteArray?
    @JvmStatic external fun free(handle: Long)
}

class LocalWhisper(private val model: () -> File?, private val threads: Int, private val idleMs: Long = 120_000) {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle = 0L
    private var unload: Job? = null

    suspend fun transcribe(pcm: FloatArray, language: String): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            unload?.cancel()
            if (handle == 0L) {
                val file = model() ?: return@withContext null
                if (!WhisperNative.available) return@withContext null
                handle = WhisperNative.load(file.absolutePath.toByteArray())
                if (handle == 0L) return@withContext null
            }
            try { WhisperNative.transcribe(handle, pcm, threads, language.toByteArray())?.toString(Charsets.UTF_8) }
            finally { unload = scope.launch { delay(idleMs); release() } }
        }
    }

    suspend fun release() {
        lock.withLock {
            val h = handle
            handle = 0L
            if (h != 0L) withContext(Dispatchers.IO) { WhisperNative.free(h) }
        }
    }
}

private val transcriptNoise = Regex("\\[[^\\]]*]|\\([^)]*\\)")
private val transcriptSpace = Regex("\\s+")
// what whisper tends to invent for a clip with nothing in it
private val transcriptFiller = setOf("you", "thank you", "thanks for watching", "bye")

// short clips can loop back to their first word, so keep one pass of a phrase that repeats
internal fun cleanTranscript(raw: String): String {
    val words = raw.replace(transcriptNoise, " ").trim().split(transcriptSpace)
    val keys = words.map { DjKeywords.norm(it) }
    val pass = (1 until keys.size).firstOrNull { n ->
        (n * 2 <= keys.size || words[n - 1].last() in ".!?") && keys.indices.all { keys[it] == keys[it % n] }
    } ?: keys.size
    val text = words.take(pass).joinToString(" ").trim('.', ',', '!', '?', ' ')
    return if (DjKeywords.norm(text) in transcriptFiller) "" else text
}

class WhisperEars(private val mic: DjMic, private val whisper: LocalWhisper, private val language: () -> String) : DjEars {
    @Volatile private var closing: CountDownLatch? = null

    // a press right after a release must not swap the recorder out from under the stop
    override fun start() = closing?.await(2, TimeUnit.SECONDS) != false && mic.start()

    override suspend fun stop(): String {
        val closed = CountDownLatch(1).also { closing = it }
        // not cancellable: a job cancelled before the switch would leave the mic open
        val (pcm, level) = withContext(NonCancellable + Dispatchers.IO) {
            val pcm = try { mic.stop() } finally { closed.countDown() }
            pcm to rms(pcm)
        }
        if (pcm.size < 8_000 || level < 0.002f) return ""
        val code = language()
        // short clips can fool language detection, so english keeps its fixed model path
        val text = try { whisper.transcribe(pcm, if (code == "en") "en" else "auto") }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return cleanTranscript(text.orEmpty())
    }

    private fun rms(pcm: FloatArray): Float {
        var sum = 0.0
        for (x in pcm) sum += (x * x).toDouble()
        return sqrt(sum / pcm.size).toFloat()
    }
}
