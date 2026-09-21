package com.aurora.music.viewmodel

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.app.Application
import android.content.Intent
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.aurora.music.AuroraApplication
import com.aurora.music.data.accountKey
import com.aurora.music.mix.*
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

data class MixUiState(
    val project: MixProject = MixProject(), val selectedId: String = "", val saved: List<MixProject> = emptyList(),
    val songs: List<Song> = emptyList(), val query: String = "", val searching: Boolean = false,
    val analyses: Map<String, MixAnalysis> = emptyMap(), val analyzing: String = "", val analysisProgress: Float = 0f,
    val message: String? = null, val busy: Boolean = false, val dirty: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val building: Boolean = false, val buildDone: Int = 0, val buildTotal: Int = 0, val buildLabel: String = "",
    val tempoMatch: Boolean = true,
    val separating: String = "", val separationProgress: Float = 0f, val separationLabel: String = "",
    val modelReady: Boolean = false,
)

@OptIn(UnstableApi::class)
class MixViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(MixUiState())
    val state = _state.asStateFlow()
    val playback = container.mixController.state.asStateFlow()
    private val undo = ArrayDeque<MixProject>()
    private val redo = ArrayDeque<MixProject>()
    private var searchJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisRequest = 0L
    private var autoJob: Job? = null
    private var stemJob: Job? = null
    private var stemRequest = 0L
    private var lastEditKey: String? = null
    private var lastEditMs = 0L
    private suspend fun account() = container.settingsStore.session.first()?.accountKey().orEmpty()

    init {
        container.mixController.activeProject?.let { active ->
            _state.value = MixUiState(project = active, selectedId = active.clips.firstOrNull()?.id.orEmpty())
            viewModelScope.launch {
                val key = account()
                for (clip in active.clips) container.mixAnalyzer.cached(key, clip.song)?.let { analysis ->
                    _state.update { it.copy(analyses = it.analyses + (clip.song.id to analysis)) }
                }
            }
        }
        refreshSaved(); search("")
        _state.update { it.copy(modelReady = container.stemSeparator.modelReady()) }
    }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun select(id: String) { _state.update { it.copy(selectedId = id) } }
    fun edit(coalesceKey: String? = null, transform: (MixProject) -> MixProject) {
        if (state.value.building) return
        val old = state.value.project
        val next = transform(old).normalized()
        if (AutoMixPlanner.maxLayers(next) > MixProject.MAX_LAYERS) {
            _state.update { it.copy(message = appString(R.string.text_up_to_eight_tracks_can_overlap_at_once_move_this_track_later_in_t_5644e4)) }; return
        }
        if (next == old) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (coalesceKey == null || coalesceKey != lastEditKey || now - lastEditMs > 350) {
            undo.addLast(old); if (undo.size > 60) undo.removeFirst()
        }
        lastEditKey = coalesceKey; lastEditMs = now; redo.clear()
        apply(next)
    }
    private fun apply(project: MixProject) {
        val current = state.value.project
        if (project.clips.map { it.id } != current.clips.map { it.id }) stop()
        _state.update { it.copy(project = project, dirty = true, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        container.mixController.commands.tryEmit(MixCommand.Update(project))
    }
    fun undo() { if (!state.value.building && undo.isNotEmpty()) { redo.addLast(state.value.project); apply(undo.removeLast()) } }
    fun redo() { if (!state.value.building && redo.isNotEmpty()) { undo.addLast(state.value.project); apply(redo.removeLast()) } }
    fun updateClip(clip: MixClip) = edit(coalesceKey = "clip:${clip.id}") { it.copy(clips = it.clips.map { c -> if (c.id == clip.id) clip else c }) }
    fun remove(id: String) = edit { it.copy(clips = it.clips.filter { c -> c.id != id }) }
    fun add(song: Song, layered: Boolean = false) {
        if (state.value.project.clips.size >= MixProject.MAX_TRACKS) { _state.update { it.copy(message = appString(R.string.text_this_mix_has_reached_10_000_tracks_115af4)) }; return }
        if (song.durationSec <= 0 || song.streamUrl.isBlank()) { _state.update { it.copy(message = appString(R.string.text_choose_a_song_with_a_known_duration_and_playable_source_ff433d)) }; return }
        val previous = state.value.project.clips.lastOrNull()
        val overlap = min(8f, min(previous?.durationSec ?: 8f, song.durationSec / 2f))
        val clip = MixClip(song = song, startSec = if (layered) 0f else ((previous?.endSec ?: overlap) - overlap).coerceAtLeast(0f),
            fadeInSec = if (previous == null) 0f else overlap)
        edit { it.copy(clips = it.clips + clip) }
        select(clip.id)
        viewModelScope.launch { container.mixAnalyzer.cached(account(), song)?.let { analysis ->
            _state.update { it.copy(analyses = it.analyses + (song.id to analysis)) }
        } }
    }
    fun seed(songs: List<Song>) { if (state.value.project.clips.isEmpty()) songs.take(MixProject.MAX_TRACKS).forEach { add(it) } }
    fun setTempoMatch(enabled: Boolean) { _state.update { it.copy(tempoMatch = enabled) } }
    fun mixCollection(kind: String, id: String, name: String, playInPlayer: Boolean = false, onReady: () -> Unit = {}) {
        val previous = autoJob
        previous?.cancel()
        cancelSeparation()
        autoJob = viewModelScope.launch {
            previous?.join()
            _state.update { it.copy(building = true, buildLabel = appString(R.string.text_loading_complete_tracklist_d6abc4), buildDone = 0, buildTotal = 0) }
            try {
                val songs = container.repository.collectionTracks(kind, id)
                check(songs.size >= 2) { appString(R.string.text_this_collection_needs_at_least_two_tracks_to_mix_142509) }
                check(songs.size <= MixProject.MAX_TRACKS) { appString(R.string.text_this_collection_exceeds_the_10_000_track_mix_limit_b8885f) }
                check(songs.all { it.durationSec > 0 && it.streamUrl.isNotBlank() }) { appString(R.string.text_some_tracks_are_unavailable_reconnect_to_the_library_and_retry_6b688e) }
                if (state.value.dirty && state.value.project.clips.isNotEmpty()) container.mixStore.save(account(), state.value.project)
                stop(); cancelAnalysis()
                // A play action must not wait for a whole collection to decode. Reuse Sonic's
                // durable measurements and use smooth fades for songs not analyzed yet.
                val cached = mutableMapOf<String, MixAnalysis>()
                val owner = account()
                if (playInPlayer) for (song in songs.distinctBy { it.id }) {
                    container.mixAnalyzer.cached(owner, song)?.let { cached[song.id] = it }
                }
                val draft = AutoMixPlanner.plan(appString(R.string.text_mix_ba4dbe, (name)), songs, cached, state.value.tempoMatch)
                undo.clear(); redo.clear()
                _state.update { it.copy(project = draft, selectedId = draft.clips.first().id, analyses = cached, dirty = true, canUndo = false, canRedo = false) }
                if (playInPlayer) {
                    _state.update { it.copy(building = false) }
                    play(tracklist = true)
                    onReady()
                } else buildTransitions()
                refreshSaved()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: appString(R.string.text_could_not_build_this_mix_b76def)) } }
            finally { _state.update { it.copy(building = false) } }
        }
    }
    fun autoMix() {
        if (state.value.building || state.value.project.clips.size < 2) return
        autoJob = viewModelScope.launch {
            _state.update { it.copy(building = true) }
            try { stop(); cancelAnalysis(); buildTransitions() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: appString(R.string.text_could_not_build_transitions_e78754)) } }
            finally { _state.update { it.copy(building = false) } }
        }
    }
    private suspend fun buildTransitions() {
        val owner = account()
        val original = state.value.project
        val songs = original.clips.map { it.song }
        val unique = songs.distinctBy { it.id }
        var failures = 0
        _state.update { it.copy(buildTotal = unique.size, buildDone = 0) }
        for ((index, song) in unique.withIndex()) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(buildLabel = song.title) }
            try {
                val analysis = container.mixAnalyzer.analyze(owner, song) { progress ->
                    _state.update { it.copy(analysisProgress = progress) }
                }
                _state.update { it.copy(analyses = it.analyses + (song.id to analysis)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failures++ }
            _state.update { it.copy(buildDone = index + 1) }
        }
        val planned = AutoMixPlanner.plan(original.name, songs, state.value.analyses, state.value.tempoMatch)
        val result = planned.copy(id = original.id, clips = planned.clips.mapIndexed { i, c ->
            val old = original.clips[i]
            c.copy(stem = old.stem, stemUri = old.stemUri, pan = old.pan, bassDb = old.bassDb,
                midDb = old.midDb, trebleDb = old.trebleDb, pitchSemitones = old.pitchSemitones, muted = old.muted, solo = old.solo)
        })
        undo.addLast(original); redo.clear(); apply(result); select(result.clips.first().id)
        _state.update { it.copy(message = if (failures == 0) appString(R.string.text_transitions_ready_every_transition_is_editable_a2170c, (songs.size - 1))
            else appString(R.string.text_mix_ready_tracks_use_gentle_fades_retry_auto_mix_to_analyze_them_b474b2, (failures))) }
    }
    fun cancelAutoMix() { autoJob?.cancel(); _state.update { it.copy(building = false, buildLabel = "") } }
    fun chooseStem(clip: MixClip, mode: StemMode) {
        if (state.value.building) return
        if (mode == StemMode.FULL) { cancelSeparation(); updateClip(clip.copy(stem = mode, stemUri = "")); return }
        cancelSeparation()
        val request = ++stemRequest
        stemJob = viewModelScope.launch {
            val owner = account()
            val projectId = state.value.project.id
            _state.update { it.copy(separating = clip.id, separationProgress = 0f, separationLabel = appString(R.string.text_preparing_vocals_f38a5a)) }
            try {
                val stems = container.stemSeparator.separate(owner, clip.song) { label, progress ->
                    if (request == stemRequest) _state.update { it.copy(separationLabel = label, separationProgress = progress) }
                }
                if (request == stemRequest && state.value.project.id == projectId) {
                    state.value.project.clips.firstOrNull { it.id == clip.id }?.let { current ->
                        updateClip(current.copy(stem = mode, stemUri = if (mode == StemMode.VOCALS) stems.vocals else stems.backing))
                    }
                    _state.update { it.copy(modelReady = true, message = appString(R.string.text_separated_audio_ready_switch_between_vocals_backing_and_original_3658bb)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: appString(R.string.text_vocal_separation_failed_please_retry_d89efc)) } }
            finally { if (request == stemRequest) _state.update { it.copy(separating = "", modelReady = container.stemSeparator.modelReady()) } }
        }
    }
    fun cancelSeparation() { stemRequest++; stemJob?.cancel(); _state.update { it.copy(separating = "") } }
    fun search(query: String) {
        searchJob?.cancel()
        _state.update { it.copy(query = query, searching = true) }
        searchJob = viewModelScope.launch {
            try {
                if (query.isNotBlank()) delay(250)
                val result = if (query.isBlank()) container.repository.songsPage(0, 100) else container.repository.search(query).songs
                _state.update { it.copy(songs = result.filter { s -> s.durationSec > 0 }, searching = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(searching = false, message = appString(R.string.text_could_not_load_songs_check_your_library_connection_65bfc8)) } }
        }
    }
    fun analyze(clip: MixClip) {
        analysisJob?.cancel()
        val request = ++analysisRequest
        analysisJob = viewModelScope.launch {
            _state.update { it.copy(analyzing = clip.id, analysisProgress = 0f) }
            try {
                val result = container.mixAnalyzer.analyze(account(), clip.song) { progress ->
                    if (request == analysisRequest) _state.update { it.copy(analysisProgress = progress) }
                }
                _state.update { it.copy(analyses = it.analyses + (clip.song.id to result)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: appString(R.string.text_analysis_failed_you_can_still_mix_manually_adb74e)) } }
            finally { if (request == analysisRequest) _state.update { it.copy(analyzing = "") } }
        }
    }
    fun cancelAnalysis() { analysisRequest++; analysisJob?.cancel(); _state.update { it.copy(analyzing = "") } }
    fun matchTempo(clip: MixClip) {
        val index = state.value.project.clips.indexOfFirst { it.id == clip.id }
        val previous = state.value.project.clips.getOrNull(index - 1) ?: return
        val bpm = state.value.analyses[clip.song.id]?.bpm ?: return
        val reference = state.value.analyses[previous.song.id]?.bpm ?: return
        if (bpm > 0 && reference > 0) updateClip(clip.copy(speed = (reference * previous.speed / bpm).coerceIn(0.5f, 2f)))
    }
    fun alignTransition(clip: MixClip, bars: Int) {
        val index = state.value.project.clips.indexOfFirst { it.id == clip.id }
        val previous = state.value.project.clips.getOrNull(index - 1) ?: return
        val bpm = state.value.analyses[previous.song.id]?.bpm ?: 0f
        val overlap = (if (bpm > 0) bars * 4 * 60f / (bpm * previous.speed) else 8f)
            .coerceAtMost(min(previous.durationSec, clip.durationSec))
        edit { project -> project.copy(clips = project.clips.map { c -> when (c.id) {
            previous.id -> c.copy(fadeOutSec = overlap)
            clip.id -> c.copy(startSec = (previous.endSec - overlap).coerceAtLeast(0f), fadeInSec = overlap)
            else -> c
        } }) }
    }
    fun play(fromSec: Float = 0f, tracklist: Boolean = false) {
        if (state.value.building) return
        val project = state.value.project
        if (project.clips.size < 2) { _state.update { it.copy(message = appString(R.string.text_add_at_least_two_tracks_to_play_a_mix_367edf)) }; return }
        if (project.clips.any { it.song.streamUrl.isBlank() }) { _state.update { it.copy(message = appString(R.string.text_a_source_is_unavailable_reconnect_to_its_library_and_reopen_this_217820)) }; return }
        if (project.clips.any { it.stem != StemMode.FULL && (it.stemUri.isBlank() || !java.io.File(android.net.Uri.parse(it.stemUri).path.orEmpty()).exists()) }) {
            _state.update { it.copy(message = appString(R.string.text_a_separated_track_was_cleared_from_cache_select_vocals_or_backing_0aa5dc)) }; return
        }
        container.mixController.pendingProject = project
        container.mixController.pendingPosition = fromSec
        container.mixController.pendingTracklist = tracklist
        getApplication<Application>().startService(Intent(getApplication(), PlaybackService::class.java).setAction(PlaybackService.ACTION_MIX))
    }
    fun toggle() { container.mixController.commands.tryEmit(MixCommand.Toggle) }
    fun seek(seconds: Float) { container.mixController.commands.tryEmit(MixCommand.Seek(seconds)) }
    fun stop() { container.mixController.commands.tryEmit(MixCommand.Stop) }
    private fun refreshSaved() { viewModelScope.launch {
        runCatching { container.mixStore.list(account()) }.onSuccess { saved -> _state.update { it.copy(saved = saved) } }
            .onFailure { _state.update { it.copy(message = appString(R.string.text_saved_mixes_could_not_be_read_7eab0e)) } }
    } }
    fun save() { viewModelScope.launch {
        val project = state.value.project
        _state.update { it.copy(busy = true) }
        try { container.mixStore.save(account(), project); _state.update { it.copy(dirty = false, message = appString(R.string.text_mix_saved_afa087)) }; refreshSaved() }
        catch (e: Exception) { _state.update { it.copy(message = appString(R.string.text_could_not_save_the_mix_your_edits_are_still_open_ce467e)) } }
        finally { _state.update { it.copy(busy = false) } }
    } }
    fun open(project: MixProject) { viewModelScope.launch {
        cancelAutoMix(); cancelAnalysis(); cancelSeparation()
        stop(); _state.update { it.copy(busy = true) }
        try {
            val owner = account()
            val clips = project.clips.map { c ->
                val song = container.repository.songFor(c.song.id) ?: c.song
                val stems = container.stemSeparator.cached(owner, song)
                c.copy(song = song, stemUri = when (c.stem) { StemMode.FULL -> ""; StemMode.VOCALS -> stems?.vocals.orEmpty(); StemMode.BACKING -> stems?.backing.orEmpty() })
            }
            undo.clear(); redo.clear()
            _state.update { it.copy(project = project.copy(clips = clips), selectedId = clips.firstOrNull()?.id.orEmpty(), dirty = false,
                canUndo = false, canRedo = false, analyses = emptyMap()) }
            val key = account()
            for (c in clips) container.mixAnalyzer.cached(key, c.song)?.let { analysis -> _state.update { it.copy(analyses = it.analyses + (c.song.id to analysis)) } }
        } catch (e: Exception) { _state.update { it.copy(message = appString(R.string.text_could_not_reopen_this_mix_check_the_library_connection_956d9c)) } }
        finally { _state.update { it.copy(busy = false) } }
    } }
    fun newMix() { cancelAutoMix(); cancelAnalysis(); cancelSeparation(); stop(); undo.clear(); redo.clear(); _state.update { MixUiState(saved = it.saved, songs = it.songs, modelReady = container.stemSeparator.modelReady()) } }
    fun delete(project: MixProject) { viewModelScope.launch { runCatching { container.mixStore.delete(account(), project.id) }.onSuccess { refreshSaved() } } }
}
