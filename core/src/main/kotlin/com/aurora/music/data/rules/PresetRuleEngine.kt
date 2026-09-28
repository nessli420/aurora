package com.aurora.music.data.rules

import com.aurora.music.data.routes.ProcessingRouteKind
import java.util.Locale

object PresetRuleEngine {
    fun preview(input: PresetRuleInput): PresetRulePreview {
        val results = input.rules.rules.withIndex().sortedWith(
            compareByDescending<IndexedValue<PresetRule>> { it.value.priority }.thenBy { it.index }
        ).map { (_, rule) ->
            val conditions = rule.conditions.map { evaluate(it, input) }
            val matched = rule.enabled && when (rule.match) {
                RuleMatch.ALL -> conditions.all { it.state == RuleConditionState.MATCH }
                RuleMatch.ANY -> conditions.any { it.state == RuleConditionState.MATCH }
            }
            PresetRuleResult(rule, matched, conditions)
        }
        val route = input.route.route
        val reason = when {
            input.observation.frozen -> "Rules are frozen for comparison."
            input.rules.manualHold || route.key in input.outputRules.manual -> "Manual sound is retained."
            !input.rules.enabled -> "Preset rules are off."
            !input.observation.playback.active -> "No active playback."
            route.kind != ProcessingRouteKind.ANDROID -> "This output does not support automatic local processing."
            route.key == null -> "Output identity is unavailable."
            else -> null
        }
        return PresetRulePreview(results.firstOrNull { it.matched }?.rule, results, reason)
    }

    fun winner(input: PresetRuleInput): PresetRule? = preview(input).takeIf { it.pausedReason == null }?.winner

    fun mayCommit(expected: PresetRuleInput, current: PresetRuleInput): Boolean =
        expected == current && !current.observation.frozen

    fun evaluate(condition: PresetRuleCondition, input: PresetRuleInput): RuleConditionResult {
        val playback = input.observation.playback
        if (condition.field == RuleField.SAMPLE_RATE) {
            val rate = playback.sampleRateHz?.takeIf { it > 0 }
                ?: return result(condition, RuleConditionState.UNKNOWN, "Sample rate is unknown.")
            val matches = (condition.minRateHz == null || rate >= condition.minRateHz) &&
                (condition.maxRateHz == null || rate <= condition.maxRateHz)
            return result(condition, if (matches) RuleConditionState.MATCH else RuleConditionState.NO_MATCH, "$rate Hz")
        }
        if (condition.field == RuleField.CONTEXT) {
            val states = condition.values.map { value -> when (RulePlaybackMode.valueOf(value)) {
                RulePlaybackMode.CAST -> playback.cast
                RulePlaybackMode.ANDROID_AUTO -> playback.androidAuto
                RulePlaybackMode.LOCAL -> when {
                    playback.cast == true || playback.androidAuto == true -> false
                    playback.cast == false && playback.androidAuto == false -> true
                    else -> null
                }
            } }
            val state = when {
                states.any { it == true } -> RuleConditionState.MATCH
                states.any { it == null } -> RuleConditionState.UNKNOWN
                else -> RuleConditionState.NO_MATCH
            }
            val display = when {
                playback.cast == true -> "Cast"
                playback.androidAuto == true -> "Android Auto"
                playback.cast == false && playback.androidAuto == false -> "Local playback"
                else -> "Playback context is unknown."
            }
            return result(condition, state, display)
        }
        val actual: Set<String>? = when (condition.field) {
            RuleField.ROUTE -> input.route.route.key?.let(::setOf)
            RuleField.HEADPHONES -> input.route.route.key?.let { setOf(input.outputRules.headphones[it].orEmpty()) }
            RuleField.SOURCE -> playback.source?.name?.let(::setOf)
            RuleField.PROVIDER -> playback.providerId?.let(::setOf)
            RuleField.ALBUM_ID -> playback.albumId?.takeIf { it.isNotBlank() }?.let(::setOf)
            RuleField.ALBUM_NAME -> playback.albumName?.takeIf { it.isNotBlank() }?.let(::setOf)
            RuleField.GENRE -> playback.genres
            RuleField.PLAYLIST_ID -> playback.playlistId?.takeIf { it.isNotBlank() }?.let(::setOf)
            RuleField.PLAYLIST_NAME -> playback.playlistName?.takeIf { it.isNotBlank() }?.let(::setOf)
            RuleField.CODEC -> playback.codec?.takeIf { it.isNotBlank() }?.let(::setOf)
            RuleField.CONTAINER -> playback.container?.takeIf { it.isNotBlank() }?.let(::setOf)
            RuleField.CONTEXT, RuleField.SAMPLE_RATE -> error("Condition evaluated separately")
        }
        if (actual == null) return result(condition, RuleConditionState.UNKNOWN, "${condition.field.label} is unknown.")
        val exact = condition.field in setOf(RuleField.ROUTE, RuleField.PROVIDER, RuleField.ALBUM_ID, RuleField.PLAYLIST_ID)
        fun normalize(value: String) = if (exact) value else value.trim().lowercase(Locale.ROOT)
        val expected = condition.values.map(::normalize).toSet()
        val matches = actual.any { normalize(it) in expected }
        val display = when (condition.field) {
            RuleField.ROUTE -> input.route.route.label
            RuleField.PROVIDER -> playback.providerLabel ?: "Current provider"
            RuleField.SOURCE -> playback.source?.label.orEmpty()
            RuleField.HEADPHONES -> actual.joinToString().ifEmpty { "Output default" }
            RuleField.CONTEXT -> actual.joinToString { RulePlaybackMode.valueOf(it).label }
            RuleField.ALBUM_ID -> playback.albumName ?: "Current album"
            RuleField.PLAYLIST_ID -> playback.playlistName ?: "Current playlist"
            else -> actual.joinToString().ifBlank { "None" }
        }
        return result(condition, if (matches) RuleConditionState.MATCH else RuleConditionState.NO_MATCH, display)
    }

    private fun result(condition: PresetRuleCondition, state: RuleConditionState, explanation: String) =
        RuleConditionResult(condition, state, explanation)
}
