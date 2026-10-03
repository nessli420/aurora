package com.aurora.music.desktop.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopPaths(val roaming: File, val local: File) {
    val settingsFile = File(roaming, "aurora_settings.preferences_pb")
    val desktopSettingsFile = File(roaming, "desktop_settings.preferences_pb")
    val cache = File(local, "cache")
    val downloads = File(local, "downloads")
    val logs = File(local, "logs")
    val library = File(local, "library")
    val javacpp = File(local, "javacpp")
    val artwork = File(cache, "metadata-artwork")
    val images = File(cache, "images")
    val staging = File(cache, "staging")

    fun createDirectories() = listOf(roaming, local, cache, downloads, logs, library, staging).forEach(File::mkdirs)

    // the saved queue holds authenticated stream urls so it stays out of the roaming profile
    fun moveLegacyQueue() {
        val legacy = File(roaming, QUEUE_FILE)
        if (legacy.isFile) runCatching { Files.move(legacy.toPath(), File(local, QUEUE_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        private const val QUEUE_FILE = "queue_state.json"

        fun default(): DesktopPaths {
            val home = File(System.getProperty("user.home"))
            fun root(variable: String, fallback: String) =
                File(System.getenv(variable)?.takeIf(String::isNotBlank)?.let(::File) ?: File(home, fallback), "Aurora")
            if (!HostPlatform.isWindows) return DesktopPaths(root("XDG_CONFIG_HOME", ".config"), root("XDG_DATA_HOME", ".local/share"))
            return DesktopPaths(root("APPDATA", "AppData/Roaming"), root("LOCALAPPDATA", "AppData/Local"))
        }
    }
}
