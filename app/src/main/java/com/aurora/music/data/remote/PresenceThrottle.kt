package com.aurora.music.data.remote

/** Keeps the newest state while spacing gateway updates below Discord's presence limit. */
internal class PresenceThrottle<T>(private val spacingMs: Long = 4500) {
    var latest: T? = null
        private set
    private var lastSent: Long? = null
    fun offer(value: T?) { latest = value }
    fun delayMs(now: Long): Long = lastSent?.let { (spacingMs - (now - it)).coerceAtLeast(250) } ?: 250
    fun sent(now: Long) { lastSent = now }
}
