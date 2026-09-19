package com.aurora.music.data

import android.content.Context
import com.aurora.music.data.routes.*
import com.aurora.music.data.rules.*
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
    private val mutablePreview = MutableStateFlow(PresetRulePreview(pausedReason = "No active playback."))
    val rulePreview = mutablePreview.asStateFlow()
    private data class OutputRequest(val observation: RouteObservation, val rules: Result<ProcessingRouteRules>,
        val legacyEnabled: Boolean, val legacy: List<EqBinding>, val presets: ProcessingPresetLibrary)
    private data class Request(val output: OutputRequest, val context: PresetRuleObservation, val rules: Result<PresetRuleSet>)
    private data class Applied(val generation: Long, val headphones: String, val presetId: String? = null, val legacy: EqBinding? = null)
    private var applied: Applied? = null

    init {
        scope.launch {
            val outputs = combine(settingsStore.processingRoutes.observations,
                settingsStore.processingRouteRules.map { Result.success(it) }.catch { emit(Result.failure(it)) },
                settingsStore.autoEqAutoSwitch, settingsStore.eqBindings, settingsStore.processingPresetLibrary,
                ::OutputRequest)
            combine(outputs, settingsStore.presetRuleContext.observations,
                settingsStore.presetRules.map { Result.success(it) }.catch { emit(Result.failure(it)) }, ::Request)
                .collectLatest { request ->
                try { apply(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    applied = null
                    val message = failure.message ?: "Preset rule failed."
                    mutablePreview.value = mutablePreview.value.copy(pausedReason = message)
                    mutableStatus.value = RouteApplicationStatus(message)
                }
            }
        }
    }

    fun currentOutputLabel(): String = settingsStore.processingRoutes.current.route.label
    fun currentOutputKey(): String = settingsStore.processingRoutes.current.route.key.orEmpty()

    private suspend fun apply(request: Request) {
        val output = request.output
        val observation = output.observation
        val route = observation.route
        val rules = output.rules.getOrThrow()
        val ordered = request.rules.getOrThrow()
        val input = PresetRuleInput(request.context, observation, ordered, rules)
        val preview = PresetRuleEngine.preview(input)
        mutablePreview.value = preview
        if (request.context.frozen) {
            applied = null
            mutableStatus.value = RouteApplicationStatus("Rules are frozen for comparison.")
            return
        }
        val transition = settingsStore.transitionPresetRule(input).getOrThrow()
        if (transition?.action == RuleTransitionAction.RESTORED) applied = null
        if (preview.mayApply) {
            applied = null
            if (transition != null) mutableStatus.value = RouteApplicationStatus(
                if (transition.presetId == null) "Current sound retained."
                else "${preview.winner!!.name}: ${transition.presetName ?: "Preset applied"}.${if (transition.restartRequired) " Restart Aurora for output changes." else ""}",
                transition.presetId)
            return
        }
        val key = route.key
        if (route.kind != ProcessingRouteKind.ANDROID || key == null) {
            applied = null
            mutableStatus.value = RouteApplicationStatus(route.detail)
            return
        }
        if (ordered.manualHold || key in rules.manual) {
            applied = null
            mutableStatus.value = RouteApplicationStatus("Manual sound retained for this output.")
            return
        }
        val presetId = RouteRuleDecision.preset(observation, rules)
        if (presetId != null) {
            require(output.presets.error == null) { output.presets.error.orEmpty() }
            require(output.presets.presets.any { it.id == presetId }) { "The bound preset was deleted." }
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
        val binding = if (output.legacyEnabled) output.legacy.firstOrNull { it.deviceKey == key }
            ?: output.legacy.firstOrNull { it.deviceKey == route.legacyKey } else null
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
