package com.aurora.music.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.ConcurrentHashMap

// windows names most usb and wireless headsets "Speakers (...)", so the bus the endpoint hangs off tells more than its name
object WindowsAudioEndpoints {
    enum class Bus { USB, BLUETOOTH, HD_AUDIO, OTHER }

    private const val RENDER = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render"
    private const val ENUMERATOR = "{b3f8fa53-0004-438e-9003-51a46e139bfc},2"
    private val cache = ConcurrentHashMap<String, Bus>()
    private val ENDPOINT = Regex("""\{0\.0\.0\.[0-9a-fA-F]{8}}\.(\{[0-9a-fA-F-]{36}})""")

    fun bus(deviceId: String): Bus? {
        if (!HostPlatform.isWindows) return null
        val endpoint = ENDPOINT.matchEntire(deviceId)?.groupValues?.get(1) ?: return null
        return cache.getOrPut(endpoint) {
            runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "$RENDER\\$endpoint\\Properties", ENUMERATOR) }
                .map(::busOf).getOrDefault(Bus.OTHER)
        }
    }

    internal fun busOf(enumerator: String): Bus {
        val upper = enumerator.uppercase()
        return when {
            "USB\\" in upper -> Bus.USB
            "BTH" in upper -> Bus.BLUETOOTH
            "HDAUDIO\\" in upper -> Bus.HD_AUDIO
            else -> Bus.OTHER
        }
    }
}
