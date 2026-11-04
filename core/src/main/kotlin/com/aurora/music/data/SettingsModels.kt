package com.aurora.music.data

val DEFAULT_SOURCE_PRIORITY = listOf("local", "downloaded", "stream")

// how youtube music and spotify tracks are swapped for a copy from your own library
data class CatalogueMatchPrefs(
    val useLibrary: Boolean = true,
    val relaxed: Boolean = false,
    val losslessOnly: Boolean = false,
)

// keep empty for all eligible servers
const val MERGE_NONE = "__none__"

// what the merged catalogues add to songs, switched overall and per kind of source a song comes from
data class MergeFeatures(
    val canvas: Boolean = true,
    val canvasOff: Set<String> = emptySet(),
    val videos: Boolean = true,
    val videosOff: Set<String> = emptySet(),
    val comments: Boolean = true,
    val commentsOff: Set<String> = emptySet(),
    val lyrics: Boolean = true,
    val lyricsOff: Set<String> = emptySet(),
) {
    fun canvasFor(type: ServerType?) = canvas && type?.name !in canvasOff
    fun videosFor(type: ServerType?) = videos && type?.name !in videosOff
    fun commentsFor(type: ServerType?) = comments && type?.name !in commentsOff
    fun lyricsFor(type: ServerType?) = lyrics && type?.name !in lyricsOff
}

// "server" is the song's own source; the first synced result in this order wins, else the first with any lyrics
val DEFAULT_LYRICS_SOURCES = listOf("server", "spotify", "lrclib")

enum class ServerType {
    SUBSONIC, JELLYFIN, SPOTIFY, LOCAL, EXTENSION, YOUTUBE_MUSIC, PLEX, SOUNDCLOUD;

    val supportsMergedLibrary: Boolean get() = this == SUBSONIC || this == JELLYFIN || this == PLEX || this == LOCAL || this == YOUTUBE_MUSIC || this == SPOTIFY || this == SOUNDCLOUD

    // streaming catalogues get their own home, search and library views instead of mixing into the merged one
    val isCatalogue: Boolean get() = this == YOUTUBE_MUSIC || this == SPOTIFY || this == SOUNDCLOUD

    // browsable and playable signed out through the service's public access
    val supportsGuest: Boolean get() = this == YOUTUBE_MUSIC || this == SOUNDCLOUD
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

    val isGuest: Boolean get() = salt == GUEST && type.supportsGuest

    val typeLabel: String get() = when (type) {
        ServerType.SPOTIFY -> "Spotify"
        ServerType.YOUTUBE_MUSIC -> "YouTube Music"
        ServerType.SOUNDCLOUD -> "SoundCloud"
        ServerType.JELLYFIN -> "Jellyfin"
        ServerType.PLEX -> "Plex"
        ServerType.SUBSONIC -> "Navidrome"
        ServerType.LOCAL -> "On this device"
        ServerType.EXTENSION -> "Extension"
    }

    companion object {
        const val GUEST = "guest"

        fun guest(type: ServerType, name: String): Session {
            require(type.supportsGuest)
            val server = if (type == ServerType.SOUNDCLOUD) com.aurora.music.data.remote.SoundCloudClient.WEB else com.aurora.music.data.remote.YouTubeMusicClient.ORIGIN
            return Session(server, name, GUEST, GUEST, type, userId = GUEST)
        }
    }
}

fun Session.accountKey(): String = when (type) {
    // the stable account id, so a changed display name stays the same account
    ServerType.YOUTUBE_MUSIC, ServerType.SPOTIFY, ServerType.SOUNDCLOUD -> "${type.name}|$server|$userId"
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
    val resumePodcasts: Boolean = true,
    val resumeLongTracks: Boolean = true,
    val longFormSkipButtons: Boolean = true,
    val bitPerfectUsb: Boolean = false,
    val independentOutput: Boolean = false, // keeps other apps playing
    val outputRatePolicy: com.aurora.music.playback.engine.OutputRatePolicy = com.aurora.music.playback.engine.OutputRatePolicy(),
    val usbOutputMode: UsbOutputMode = UsbOutputMode.DIRECT,
    val usbFallbackPolicy: UsbFallbackPolicy = UsbFallbackPolicy.PAUSE,
    val usbDsdMode: UsbDsdMode? = null,
    val usbDsdExperimental: Boolean? = null,
    val usbHardwareVolume: Boolean? = null,
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

object LyricsSync { const val LINE = 0; const val WORD = 1; const val VOCALS = 2 }

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
    val tabletDockScale: Float = TabletSetting.DOCK.default,
    val tabletPanelScale: Float = TabletSetting.PANEL.default,
    val tabletPanelSpacing: Float = TabletSetting.PANEL_SPACING.default,
    val tabletNavGap: Float = TabletSetting.NAV_GAP.default,
    val tabletPageMargin: Float = TabletSetting.PAGE_MARGIN.default,
    val tabletHeroScale: Float = TabletSetting.HERO.default,
    val tabletFavouriteScale: Float = TabletSetting.FAVOURITE.default,
    val tabletPlayerSplit: Float = TabletSetting.PLAYER_SPLIT.default,
    val navLayout: String = "",
    val lyricsSync: Int = LyricsSync.WORD,
    val lyricsTranslate: Boolean = false,
)

