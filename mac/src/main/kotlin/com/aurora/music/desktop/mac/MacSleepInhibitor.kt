package com.aurora.music.desktop.mac

import com.aurora.music.util.AppLog

// caffeinate holds an idle-sleep assertion for as long as it runs, and -w ends it with aurora if aurora dies first
class MacSleepInhibitor {
    private var process: Process? = null

    @Synchronized
    fun set(enabled: Boolean) {
        if (enabled == (process?.isAlive == true)) return
        if (!enabled) {
            process?.destroy()
            process = null
            return
        }
        process = runCatching {
            ProcessBuilder("/usr/bin/caffeinate", "-i", "-w", ProcessHandle.current().pid().toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { AppLog.w(TAG, "Could not keep the Mac awake", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "MacSleepInhibitor"
    }
}
