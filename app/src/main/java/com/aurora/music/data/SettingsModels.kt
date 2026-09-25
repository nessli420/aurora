package com.aurora.music.data

import com.aurora.music.R
import com.aurora.music.localization.appString

val DEFAULT_SOURCE_PRIORITY = listOf("local", "downloaded", "stream")

// keep empty for all eligible servers
const val MERGE_NONE = "__none__"

enum class ServerType {
    SUBSONIC, JELLYFIN, SPOTIFY, LOCAL, EXTENSION, YOUTUBE_MUSIC, PLEX;

    val supportsMergedLibrary: Boolean get() = this == SUBSONIC || this == JELLYFIN || this == PLEX || this == LOCAL || this == YOUTUBE_MUSIC
}

// sessions store tokens without raw passwords
data class Session(
    val server: String,
    val username: String,
    val salt: String,
    val token: String,
    val type: ServerType = ServerType.SUBSONIC,
    val userId: String = "",
    val imageUrl: String = "",
    val clientToken: String = "",
    val clientVersion: String = "",
) {
    val isValid: Boolean get() = server.isNotBlank() && username.isNotBlank() && token.isNotBlank()

    val typeLabel: String get() = when (type) {
        ServerType.SPOTIFY -> "Spotify"
        ServerType.YOUTUBE_MUSIC -> "YouTube Music"
        ServerType.JELLYFIN -> "Jellyfin"
        ServerType.PLEX -> "Plex"
        ServerType.SUBSONIC -> "Navidrome"
        ServerType.LOCAL -> "On this device"
        ServerType.EXTENSION -> "Extension"
    }
}

fun Session.accountKey(): String = when (type) {
    ServerType.YOUTUBE_MUSIC -> "${type.name}|$server|$userId"
    ServerType.PLEX -> {
        // separate users sharing the same plex server
        val credentialId = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        "${type.name}|$server|$userId|$credentialId"
    }
    else -> "${type.name}|$server|$username|$userId"
}

data class PlaybackPrefs(
    val skipSilence: Boolean = false,
    val crossfadeSec: Int = 0,
    val crossfadeCurve: String = "SMOOTH",
    val crossfadeHeadroom: Boolean = true,
    val gapless: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val monoAudio: Boolean = false,
    val streamWifi: Int = 0,        // 0 uses original quality
    val streamCellular: Int = 0,
    val downloadBitrate: Int = 0,   // 0 uses original quality
    val preferHighRes: Boolean = false,
    val scrobble: Boolean = true,
    val autoplayRadio: Boolean = false,
    val bitPerfectUsb: Boolean = false,
    val independentOutput: Boolean = false, // keeps other apps playing
    val outputRatePolicy: com.aurora.music.playback.engine.OutputRatePolicy = com.aurora.music.playback.engine.OutputRatePolicy(),
    val usbOutputMode: UsbOutputMode = UsbOutputMode.DIRECT,
    val usbFallbackPolicy: UsbFallbackPolicy = UsbFallbackPolicy.PAUSE,
    val usbDsdMode: UsbDsdMode? = null,
    val usbDsdExperimental: Boolean? = null,
)

