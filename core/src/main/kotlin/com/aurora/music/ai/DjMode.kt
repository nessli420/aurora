package com.aurora.music.ai

import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DjMode(private val engine: DjEngine, private val scope: CoroutineScope) {
    data class State(
        val active: Boolean = false,
        val segment: DjSegment? = null,
        val artist: String? = null,
        val intent: DjIntent? = null,
        val prompt: String = "",
        val busy: Boolean = false,
        val failed: Boolean = false,
    )

    interface Queue {
        val current: Song?
        val upcoming: List<Song>
        fun append(songs: List<Song>)
        fun replaceUpcoming(songs: List<Song>)
        fun skipToNext()
    }

    companion object { const val BATCH = 8 }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var session = DjSession()
    private val sets = HashMap<String, DjSet>()
    private var starting = false
    private var job: Job? = null

    private fun remember(set: DjSet) = set.songs.forEach { sets[it.id] = set }

    private fun work(block: suspend () -> Boolean) {
        job?.cancel()
        _state.update { it.copy(busy = true, failed = false) }
        job = scope.launch {
            try {
                val ok = block()
                _state.update { it.copy(busy = false, failed = !ok) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    fun start(queue: Queue, play: (List<Song>) -> Unit, onResult: (Boolean) -> Unit) {
        job?.cancel()
        session = DjSession()
        sets.clear()
        starting = true
        _state.value = State(active = true, busy = true)
        job = scope.launch {
            val set = try { engine.nextSet(session, queue.current) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            starting = false
            if (set == null) {
                _state.value = State()
                onResult(false)
                return@launch
            }
            remember(set)
            _state.update { it.copy(segment = set.segment, artist = set.artist, busy = false) }
            play(set.songs)
            onResult(true)
        }
    }

    fun stop() {
        job?.cancel()
        starting = false
        sets.clear()
        _state.value = State()
    }

    // moves straight on to the dj's next set
    fun switchUp(queue: Queue) {
        if (!_state.value.active) return
        _state.update { it.copy(intent = null, prompt = "") }
        work {
            val set = engine.nextSet(session, queue.current) ?: return@work false
            remember(set)
            queue.replaceUpcoming(set.songs)
            queue.skipToNext()
            true
        }
    }

    fun steer(text: String, queue: Queue) {
        if (text.isBlank() || !_state.value.active) return
        val intent = _state.value.intent ?: DjIntent()
        work {
            val next = engine.interpret(text, intent)
            val songs = engine.pick(next.intent, BATCH, session.placed.toSet(), queue.current)
            session.mark(songs)
            if (songs.isNotEmpty()) queue.replaceUpcoming(songs)
            _state.update { it.copy(intent = next.intent, prompt = text.trim()) }
            songs.isNotEmpty()
        }
    }

    // call on every queue or track change, refills before the set runs dry
    fun onQueueChanged(queue: Queue) {
        val s = _state.value
        if (!s.active || starting) return
        val current = queue.current ?: return
        val upcoming = queue.upcoming
        if (current.id !in session.placed && upcoming.none { it.id in session.placed }) { stop(); return }
        sets[current.id]?.let { set ->
            if (set.segment != s.segment || set.artist != s.artist) _state.update { it.copy(segment = set.segment, artist = set.artist) }
        }
        if (upcoming.size > 2 || job?.isActive == true) return
        val last = upcoming.lastOrNull() ?: current
        work {
            val songs = s.intent?.let { intent ->
                val steady = intent.copy(energyStart = intent.energyEnd)
                engine.pick(steady, BATCH, session.placed + upcoming.map { it.id }, last).also(session::mark)
            } ?: engine.nextSet(session, last)?.also(::remember)?.songs.orEmpty()
            if (songs.isNotEmpty() && _state.value.active) queue.append(songs)
            songs.isNotEmpty()
        }
    }
}
