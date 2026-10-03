package com.aurora.music.desktop.linux

import com.aurora.music.util.AppLog
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

object SessionBus {
    private const val TAG = "SessionBus"
    private var connection: DBusConnection? = null
    private var unavailable = false

    @Synchronized
    fun connection(): DBusConnection? {
        connection?.takeIf { it.isConnected }?.let { return it }
        if (unavailable) return null
        return runCatching { DBusConnectionBuilder.forSessionBus().withShared(false).build() }
            .onFailure {
                unavailable = true
                AppLog.w(TAG, "The session bus is unavailable", it)
            }
            .getOrNull()
            ?.also { connection = it }
    }
}
