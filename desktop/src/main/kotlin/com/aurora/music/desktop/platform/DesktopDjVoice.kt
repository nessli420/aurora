package com.aurora.music.desktop.platform

import com.aurora.music.ai.DjAudio
import com.aurora.music.ai.DjMic
import com.aurora.music.ai.DjMoment
import com.aurora.music.ai.DjScript
import com.aurora.music.ai.DjSpeaker
import com.aurora.music.ai.DjVoice
import com.aurora.music.ai.wavSeconds
import com.aurora.music.desktop.natives.SpeechNative
import com.aurora.music.localization.AppStrings
import com.aurora.music.ui.screens.aidj.aiDjScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

// the downloaded voice, otherwise windows' own sapi voices or espeak-ng on linux; nothing leaves the machine
class DesktopDjVoice(cache: File, private val speaker: DjSpeaker, private val spoken: suspend () -> Boolean) : DjVoice {
    private val dir = File(cache, "dj-speech")

    override fun script(moment: DjMoment): DjScript = aiDjScript(moment)

    private fun fileFor(id: String) = File(dir, "${id.hashCode().toUInt()}.wav")

    override fun clear(keep: String?) { dir.listFiles()?.forEach { if (keep == null || it != fileFor(keep)) it.delete() } }

    override suspend fun synthesize(text: String, id: String): DjAudio? = withContext(Dispatchers.IO) {
        if (!spoken()) return@withContext null
        dir.mkdirs()
        val file = fileFor(id)
        val ok = speaker.speak(text, AppStrings.locale.language, file) ||
            when {
                HostPlatform.isWindows -> SpeechNative.toWave(text, file.path)
                HostPlatform.isMac -> say(text, file)
                else -> espeak(text, file)
            }
        if (ok && file.length() > 44) DjAudio(desktopFileUri(file.path), wavSeconds(file)) else null
    }

    // the system voice, written as 16-bit wave so the player reads it like the other platforms' files
    private fun say(text: String, file: File): Boolean = runCatching {
        val process = ProcessBuilder("/usr/bin/say", "-o", file.path, "--file-format=WAVE", "--data-format=LEI16@22050", "--", text)
            .redirectErrorStream(true).start()
        process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun espeak(text: String, file: File): Boolean = runCatching {
        val process = ProcessBuilder("espeak-ng", "-w", file.path, text).redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)
}

class DesktopDjMic : DjMic {
    private var line: TargetDataLine? = null
    private var reader: Thread? = null
    @Volatile private var captured = FloatArray(0)

    override fun start(): Boolean {
        val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        val mic = runCatching { AudioSystem.getTargetDataLine(format).apply { open(format); start() } }.getOrNull() ?: return false
        line = mic
        captured = FloatArray(0)
        reader = thread(name = "dj-mic", isDaemon = true) {
            val all = FloatArray(RATE * MAX_SECONDS)
            val chunk = ByteArray(RATE / 5)
            var filled = 0
            while (line === mic && filled < all.size) {
                val read = mic.read(chunk, 0, minOf(chunk.size, (all.size - filled) * 2))
                if (read <= 0) break
                for (i in 0 until read / 2) {
                    all[filled + i] = ((chunk[i * 2 + 1].toInt() shl 8) or (chunk[i * 2].toInt() and 0xFF)) / 32768f
                }
                filled += read / 2
            }
            captured = all.copyOf(filled)
        }
        return true
    }

    override fun stop(): FloatArray {
        val mic = line ?: return FloatArray(0)
        line = null
        runCatching { mic.stop(); mic.close() }
        reader?.join(1000)
        return captured
    }

    private companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 30
    }
}
