package com.aurora.music.desktop.linux

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.math.roundToInt

private const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"

object PortalAccent {
    private const val NAMESPACE = "org.freedesktop.appearance"
    private const val KEY = "accent-color"

    fun read(bus: DBusConnection? = SessionBus.connection()): Int? {
        val settings = bus?.getRemoteObject(PORTAL_BUS, PORTAL_PATH, PortalSettings::class.java) ?: return null
        val value = runCatching { settings.ReadOne(NAMESPACE, KEY).value }
            .recoverCatching { settings.Read(NAMESPACE, KEY).value.let { (it as? Variant<*>)?.value ?: it } }
            .getOrNull()
        return argb(value)
    }

    fun listen(onChange: (Int) -> Unit, bus: DBusConnection? = SessionBus.connection()): AutoCloseable? = runCatching {
        bus?.addSigHandler(PortalSettings.SettingChanged::class.java) { signal ->
            if (signal.namespace == NAMESPACE && signal.key == KEY) argb(signal.value.value)?.let(onChange)
        }
    }.getOrNull()

    internal fun argb(value: Any?): Int? {
        val parts = when (value) {
            is Struct -> value.parameters.toList()
            is Array<*> -> value.toList()
            is List<*> -> value
            else -> return null
        }.map { (it as? Number)?.toDouble() ?: return null }
        if (parts.size != 3 || parts.any { it !in 0.0..1.0 }) return null
        val (r, g, b) = parts.map { (it * 255).roundToInt() }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

class SleepInhibitor(private val connection: () -> DBusConnection? = SessionBus::connection) {
    private var release: (() -> Unit)? = null

    @Synchronized
    fun set(enabled: Boolean) {
        if (enabled == (release != null)) return
        if (!enabled) {
            release?.invoke()
            release = null
            return
        }
        val bus = connection() ?: return
        release = runCatching {
            val handle = bus.getRemoteObject(PORTAL_BUS, PORTAL_PATH, PortalInhibit::class.java)
                .Inhibit("", UInt32(SUSPEND), mapOf("reason" to Variant("Playing music")))
            val close: () -> Unit = { runCatching { bus.getRemoteObject(PORTAL_BUS, handle.path, PortalRequest::class.java).Close() } }
            close
        }.getOrNull()
    }

    private companion object {
        const val SUSPEND = 4L
    }
}
