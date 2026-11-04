package com.aurora.music.ai

import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DjMode(
    private val engine: DjEngine,
    private val scope: CoroutineScope,
    private val voice: DjVoice? = null,
    private val ears: DjEars? = null,
    private val retryMs: Long = 20_000,
    private val startGraceMs: Long = 10_000,
) {
    data class State(
        val active: Boolean = false,
        val segment: DjSegment? = null,
        val artist: String? = null,
        val intent: DjIntent? = null,
        val prompt: String = "",
        val busy: Boolean = false,
        val failed: Boolean = false,
        val speech: DjSpeech? = null,
        val listening: Boolean = false,
        val thinking: Boolean = false,
        val missed: Boolean = false,
    )

    interface Queue {
        val current: Song?
        val upcoming: List<Song>
        val playing: Boolean
        fun append(songs: List<Song>)
        fun replaceUpcoming(songs: List<Song>)
        fun skipToNext()
        fun pause()
        fun play()
    }

    companion object {
        const val BATCH = 8
        // someone is waiting on these; a refill has minutes before its set is due
        private const val WAITING_MS = 30_000L
        private const val BACKGROUND_MS = 150_000L
        private val SWITCH_WORDS = listOf("switch it up", "change it up", "something else", "something different", "next", "skip")
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var session = DjSession()
    private val sets = HashMap<String, DjSet>()
    private val speeches = HashMap<String, DjSpeech>()
    private val placed = HashMap<String, Song>()
    private var retry: Job? = null
    private var grace: Job? = null
    private var starting = false
    private var resumeAfterListening = false
    private var job: Job? = null

    private fun remember(set: DjSet) = set.songs.forEach { sets[it.id] = set; placed[it.id] = it }

    // a screen opened after these were queued still gets the full track details
    fun known(id: String): Song? = placed[id]

    private fun forget() {
        retry?.cancel()
        grace?.cancel()
        sets.clear()
        speeches.clear()
        placed.clear()
    }

    // no voice or a failed synth just means the music starts without an intro
    private suspend fun speak(moment: DjMoment, set: DjSet? = null, patience: Long = WAITING_MS): Song? {
        val voice = voice ?: return null
        val script = voice.script(moment)
        val id = "$DJ_SPEECH_PREFIX${System.nanoTime()}"
        val began = System.currentTimeMillis()
        val audio = try { withTimeoutOrNull(patience) { voice.synthesize(script.text, id) } }
            catch (e: CancellationException) { throw e } catch (error: Exception) { AppLog.w("AiDj", "speech failed", error); null }
        AppLog.d("AiDj", "speech ${moment.javaClass.simpleName} ${System.currentTimeMillis() - began}ms ${audio?.let { "${it.seconds}s" } ?: "none"}")
        if (audio == null) return null
        session.placed += id
        speeches[id] = DjSpeech(script.text, moment.stats)
        set?.let { sets[id] = it }
        return Song(id, script.title, script.label, script.title, "", audio.seconds, streamUrl = audio.uri, accentArgb = accentArgbFor("aurora-dj")).also { placed[id] = it }
    }

    private fun work(thinking: Boolean = false, block: suspend () -> Boolean) {
        job?.cancel()
        retry?.cancel()
        _state.update { it.copy(busy = true, failed = false, thinking = thinking, missed = false) }
        job = scope.launch {
            try {
                val ok = block()
                _state.update { it.copy(busy = false, failed = !ok, thinking = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _state.update { it.copy(busy = false, failed = true, thinking = false) }
            }
        }
    }

    fun start(queue: Queue, play: (List<Song>) -> Unit, onResult: (Boolean) -> Unit) {
        job?.cancel()
        session = engine.newSession()
        forget()
        starting = true
        _state.value = State(active = true, busy = true)
        job = scope.launch {
            val set = try {
                engine.nextSet(session, queue.current)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (set == null) {
                starting = false
                _state.value = State()
                onResult(false)
                return@launch
            }
            remember(set)
            val stats = if (voice == null) null else engine.stats()
            val fact = engine.memory.nextFact(stats)
            val intro = speak(DjMoment.Welcome(set, stats.takeIf { fact != null }, fact, engine.memory.nextTurn()), set)
            _state.update { it.copy(segment = set.segment, artist = set.artist, busy = false) }
            play(listOfNotNull(intro) + set.songs)
            // the player can still report on the old queue for a moment, so starting ends when it reports this one
            grace = scope.launch { delay(startGraceMs); if (starting) stop() }
            // only now, the old queue may still be about to play one of its lines
            voice?.clear(intro?.id)
            onResult(true)
        }
    }

    fun stop() {
        job?.cancel()
        starting = false
        forget()
        if (_state.value.listening) scope.launch { runCatching { ears?.stop() } }
        _state.value = State()
    }

    // moves straight on to the dj's next set
    fun switchUp(queue: Queue) {
        if (!_state.value.active) return
        _state.update { it.copy(intent = null, prompt = "") }
        work(thinking = true) { runSwitch(queue) }
    }

    private suspend fun runSwitch(queue: Queue): Boolean {
        val set = engine.nextSet(session, queue.current) ?: return false
        remember(set)
        val line = speak(DjMoment.Switch(set, engine.memory.nextTurn()), set)
        queue.replaceUpcoming(listOfNotNull(line) + set.songs)
        queue.skipToNext()
        queue.play()
        return true
    }

    fun steer(text: String, queue: Queue) {
        if (text.isBlank() || !_state.value.active) return
        work(thinking = true) { runSteer(text, queue) }
    }

    private suspend fun runSteer(text: String, queue: Queue): Boolean {
        val next = engine.interpret(text, _state.value.intent ?: DjIntent())
        val songs = engine.pick(next.intent, BATCH, session.placed.toSet(), queue.current)
        session.mark(songs)
        songs.forEach { placed[it.id] = it }
        _state.update { it.copy(intent = next.intent, prompt = text.trim()) }
        if (songs.isEmpty()) return false
        val line = speak(DjMoment.Steered(text.trim(), engine.memory.nextTurn()))
        queue.replaceUpcoming(listOfNotNull(line) + songs)
        queue.skipToNext()
        queue.play()
        return true
    }

    // hold on the player button: the music waits while the mic is open
    fun startListening(queue: Queue): Boolean {
        val ears = ears ?: return false
        if (!_state.value.active || !ears.start()) return false
        resumeAfterListening = queue.playing
        if (resumeAfterListening) queue.pause()
        _state.update { it.copy(listening = true, missed = false) }
        return true
    }

    fun stopListening(queue: Queue) {
        val ears = ears ?: return
        if (!_state.value.listening) return
        _state.update { it.copy(listening = false) }
        work(thinking = true) {
            val text = ears.stop()
            val words = DjKeywords.norm(text)
            val ok = when {
                text.isBlank() -> { _state.update { it.copy(missed = true) }; true }
                words.split(' ').size <= 5 && SWITCH_WORDS.any { " $words ".contains(" $it ") } -> {
                    _state.update { it.copy(intent = null, prompt = "") }
                    runSwitch(queue)
                }
                else -> runSteer(text, queue)
            }
            if (resumeAfterListening && !queue.playing) queue.play()
            ok
        }
    }

    // call on every queue or track change, refills before the set runs dry
    fun onQueueChanged(queue: Queue) {
        val s = _state.value
        if (!s.active) return
        val current = queue.current ?: run { if (!starting) stop(); return }
        if (starting) {
            if (current.id !in session.placed) return
            starting = false
            grace?.cancel()
        }
        val upcoming = queue.upcoming
        if (current.id !in session.placed && upcoming.none { it.id in session.placed }) { stop(); return }
        val speech = speeches[current.id]
        if (speech != s.speech) _state.update { it.copy(speech = speech) }
        sets[current.id]?.let { set ->
            if (set.segment != s.segment || set.artist != s.artist) _state.update { it.copy(segment = set.segment, artist = set.artist) }
        }
        // generating the next line while this one plays is what makes it stutter, the next track is soon enough
        if (upcoming.size > 2 || job?.isActive == true || current.isDjSpeech()) return
        val last = upcoming.lastOrNull { !it.isDjSpeech() } ?: current
        work {
            val songs = s.intent?.let { intent ->
                val steady = intent.copy(energyStart = intent.energyEnd)
                val picked = engine.pick(steady, BATCH, session.placed + upcoming.map { it.id }, last).also(session::mark)
                picked.forEach { placed[it.id] = it }
                val line = if (picked.isEmpty() || s.prompt.isBlank()) null else speak(DjMoment.More(s.prompt, engine.memory.nextTurn()), patience = BACKGROUND_MS)
                listOfNotNull(line) + picked
            } ?: engine.nextSet(session, last)?.let { set ->
                remember(set)
                val stats = if (voice == null) null else engine.stats()
                val fact = engine.memory.nextFact(stats)
                val line = speak(DjMoment.NextSet(set, stats.takeIf { fact != null }, fact, engine.memory.nextTurn()), set, BACKGROUND_MS)
                listOfNotNull(line) + set.songs
            }.orEmpty()
            if (songs.isNotEmpty() && _state.value.active) queue.append(songs)
            // no network right now shouldn't leave the queue to run out, there may be no track change to try again on
            if (songs.isEmpty()) retry = scope.launch { delay(retryMs); onQueueChanged(queue) }
            songs.isNotEmpty()
        }
    }
}
