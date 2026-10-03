package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class DeviceWatcher(private val list: () -> List<AudioDevice>, private val intervalMs: Long = 2_000) {
    private val listeners = CopyOnWriteArrayList<(DeviceEvent) -> Unit>()
    private var poller: Thread? = null
    private var last: List<AudioDevice> = emptyList()

    @Synchronized
    fun listen(listener: (DeviceEvent) -> Unit): AutoCloseable {
        listeners += listener
        if (poller == null) {
            last = runCatching(list).getOrDefault(emptyList())
            poller = thread(isDaemon = true, name = "audio-devices") { run() }
        }
        return AutoCloseable {
            synchronized(this) {
                if (listeners.remove(listener) && listeners.isEmpty()) {
                    poller?.interrupt()
                    poller = null
                }
            }
        }
    }

    fun poll() {
        val now = runCatching(list).getOrNull() ?: return
        val events = synchronized(this) { changes(last, now).also { last = now } }
        events.forEach { event -> listeners.forEach { it(event) } }
    }

    private fun run() {
        while (synchronized(this) { poller === Thread.currentThread() }) {
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                return
            }
            poll()
        }
    }

    companion object {
        fun changes(before: List<AudioDevice>, after: List<AudioDevice>): List<DeviceEvent> = buildList {
            val old = before.map { it.id }.toSet()
            val new = after.map { it.id }.toSet()
            (old - new).forEach { add(DeviceEvent.Removed(it)) }
            (new - old).forEach { add(DeviceEvent.Added(it)) }
            val defaultBefore = before.firstOrNull { it.isDefault }?.id
            val defaultAfter = after.firstOrNull { it.isDefault }?.id
            if (defaultBefore != defaultAfter) add(DeviceEvent.DefaultChanged(defaultAfter))
        }
    }
}