object VisualizerStyle {
    const val BARS = 0
    const val MIRROR_BARS = 1
    const val WAVEFORM = 2
    const val FILLED_WAVE = 3
    const val RADIAL_BARS = 4
    const val RADIAL_WAVE = 5
    const val PARTICLES = 6
    const val FLUID = 7
    const val COMBO = 8
    const val SMOOTH_CURVE = 9
    const val DOT_GRID = 10
    const val RINGS = 11
    const val ORB = 12
    const val LADDER = 13
    const val HORIZON = 14
    const val CONSTELLATION = 15
    const val PEAK_DOTS = 16
    const val SPECTRUM_LINE = 17
    const val AURORA = 18
    const val SPECTRAL_RIVER = 19
    const val SPECTRAL_TERRAIN = 20
    const val CURL_FLOW = 21
    const val STRANGE_ATTRACTOR = 22
    const val CYMATIC = 23
    const val SUPERFORMULA_BLOOM = 24
    const val WORMHOLE = 25
    const val PLASMA = 26
    const val SILK_VEIL = 27
    const val NEBULA = 28
    const val HARMONOGRAPH = 29
    const val INK_BLOOM = 30
    const val count = 31
    fun label(v: Int) = when (v) {
        BARS -> appString(R.string.text_spectrum_bars_6379ce); MIRROR_BARS -> appString(R.string.text_mirror_bars_b4cfbc); WAVEFORM -> appString(R.string.text_waveform_200f14)
        FILLED_WAVE -> appString(R.string.text_filled_wave_5eaa91); RADIAL_BARS -> appString(R.string.text_radial_spectrum_477a35); RADIAL_WAVE -> appString(R.string.text_radial_wave_03beca)
        PARTICLES -> appString(R.string.text_particles_07cdbc); FLUID -> appString(R.string.text_fluid_blob_088ed9); COMBO -> appString(R.string.text_combo_dcae58)
        SMOOTH_CURVE -> appString(R.string.text_spectrum_curve_f0b0b0); DOT_GRID -> appString(R.string.text_dot_matrix_d4d639); RINGS -> appString(R.string.text_pulse_rings_0a7012)
        ORB -> appString(R.string.text_orb_980143); LADDER -> appString(R.string.text_led_ladder_dc67bb); HORIZON -> appString(R.string.text_horizon_eda243); CONSTELLATION -> appString(R.string.text_constellation_785bdc)
        PEAK_DOTS -> appString(R.string.text_peak_dots_3fb145); SPECTRUM_LINE -> appString(R.string.text_neon_line_af89a0); AURORA -> appString(R.string.text_aurora_eeee9b)
        SPECTRAL_RIVER -> appString(R.string.text_spectral_river_b18902); SPECTRAL_TERRAIN -> appString(R.string.text_terrain_flyover_ba8ceb); CURL_FLOW -> appString(R.string.text_curl_flow_19ab49)
        STRANGE_ATTRACTOR -> appString(R.string.text_strange_attractor_70352b); CYMATIC -> appString(R.string.text_cymatics_459c6a); SUPERFORMULA_BLOOM -> appString(R.string.text_bloom_3ef84d)
        WORMHOLE -> appString(R.string.text_wormhole_18880e)
        PLASMA -> appString(R.string.text_liquid_chrome_af5cd1); SILK_VEIL -> appString(R.string.text_silk_veil_fa6add); NEBULA -> appString(R.string.text_nebula_drift_4aee62)
        HARMONOGRAPH -> appString(R.string.text_harmonograph_225500); INK_BLOOM -> appString(R.string.text_ink_bloom_32d8dc)
        else -> appString(R.string.text_spectrum_bars_6379ce)
    }
}

object VizColor { const val ACCENT = 0; const val CUSTOM = 1; const val GRADIENT = 2; const val ALBUM_ART = 3 }

object VizBackground { const val BLACK = 0; const val GRADIENT = 1; const val ALBUM_BLUR = 2 }

data class VisualizerPrefs(
    val style: Int = VisualizerStyle.BARS,
    val colorSource: Int = VizColor.ACCENT,
    val primaryColor: Int = 0xFF7C4DFF.toInt(),
    val secondaryColor: Int = 0xFF00E5FF.toInt(),
    val background: Int = VizBackground.GRADIENT,
    val barCount: Int = 64,
    val smoothing: Float = 0.78f,
    val sensitivity: Float = 1.0f,
    val minHz: Int = 30,
    val maxHz: Int = 16000,
    val peakHold: Boolean = true,
    val mirror: Boolean = false,
    val fftSize: Int = 2048,
    val fpsCap: Int = 60,
    val rotate: Boolean = false,
    val particleCount: Int = 140,
    val showAlbumArt: Boolean = true,
    val showTrackInfo: Boolean = true,
)

object DspMode { const val SYSTEM = 0; const val CUSTOM = 1; const val OFF = 2 }

