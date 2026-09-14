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
import java.time.ZoneId

// setAlarmClock fires exactly in doze and grants the fgs exemption to start playback from background
object AlarmScheduler {
    private const val REQUEST_CODE = 0x4A1A
    private val controller = AlarmScheduleController()
    val state = controller.state

    fun apply(context: Context, prefs: AlarmPrefs) {
        controller.apply(prefs, backend(context), System.currentTimeMillis(), ZoneId.systemDefault())
    }

    /** Safe to call when entering a settings screen or returning from Android permissions. */
    fun refreshStatus(context: Context) {
        controller.refreshStatus(backend(context))
    }

    private fun backend(context: Context) = object : AlarmScheduleBackend {
        private fun manager(): AlarmManager =
            requireNotNull(context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)

        override fun canScheduleExact() = AlarmScheduler.canScheduleExact(manager())
        override fun cancel() = manager().cancel(firePendingIntent(context))
        override fun scheduleExact(triggerAtMs: Long) {
            manager().setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, null), firePendingIntent(context))
        }
        override fun scheduleInexact(triggerAtMs: Long) {
            manager().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePendingIntent(context))
        }
    }

    private fun firePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
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
        private val RESCHEDULE_ACTIONS = setOf(
            ACTION_FIRE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
