package com.aurora.music.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

object AiDjDevice {
    // null when the on-device model can run here, otherwise the reason it can't
    fun unsupported(context: Context): String? {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return "ram"
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        if (activity.isLowRamDevice || memory.totalMem < 5L * 1024 * 1024 * 1024) return "ram"
        val abis = Build.SUPPORTED_64_BIT_ABIS
        if ("arm64-v8a" in abis) {
            val cpu = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
            return if (Regex("\\basimddp\\b").containsMatchIn(cpu)) null else "cpu"
        }
        return if ("x86_64" in abis) null else "cpu"
    }

    fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
}
