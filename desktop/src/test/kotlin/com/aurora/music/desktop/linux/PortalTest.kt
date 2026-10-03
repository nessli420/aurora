package com.aurora.music.desktop.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PortalTest {
    class Rgb(@field:Position(0) @JvmField val r: Double, @field:Position(1) @JvmField val g: Double, @field:Position(2) @JvmField val b: Double) : Struct()

    private class FakePortal(private val bus: DBusConnection, @Volatile var accent: Rgb?) : PortalSettings, PortalInhibit {
        val inhibits = CopyOnWriteArrayList<Long>()
        val closed = CopyOnWriteArrayList<String>()

        override fun getObjectPath() = PATH

        override fun ReadOne(namespace: String, key: String): Variant<*> {
            val value = accent.takeIf { namespace == "org.freedesktop.appearance" && key == "accent-color" }
                ?: throw DBusExecutionException("Requested setting not found")
            return Variant(value)
        }

        override fun Read(namespace: String, key: String): Variant<*> = Variant(ReadOne(namespace, key))

        override fun Inhibit(window: String, flags: UInt32, options: Map<String, Variant<*>>): DBusPath {
            inhibits += flags.toLong()
            val path = "$PATH/request/test/${inhibits.size}"
            bus.exportObject(path, object : PortalRequest {
                override fun getObjectPath() = path
                override fun Close() { closed += path }
            })
            return DBusPath(path)
        }
    }

    private fun PrivateBus.portal(accent: Rgb?): FakePortal {
        val connection = connect()
        return FakePortal(connection, accent).also {
            connection.exportObject(it)
            connection.requestBusName("org.freedesktop.portal.Desktop")
        }
    }

    @Test fun theAccentIsReadAndFollowed() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val portal = bus.portal(Rgb(1.0, 0.5, 0.0))
            val client = bus.connect()
            assertEquals(0xFFFF8000.toInt(), PortalAccent.read(client))
            val changed = CompletableFuture<Int>()
            PortalAccent.listen({ changed.complete(it) }, client)!!.use {
                bus.connect().sendMessage(PortalSettings.SettingChanged(PATH, "org.freedesktop.appearance", "accent-color", Variant(Rgb(0.0, 0.0, 1.0))))
                assertEquals(0xFF0000FF.toInt(), changed.get(5, TimeUnit.SECONDS))
            }
            portal.accent = null
            assertNull(PortalAccent.read(client))
        }
    }

    @Test fun anUnsetAccentIsIgnored() {
        assertNull(PortalAccent.argb(arrayOf(-1.0, -1.0, -1.0)))
        assertNull(PortalAccent.argb(listOf(0.1, 0.2)))
        assertNull(PortalAccent.argb("blue"))
        assertEquals(0xFF336699.toInt(), PortalAccent.argb(listOf(0.2, 0.4, 0.6)))
    }

    @Test fun playbackInhibitsSuspendUntilItStops() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val portal = bus.portal(null)
            val client = bus.connect()
            val inhibitor = SleepInhibitor { client }
            inhibitor.set(true)
            inhibitor.set(true)
            assertEquals(listOf(4L), portal.inhibits.toList())
            inhibitor.set(false)
            inhibitor.set(false)
            assertEquals(listOf("$PATH/request/test/1"), portal.closed.toList())
            SleepInhibitor { null }.set(true)
        }
    }

    @Test fun declaredSignaturesMatchThePortalSpecification() {
        PrivateBus.assumeAvailable("gdbus")
        PrivateBus().use { bus ->
            bus.portal(null)
            val xml = bus.run("gdbus", "introspect", "--session", "--xml", "-d", "org.freedesktop.portal.Desktop", "-o", PATH)
                .inputStream.bufferedReader().readText().replace(Regex("\\s+"), " ")
            fun types(name: String) = Regex("<method name=\"$name\"\\s*>(.*?)</method>").find(xml)!!.groupValues[1]
                .let { body -> Regex("type=\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList() }
            assertEquals(listOf("s", "s", "v"), types("ReadOne"))
            assertEquals(listOf("s", "u", "a{sv}", "o"), types("Inhibit"))
        }
    }

    private companion object {
        const val PATH = "/org/freedesktop/portal/desktop"
    }
}
