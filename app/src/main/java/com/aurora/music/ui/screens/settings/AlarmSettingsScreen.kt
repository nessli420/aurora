package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
        SettingsTopBar(appString(R.string.text_alarm_25f8c5), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { SettingsSectionTitle(appString(R.string.text_daily_wake_up_30bad6)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.Alarm, appString(R.string.text_wake_to_music_alarm_40ab13),
                        appString(R.string.text_fade_in_music_from_your_library_every_day_51b4c6), alarm.enabled,
                    ) { enabled -> scope.launch { store.setAlarm(enabled, alarm.hour, alarm.minute) } }
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Alarm, appString(R.string.text_alarm_time_d2dd88), value = alarmTime) {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> scope.launch { store.setAlarm(alarm.enabled, hour, minute) } },
                            alarm.hour, alarm.minute, DateFormat.is24HourFormat(context),
                        ).show()
                    }
                }
            }
            item { SettingsSectionTitle(appString(R.string.text_schedule_0a8ada)) }
            item {
                SettingsGroup {
                    Column(Modifier.padding(20.dp)) {
                        val title = when {
                            !currentResult -> appString(R.string.text_updating_alarm_221e61)
                            schedule.mode == AlarmScheduleMode.DISABLED -> appString(R.string.text_alarm_is_off_fc6331)
                            schedule.mode == AlarmScheduleMode.EXACT -> appString(R.string.text_exact_alarm_scheduled_afe593)
                            schedule.mode == AlarmScheduleMode.INEXACT -> appString(R.string.text_approximate_alarm_scheduled_f3c47b)
                            !alarm.enabled -> appString(R.string.text_could_not_turn_off_alarm_a8caac)
                            else -> appString(R.string.text_alarm_was_not_scheduled_8d9142)
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (currentResult) {
                            schedule.nextTriggerMs?.let { trigger ->
                                Text(
                                    appString(R.string.text_next_15c20d, (formatScheduledAlarm(context, trigger))),
                                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            val explanation = when (schedule.mode) {
                                AlarmScheduleMode.DISABLED -> appString(R.string.text_your_saved_time_is_d52817, (alarmTime))
                                AlarmScheduleMode.EXACT -> appString(R.string.text_android_accepted_the_scheduled_time_music_and_the_wake_up_screen_f303a2)
                                AlarmScheduleMode.INEXACT -> appString(R.string.text_exact_alarm_access_is_unavailable_android_may_delay_this_alarm_an_97544e)
                                AlarmScheduleMode.FAILED -> schedule.error ?: appString(R.string.text_check_alarm_permissions_and_try_enabling_it_again_764ff9)
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
            item { SettingsSectionTitle(appString(R.string.text_access_delivery_270b49)) }
            item {
                SettingsGroup {
                    SettingsNavRow(
                        Icons.Filled.Security, appString(R.string.text_alarm_permissions_c50074),
                        appString(R.string.text_exact_alarms_notifications_battery_and_lock_screen_access_d38e18),
                        onClick = onOpenPermissions,
                    )
                }
            }
            item {
                Text(
                    appString(R.string.text_plays_your_liked_music_or_downloaded_songs_when_liked_music_is_un_58abcb),
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
    if (schedule.request != alarm) return appString(R.string.text_checking_alarm_e06c5c)
    return when (schedule.mode) {
        AlarmScheduleMode.DISABLED -> appString(R.string.text_off_e3de5a)
        AlarmScheduleMode.FAILED -> if (alarm.enabled) appString(R.string.text_alarm_could_not_be_scheduled_89c0e4) else appString(R.string.text_could_not_turn_off_alarm_a8caac)
        AlarmScheduleMode.EXACT, AlarmScheduleMode.INEXACT -> {
            val time = schedule.nextTriggerMs?.let { formatScheduledAlarm(context, it) } ?: appString(R.string.text_checking_time_d916f1)
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
