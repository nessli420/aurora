package com.aurora.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.playback.PlaybackService
import com.aurora.music.playback.compare.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ComparisonUiState(val preparing: Boolean = false, val active: Boolean = false,
    val blind: Boolean = false, val selection: ComparisonSelection = ComparisonSelection.A,
    val answered: Int = 0, val planned: Int = 16, val result: AbxResult? = null,
    val levels: ComparisonLevels? = null, val rate: Int = 0, val selectionFrames: Int = 0, val error: String? = null)

class ComparisonViewModel internal constructor(app: Application,
    private val prepareAudio: suspend (Application, Uri, Int, ProcessingPreset, ProcessingPreset, Double) -> ComparisonAudio,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, ComparisonRenderer::prepare)
    private val container = (app as AuroraApplication).container
    private val store = container.settingsStore
    private val manager = app.getSystemService(AudioManager::class.java)
    private val mutable = MutableStateFlow(ComparisonUiState())
    val state = mutable.asStateFlow()
    private var controller: MediaController? = null
    @OptIn(UnstableApi::class)
    private val controllerFuture = MediaController.Builder(app, SessionToken(app,
        ComponentName(app, PlaybackService::class.java))).buildAsync()
    private var task: Job? = null
    private var playback: ComparisonPlayback? = null
    private var trial: AbxTrial? = null
    private var restorePlaying = false
    private var originalId: String? = null
    private var frozen = false
    private var generation = 0L
    private var closing = false
    private var cleared = false
    private var controllerReleased = false
    private var measuredLevels: ComparisonLevels? = null

    init {
        controllerFuture.addListener({ runCatching { controller = controllerFuture.get() } }, ContextCompat.getMainExecutor(app))
    }

    fun start(uri: Uri, startSeconds: Int, a: ProcessingPreset, b: ProcessingPreset, blind: Boolean) {
        if (mutable.value.active || mutable.value.preparing || closing || cleared) return
        val session = ++generation
        trial = null
        measuredLevels = null
        mutable.value = ComparisonUiState(preparing = true, blind = blind)
        task = viewModelScope.launch {
            try {
                val player = controller ?: error("The player is still connecting.")
                val route = store.processingRoutes.current.route
                require(route.kind == ProcessingRouteKind.ANDROID || route.kind == ProcessingRouteKind.IDLE) {
                    "Use Android audio output for comparison."
                }
                val expectedDeviceId = route.androidDeviceId
                val preferredDeviceId = container.preferredAudioDeviceId.value
                require(route.kind != ProcessingRouteKind.ANDROID || expectedDeviceId != null) { "Wait for the playback output to be confirmed." }
                require(!store.presetRuleContext.current.frozen) { "Another comparison is active." }
                store.presetRuleContext.setFrozen(true); frozen = true
                originalId = player.currentMediaItem?.mediaId
                restorePlaying = player.playWhenReady
                player.pause()
                val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val relativeVolume = volume.toDouble() / manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                fun checkContext() {
                    if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) != volume || player.currentMediaItem?.mediaId != originalId ||
                        player.playWhenReady || container.preferredAudioDeviceId.value != preferredDeviceId) {
                        restorePlaying = false
                        error("Playback, output or volume changed. Prepare the comparison again.")
                    }
                }
                val audio = withTimeoutOrNull(120_000) {
                    prepareAudio(getApplication(), uri, startSeconds, a, b, relativeVolume)
                } ?: error("Preparing the comparison timed out.")
                checkContext()
                measuredLevels = audio.levels
                val output = ComparisonPlayback(getApplication(), audio, expectedDeviceId,
                    preferredDeviceId) { reason -> viewModelScope.launch {
                    if (generation == session) stop(reason)
                } }
                playback = output
                output.start()
                output.awaitReady()
                checkContext()
                trial = if (blind) AbxTrial() else null
                mutable.value = ComparisonUiState(active = true, blind = blind, levels = if (blind) null else audio.levels,
                    rate = audio.rate, selectionFrames = audio.frames)
                while (mutable.value.active) {
                    delay(250)
                    if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) != volume) {
                        stop("Volume changed. The comparison stopped."); break
                    }
                    if (container.preferredAudioDeviceId.value != preferredDeviceId) {
                        stop("The selected output changed. The comparison stopped."); break
                    }
                    if (player.currentMediaItem?.mediaId != originalId || player.playWhenReady) {
                        restorePlaying = false
                        stop("Playback changed. The comparison stopped."); break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == session) mutable.value = mutable.value.copy(preparing = false, active = false,
                    error = failure.message?.takeUnless { it.contains("://") } ?: "Could not prepare this audio selection.")
            } finally {
                if (generation == session) {
                    releaseOutput()
                    mutable.value = mutable.value.copy(preparing = false, active = false)
                }
            }
        }
    }

    fun select(selection: ComparisonSelection) {
        if (!mutable.value.active) return
        val a = when (selection) {
            ComparisonSelection.A -> true
            ComparisonSelection.B -> false
            ComparisonSelection.X -> trial?.takeIf { it.active }?.xIsA() ?: return
        }
        playback?.select(a)
        mutable.value = mutable.value.copy(selection = selection)
    }

    fun guess(a: Boolean) {
        val current = trial?.takeIf { it.active && mutable.value.active } ?: return
        current.guess(a)
        if (current.complete) {
            generation++
            task?.cancel(); task = null
            mutable.value = mutable.value.copy(active = false, answered = current.answered, result = current.result(), levels = measuredLevels)
            releaseOutput()
        } else {
            mutable.value = mutable.value.copy(answered = current.answered)
            select(ComparisonSelection.A)
        }
    }

    fun stop(reason: String? = null) {
        generation++
        if (reason != null) restorePlaying = false
        trial?.takeIf { it.active }?.interrupt()
        mutable.value = mutable.value.copy(active = false, preparing = false, error = reason,
            result = trial?.takeIf { !it.active }?.result(), levels = measuredLevels)
        task?.cancel(); task = null
        releaseOutput()
    }

    private fun releaseOutput() {
        if (closing) return
        val output = playback
        playback = null
        val unfreeze = frozen
        frozen = false
        val resume = restorePlaying && output?.invalidationReason == null
        val mediaId = originalId
        restorePlaying = false
        fun complete() {
            if (unfreeze) store.presetRuleContext.setFrozen(false)
            if (resume && controller?.currentMediaItem?.mediaId == mediaId && controller?.playWhenReady == false) {
                runCatching { controller?.play() }
            }
            closing = false
            if (cleared) releaseController()
        }
        if (output == null) complete() else {
            closing = true
            output.whenClosed(::complete)
            output.close()
        }
    }

    override fun onCleared() {
        cleared = true
        stop()
        super.onCleared()
    }

    private fun releaseController() {
        if (controllerReleased) return
        controllerReleased = true
        MediaController.releaseFuture(controllerFuture)
    }
}
