package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DeviceWatcherTest {
    private fun device(id: String, default: Boolean = false) = AudioDevice(id, id, DeviceKind.UNKNOWN, default)

    @Test fun plugAndDefaultChangesAreReported() {
        val before = listOf(device("default", true), device("usb"))
        val after = listOf(device("default"), device("hdmi", true))
        assertEquals(
            listOf(DeviceEvent.Removed("usb"), DeviceEvent.Added("hdmi"), DeviceEvent.DefaultChanged("hdmi")),
            DeviceWatcher.changes(before, after),
        )
        assertEquals(emptyList<DeviceEvent>(), DeviceWatcher.changes(before, before))
    }

    @Test fun listenersHearPolledChangesUntilClosed() {
        var devices = listOf(device("default", true))
        val watcher = DeviceWatcher({ devices }, intervalMs = 20)
        val events = LinkedBlockingQueue<DeviceEvent>()
        val heard = CopyOnWriteArrayList<DeviceEvent>()
        watcher.listen { events += it }.use {
            watcher.listen { heard += it }.close()
            devices = devices + device("usb")
            assertEquals(DeviceEvent.Added("usb"), events.poll(5, TimeUnit.SECONDS))
        }
        devices = listOf(device("default", true))
        watcher.poll()
        assertEquals(0, events.size)
        assertEquals(emptyList<DeviceEvent>(), heard.toList())
    }
}
