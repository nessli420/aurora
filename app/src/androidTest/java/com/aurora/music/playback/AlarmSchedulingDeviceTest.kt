package com.aurora.music.playback

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AlarmPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AlarmSchedulingDeviceTest {
    @Test fun observingAndUnrelatedPreferenceWritesDoNotRearmAlarm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as AuroraApplication).container.settingsStore
        val originalAlarm = store.alarmPrefs.first()
        val originalHaptics = store.haptics.first()
        val time = LocalTime.now().plusHours(1)
        val temporary = AlarmPrefs(true, time.hour, time.minute)
        try {
            store.setAlarm(temporary.enabled, temporary.hour, temporary.minute)
            val scheduled = withTimeout(5_000) { AlarmScheduler.state.first { it.request == temporary } }
            assertTrue(scheduled.mode in setOf(AlarmScheduleMode.EXACT, AlarmScheduleMode.INEXACT))
            repeat(3) { AlarmScheduler.refreshStatus(context) }
            assertEquals(scheduled, AlarmScheduler.state.value)
            // This used to cancel/re-arm daily alarms because every DataStore edit emitted AlarmPrefs.
            SystemClock.sleep(100)
            store.setHaptics(!originalHaptics)
            SystemClock.sleep(400)
            assertEquals(scheduled, AlarmScheduler.state.value)
            val disabled = temporary.copy(enabled = false)
            store.setAlarm(false, temporary.hour, temporary.minute)
            val cancelled = withTimeout(5_000) { AlarmScheduler.state.first { it.request == disabled } }
            assertEquals(AlarmScheduleMode.DISABLED, cancelled.mode)
        } finally {
            store.setHaptics(originalHaptics)
            store.setAlarm(originalAlarm.enabled, originalAlarm.hour, originalAlarm.minute)
            withTimeout(5_000) { AlarmScheduler.state.first { it.request == originalAlarm } }
        }
    }
}
