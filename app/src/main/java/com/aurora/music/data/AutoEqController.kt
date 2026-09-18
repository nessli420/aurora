package com.aurora.music.data

import android.content.Context
import com.aurora.music.data.routes.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AutoEqController(
    context: Context,
    private val settingsStore: SettingsStore,
    scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow(RouteApplicationStatus())
    val status = mutableStatus.asStateFlow()
    private data class Request(val observation: RouteObservation, val rules: Result<ProcessingRouteRules>,
        val legacyEnabled: Boolean, val legacy: List<EqBinding>, val presets: ProcessingPresetLibrary)
    private data class Applied(val generation: Long, val headphones: String, val presetId: String? = null, val legacy: EqBinding? = null)
    private var applied: Applied? = null

    init {
        scope.launch {
            combine(settingsStore.processingRoutes.observations,
                settingsStore.processingRouteRules.map { Result.success(it) }.catch { emit(Result.failure(it)) },
                settingsStore.autoEqAutoSwitch, settingsStore.eqBindings, settingsStore.processingPresetLibrary,
                ::Request).collectLatest { request ->
                try { apply(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { applied = null; mutableStatus.value = RouteApplicationStatus(failure.message ?: "Output rule failed.") }
            }
        }
    }

    fun currentOutputLabel(): String = settingsStore.processingRoutes.current.route.label
    fun currentOutputKey(): String = settingsStore.processingRoutes.current.route.key.orEmpty()

    private suspend fun apply(request: Request) {
        val observation = request.observation
        val route = observation.route
        val rules = request.rules.getOrThrow()
        val key = route.key
        if (route.kind != ProcessingRouteKind.ANDROID || key == null) {
            applied = null
            mutableStatus.value = RouteApplicationStatus(route.detail)
            return
        }
        if (key in rules.manual) {
            applied = null
            mutableStatus.value = RouteApplicationStatus("Manual sound retained for this output.")
            return
        }
        val presetId = RouteRuleDecision.preset(observation, rules)
        if (presetId != null) {
            require(request.presets.error == null) { request.presets.error.orEmpty() }
            require(request.presets.presets.any { it.id == presetId }) { "The bound preset was deleted." }
            val target = Applied(observation.generation, rules.headphones[key].orEmpty(), presetId)
            if (applied == target) return
            val result = settingsStore.applyRoutePreset(observation, presetId).getOrThrow() ?: return
            applied = target
            mutableStatus.value = RouteApplicationStatus("Applied ${result.presetName}.${if (result.restartRequired) " Restart Aurora for output changes." else ""}", presetId)
            return
        }
        if (rules.enabled && rules.bindings.any { it.routeKey == key }) {
            applied = null
            mutableStatus.value = RouteApplicationStatus("No preset for these headphones. Current sound retained.")
            return
        }
        val binding = if (request.legacyEnabled) request.legacy.firstOrNull { it.deviceKey == key }
            ?: request.legacy.firstOrNull { it.deviceKey == route.legacyKey } else null
        val target = Applied(observation.generation, rules.headphones[key].orEmpty(), legacy = binding)
        if (binding != null && applied == target) return
        if (binding != null && settingsStore.applyEqBindingIfEnabled(binding, observation)) {
            applied = target
            mutableStatus.value = RouteApplicationStatus("Applied ${binding.profileName}.")
        } else {
            applied = null
            mutableStatus.value = RouteApplicationStatus("No output binding. Current sound retained.")
        }
    }
}
