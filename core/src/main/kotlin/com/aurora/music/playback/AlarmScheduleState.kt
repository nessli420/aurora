package com.aurora.music.playback

import com.aurora.music.data.AlarmPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class AlarmScheduleMode { DISABLED, EXACT, INEXACT, FAILED }

/** The result of submitting our single daily alarm to Android, not proof of future delivery. */
data class AlarmScheduleState(
    val request: AlarmPrefs? = null,
    val mode: AlarmScheduleMode = AlarmScheduleMode.DISABLED,
    val nextTriggerMs: Long? = null,
    val scheduledAtMs: Long? = null,
    val exactAllowed: Boolean? = null,
    val error: String? = null,
)

/** Re-evaluate the requested wall time each day so a DST gap does not shift subsequent days. */
internal fun nextDailyAlarmMs(hour: Int, minute: Int, nowMs: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMs)
    val date = now.atZone(zone).toLocalDate()
    val time = LocalTime.of(hour, minute)
    // atZone shifts a missing spring time by the gap and chooses the first fall occurrence.
    // Once that occurrence has passed, use tomorrow: never ring twice for an overlapping hour.
    val today = date.atTime(time).atZone(zone).toInstant()
    return (if (today > now) today else date.plusDays(1).atTime(time).atZone(zone).toInstant())
        .toEpochMilli()
}

internal interface AlarmScheduleBackend {
    fun canScheduleExact(): Boolean
    fun cancel()
    fun scheduleExact(triggerAtMs: Long)
    fun scheduleInexact(triggerAtMs: Long)
}

/** A small testable boundary around Android's schedule calls; observing/refreshing never calls them. */
internal class AlarmScheduleController {
    private val mutableState = MutableStateFlow(AlarmScheduleState())
    val state: StateFlow<AlarmScheduleState> = mutableState.asStateFlow()

    @Synchronized
    fun apply(prefs: AlarmPrefs, backend: AlarmScheduleBackend, nowMs: Long, zone: ZoneId) {
        var exactAllowed: Boolean? = null
        try {
            if (!prefs.enabled) {
                backend.cancel()
                mutableState.value = AlarmScheduleState(request = prefs)
                return
            }
            val triggerAt = nextDailyAlarmMs(prefs.hour, prefs.minute, nowMs, zone)
            exactAllowed = backend.canScheduleExact()
            val mode = if (exactAllowed) {
                try {
                    backend.scheduleExact(triggerAt)
                    AlarmScheduleMode.EXACT
                } catch (_: SecurityException) {
                    // Access may change between checking and setting. Keep the existing fallback.
                    exactAllowed = false
                    backend.scheduleInexact(triggerAt)
                    AlarmScheduleMode.INEXACT
                }
            } else {
                backend.scheduleInexact(triggerAt)
                AlarmScheduleMode.INEXACT
            }
            mutableState.value = AlarmScheduleState(
                request = prefs, mode = mode, nextTriggerMs = triggerAt,
                scheduledAtMs = nowMs, exactAllowed = exactAllowed,
            )
        } catch (_: Exception) {
            // A failed replacement must not leave an older time silently armed.
            val cancelFailed = runCatching { backend.cancel() }.isFailure
            mutableState.value = AlarmScheduleState(
                request = prefs, mode = AlarmScheduleMode.FAILED, exactAllowed = exactAllowed,
                error = if (cancelFailed) "Android could not update or cancel the alarm. Check alarm permissions."
                else if (prefs.enabled) "Android could not schedule the alarm. Check permissions and try enabling it again."
                else "Android could not turn off the alarm. Try again.",
            )
        }
    }

    @Synchronized
    fun refreshStatus(backend: AlarmScheduleBackend) {
        val previous = mutableState.value
        val exactAllowed = runCatching { backend.canScheduleExact() }.getOrNull()
        mutableState.value = if (previous.mode == AlarmScheduleMode.EXACT && exactAllowed == false) {
            // Revocation deletes exact alarms. A receiver/startup re-establishes them; screens only read.
            previous.copy(
                mode = AlarmScheduleMode.FAILED, nextTriggerMs = null, scheduledAtMs = null,
                exactAllowed = false, error = "Exact alarm access was removed. Enable the alarm again or grant access.",
            )
        } else previous.copy(exactAllowed = exactAllowed)
    }
}