data class AudioPrefs(
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,                 // -1 means custom
    val eqBands: List<Int> = emptyList(),   // millibel gain per band
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudnessGain: Int = 0,
    val replayGain: Int = 0,                // 0 off 1 track 2 album
    val dspMode: Int = DspMode.SYSTEM,
    val dspGraphicBands: List<Float> = emptyList(),
    val dspParametric: List<ParamBand> = emptyList(),
    val dspPreampDb: Float = 0f,
    val dspBalance: Float = 0f,             // -1 left 1 right
    val dspWidth: Float = 1f,               // 0 mono 1 normal 2 wide
    val dspCrossfeed: Float = 0f,
    val dspLimiterEnabled: Boolean = true,
    val dspLimiterCeilingDb: Float = -0.3f,
    val dspCompEnabled: Boolean = false,
    val dspCompThreshDb: Float = -18f,
    val dspCompRatio: Float = 2f,
    val dspConvEnabled: Boolean = false,
    val dspConvIrPath: String = "",
    val dspConvIrName: String = "",
    val dspConvMakeupDb: Float = 0f,
    val dspGraphicLayout: Int = 0,          // index into graphic layouts
    val dspSaturation: Float = 0f,
    val dspDelayLeftMs: Float = 0f,
    val dspDelayRightMs: Float = 0f,
    val dspTrimLeftDb: Float = 0f,
    val dspTrimRightDb: Float = 0f,
)

object ThemeMode { const val SYSTEM = 0; const val LIGHT = 1; const val DARK = 2; const val AMOLED = 3 }

object ThemeStyle {
    const val AURORA = 0
    const val RETRO = 1
    const val AERO = 2
    const val GLASS = 3
}

object AppTypeface {
    const val THEME_DEFAULT = 0
    const val DM_SANS = 1
    const val PLUS_JAKARTA_SANS = 2
    const val MANROPE = 3

    fun normalize(value: Int): Int = value.takeIf { it in THEME_DEFAULT..MANROPE } ?: THEME_DEFAULT
}

object AccentMode { const val PRESET = 0; const val CUSTOM = 1; const val MATERIAL_YOU = 2 }

object CornerStyle { const val SHARP = 0; const val DEFAULT = 1; const val ROUNDED = 2; const val PILL = 3 }

object SeekStyle { const val WAVEFORM = 0; const val BAR = 1 }

object MiniStyle { const val STANDARD = 0; const val COMPACT = 1; const val PROMINENT = 2 }

object MiniProgress { const val LINE = 0; const val BAR = 1; const val NONE = 2 }

object HomeSection {
    const val HERO = "hero"; const val RECENT = "recent"; const val PLAYLISTS = "playlists"
    const val FAVOURITE = "favourite"; const val MOST = "most"; const val ARTISTS = "artists"; const val NEW = "new"
    const val RECOMMENDED = "recommended"
}

data class UiPrefs(
    val themeMode: Int = ThemeMode.DARK,
    val themeStyle: Int = ThemeStyle.AURORA,
    val accentMode: Int = AccentMode.PRESET,
    val accentPreset: Int = 0,
    val accentColor: Long = 0xFFFF2E7EL,
    val fontScale: Float = 1f,
    val typeface: Int = AppTypeface.THEME_DEFAULT,
    val cornerStyle: Int = CornerStyle.DEFAULT,
    val playerSeekStyle: Int = SeekStyle.WAVEFORM,
    val playerWaveBars: Int = 60,
    val playerArtSize: Float = 0.86f,
    val playerGradient: Float = 1f,
    val playerShowUtilities: Boolean = true,
    val miniStyle: Int = MiniStyle.STANDARD,
    val miniProgress: Int = MiniProgress.LINE,
    val libraryColumns: Int = 2,
    val hiddenHomeSections: Set<String> = emptySet(),
)

data class LastfmAccount(
    val sessionKey: String = "",
    val username: String = "",
    val imageUrl: String = "",
    val enabled: Boolean = true,
)

data class ListenBrainzAccount(
    val token: String = "",
    val username: String = "",
    val enabled: Boolean = true,
)

data class DiscordAccount(
    val token: String = "",
    val username: String = "",
    val enabled: Boolean = true,
    val imgurClientId: String = "",
    val appId: String = "",
    val showAlbum: Boolean = true,
    val activityName: String = "aurora",
)

// pins are scoped to server id
data class Pin(
    val id: String = "",
    val kind: String = "",        // album playlist artist
    val title: String = "",
    val subtitle: String = "",
    val coverUrl: String = "",
    val serverId: String = "",
)

data class EqBinding(
    val deviceKey: String = "",
    val deviceLabel: String = "",
    val profileName: String = "",
    val preampDb: Float = 0f,
    val bands: List<ParamBand> = emptyList(),
)

data class AlarmPrefs(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
)

data class GesturePrefs(
    val swipeArtwork: Boolean = true,
    val swipeDownDismiss: Boolean = true,
    val doubleTapPause: Boolean = true,
)
