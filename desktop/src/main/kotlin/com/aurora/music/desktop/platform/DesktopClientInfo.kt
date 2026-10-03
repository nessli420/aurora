package com.aurora.music.desktop.platform

import com.aurora.music.data.remote.ClientInfo

val desktopClientInfo = ClientInfo("Aurora", BuildInfo.VERSION_NAME, HostPlatform.name, HostPlatform.name, "Aurora ${HostPlatform.name}")
