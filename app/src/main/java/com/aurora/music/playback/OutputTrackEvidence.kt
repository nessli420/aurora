package com.aurora.music.playback

/**
 * Media3 releases AudioTracks asynchronously. A release describes the old configuration, not
 * necessarily the current track. Keep each initialization, including identical configurations,
 * until its matching release arrives. Releases from Media3's serial release executor are FIFO
 * for identical configurations; never resurrect an older pending-release track as current.
 */
internal class OutputTrackEvidence<T>(private val sameConfiguration: (T, T) -> Boolean) {
    private data class Entry<T>(val generation: Long, val configuration: T)
    private val pending = mutableListOf<Entry<T>>()
    private var generation = 0L
    private var current: Entry<T>? = null
    val configuration: T? get() = current?.configuration

    fun initialized(configuration: T) {
        val entry = Entry(++generation, configuration)
        pending.add(entry)
        current = entry
    }

    fun released(configuration: T) {
        val index = pending.indexOfFirst { sameConfiguration(it.configuration, configuration) }
        if (index < 0) return
        val released = pending.removeAt(index)
        if (current?.generation == released.generation) current = null
    }
}
