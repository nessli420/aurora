package com.aurora.music.desktop.platform

import com.aurora.music.desktop.linux.PortalAccent
import com.aurora.music.desktop.mac.MacAccent
import com.aurora.music.desktop.natives.WindowNative

object SystemAccent {
    fun current(): Int? = runCatching {
        when {
            HostPlatform.isWindows -> WindowNative.accentColor()
            HostPlatform.isLinux -> PortalAccent.read()
            HostPlatform.isMac -> MacAccent.read()
            else -> null
        }
    }.getOrNull()

    fun listen(onChange: (Int) -> Unit): AutoCloseable? = runCatching {
        when {
            HostPlatform.isWindows -> WindowNative.addAccentListener(onChange)
            HostPlatform.isLinux -> PortalAccent.listen(onChange)
            HostPlatform.isMac -> MacAccent.listen(onChange)
            else -> null
        }
    }.getOrNull()
}
