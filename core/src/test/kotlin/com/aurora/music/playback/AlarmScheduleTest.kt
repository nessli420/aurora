package com.aurora.music.playback

import com.aurora.music.data.AlarmPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlarmScheduleTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun sameDayAndPassedTimeChooseTheNextOccurrence() {
        assertEquals(ms("2026-09-08T07:00:00Z"), nextDailyAlarmMs(7, 0, ms("2026-09-08T06:59:59Z"), utc))
        assertEquals(ms("2026-09-09T07:00:00Z"), nextDailyAlarmMs(7, 0, ms("2026-09-08T07:00:00Z"), utc))
        assertEquals(ms("2026-09-09T07:00:00Z"), nextDailyAlarmMs(7, 0, ms("2026-09-08T23:58:00Z"), utc))
    }

    @Test fun midnightRollsAcrossYearBoundary() {
        assertEquals(ms("2027-01-01T00:00:00Z"), nextDailyAlarmMs(0, 0, ms("2026-12-31T23:59:59Z"), utc))
        assertEquals(ms("2027-01-02T00:00:00Z"), nextDailyAlarmMs(0, 0, ms("2027-01-01T00:00:00Z"), utc))
    }

    @Test fun springGapMovesOnlyTheMissingOccurrence() {
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        // 02:30 does not exist on March 29: use 03:30 CEST, then restore 02:30 the next day.
        assertEquals(ms("2026-03-29T01:30:00Z"), nextDailyAlarmMs(2, 30, ms("2026-03-28T23:00:00Z"), amsterdam))
        assertEquals(ms("2026-03-30T00:30:00Z"), nextDailyAlarmMs(2, 30, ms("2026-03-29T01:30:00Z"), amsterdam))
    }

    @Test fun fallOverlapRingsOnceAtTheFirstOccurrence() {
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        assertEquals(ms("2026-10-25T00:30:00Z"), nextDailyAlarmMs(2, 30, ms("2026-10-24T23:00:00Z"), amsterdam))
        assertEquals(ms("2026-10-26T01:30:00Z"), nextDailyAlarmMs(2, 30, ms("2026-10-25T00:30:00Z"), amsterdam))
        assertEquals(ms("2026-10-26T01:30:00Z"), nextDailyAlarmMs(2, 30, ms("2026-10-25T01:15:00Z"), amsterdam))
    }

    @Test fun changingTimezoneOrClockReevaluatesTheLocalTime() {
        val now = ms("2026-09-08T06:00:00Z")
        assertEquals(ms("2026-09-08T07:00:00Z"), nextDailyAlarmMs(7, 0, now, utc))
        assertEquals(ms("2026-09-09T05:00:00Z"), nextDailyAlarmMs(7, 0, now, ZoneId.of("Europe/Amsterdam")))
        assertEquals(ms("2026-09-08T05:00:00Z"), nextDailyAlarmMs(7, 0, ms("2026-09-08T04:00:00Z"), ZoneId.of("Europe/Amsterdam")))
    }

    @Test fun statusRefreshNeverCreatesCancelsOrReschedulesAlarms() {
        val controller = AlarmScheduleController()
        val backend = FakeBackend()
        repeat(3) { controller.refreshStatus(backend) }
        assertTrue(backend.calls.isEmpty())
        controller.apply(AlarmPrefs(true, 7, 0), backend, ms("2026-09-08T06:00:00Z"), utc)
        val scheduled = controller.state.value
        repeat(3) { controller.refreshStatus(backend) }
        assertEquals(listOf("exact:${ms("2026-09-08T07:00:00Z")}"), backend.calls)
        assertEquals(scheduled, controller.state.value)
    }

    @Test fun exactAndFallbackModesReportOnlySuccessfullySubmittedTriggers() {
        val controller = AlarmScheduleController()
        val backend = FakeBackend()
        val now = ms("2026-09-08T06:00:00Z")
        controller.apply(AlarmPrefs(true, 7, 0), backend, now, utc)
        assertEquals(AlarmScheduleMode.EXACT, controller.state.value.mode)
        assertEquals(now, controller.state.value.scheduledAtMs)
        backend.exactAllowed = false
        controller.apply(AlarmPrefs(true, 8, 0), backend, now, utc)
        assertEquals(AlarmScheduleMode.INEXACT, controller.state.value.mode)
        assertEquals(ms("2026-09-08T08:00:00Z"), controller.state.value.nextTriggerMs)
        assertFalse(controller.state.value.exactAllowed!!)
        assertEquals(2, backend.calls.size)
    }

    @Test fun exactPermissionRaceFallsBackAndSubsequentFailuresAreVisible() {
        val controller = AlarmScheduleController()
        val backend = FakeBackend().apply { rejectExact = true }
        val now = ms("2026-09-08T06:00:00Z")
        controller.apply(AlarmPrefs(true, 7, 0), backend, now, utc)
        assertEquals(AlarmScheduleMode.INEXACT, controller.state.value.mode)
        assertNotNull(controller.state.value.nextTriggerMs)
        backend.rejectInexact = true
        controller.apply(AlarmPrefs(true, 8, 0), backend, now, utc)
        assertEquals(AlarmScheduleMode.FAILED, controller.state.value.mode)
        assertNull(controller.state.value.nextTriggerMs)
        assertNotNull(controller.state.value.error)
        assertEquals("cancel", backend.calls.last())
    }

    @Test fun disablingCancelsTheOneAlarmAndClearsItsTrigger() {
        val controller = AlarmScheduleController()
        val backend = FakeBackend()
        val now = ms("2026-09-08T06:00:00Z")
        controller.apply(AlarmPrefs(true, 7, 0), backend, now, utc)
        controller.apply(AlarmPrefs(false, 7, 0), backend, now, utc)
        assertEquals(AlarmScheduleMode.DISABLED, controller.state.value.mode)
        assertNull(controller.state.value.nextTriggerMs)
        assertEquals("cancel", backend.calls.last())
    }

    @Test fun revokedExactAccessInvalidatesStatusWithoutClaimingAFallbackWasScheduled() {
        val controller = AlarmScheduleController()
        val backend = FakeBackend()
        controller.apply(AlarmPrefs(true, 7, 0), backend, ms("2026-09-08T06:00:00Z"), utc)
        backend.exactAllowed = false
        controller.refreshStatus(backend)
        assertEquals(AlarmScheduleMode.FAILED, controller.state.value.mode)
        assertNull(controller.state.value.nextTriggerMs)
        assertEquals(1, backend.calls.size)
    }

    private class FakeBackend : AlarmScheduleBackend {
        var exactAllowed = true
        var rejectExact = false
        var rejectInexact = false
        val calls = mutableListOf<String>()
        override fun canScheduleExact() = exactAllowed
        override fun cancel() { calls += "cancel" }
        override fun scheduleExact(triggerAtMs: Long) {
            calls += "exact:$triggerAtMs"
            if (rejectExact) throw SecurityException("Access changed")
        }
        override fun scheduleInexact(triggerAtMs: Long) {
            calls += "inexact:$triggerAtMs"
            if (rejectInexact) throw IllegalStateException("Scheduling failed")
        }
    }
}
