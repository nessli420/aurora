package com.aurora.music.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AlarmPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Schedules the wake-to-music alarm via [AlarmManager]. Uses `setAlarmClock` so it fires exactly even
 * in Doze and shows the system alarm icon (and grants the alarm-triggered foreground-service
 * exemption needed to start playback from the background). Falls back to an inexact alarm if exact
 * scheduling isn't permitted.
 */
object AlarmScheduler {
    private const val REQUEST_CODE = 0x4A1A

    fun apply(context: Context, prefs: AlarmPrefs) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = firePendingIntent(context)
        am.cancel(pi)
        if (!prefs.enabled) return
        val triggerAt = nextTriggerMs(prefs.hour, prefs.minute)
        runCatching {
            if (canScheduleExact(am)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pi)
            } else {
                // No exact-alarm permission — best-effort inexact wake.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private fun firePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Next clock time at [hour]:[minute] — today if still ahead, otherwise tomorrow. */
    private fun nextTriggerMs(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }

    fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
}

/**
 * Fires the alarm (starts wake-to-music playback) and reschedules the next day; also re-arms the
 * alarm after a device reboot, since alarms don't survive reboots.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == ACTION_FIRE) {
            // Start playback right away (within the alarm-triggered FGS exemption window).
            runCatching {
                val svc = Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_ALARM)
                ContextCompat.startForegroundService(appContext, svc)
            }
        }
        // Re-arm (next occurrence after a fire, or restore after a boot).
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = (appContext as AuroraApplication).container.settingsStore
                AlarmScheduler.apply(appContext, store.alarmPrefs.first())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.aurora.music.action.ALARM_FIRE"
    }
}
