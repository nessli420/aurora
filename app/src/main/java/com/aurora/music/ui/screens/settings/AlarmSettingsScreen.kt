package com.aurora.music.ui.screens.settings

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AlarmPrefs
import com.aurora.music.playback.AlarmScheduleMode
import com.aurora.music.playback.AlarmScheduler
import kotlinx.coroutines.launch
import java.util.Date
import java.util.TimeZone

@Composable
fun AlarmSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AuroraApplication).container.settingsStore }
    val alarm by store.alarmPrefs.collectAsStateWithLifecycle(initialValue = AlarmPrefs())
    val schedule by AlarmScheduler.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var timeFormatRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                timeFormatRevision++
                AlarmScheduler.refreshStatus(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        // Reading permission changes is safe here; scheduling belongs to preference/system events.
        AlarmScheduler.refreshStatus(context)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val currentResult = schedule.request == alarm
    val alarmTime = remember(context, alarm.hour, alarm.minute, timeFormatRevision) {
        formatAlarmTime(context, alarm.hour, alarm.minute)
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Alarm", onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { SettingsSectionTitle("Daily wake-up") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.Alarm, "Wake-to-music alarm",
                        "Fade in music from your library every day", alarm.enabled,
                    ) { enabled -> scope.launch { store.setAlarm(enabled, alarm.hour, alarm.minute) } }
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Alarm, "Alarm time", value = alarmTime) {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> scope.launch { store.setAlarm(alarm.enabled, hour, minute) } },
                            alarm.hour, alarm.minute, DateFormat.is24HourFormat(context),
                        ).show()
                    }
                }
            }
            item { SettingsSectionTitle("Schedule") }
            item {
                SettingsGroup {
                    Column(Modifier.padding(20.dp)) {
                        val title = when {
                            !currentResult -> "Updating alarm…"
                            schedule.mode == AlarmScheduleMode.DISABLED -> "Alarm is off"
                            schedule.mode == AlarmScheduleMode.EXACT -> "Exact alarm scheduled"
                            schedule.mode == AlarmScheduleMode.INEXACT -> "Approximate alarm scheduled"
                            !alarm.enabled -> "Could not turn off alarm"
                            else -> "Alarm was not scheduled"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (currentResult) {
                            schedule.nextTriggerMs?.let { trigger ->
                                Text(
                                    "Next: ${formatScheduledAlarm(context, trigger)}",
                                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            val explanation = when (schedule.mode) {
                                AlarmScheduleMode.DISABLED -> "Your saved time is $alarmTime."
                                AlarmScheduleMode.EXACT -> "Android accepted the scheduled time. Music and the wake-up screen still depend on playback, notifications and lock-screen access."
                                AlarmScheduleMode.INEXACT -> "Exact alarm access is unavailable. Android may delay this alarm, and background playback may be restricted."
                                AlarmScheduleMode.FAILED -> schedule.error ?: "Check alarm permissions and try enabling it again."
                            }
                            Text(
                                explanation, Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (schedule.mode == AlarmScheduleMode.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { SettingsSectionTitle("Access & delivery") }
            item {
                SettingsGroup {
                    SettingsNavRow(
                        Icons.Filled.Security, "Alarm permissions",
                        "Exact alarms, notifications, battery and lock-screen access",
                        onClick = onOpenPermissions,
                    )
                }
            }
            item {
                Text(
                    "Plays your liked music, or downloaded songs when liked music is unavailable. The volume fades in over 30 seconds. Keep music available on this device or through your connected library.",
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Landing-page summary; collecting this does not cancel, create or re-arm any alarm. */
@Composable
fun alarmSettingsSummary(): String {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AuroraApplication).container.settingsStore }
    val alarm by store.alarmPrefs.collectAsStateWithLifecycle(initialValue = AlarmPrefs())
    val schedule by AlarmScheduler.state.collectAsStateWithLifecycle()
    if (schedule.request != alarm) return "Checking alarm…"
    return when (schedule.mode) {
        AlarmScheduleMode.DISABLED -> "Off"
        AlarmScheduleMode.FAILED -> if (alarm.enabled) "Alarm could not be scheduled" else "Could not turn off alarm"
        AlarmScheduleMode.EXACT, AlarmScheduleMode.INEXACT -> {
            val time = schedule.nextTriggerMs?.let { formatScheduledAlarm(context, it) } ?: "Checking time"
            "$time · ${if (schedule.mode == AlarmScheduleMode.EXACT) "Exact" else "Approximate"}"
        }
    }
}

private fun formatAlarmTime(context: Context, hour: Int, minute: Int): String {
    // Format a wall time in UTC so today's DST gap cannot change the saved hour in the UI.
    val formatter = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return formatter.format(Date((hour * 60L + minute) * 60_000L))
}

private fun formatScheduledAlarm(context: Context, triggerMs: Long): String {
    val date = Date(triggerMs)
    return "${DateFormat.getMediumDateFormat(context).format(date)}, ${DateFormat.getTimeFormat(context).format(date)}"
}
