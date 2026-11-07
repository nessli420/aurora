package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// podcasts and anything as long as an audiobook chapter are listened to in sittings
object LongForm {
    const val MIN_SEC = 20 * 60
    const val SKIP_SEC = 10

    fun applies(id: String, durationSec: Int): Boolean =
        id.startsWith("podcast:") || (durationSec >= MIN_SEC && !id.startsWith("radio:") && !id.startsWith("aurora-mix:"))

    // chapters split into short files still need scrubbing so spoken word tags count at any length
    fun skips(id: String, durationSec: Int, genre: String): Boolean =
        applies(id, durationSec) || (spoken(genre) && !id.startsWith("radio:") && !id.startsWith("aurora-mix:"))

    fun spoken(genre: String): Boolean = GenreText.split(genre).any { g ->
        val norm = g.lowercase().replace(Regex("[^\\p{L}]+"), " ").trim()
        SPOKEN.any { norm == it || norm.startsWith("$it ") || norm.endsWith(" $it") }
    }

    private val SPOKEN = listOf(
        "audiobook", "audiobooks", "audio book", "audio books", "spoken word", "podcast", "podcasts",
        "speech", "books spoken", "hörbuch", "hörbücher", "hörspiel", "livre audio", "livres audio", "audiolibro",
        "audiolibros", "audiolivro", "audiolibri", "luisterboek", "ljudbok", "lydbog", "lydbok", "äänikirja",
        "аудиокнига", "аудиокниги", "sesli kitap", "kitab audio", "audiobuku",
    )

    fun resumes(id: String, durationSec: Int, prefs: PlaybackPrefs): Boolean = when {
        id.startsWith("podcast:") -> prefs.resumePodcasts
        else -> prefs.resumeLongTracks && applies(id, durationSec)
    }
}

data class ResumePoint(val positionSec: Int = 0, val savedAtMs: Long = 0)

// where each episode or long track was left; finished ones and barely started ones are forgotten
class ResumePoints(dir: File, private val persist: (File, ByteArray) -> Unit = ::persistBackupFileAtomically) {
    private val file = File(dir, "resume_points.json")
    private val gson = Gson()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val points: MutableMap<String, ResumePoint> = load()
    private var pendingFlush: Job? = null
    @Volatile private var dirty = false

    private fun load(): MutableMap<String, ResumePoint> = runCatching {
        if (!file.exists()) HashMap()
        else gson.fromJson<MutableMap<String, ResumePoint>>(file.readText(), object : TypeToken<MutableMap<String, ResumePoint>>() {}.type) ?: HashMap()
    }.getOrDefault(HashMap())

    fun positionSec(id: String): Int? = synchronized(lock) { points[id]?.positionSec }

    fun record(id: String, positionSec: Int, durationSec: Int, now: Long = System.currentTimeMillis()) {
        if (id.isBlank()) return
        val done = positionSec < START_SEC || (durationSec > 0 && durationSec - positionSec < END_SEC)
        synchronized(lock) {
            val changed = if (done) points.remove(id) != null
            else points.put(id, ResumePoint(positionSec, now))?.positionSec != positionSec
            if (!changed) return
            if (points.size > MAX_POINTS) points.entries.sortedBy { it.value.savedAtMs }.take(points.size - MAX_POINTS).forEach { points.remove(it.key) }
            dirty = true
            if (pendingFlush?.isActive != true) pendingFlush = scope.launch { delay(2000); flushNow() }
        }
    }

    fun flushNow() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            HashMap(points)
        }
        runCatching { persist(file, gson.toJson(snapshot).toByteArray(Charsets.UTF_8)) }.onFailure { dirty = true }
    }

    private companion object {
        const val START_SEC = 15
        const val END_SEC = 30
        const val MAX_POINTS = 500
    }
}
