package com.aurora.music.desktop.platform

import com.aurora.music.desktop.linux.PortalAccent
import com.aurora.music.desktop.natives.WindowNative

object SystemAccent {
    fun current(): Int? = runCatching {
        when {
            HostPlatform.isWindows -> WindowNative.accentColor()
            HostPlatform.isLinux -> PortalAccent.read()
            else -> null
        }
    }.getOrNull()

    fun listen(onChange: (Int) -> Unit): AutoCloseable? = runCatching {
        when {
            HostPlatform.isWindows -> WindowNative.addAccentListener(onChange)
            HostPlatform.isLinux -> PortalAccent.listen(onChange)
            else -> null
        }
    }.getOrNull()
}
