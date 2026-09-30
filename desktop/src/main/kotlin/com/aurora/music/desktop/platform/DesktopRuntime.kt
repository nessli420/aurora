package com.aurora.music.desktop.platform

import com.aurora.music.desktop.audio.decode.FfmpegRuntime
import com.aurora.music.desktop.natives.SystemNative
import com.aurora.music.util.AppLog
import kotlin.concurrent.thread

object DesktopRuntime {
    const val APP_USER_MODEL_ID = "com.aurora.music"
    private const val TAG = "DesktopRuntime"

    fun init(paths: DesktopPaths = DesktopPaths.default()): InstanceLock? {
        val instance = InstanceLock.acquire(paths.local) ?: return null
        AppLog.sink = FileLog(paths.logs)::write
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> AppLog.e(TAG, "Uncaught exception on ${thread.name}", error) }
        runCatching { check(SystemNative.setAppUserModelId(APP_USER_MODEL_ID)) }
            .onFailure { AppLog.w(TAG, "Could not set the app user model id", it) }
        thread(isDaemon = true, name = "ffmpeg-init") {
            runCatching { FfmpegRuntime.init(paths.javacpp) }.onFailure { AppLog.e(TAG, "FFmpeg failed to load", it) }
        }
        return instance
    }
}
