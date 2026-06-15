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

// setAlarmClock fires exactly in doze and grants the fgs exemption to start playback from background
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
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private fun firePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

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

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == ACTION_FIRE) {
            // start playback within the alarm-triggered fgs exemption window
            runCatching {
                val svc = Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_ALARM)
                ContextCompat.startForegroundService(appContext, svc)
            }
        }
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
