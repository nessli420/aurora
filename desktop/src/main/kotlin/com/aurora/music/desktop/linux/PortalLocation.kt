package com.aurora.music.desktop.linux

import com.aurora.music.desktop.platform.LocationFix
import com.aurora.music.desktop.platform.LocationResult
import com.aurora.music.desktop.platform.LocationSource
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// the desktop portal's location service (geoclue underneath), switched on and off in the desktop's privacy settings
class PortalLocation(
    private val connection: () -> DBusConnection? = SessionBus::connection,
    private val timeoutMs: Long = 20_000,
) : LocationSource {
    override fun locate(): LocationResult {
        val bus = connection() ?: return LocationResult.Unavailable
        return runCatching { locate(bus) }.getOrDefault(LocationResult.Unavailable)
    }

    private fun locate(bus: DBusConnection): LocationResult {
        val token = "aurora_" + UUID.randomUUID().toString().replace("-", "")
        val request = "$PORTAL_PATH/request/${bus.uniqueName.removePrefix(":").replace('.', '_')}/$token"
        val portal = bus.getRemoteObject(PORTAL_BUS, PORTAL_PATH, PortalLocationService::class.java)
        val session = portal.CreateSession(mapOf("session_handle_token" to Variant(token), "accuracy" to Variant(UInt32(EXACT))))
        val outcome = CompletableFuture<LocationResult>()
        // both handlers go in before Start so neither the answer nor the first fix can arrive unheard
        val updates = bus.addSigHandler(PortalLocationService.LocationUpdated::class.java) { signal ->
            if (signal.session.path == session.path) fix(signal.location)?.let { outcome.complete(LocationResult.Found(it)) }
        }
        val responses = bus.addSigHandler(PortalRequest.Response::class.java) { signal ->
            if (signal.path == request && signal.response.toLong() != 0L) outcome.complete(LocationResult.Denied)
        }
        return try {
            portal.Start(session, "", mapOf("handle_token" to Variant(token)))
            outcome.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            LocationResult.Unavailable
        } finally {
            runCatching { updates.close() }
            runCatching { responses.close() }
            runCatching { bus.getRemoteObject(PORTAL_BUS, session.path, PortalSession::class.java).Close() }
        }
    }

    internal fun fix(location: Map<String, Variant<*>>): LocationFix? {
        val latitude = (location["Latitude"]?.value as? Double)?.takeIf { it in -90.0..90.0 } ?: return null
        val longitude = (location["Longitude"]?.value as? Double)?.takeIf { it in -180.0..180.0 } ?: return null
        val accuracy = (location["Accuracy"]?.value as? Double)?.toFloat()?.takeIf { it.isFinite() && it > 0 } ?: 1000f
        return LocationFix(latitude, longitude, accuracy, System.currentTimeMillis())
    }

    private companion object {
        const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
        const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        const val EXACT = 5L
    }
}
