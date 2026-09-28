package com.aurora.music.data.rules

import com.aurora.music.data.routes.ProcessingRouteRules
import com.aurora.music.data.routes.RouteObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RuleMatch { ALL, ANY }
enum class RuleSource(val label: String) {
    LOCAL_FILE("Local file"), DOWNLOAD("Download"), STREAM("Stream"), RADIO("Radio"), PODCAST("Podcast"),
}
enum class RulePlaybackMode(val label: String) { LOCAL("Local playback"), ANDROID_AUTO("Android Auto"), CAST("Cast") }
enum class RuleField(val label: String) {
    ROUTE("Output"), HEADPHONES("Headphones"), SOURCE("Source"), PROVIDER("Provider"),
    ALBUM_ID("Album ID"), ALBUM_NAME("Album"), GENRE("Genre"), PLAYLIST_ID("Playlist ID"),
    PLAYLIST_NAME("Playlist"), SAMPLE_RATE("Sample rate"), CODEC("Codec"), CONTAINER("Container"), CONTEXT("Playback context"),
}

data class PresetRuleCondition(
    val field: RuleField,
    val values: List<String> = emptyList(),
    val minRateHz: Int? = null,
    val maxRateHz: Int? = null,
)

data class PresetRule(
    val id: String,
    val name: String,
    val presetId: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val match: RuleMatch = RuleMatch.ALL,
    val conditions: List<PresetRuleCondition>,
)

data class PresetRuleSet(
    val enabled: Boolean = false,
    val manualHold: Boolean = false,
    val rules: List<PresetRule> = emptyList(),
)

data class PresetPlaybackContext(
    val active: Boolean = false,
    val mediaId: String? = null,
    val source: RuleSource? = null,
    val providerId: String? = null,
    val providerLabel: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val genres: Set<String>? = null,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val sampleRateHz: Int? = null,
    val codec: String? = null,
    val container: String? = null,
    val androidAuto: Boolean? = null,
    val cast: Boolean? = null,
)

data class PresetRuleObservation(
    val generation: Long = 0,
    val playback: PresetPlaybackContext = PresetPlaybackContext(),
    val frozen: Boolean = false,
)

class PresetRuleContextMonitor {
    private val mutable = MutableStateFlow(PresetRuleObservation())
    val observations = mutable.asStateFlow()
    val current get() = mutable.value

    @Synchronized fun publish(playback: PresetPlaybackContext) {
        val previous = mutable.value
        if (previous.playback != playback) mutable.value = previous.copy(generation = previous.generation + 1, playback = playback)
    }

    @Synchronized fun setFrozen(frozen: Boolean) {
        val previous = mutable.value
        if (previous.frozen != frozen) mutable.value = previous.copy(generation = previous.generation + 1, frozen = frozen)
    }
}

data class PresetRuleInput(
    val observation: PresetRuleObservation,
    val route: RouteObservation,
    val rules: PresetRuleSet,
    val outputRules: ProcessingRouteRules,
)

enum class RuleConditionState { MATCH, NO_MATCH, UNKNOWN }
data class RuleConditionResult(val condition: PresetRuleCondition, val state: RuleConditionState, val explanation: String)
data class PresetRuleResult(val rule: PresetRule, val matched: Boolean, val conditions: List<RuleConditionResult>)
data class PresetRulePreview(
    val winner: PresetRule? = null,
    val results: List<PresetRuleResult> = emptyList(),
    val pausedReason: String? = null,
) {
    val mayApply get() = winner != null && pausedReason == null
}

enum class RuleTransitionAction { APPLIED, RESTORED, RETAINED }
data class PresetRuleTransition(
    val action: RuleTransitionAction,
    val ruleId: String? = null,
    val presetId: String? = null,
    val presetName: String? = null,
    val restartRequired: Boolean = false,
)
