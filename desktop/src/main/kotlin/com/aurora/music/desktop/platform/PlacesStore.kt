package com.aurora.music.desktop.platform

import com.aurora.music.data.PlaceZones
import com.aurora.music.data.Places
import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.desktop.linux.PortalLocation
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class LocationFix(val latitude: Double, val longitude: Double, val accuracyM: Float, val at: Long)

sealed interface LocationResult {
    data class Found(val fix: LocationFix) : LocationResult
    data object Denied : LocationResult
    data object Unavailable : LocationResult
}

fun interface LocationSource {
    fun locate(): LocationResult
}

// windows' own location service, the one its privacy settings switch on and off for desktop apps
object WindowsLocation : LocationSource {
    private const val SCRIPT = "Add-Type -AssemblyName System.Device;" +
        "\$w = New-Object System.Device.Location.GeoCoordinateWatcher('Default');" +
        "\$w.Start();" +
        "\$end = (Get-Date).AddSeconds(15);" +
        "while (\$w.Status -ne 'Ready' -and \$w.Permission -ne 'Denied' -and (Get-Date) -lt \$end) { Start-Sleep -Milliseconds 200 };" +
        "\$c = \$w.Position.Location;" +
        "if (\$w.Permission -eq 'Denied') { 'DENIED' } elseif (\$w.Status -eq 'Ready' -and -not \$c.IsUnknown) {" +
        "\$i = [Globalization.CultureInfo]::InvariantCulture;" +
        "'FIX ' + \$c.Latitude.ToString(\$i) + ' ' + \$c.Longitude.ToString(\$i) + ' ' + \$c.HorizontalAccuracy.ToString(\$i) } else { 'NONE' };" +
        "\$w.Stop()"

    override fun locate(): LocationResult = runCatching {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", SCRIPT)
            .redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return LocationResult.Unavailable
        }
        parse(process.inputStream.bufferedReader().readText())
    }.getOrDefault(LocationResult.Unavailable)

    internal fun parse(output: String): LocationResult {
        val line = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("FIX ") || it == "DENIED" || it == "NONE" }
        if (line == "DENIED") return LocationResult.Denied
        val parts = line?.removePrefix("FIX ")?.split(' ')?.mapNotNull(String::toDoubleOrNull)
        if (line == null || !line.startsWith("FIX ") || parts?.size != 3) return LocationResult.Unavailable
        val (latitude, longitude, accuracy) = parts
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return LocationResult.Unavailable
        return LocationResult.Found(LocationFix(latitude, longitude, accuracy.toFloat().takeIf { it.isFinite() && it > 0 } ?: 1000f, System.currentTimeMillis()))
    }
}

// home and work stay out of settings and every backup; only this computer ever holds them
class PlacesStore(directory: File, private val source: LocationSource? = when {
    HostPlatform.isWindows -> WindowsLocation
    HostPlatform.isLinux -> PortalLocation()
    else -> null
}) {
    private val file = File(directory, "places.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(load())
    val places: StateFlow<Places> = state.asStateFlow()
    private val mutableDenied = MutableStateFlow(false)
    val denied: StateFlow<Boolean> = mutableDenied.asStateFlow()

    @Volatile private var zone: String? = null
    @Volatile private var fixedAt = Long.MIN_VALUE / 2
    @Volatile private var pending = false
    @Volatile private var lastFix: LocationFix? = null
    @Volatile private var pointInterval = POINT_REFRESH_MS

    val supported: Boolean get() = source != null

    fun update(transform: (Places) -> Places) {
        val next = transform(state.value)
        state.value = next
        runCatching { persistBackupFileAtomically(file, gson.toJson(next).toByteArray(Charsets.UTF_8)) }
        zone = null
        fixedAt = Long.MIN_VALUE / 2
    }

    fun clear() {
        state.value = Places()
        file.delete()
        zone = null
    }

    // fixes only while something plays: every ten minutes for labels, every two for the location history map,
    // stretching while the computer stays put. the lookup runs in the background and the play uses the last answer
    fun sample(zone: Boolean, point: Boolean): Pair<String?, LocationFix?> {
        val now = System.currentTimeMillis()
        val wanted = (zone && state.value.configured) || point
        val refresh = if (point) pointInterval else REFRESH_MS
        if (wanted && !pending && now - fixedAt > refresh && source != null) {
            pending = true
            scope.launch {
                val result = source.locate()
                fixedAt = System.currentTimeMillis()
                mutableDenied.value = result is LocationResult.Denied
                val previous = lastFix
                lastFix = (result as? LocationResult.Found)?.fix
                if (point) pointInterval = nextPointInterval(previous, lastFix)
                this@PlacesStore.zone = lastFix?.let { PlaceZones.zoneFor(it.latitude, it.longitude, it.accuracyM, state.value) }
                pending = false
            }
        }
        return (if (zone) this.zone?.takeIf { now - fixedAt <= STALE_MS } else null) to
            (if (point) lastFix?.takeIf { now - it.at <= pointInterval + POINT_STALE_SLACK_MS } else null)
    }

    suspend fun currentLocation(): LocationResult = withContext(Dispatchers.IO) {
        (source?.locate() ?: LocationResult.Unavailable).also { mutableDenied.value = it is LocationResult.Denied }
    }

    private fun nextPointInterval(previous: LocationFix?, current: LocationFix?): Long {
        if (previous == null || current == null) return POINT_REFRESH_MS
        val moved = PlaceZones.distanceM(previous.latitude, previous.longitude, current.latitude, current.longitude)
        val still = moved <= maxOf(STILL_RADIUS_M, previous.accuracyM, current.accuracyM).toDouble()
        return if (still) (pointInterval * 2).coerceAtMost(POINT_MAX_REFRESH_MS) else POINT_REFRESH_MS
    }

    private fun load(): Places = runCatching {
        if (file.exists()) gson.fromJson(file.readText(), Places::class.java) ?: Places() else Places()
    }.getOrDefault(Places())

    private companion object {
        const val REFRESH_MS = 10 * 60_000L
        const val STALE_MS = 30 * 60_000L
        const val POINT_REFRESH_MS = 2 * 60_000L
        const val POINT_MAX_REFRESH_MS = 8 * 60_000L
        const val POINT_STALE_SLACK_MS = 3 * 60_000L
        const val STILL_RADIUS_M = 60f
    }
}
