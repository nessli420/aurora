package com.aurora.music.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aurora.music.ai.DjAudio
import com.aurora.music.ai.DjMic
import com.aurora.music.ai.DjMoment
import com.aurora.music.ai.DjScript
import com.aurora.music.ai.DjSpeaker
import com.aurora.music.ai.DjVoice
import com.aurora.music.ai.wavSeconds
import com.aurora.music.localization.AppStrings
import com.aurora.music.ui.screens.aidj.aiDjScript
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.resume

// speech is written to a file so it takes the same audio path as the music. the downloaded voice
// wins, otherwise the system's own
class AiDjSystemVoice(private val context: Context, private val speaker: DjSpeaker, private val spoken: suspend () -> Boolean) : DjVoice {
    private val dir = File(context.cacheDir, "dj-speech")
    private val lock = Mutex()
    private var engine: TextToSpeech? = null

    override fun script(moment: DjMoment): DjScript = aiDjScript(moment)

    private fun fileFor(id: String) = File(dir, "${id.hashCode().toUInt()}.wav")

    override fun clear(keep: String?) { dir.listFiles()?.forEach { if (keep == null || it != fileFor(keep)) it.delete() } }

    override suspend fun synthesize(text: String, id: String): DjAudio? = lock.withLock {
        if (!spoken()) return null
        dir.mkdirs()
        val file = fileFor(id)
        if (speaker.speak(text, AppStrings.context.resources.configuration.locales[0].language, file)) return DjAudio(Uri.fromFile(file).toString(), wavSeconds(file))
        val tts = engine ?: open()?.also { engine = it } ?: return null
        val done = suspendCancellableCoroutine { result ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (result.isActive) result.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (result.isActive) result.resume(false) }
            })
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS && result.isActive) result.resume(false)
        }
        if (done && file.length() > 44) DjAudio(Uri.fromFile(file).toString(), wavSeconds(file)) else null
    }

    private suspend fun open(): TextToSpeech? = suspendCancellableCoroutine { result ->
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context.applicationContext) { status ->
            val ready = status == TextToSpeech.SUCCESS && local(tts)
            if (!ready) tts.shutdown()
            if (result.isActive) result.resume(if (ready) tts else null)
        }
    }

    // only installed voices for the app's language, so nothing leaves the device
    private fun local(tts: TextToSpeech): Boolean {
        val locale = AppStrings.context.resources.configuration.locales[0]
        val voice = tts.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.maxByOrNull { (if (it.locale.country == locale.country) 1000 else 0) + it.quality } ?: return false
        return tts.setVoice(voice) == TextToSpeech.SUCCESS
    }
}

class AiDjMicrophone : DjMic {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var captured = FloatArray(0)

    // the caller checks RECORD_AUDIO before holding the button opens this
    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        val size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(RATE)
        val mic = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull() ?: return false
        if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); return false }
        mic.startRecording()
        record = mic
        captured = FloatArray(0)
        reader = thread(name = "dj-mic") {
            val all = FloatArray(RATE * MAX_SECONDS)
            val chunk = ShortArray(RATE / 10)
            var filled = 0
            while (record === mic && filled < all.size) {
                val read = mic.read(chunk, 0, minOf(chunk.size, all.size - filled))
                if (read <= 0) break
                for (i in 0 until read) all[filled + i] = chunk[i] / 32768f
                filled += read
            }
            captured = all.copyOf(filled)
        }
        return true
    }

    override fun stop(): FloatArray {
        val mic = record ?: return FloatArray(0)
        record = null
        runCatching { mic.stop() }
        reader?.join(1000)
        mic.release()
        return captured
    }

    private companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 30
    }
}
