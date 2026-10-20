package com.aurora.music.desktop.linux

import com.aurora.music.desktop.platform.LocationResult
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class PortalLocationTest {
    private class FakePortal(private val bus: DBusConnection, private val client: String, private val answer: Answer) : PortalLocationService {
        enum class Answer { FIX, REFUSE, SILENCE }

        val closed = CopyOnWriteArrayList<String>()
        val accuracy = CopyOnWriteArrayList<Long>()

        override fun getObjectPath() = PATH

        override fun CreateSession(options: Map<String, Variant<*>>): DBusPath {
            accuracy += (options["accuracy"]?.value as UInt32).toLong()
            val path = "$PATH/session/$client/${options.getValue("session_handle_token").value}"
            bus.exportObject(path, object : PortalSession {
                override fun getObjectPath() = path
                override fun Close() { closed += path }
            })
            return DBusPath(path)
        }

        override fun Start(session: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath {
            val request = "$PATH/request/$client/${options.getValue("handle_token").value}"
            thread {
                Thread.sleep(100)
                when (answer) {
                    Answer.FIX -> {
                        bus.sendMessage(PortalLocationService.LocationUpdated(PATH, DBusPath("$PATH/session/$client/other"),
                            mapOf("Latitude" to Variant(1.0), "Longitude" to Variant(2.0), "Accuracy" to Variant(5.0))))
                        bus.sendMessage(PortalLocationService.LocationUpdated(PATH, session,
                            mapOf("Latitude" to Variant(52.37), "Longitude" to Variant(4.89), "Accuracy" to Variant(35.0))))
                    }
                    Answer.REFUSE -> bus.sendMessage(PortalRequest.Response(request, UInt32(2), emptyMap()))
                    Answer.SILENCE -> Unit
                }
            }
            return DBusPath(request)
        }
    }

    private fun PrivateBus.portal(client: DBusConnection, answer: FakePortal.Answer): FakePortal {
        val connection = connect()
        return FakePortal(connection, client.uniqueName.removePrefix(":").replace('.', '_'), answer).also {
            connection.exportObject(it)
            connection.requestBusName("org.freedesktop.portal.Desktop")
        }
    }

    @Test fun theFirstFixForOurSessionIsReturnedAndTheSessionClosed() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val client = bus.connect()
            val portal = bus.portal(client, FakePortal.Answer.FIX)
            val result = PortalLocation({ client }).locate()
            val fix = (result as LocationResult.Found).fix
            assertEquals(52.37, fix.latitude, 0.0)
            assertEquals(4.89, fix.longitude, 0.0)
            assertEquals(35f, fix.accuracyM)
            assertEquals(listOf(5L), portal.accuracy.toList())
            val until = System.currentTimeMillis() + 5_000
            while (portal.closed.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(1, portal.closed.size)
        }
    }

    @Test fun aRefusalReadsAsDenied() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val client = bus.connect()
            bus.portal(client, FakePortal.Answer.REFUSE)
            assertEquals(LocationResult.Denied, PortalLocation({ client }).locate())
        }
    }

    @Test fun noPortalOrNoAnswerIsUnavailable() {
        PrivateBus.assumeAvailable()
        PrivateBus().use { bus ->
            val client = bus.connect()
            assertEquals(LocationResult.Unavailable, PortalLocation({ client }).locate())
            assertEquals(LocationResult.Unavailable, PortalLocation({ null }).locate())
            bus.portal(client, FakePortal.Answer.SILENCE)
            val started = System.currentTimeMillis()
            assertEquals(LocationResult.Unavailable, PortalLocation({ client }, timeoutMs = 500).locate())
            assertTrue(System.currentTimeMillis() - started < 5_000)
        }
    }

    private companion object {
        const val PATH = "/org/freedesktop/portal/desktop"
    }
}
