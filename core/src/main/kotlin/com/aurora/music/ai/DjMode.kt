package com.aurora.music.ai

import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        private val SWITCH_WORDS = listOf("switch it up", "change it up", "something else", "something different", "next", "skip")
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var session = DjSession()
    private val sets = HashMap<String, DjSet>()
    private val speeches = HashMap<String, DjSpeech>()
    private var starting = false
    private var resumeAfterListening = false
    private var job: Job? = null

    private fun remember(set: DjSet) = set.songs.forEach { sets[it.id] = set }

    // no voice or a failed synth just means the music starts without an intro
    private suspend fun speak(moment: DjMoment, set: DjSet? = null): Song? {
        val voice = voice ?: return null
        val script = voice.script(moment)
        val id = "$DJ_SPEECH_PREFIX${System.nanoTime()}"
        val audio = try { withTimeoutOrNull(30_000) { voice.synthesize(script.text, id) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        session.placed += id
        speeches[id] = DjSpeech(script.text, moment.stats)
        set?.let { sets[id] = it }
        return Song(id, script.title, script.label, script.title, "", audio.seconds, streamUrl = audio.uri, accentArgb = accentArgbFor("aurora-dj"))
    }

    private fun work(thinking: Boolean = false, block: suspend () -> Boolean) {
        job?.cancel()
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
        session = DjSession()
        sets.clear()
        speeches.clear()
        starting = true
        _state.value = State(active = true, busy = true)
        job = scope.launch {
            val set = try {
                voice?.clear()
                engine.nextSet(session, queue.current)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (set == null) {
                starting = false
                _state.value = State()
                onResult(false)
                return@launch
            }
            remember(set)
            val intro = speak(DjMoment.Welcome(set, engine.stats()), set)
            starting = false
            _state.update { it.copy(segment = set.segment, artist = set.artist, busy = false) }
            play(listOfNotNull(intro) + set.songs)
            onResult(true)
        }
    }

    fun stop() {
        job?.cancel()
        starting = false
        sets.clear()
        speeches.clear()
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
        val line = speak(DjMoment.Switch(set), set)
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
        _state.update { it.copy(intent = next.intent, prompt = text.trim()) }
        if (songs.isEmpty()) return false
        val line = speak(DjMoment.Steered(text.trim()))
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
        if (!s.active || starting) return
        val current = queue.current ?: return
        val upcoming = queue.upcoming
        if (current.id !in session.placed && upcoming.none { it.id in session.placed }) { stop(); return }
        val speech = speeches[current.id]
        if (speech != s.speech) _state.update { it.copy(speech = speech) }
        sets[current.id]?.let { set ->
            if (set.segment != s.segment || set.artist != s.artist) _state.update { it.copy(segment = set.segment, artist = set.artist) }
        }
        if (upcoming.size > 2 || job?.isActive == true) return
        val last = upcoming.lastOrNull { !it.isDjSpeech() } ?: current
        work {
            val songs = s.intent?.let { intent ->
                val steady = intent.copy(energyStart = intent.energyEnd)
                engine.pick(steady, BATCH, session.placed + upcoming.map { it.id }, last).also(session::mark)
            } ?: engine.nextSet(session, last)?.let { set ->
                remember(set)
                val line = speak(DjMoment.NextSet(set, if (session.next % 3 == 0) engine.stats() else null), set)
                listOfNotNull(line) + set.songs
            }.orEmpty()
            if (songs.isNotEmpty() && _state.value.active) queue.append(songs)
            songs.isNotEmpty()
        }
    }
}
