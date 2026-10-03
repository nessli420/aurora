package com.aurora.music.desktop.platform

import java.util.Locale

object HostPlatform {
    private val os: String = System.getProperty("os.name").orEmpty()
    val isWindows: Boolean = os.lowercase(Locale.ROOT).startsWith("windows")
    val isLinux: Boolean = os.lowercase(Locale.ROOT).startsWith("linux")
    val name: String = when {
        isWindows -> "Windows"
        isLinux -> "Linux"
        else -> os
    }
}