enum class TabletSetting(val default: Float, val range: ClosedFloatingPointRange<Float>) {
    DOCK(0.85f, 0.7f..1.15f),
    PANEL(0.8f, 0.7f..1.1f),
    PANEL_SPACING(6f, 0f..20f),
    NAV_GAP(16f, 0f..40f),
    PAGE_MARGIN(24f, 0f..120f),
    HERO(0.85f, 0.6f..1.1f),
    FAVOURITE(0.8f, 0.6f..1f),
    PLAYER_SPLIT(0.5f, 0.35f..0.65f),
}

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

data class ReverbPrefs(
    val enabled: Boolean = false,
    val preset: Int = ReverbPreset.HALL,    // -1 means custom
    val mix: Float = 0.25f,
    val decaySec: Float = 2.4f,
    val size: Float = 0.7f,
    val predelayMs: Float = 20f,
    val damping: Float = 0.45f,
    val lowCutHz: Float = 160f,
    val highCutHz: Float = 9000f,
    val width: Float = 1f,
    val early: Float = 0.3f,
    val modulation: Float = 0.35f,
    val concert: ConcertPrefs? = ConcertPrefs(),
) {
    val concertPrefs: ConcertPrefs get() = concert ?: ConcertPrefs()
    fun clamped(): ReverbPrefs = copy(
        mix = mix.finite(0.25f).coerceIn(0f, 1f), decaySec = decaySec.finite(2.4f).coerceIn(0.2f, 12f),
        size = size.finite(0.7f).coerceIn(0f, 1f), predelayMs = predelayMs.finite(20f).coerceIn(0f, 200f),
        damping = damping.finite(0.45f).coerceIn(0f, 1f), lowCutHz = lowCutHz.finite(160f).coerceIn(20f, 1000f),
        highCutHz = highCutHz.finite(9000f).coerceIn(1500f, 20000f), width = width.finite(1f).coerceIn(0f, 1.5f),
        early = early.finite(0.3f).coerceIn(0f, 1f), modulation = modulation.finite(0.35f).coerceIn(0f, 1f),
        concert = concertPrefs.clamped(),
    )
    fun withPreset(id: Int): ReverbPrefs = ReverbPreset.ALL.getOrNull(id)?.let {
        it.copy(enabled = enabled, preset = id, mix = mix, concert = concertPrefs.copy(enabled = false))
    } ?: this
    private fun Float.finite(fallback: Float) = if (isFinite()) this else fallback
}

object ReverbPreset {
    const val ROOM = 0; const val HALL = 1; const val PLATE = 2; const val CATHEDRAL = 3; const val AMBIENCE = 4; const val LUSH = 5
    // mix is user-owned, presets only shape the space
    val ALL = listOf(
        ReverbPrefs(decaySec = 0.9f, size = 0.35f, predelayMs = 6f, damping = 0.55f, lowCutHz = 140f, highCutHz = 8000f, width = 0.9f, early = 0.6f, modulation = 0.2f),
        ReverbPrefs(),
        ReverbPrefs(decaySec = 1.9f, size = 0.5f, predelayMs = 8f, damping = 0.2f, lowCutHz = 220f, highCutHz = 12000f, width = 1.1f, early = 0.05f, modulation = 0.55f),
        ReverbPrefs(decaySec = 6f, size = 1f, predelayMs = 45f, damping = 0.5f, lowCutHz = 120f, highCutHz = 7000f, width = 1.2f, early = 0.25f, modulation = 0.4f),
        ReverbPrefs(decaySec = 0.5f, size = 0.2f, predelayMs = 0f, damping = 0.4f, lowCutHz = 250f, highCutHz = 10000f, width = 1f, early = 0.8f, modulation = 0.1f),
        ReverbPrefs(decaySec = 3.8f, size = 0.85f, predelayMs = 30f, damping = 0.6f, lowCutHz = 180f, highCutHz = 6500f, width = 1.3f, early = 0.15f, modulation = 0.7f),
    )
}

data class MusicHapticsPrefs(
    val enabled: Boolean = false,
    val intensity: Float = 0.8f,
    val detail: Int = 1,
    val texture: Boolean = true,
    val offsetMs: Int = 0,
)

data class GesturePrefs(
    val swipeArtwork: Boolean = true,
    val swipeDownDismiss: Boolean = true,
    val doubleTapPause: Boolean = true,
)
