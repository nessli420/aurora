package com.aurora.music.desktop.player

import com.aurora.music.data.SettingsStore
import com.aurora.music.data.routes.OutputDeviceCategory
import com.aurora.music.data.routes.ProcessingRoute
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.rules.PresetPlaybackContext
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.OutputInfo
import com.aurora.music.desktop.platform.WindowsAudioEndpoints
import java.util.Locale

// the output device and the playing track, published for preset rules and output bindings the same way android does;
// the computer's own outputs count as the device route so per-output presets and eq bindings follow them
internal class RulePublisher(private val store: SettingsStore,
    private val busOf: (String) -> WindowsAudioEndpoints.Bus? = WindowsAudioEndpoints::bus) {
    // null matches the monitor's initial idle route
    private var published: OutputInfo? = null

    fun publish(state: EngineState) {
        if (state.output != published) {
            published = state.output
            store.processingRoutes.publish(route(state.output))
        }
        store.presetRuleContext.publish(context(state))
    }

    internal fun route(output: OutputInfo?): ProcessingRoute {
        if (output == null) return ProcessingRoute()
        val name = output.deviceName?.takeIf { it.isNotBlank() } ?: "System output"
        val id = output.deviceId?.takeIf { it.isNotBlank() } ?: name
        val hardware = output.hardwareVolumeDb?.let { String.format(Locale.US, " · hardware volume %.1f dB", it) }.orEmpty()
        return ProcessingRoute(ProcessingRouteKind.ANDROID, key = routeKey(id), label = name,
            detail = (if (output.exclusive) "Exclusive output" else "Shared output") + hardware, category = category(name, output.deviceId))
    }

    internal fun context(state: EngineState): PresetPlaybackContext {
        val song = state.current?.song
        if (song == null || !state.effectivelyPlaying && !state.playWhenReady) return PresetPlaybackContext()
        val source = song.playbackSource
        val stream = state.source
        return PresetPlaybackContext(active = true, mediaId = song.id,
            source = source?.source ?: if (song.streamUrl.startsWith("file:")) RuleSource.LOCAL_FILE else null,
            providerId = source?.providerId?.takeIf(PROVIDER::matches), providerLabel = source?.providerLabel?.take(512),
            albumId = source?.albumId?.takeIf(ALBUM::matches), albumName = song.album.takeIf { it.isNotBlank() }?.take(512),
            genres = song.genre.takeIf { it.isNotBlank() }?.let { setOf(it.take(512)) },
            playlistId = song.playbackCollection?.id?.takeIf(PLAYLIST::matches), playlistName = song.playbackCollection?.name?.takeIf { it.isNotBlank() }?.take(512),
            sampleRateHz = stream?.sampleRate?.takeIf { it > 0 }, codec = stream?.codec?.let(::token),
            container = (stream?.container?.substringBefore(',') ?: song.suffix).let(::container), cast = false)
    }

    private fun category(name: String, deviceId: String?): OutputDeviceCategory {
        when (deviceId?.let(busOf)) {
            WindowsAudioEndpoints.Bus.USB -> return OutputDeviceCategory.USB
            WindowsAudioEndpoints.Bus.BLUETOOTH -> return OutputDeviceCategory.BLUETOOTH
            else -> Unit
        }
        val lower = name.lowercase(Locale.ROOT)
        return when {
            listOf("bluetooth", "hands-free", "a2dp", "airpods", "buds").any(lower::contains) -> OutputDeviceCategory.BLUETOOTH
            listOf("usb", "dac").any(lower::contains) -> OutputDeviceCategory.USB
            "hdmi" in lower || "display" in lower -> OutputDeviceCategory.HDMI
            "headphone" in lower || "headset" in lower -> OutputDeviceCategory.HEADPHONES
            "speaker" in lower -> OutputDeviceCategory.SPEAKER
            else -> OutputDeviceCategory.OTHER
        }
    }

    private fun token(value: String): String? = value.lowercase(Locale.ROOT).takeIf { it.matches(TOKEN) }

    private fun container(value: String): String? = when (val lower = value.lowercase(Locale.ROOT)) {
        "wave", "x-wav" -> "wav"
        "mov", "m4a", "m4b", "mp4" -> "mp4"
        "ogg", "opus" -> "ogg"
        else -> lower
    }.takeIf { it.matches(TOKEN) }

    companion object {
        // shaped like android's route keys, which the shared rule, binding and level stores accept; type 0 never names an android device
        fun routeKey(deviceId: String): String = "android:0:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("desktop\u0000$deviceId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private val PROVIDER = Regex("provider:[0-9a-f]{64}")
        private val ALBUM = Regex("album:[0-9a-f]{64}")
        private val PLAYLIST = Regex("playlist:[0-9a-f]{64}")
        private val TOKEN = Regex("[a-z0-9._+-]{1,40}")
    }
}
