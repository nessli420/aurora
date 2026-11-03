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
        val intent: DjIntent? = null,
        val prompt: String = "",
        val busy: Boolean = false,
        val usedModel: Boolean = false,
        val failed: Boolean = false,
    )

    interface Queue {
        val current: Song?
        val upcoming: List<Song>
        fun append(songs: List<Song>)
        fun replaceUpcoming(songs: List<Song>)
    }

    companion object { const val BATCH = 8 }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val placed = LinkedHashSet<String>()
    private var job: Job? = null

    fun start(prompt: String, intent: DjIntent, usedModel: Boolean, songs: List<Song>) {
        job?.cancel()
        placed.clear()
        songs.mapTo(placed) { it.id }
        _state.value = State(active = true, intent = intent, prompt = prompt, usedModel = usedModel)
    }

    fun stop() {
        job?.cancel()
        placed.clear()
        _state.value = State()
    }

    fun steer(text: String, queue: Queue) {
        val intent = _state.value.intent ?: return
        if (text.isBlank()) return
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(busy = true, failed = false) }
            try {
                val next = engine.interpret(text, intent)
                val songs = engine.pick(next.intent, BATCH, placed.toSet(), queue.current)
                if (songs.isNotEmpty()) {
                    songs.mapTo(placed) { it.id }
                    queue.replaceUpcoming(songs)
                }
                _state.update { it.copy(intent = next.intent, prompt = text.trim(), usedModel = next.usedModel, busy = false, failed = songs.isEmpty()) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    // call on every queue or track change, refills before the set runs dry
    fun onQueueChanged(queue: Queue) {
        val s = _state.value
        val intent = s.intent ?: return
        if (!s.active) return
        val current = queue.current ?: return
        val upcoming = queue.upcoming
        if (current.id !in placed && upcoming.none { it.id in placed }) { stop(); return }
        if (upcoming.size > 2 || job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true, failed = false) }
            try {
                val steady = intent.copy(energyStart = intent.energyEnd)
                val songs = engine.pick(steady, BATCH, placed.toSet() + upcoming.map { it.id }, upcoming.lastOrNull() ?: current)
                songs.mapTo(placed) { it.id }
                if (songs.isNotEmpty() && _state.value.active) queue.append(songs)
                _state.update { it.copy(busy = false, failed = songs.isEmpty()) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }
}
