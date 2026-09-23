package com.aurora.music.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.navigation.Routes

enum class SetupPhase { HIDDEN, QUESTIONS, SOURCES, AUTH, TASKS, TASK }
enum class ListeningMode { EVERYDAY, ADVANCED }

enum class SetupSource(val type: ServerType, val title: Int, val detail: Int) {
    LOCAL(ServerType.LOCAL, R.string.setup_source_local, R.string.setup_source_local_detail),
    NAVIDROME(ServerType.SUBSONIC, R.string.setup_source_navidrome, R.string.setup_source_navidrome_detail),
    JELLYFIN(ServerType.JELLYFIN, R.string.setup_source_jellyfin, R.string.setup_source_jellyfin_detail),
    PLEX(ServerType.PLEX, R.string.setup_source_plex, R.string.setup_source_plex_detail),
    YOUTUBE(ServerType.YOUTUBE_MUSIC, R.string.setup_source_youtube, R.string.setup_source_youtube_detail),
    SPOTIFY(ServerType.SPOTIFY, R.string.setup_source_spotify, R.string.setup_source_spotify_detail),
}

enum class SetupTask(val route: String, val title: Int, val detail: Int) {
    DAC_ACCESS(Routes.SETTINGS_PERMISSIONS, R.string.setup_task_dac_access, R.string.setup_task_dac_access_detail),
    DAC_OUTPUT(Routes.SETTINGS_OUTPUT, R.string.setup_task_dac_output, R.string.setup_task_dac_output_detail),
    IEM_EQ(Routes.SETTINGS_EQ, R.string.setup_task_iem_eq, R.string.setup_task_iem_eq_detail),
    AUTO_EQ(Routes.SETTINGS_EQ, R.string.setup_task_autoeq, R.string.setup_task_autoeq_detail),
    ADVANCED(Routes.SETTINGS_ADVANCED_AUDIO, R.string.setup_task_advanced, R.string.setup_task_advanced_detail),
    MERGE(Routes.SETTINGS_SOURCES, R.string.setup_task_merge, R.string.setup_task_merge_detail),
    ALARM_ACCESS(Routes.SETTINGS_PERMISSIONS, R.string.setup_task_alarm_access, R.string.setup_task_alarm_access_detail),
    ALARM(Routes.SETTINGS_ALARM, R.string.setup_task_alarm, R.string.setup_task_alarm_detail),
    OFFLINE(Routes.SETTINGS_STORAGE, R.string.setup_task_offline, R.string.setup_task_offline_detail),
}

class OnboardingState {
    var phase by mutableStateOf(SetupPhase.HIDDEN)
    var page by mutableIntStateOf(0)
    var mode by mutableStateOf(ListeningMode.EVERYDAY)
    var usbDac by mutableStateOf(false)
    var iems by mutableStateOf(false)
    var autoEq by mutableStateOf(false)
    var merge by mutableStateOf(false)
    var alarm by mutableStateOf(false)
    var offline by mutableStateOf(false)
    var sources by mutableStateOf(setOf<SetupSource>())
    var skippedSources by mutableStateOf(setOf<SetupSource>())
    var completedTasks by mutableStateOf(setOf<SetupTask>())
    var skippedTasks by mutableStateOf(setOf<SetupTask>())
    var connectingSource by mutableStateOf<SetupSource?>(null)
    var activeTask by mutableStateOf<SetupTask?>(null)

    val canMerge: Boolean get() = sources.count { it.type.supportsMergedLibrary } >= 2
    val tasks: List<SetupTask> get() = buildList {
        if (usbDac) { add(SetupTask.DAC_ACCESS); add(SetupTask.DAC_OUTPUT) }
        if (autoEq) add(SetupTask.AUTO_EQ) else if (iems) add(SetupTask.IEM_EQ)
        if (mode == ListeningMode.ADVANCED) add(SetupTask.ADVANCED)
        if (merge && canMerge) add(SetupTask.MERGE)
        if (alarm) { add(SetupTask.ALARM_ACCESS); add(SetupTask.ALARM) }
        if (offline) add(SetupTask.OFFLINE)
    }

    fun restart() {
        phase = SetupPhase.QUESTIONS
        page = 0
        mode = ListeningMode.EVERYDAY
        usbDac = false
        iems = false
        autoEq = false
        merge = false
        alarm = false
        offline = false
        sources = emptySet()
        skippedSources = emptySet()
        completedTasks = emptySet()
        skippedTasks = emptySet()
        connectingSource = null
        activeTask = null
    }
}

private val onboardingStateSaver = listSaver<OnboardingState, String>(
    save = { state -> listOf(
        state.phase.name, state.page.toString(), state.mode.name, state.usbDac.toString(),
        state.iems.toString(), state.autoEq.toString(), state.merge.toString(), state.alarm.toString(),
        state.offline.toString(), state.sources.joinToString(",") { it.name },
        state.skippedSources.joinToString(",") { it.name }, state.completedTasks.joinToString(",") { it.name },
        state.skippedTasks.joinToString(",") { it.name }, state.connectingSource?.name.orEmpty(),
        state.activeTask?.name.orEmpty(),
    ) },
    restore = { values ->
        fun <T : Enum<T>> names(value: String, entries: Array<T>): Set<T> = value.split(',').mapNotNull { name ->
            entries.firstOrNull { it.name == name }
        }.toSet()
        OnboardingState().apply {
            phase = SetupPhase.valueOf(values[0]); page = values[1].toInt()
            mode = ListeningMode.valueOf(values[2]); usbDac = values[3].toBooleanStrict()
            iems = values[4].toBooleanStrict(); autoEq = values[5].toBooleanStrict()
            merge = values[6].toBooleanStrict(); alarm = values[7].toBooleanStrict()
            offline = values[8].toBooleanStrict()
            sources = names(values[9], SetupSource.entries.toTypedArray())
            skippedSources = names(values[10], SetupSource.entries.toTypedArray())
            completedTasks = names(values[11], SetupTask.entries.toTypedArray())
            skippedTasks = names(values[12], SetupTask.entries.toTypedArray())
            connectingSource = SetupSource.entries.firstOrNull { it.name == values[13] }
            activeTask = SetupTask.entries.firstOrNull { it.name == values[14] }
        }
    },
)

@Composable
fun rememberOnboardingState(): OnboardingState = rememberSaveable(saver = onboardingStateSaver) { OnboardingState() }
