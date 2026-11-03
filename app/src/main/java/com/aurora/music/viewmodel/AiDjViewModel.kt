package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.ai.DjEngine
import com.aurora.music.ai.DjIntent
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiDjViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container

    enum class Phase { IDLE, THINKING, PICKING, DONE }

    data class UiState(
        val prompt: String = "",
        val phase: Phase = Phase.IDLE,
        val intent: DjIntent? = null,
        val songs: List<Song> = emptyList(),
        val usedModel: Boolean = false,
        val thinkingMs: Long = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val model = container.aiDjModels.state
    val unsupported: String? get() = container.aiDjUnsupported
    private var job: Job? = null

    init { viewModelScope.launch { runCatching { container.aiDj.vocabulary() } } }

    fun setPrompt(text: String) = _state.update { it.copy(prompt = text) }

    fun downloadModel() = container.downloadAiDjModel()

    fun cancelDownload() = container.aiDjModels.cancel()

    // an empty prompt with a seed keeps going from the song that's playing
    fun generate(seed: Song? = null, count: Int? = null, then: (UiState) -> Unit = {}) {
        val text = _state.value.prompt.trim()
        if (text.isEmpty() && seed == null) return
        job?.cancel()
        job = viewModelScope.launch {
            val start = System.currentTimeMillis()
            _state.update { it.copy(phase = Phase.THINKING, songs = emptyList(), intent = null) }
            try {
                val engine = container.aiDj
                val read = if (text.isNotEmpty()) engine.interpret(text)
                    else DjEngine.Interpretation(DjIntent(title = seed!!.artist, artists = listOf(seed.artist), genres = listOf(seed.genre).filter { it.isNotBlank() }), false)
                val thinking = System.currentTimeMillis() - start
                _state.update { it.copy(phase = Phase.PICKING, intent = read.intent, usedModel = read.usedModel, thinkingMs = thinking) }
                val songs = engine.pick(read.intent, count ?: read.intent.count, after = seed)
                AppLog.d("AiDj", "model=${read.usedModel} think=${thinking}ms pick=${System.currentTimeMillis() - start - thinking}ms songs=${songs.size} ${read.timings ?: ""}")
                val done = _state.value.copy(phase = Phase.DONE, songs = songs)
                _state.value = done
                then(done)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                AppLog.w("AiDj", "generate failed: ${e.message}")
                _state.update { it.copy(phase = Phase.DONE, songs = emptyList()) }
            }
        }
    }

    fun save(name: String, onResult: (String) -> Unit) {
        val title = name.trim()
        val ids = _state.value.songs.map { it.id }
        if (title.isEmpty() || ids.isEmpty()) return
        viewModelScope.launch {
            val ok = try { container.repository.createPlaylistFromSongs(title, ids) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            onResult(if (ok) appString(R.string.text_saved_bac2cc, title) else appString(R.string.text_couldn_t_save_playlist_d7a0f6))
        }
    }
}
