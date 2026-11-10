package com.aurora.music.desktop.mac

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// macos keeps the accent as a numbered choice in the global defaults; no key means the default blue (multicolour)
object MacAccent {
    private val colors = mapOf(
        -1 to 0xFF8C8C8C.toInt(), // graphite
        0 to 0xFFFF5257.toInt(),  // red
        1 to 0xFFF7821B.toInt(),  // orange
        2 to 0xFFFFC600.toInt(),  // yellow
        3 to 0xFF62BA46.toInt(),  // green
        4 to 0xFF007AFF.toInt(),  // blue
        5 to 0xFFA550A7.toInt(),  // purple
        6 to 0xFFF74F9E.toInt(),  // pink
    )

    fun read(): Int? {
        val process = ProcessBuilder("/usr/bin/defaults", "read", "-g", "AppleAccentColor").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        val choice = if (process.exitValue() == 0) output.toIntOrNull() else 4
        return colors[choice ?: 4]
    }

    // there is no public change notification without cocoa, so a slow poll stands in
    fun listen(onChange: (Int) -> Unit): AutoCloseable {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mac-accent").apply { isDaemon = true } }
        var last = runCatching { read() }.getOrNull()
        timer.scheduleWithFixedDelay({
            val now = runCatching { read() }.getOrNull()
            if (now != null && now != last) {
                last = now
                onChange(now)
            }
        }, 5, 5, TimeUnit.SECONDS)
        return AutoCloseable { timer.shutdownNow() }
    }
}
