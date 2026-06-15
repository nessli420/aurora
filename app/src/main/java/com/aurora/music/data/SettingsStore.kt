package com.aurora.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aurora_settings")

/** Playback source tiers in default priority order (7.1b): on-device file, app download, then stream. */
val DEFAULT_SOURCE_PRIORITY = listOf("local", "downloaded", "stream")

/** Sentinel in `mergeSources` meaning "no servers" (so empty can keep meaning "all eligible"). */
const val MERGE_NONE = "__none__"

/** Which kind of media server a session talks to. */
enum class ServerType { SUBSONIC, JELLYFIN, SPOTIFY, LOCAL }

/**
 * Stored server session. For Subsonic (Navidrome) we keep salt+token (token auth — never the raw
 * password). For Jellyfin, [token] holds the access token, [userId] the authenticated user id,
 * and [salt] is unused.
 */
data class Session(
    val server: String,
    val username: String,
    val salt: String,
    val token: String,
    val type: ServerType = ServerType.SUBSONIC,
    val userId: String = "",
    val imageUrl: String = "",
    // Spotify web-player only: the client-token + app-version that /v1 requires alongside the bearer.
    val clientToken: String = "",
    val clientVersion: String = "",
) {
    val isValid: Boolean get() = server.isNotBlank() && username.isNotBlank() && token.isNotBlank()

    /** Human-readable backend name (for the profile/settings server badge). */
    val typeLabel: String get() = when (type) {
        ServerType.SPOTIFY -> "Spotify"
        ServerType.JELLYFIN -> "Jellyfin"
        ServerType.SUBSONIC -> "Navidrome"
        ServerType.LOCAL -> "On this device"
    }
}

/** Stable per-account key (used for account-change detection and unified-library membership). */
fun Session.accountKey(): String = "${type.name}|$server|$username|$userId"

/** Playback preferences that drive the ExoPlayer engine. */
data class PlaybackPrefs(
    val skipSilence: Boolean = false,
    val crossfadeSec: Int = 0,
    val gapless: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val monoAudio: Boolean = false,
    val streamWifi: Int = 0,        // 0 = lossless / original (format=raw)
    val streamCellular: Int = 0,
    val downloadBitrate: Int = 0,   // 0 = lossless / original
    val preferHighRes: Boolean = false, // float output; off so speed/pitch/EQ-chain work everywhere
    val scrobble: Boolean = true,
    val autoplayRadio: Boolean = false,
    val bitPerfectUsb: Boolean = false, // experimental: route to USB DAC via the decent-player driver
)

/** Visualizer render style. */
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
    const val count = 26
    fun label(v: Int) = when (v) {
        BARS -> "Spectrum bars"; MIRROR_BARS -> "Mirror bars"; WAVEFORM -> "Waveform"
        FILLED_WAVE -> "Filled wave"; RADIAL_BARS -> "Radial spectrum"; RADIAL_WAVE -> "Radial wave"
        PARTICLES -> "Particles"; FLUID -> "Fluid blob"; COMBO -> "Combo"
        SMOOTH_CURVE -> "Spectrum curve"; DOT_GRID -> "Dot matrix"; RINGS -> "Pulse rings"
        ORB -> "Orb"; LADDER -> "LED ladder"; HORIZON -> "Horizon"; CONSTELLATION -> "Constellation"
        PEAK_DOTS -> "Peak dots"; SPECTRUM_LINE -> "Neon line"; AURORA -> "Aurora"
        SPECTRAL_RIVER -> "Spectral river"; SPECTRAL_TERRAIN -> "Terrain flyover"; CURL_FLOW -> "Curl flow"
        STRANGE_ATTRACTOR -> "Strange attractor"; CYMATIC -> "Cymatics"; SUPERFORMULA_BLOOM -> "Bloom"
        WORMHOLE -> "Wormhole"
        else -> "Spectrum bars"
    }
}

/** Where the visualizer takes its colours from. */
object VizColor { const val ACCENT = 0; const val CUSTOM = 1; const val GRADIENT = 2; const val ALBUM_ART = 3 }

/** Visualizer backdrop. */
object VizBackground { const val BLACK = 0; const val GRADIENT = 1; const val ALBUM_BLUR = 2 }

/**
 * Audio-visualizer settings. The renderer reads [style]; the analysis engine
 * ([com.aurora.music.playback.VisualizerController]) reads the band/FFT/sensitivity fields.
 */
data class VisualizerPrefs(
    val style: Int = VisualizerStyle.BARS,
    val colorSource: Int = VizColor.ACCENT,
    val primaryColor: Int = 0xFF7C4DFF.toInt(),
    val secondaryColor: Int = 0xFF00E5FF.toInt(),
    val background: Int = VizBackground.GRADIENT,
    val barCount: Int = 64,
    val smoothing: Float = 0.78f,   // temporal decay, 0 = snappy, ~0.95 = floaty
    val sensitivity: Float = 1.0f,  // input gain, 0.25..4
    val minHz: Int = 30,
    val maxHz: Int = 16000,
    val peakHold: Boolean = true,
    val mirror: Boolean = false,
    val fftSize: Int = 2048,        // 1024 / 2048 / 4096
    val fpsCap: Int = 60,           // 30 / 60 / 90 / 120
    val rotate: Boolean = false,    // slow spin for radial styles
    val particleCount: Int = 140,
    val showAlbumArt: Boolean = true,
    val showTrackInfo: Boolean = true,
)

/** Parametric filter type: peaking, low-shelf, or high-shelf (matches AutoEq PK/LSC/HSC). */
object BandType { const val PEAK = 0; const val LOW_SHELF = 1; const val HIGH_SHELF = 2 }

/** One parametric EQ band for the custom software DSP. */
data class ParamBand(val freqHz: Float, val gainDb: Float, val q: Float, val type: Int = BandType.PEAK)

/** DSP engine selection: which tone-shaping path is active. */
object DspMode { const val SYSTEM = 0; const val CUSTOM = 1; const val OFF = 2 }

/**
 * Audiophile DSP chain. Two engines, selected by [dspMode]:
 *  - SYSTEM: the Android AudioEffect chain (graphic EQ/bass/virtualizer/loudness, device-dependent).
 *  - CUSTOM: Aurora's device-independent software DSP (10-band graphic + parametric EQ, preamp,
 *    balance, stereo width, crossfeed, compressor, limiter) — see [com.aurora.music.playback.AuroraDspProcessor].
 *  - OFF: no tone shaping.
 * ReplayGain ([replayGain]) is shared across engines.
 */
data class AudioPrefs(
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,                 // -1 = custom
    val eqBands: List<Int> = emptyList(),   // millibel gain per band (system EQ)
    val bassBoost: Int = 0,                 // 0..1000
    val virtualizer: Int = 0,               // 0..1000
    val loudnessGain: Int = 0,              // millibels, 0..2000
    val replayGain: Int = 0,                // 0=off, 1=track, 2=album
    // --- Custom software DSP ---
    val dspMode: Int = DspMode.SYSTEM,
    val dspGraphicBands: List<Float> = emptyList(), // dB gain per fixed graphic band (10)
    val dspParametric: List<ParamBand> = emptyList(),
    val dspPreampDb: Float = 0f,
    val dspBalance: Float = 0f,             // -1 (L) .. +1 (R)
    val dspWidth: Float = 1f,               // 0 = mono, 1 = normal, 2 = wide
    val dspCrossfeed: Float = 0f,           // 0 = off .. 1
    val dspLimiterEnabled: Boolean = true,
    val dspLimiterCeilingDb: Float = -0.3f,
    val dspCompEnabled: Boolean = false,
    val dspCompThreshDb: Float = -18f,
    val dspCompRatio: Float = 2f,
    // --- Convolution / impulse response ---
    val dspConvEnabled: Boolean = false,
    val dspConvIrPath: String = "",         // path to imported WAV IR ("" = none)
    val dspConvIrName: String = "",         // display name of the loaded IR
    val dspConvMakeupDb: Float = 0f,        // post-convolution makeup gain
    val dspGraphicLayout: Int = 0,          // index into DspCoeffBuilder.GRAPHIC_LAYOUTS (10/15/31-band)
    val dspSaturation: Float = 0f,          // 0..1 tube/harmonic drive
    val dspDelayLeftMs: Float = 0f,         // per-channel alignment delay
    val dspDelayRightMs: Float = 0f,
    val dspTrimLeftDb: Float = 0f,          // per-channel attenuation
    val dspTrimRightDb: Float = 0f,
)

/** App theme mode. */
object ThemeMode { const val SYSTEM = 0; const val LIGHT = 1; const val DARK = 2; const val AMOLED = 3 }

/** Where the accent color comes from. */
object AccentMode { const val PRESET = 0; const val CUSTOM = 1; const val MATERIAL_YOU = 2 }

/** Global corner-rounding style. */
object CornerStyle { const val SHARP = 0; const val DEFAULT = 1; const val ROUNDED = 2; const val PILL = 3 }

/** Fullscreen-player seek control style. */
object SeekStyle { const val WAVEFORM = 0; const val BAR = 1 }

/** Miniplayer layout preset. */
object MiniStyle { const val STANDARD = 0; const val COMPACT = 1; const val PROMINENT = 2 }

/** Miniplayer progress indicator style. */
object MiniProgress { const val LINE = 0; const val BAR = 1; const val NONE = 2 }

/** Home section identifiers (for show/hide). */
object HomeSection {
    const val HERO = "hero"; const val RECENT = "recent"; const val PLAYLISTS = "playlists"
    const val FAVOURITE = "favourite"; const val MOST = "most"; const val ARTISTS = "artists"; const val NEW = "new"
}

/**
 * Visual / appearance preferences. Defaults reproduce the app's original look (forced dark, rose
 * accent, current layouts) so nothing changes until the user opts in.
 */
data class UiPrefs(
    val themeMode: Int = ThemeMode.DARK,
    val accentMode: Int = AccentMode.PRESET,
    val accentPreset: Int = 0,              // index into AccentPresets (ui/theme/Color.kt)
    val accentColor: Long = 0xFFFF2E7EL,    // ARGB for custom accent (default = rose)
    // --- Global look ---
    val fontScale: Float = 1f,              // 0.85 .. 1.3
    val cornerStyle: Int = CornerStyle.DEFAULT,
    // --- Fullscreen player ---
    val playerSeekStyle: Int = SeekStyle.WAVEFORM,
    val playerWaveBars: Int = 60,           // 24 .. 96
    val playerArtSize: Float = 0.86f,       // 0.6 .. 1.0 (fraction of width)
    val playerGradient: Float = 1f,         // 0 .. 1.5 (accent gradient intensity)
    val playerShowUtilities: Boolean = true,
    // --- Miniplayer ---
    val miniStyle: Int = MiniStyle.STANDARD,
    val miniProgress: Int = MiniProgress.LINE,
    // --- Library / home ---
    val libraryColumns: Int = 2,            // 2 .. 4
    val hiddenHomeSections: Set<String> = emptySet(),
)

/** Linked Last.fm account (empty [sessionKey] = not connected). */
data class LastfmAccount(
    val sessionKey: String = "",
    val username: String = "",
    val imageUrl: String = "",
    val enabled: Boolean = true,
)

/** Linked ListenBrainz account (empty [token] = not connected). */
data class ListenBrainzAccount(
    val token: String = "",
    val username: String = "",
    val enabled: Boolean = true,
)

/** Linked Discord account for Rich Presence (empty [token] = not connected). */
data class DiscordAccount(
    val token: String = "",
    val username: String = "",
    val enabled: Boolean = true,
    val imgurClientId: String = "",  // optional override for album-art uploads
    val appId: String = "",          // user's own Discord application id (for rich presence + art)
)

/**
 * A library item pinned to the top of the Library "All" tab. Scoped to the connection it was pinned
 * on ([serverId] = the session's server, e.g. the Navidrome URL, Spotify's base, or "On this
 * device"), so it persists across logouts but only reappears on that same connection.
 */
data class Pin(
    val id: String = "",
    val kind: String = "",        // album | playlist | artist
    val title: String = "",
    val subtitle: String = "",
    val coverUrl: String = "",
    val serverId: String = "",
)

/** An AutoEQ correction bound to a specific output device, applied automatically when it connects. */
data class EqBinding(
    val deviceKey: String = "",
    val deviceLabel: String = "",
    val profileName: String = "",
    val preampDb: Float = 0f,
    val bands: List<ParamBand> = emptyList(),
)

/** Wake-to-music alarm: at [hour]:[minute] each day, fade in the user's liked/downloaded library. */
data class AlarmPrefs(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
)

/** Player gesture toggles. */
data class GesturePrefs(
    val swipeArtwork: Boolean = true,       // swipe artwork left/right → next/prev
    val swipeDownDismiss: Boolean = true,   // swipe down → collapse player / sheets
    val doubleTapPause: Boolean = true,     // double-tap artwork → play/pause
)

class SettingsStore(private val context: Context) {

    private val gson = Gson()

    private object Keys {
        val SERVER = stringPreferencesKey("server")
        val USERNAME = stringPreferencesKey("username")
        val SALT = stringPreferencesKey("salt")
        val TOKEN = stringPreferencesKey("token")
        val SERVER_TYPE = stringPreferencesKey("server_type")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_IMAGE = stringPreferencesKey("user_image")
        val CLIENT_TOKEN = stringPreferencesKey("sp_client_token")
        val CLIENT_VERSION = stringPreferencesKey("sp_client_version")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val CROSSFADE = intPreferencesKey("crossfade_sec")
        val GAPLESS = booleanPreferencesKey("gapless")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val MONO = booleanPreferencesKey("mono_audio")
        val STREAM_WIFI = intPreferencesKey("stream_wifi")
        val STREAM_CELLULAR = intPreferencesKey("stream_cellular")
        val DOWNLOAD_BITRATE = intPreferencesKey("download_bitrate")
        val PREFER_HIRES = booleanPreferencesKey("prefer_hires")
        val BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb")
        val VIZ_STYLE = intPreferencesKey("viz_style")
        val VIZ_COLOR_SOURCE = intPreferencesKey("viz_color_source")
        val VIZ_PRIMARY = intPreferencesKey("viz_primary")
        val VIZ_SECONDARY = intPreferencesKey("viz_secondary")
        val VIZ_BACKGROUND = intPreferencesKey("viz_background")
        val VIZ_BAR_COUNT = intPreferencesKey("viz_bar_count")
        val VIZ_SMOOTHING = floatPreferencesKey("viz_smoothing")
        val VIZ_SENSITIVITY = floatPreferencesKey("viz_sensitivity")
        val VIZ_MIN_HZ = intPreferencesKey("viz_min_hz")
        val VIZ_MAX_HZ = intPreferencesKey("viz_max_hz")
        val VIZ_PEAK_HOLD = booleanPreferencesKey("viz_peak_hold")
        val VIZ_MIRROR = booleanPreferencesKey("viz_mirror")
        val VIZ_FFT_SIZE = intPreferencesKey("viz_fft_size")
        val VIZ_FPS = intPreferencesKey("viz_fps")
        val VIZ_ROTATE = booleanPreferencesKey("viz_rotate")
        val VIZ_PARTICLES = intPreferencesKey("viz_particles")
        val VIZ_ALBUM_ART = booleanPreferencesKey("viz_album_art")
        val VIZ_TRACK_INFO = booleanPreferencesKey("viz_track_info")
        val SONIC_AUTO_ANALYZE = booleanPreferencesKey("sonic_auto_analyze")
        val SCROBBLE = booleanPreferencesKey("scrobble")
        val AUTOPLAY_RADIO = booleanPreferencesKey("autoplay_radio")
        val OFFLINE = booleanPreferencesKey("offline_mode")
        val LRCLIB = booleanPreferencesKey("lrclib_enabled")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val PRIVATE_SESSION = booleanPreferencesKey("private_session")
        val GESTURE_SWIPE_ART = booleanPreferencesKey("gesture_swipe_art")
        val GESTURE_SWIPE_DISMISS = booleanPreferencesKey("gesture_swipe_dismiss")
        val GESTURE_DOUBLE_TAP = booleanPreferencesKey("gesture_double_tap")
        val HAPTICS = booleanPreferencesKey("haptics")
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
        val ALARM_HOUR = intPreferencesKey("alarm_hour")
        val ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val PINS = stringPreferencesKey("library_pins")   // JSON; not cleared on logout
        val SMART_PLAYLISTS = stringPreferencesKey("smart_playlists")  // JSON; not cleared on logout
        val RADIO_FAVORITES = stringPreferencesKey("radio_favorites")  // JSON list; not cleared on logout
        val PODCAST_SUBS = stringPreferencesKey("podcast_subs")        // JSON list; not cleared on logout
        val ARTIST_ENRICHMENT = booleanPreferencesKey("artist_enrichment")
        val PREFER_LOCAL = booleanPreferencesKey("prefer_local_sources")
        val SOURCE_PRIORITY = stringPreferencesKey("source_priority")     // ordered tiers: local/downloaded/stream
        val UNIFIED_LIBRARY = booleanPreferencesKey("unified_library")    // merge saved servers + local into one
        val MERGE_SOURCES = stringSetPreferencesKey("merge_sources")      // account keys included (empty = all)
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")  // JSON list, most-recent first
        val SQUIG_BASE = stringPreferencesKey("squig_base_url")        // squig.link instance for live AutoEQ
        val SQUIG_TARGET = stringPreferencesKey("squig_target")        // target curve file stem
        val SAVED_SESSIONS = stringPreferencesKey("saved_sessions")   // JSON list; remembered logins
        val SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")  // user's own app; survives logout
        val ACOUSTID_KEY = stringPreferencesKey("acoustid_key")           // user's AcoustID app key; survives logout
        val EQ_BINDINGS = stringPreferencesKey("eq_bindings")              // JSON; AutoEQ per-device
        val AUTOEQ_SWITCH = booleanPreferencesKey("autoeq_autoswitch")
        val AUTOEQ_PROFILE = stringPreferencesKey("autoeq_active_profile") // display name of applied profile
        val LIKED_PLAYLISTS = stringSetPreferencesKey("liked_playlists")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = intPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val VIRTUALIZER = intPreferencesKey("virtualizer")
        val LOUDNESS = intPreferencesKey("loudness_gain")
        val REPLAY_GAIN = intPreferencesKey("replay_gain")
        val DSP_MODE = intPreferencesKey("dsp_mode")
        val DSP_GRAPHIC = stringPreferencesKey("dsp_graphic")        // "0.0,1.5,-2.0,..."
        val DSP_PARAMETRIC = stringPreferencesKey("dsp_parametric")  // "f:g:q;f:g:q"
        val DSP_PREAMP = floatPreferencesKey("dsp_preamp")
        val DSP_BALANCE = floatPreferencesKey("dsp_balance")
        val DSP_WIDTH = floatPreferencesKey("dsp_width")
        val DSP_CROSSFEED = floatPreferencesKey("dsp_crossfeed")
        val DSP_LIMITER = booleanPreferencesKey("dsp_limiter")
        val DSP_CEILING = floatPreferencesKey("dsp_ceiling")
        val DSP_COMP = booleanPreferencesKey("dsp_comp")
        val DSP_COMP_THRESH = floatPreferencesKey("dsp_comp_thresh")
        val DSP_COMP_RATIO = floatPreferencesKey("dsp_comp_ratio")
        val DSP_CONV = booleanPreferencesKey("dsp_conv_enabled")
        val DSP_CONV_PATH = stringPreferencesKey("dsp_conv_path")
        val DSP_CONV_NAME = stringPreferencesKey("dsp_conv_name")
        val DSP_CONV_MAKEUP = floatPreferencesKey("dsp_conv_makeup")
        val DSP_GRAPHIC_LAYOUT = intPreferencesKey("dsp_graphic_layout")
        val DSP_SATURATION = floatPreferencesKey("dsp_saturation")
        val DSP_DELAY_L = floatPreferencesKey("dsp_delay_l")
        val DSP_DELAY_R = floatPreferencesKey("dsp_delay_r")
        val DSP_TRIM_L = floatPreferencesKey("dsp_trim_l")
        val DSP_TRIM_R = floatPreferencesKey("dsp_trim_r")
        val UI_THEME_MODE = intPreferencesKey("ui_theme_mode")
        val UI_ACCENT_MODE = intPreferencesKey("ui_accent_mode")
        val UI_ACCENT_PRESET = intPreferencesKey("ui_accent_preset")
        val UI_ACCENT_COLOR = longPreferencesKey("ui_accent_color")
        val UI_FONT_SCALE = floatPreferencesKey("ui_font_scale")
        val UI_CORNER_STYLE = intPreferencesKey("ui_corner_style")
        val UI_PLAYER_SEEK = intPreferencesKey("ui_player_seek")
        val UI_PLAYER_WAVE_BARS = intPreferencesKey("ui_player_wave_bars")
        val UI_PLAYER_ART = floatPreferencesKey("ui_player_art")
        val UI_PLAYER_GRADIENT = floatPreferencesKey("ui_player_gradient")
        val UI_PLAYER_UTILITIES = booleanPreferencesKey("ui_player_utilities")
        val UI_MINI_STYLE = intPreferencesKey("ui_mini_style")
        val UI_MINI_PROGRESS = intPreferencesKey("ui_mini_progress")
        val UI_LIBRARY_COLUMNS = intPreferencesKey("ui_library_columns")
        val UI_HIDDEN_HOME = stringSetPreferencesKey("ui_hidden_home")
        val LASTFM_SK = stringPreferencesKey("lastfm_sk")
        val LASTFM_USER = stringPreferencesKey("lastfm_user")
        val LASTFM_IMAGE = stringPreferencesKey("lastfm_image")
        val LASTFM_ENABLED = booleanPreferencesKey("lastfm_enabled")
        val LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")   // user's own Last.fm API key
        val LASTFM_SECRET = stringPreferencesKey("lastfm_secret")     // user's own Last.fm shared secret
        val LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")
        val LISTENBRAINZ_USER = stringPreferencesKey("listenbrainz_user")
        val LISTENBRAINZ_ENABLED = booleanPreferencesKey("listenbrainz_enabled")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val DISCORD_USER = stringPreferencesKey("discord_user")
        val DISCORD_ENABLED = booleanPreferencesKey("discord_enabled")
        val DISCORD_IMGUR = stringPreferencesKey("discord_imgur")
        val DISCORD_APP_ID = stringPreferencesKey("discord_app_id")   // user's own Discord application id
    }

    val discord: Flow<DiscordAccount> = context.dataStore.data.map { p ->
        DiscordAccount(
            token = p[Keys.DISCORD_TOKEN].orEmpty(),
            username = p[Keys.DISCORD_USER].orEmpty(),
            enabled = p[Keys.DISCORD_ENABLED] ?: true,
            imgurClientId = p[Keys.DISCORD_IMGUR].orEmpty(),
            appId = p[Keys.DISCORD_APP_ID].orEmpty(),
        )
    }

    val lastfm: Flow<LastfmAccount> = context.dataStore.data.map { p ->
        LastfmAccount(
            sessionKey = p[Keys.LASTFM_SK].orEmpty(),
            username = p[Keys.LASTFM_USER].orEmpty(),
            imageUrl = p[Keys.LASTFM_IMAGE].orEmpty(),
            enabled = p[Keys.LASTFM_ENABLED] ?: true,
        )
    }

    /** The user's own Last.fm API key + shared secret (from last.fm/api/account/create). */
    val lastfmKeys: Flow<Pair<String, String>> = context.dataStore.data.map { p ->
        (p[Keys.LASTFM_API_KEY].orEmpty()) to (p[Keys.LASTFM_SECRET].orEmpty())
    }

    val listenBrainz: Flow<ListenBrainzAccount> = context.dataStore.data.map { p ->
        ListenBrainzAccount(
            token = p[Keys.LISTENBRAINZ_TOKEN].orEmpty(),
            username = p[Keys.LISTENBRAINZ_USER].orEmpty(),
            enabled = p[Keys.LISTENBRAINZ_ENABLED] ?: true,
        )
    }

    val uiPrefs: Flow<UiPrefs> = context.dataStore.data.map { p ->
        UiPrefs(
            themeMode = p[Keys.UI_THEME_MODE] ?: ThemeMode.DARK,
            accentMode = p[Keys.UI_ACCENT_MODE] ?: AccentMode.PRESET,
            accentPreset = p[Keys.UI_ACCENT_PRESET] ?: 0,
            accentColor = p[Keys.UI_ACCENT_COLOR] ?: 0xFFFF2E7EL,
            fontScale = p[Keys.UI_FONT_SCALE] ?: 1f,
            cornerStyle = p[Keys.UI_CORNER_STYLE] ?: CornerStyle.DEFAULT,
            playerSeekStyle = p[Keys.UI_PLAYER_SEEK] ?: SeekStyle.WAVEFORM,
            playerWaveBars = p[Keys.UI_PLAYER_WAVE_BARS] ?: 60,
            playerArtSize = p[Keys.UI_PLAYER_ART] ?: 0.86f,
            playerGradient = p[Keys.UI_PLAYER_GRADIENT] ?: 1f,
            playerShowUtilities = p[Keys.UI_PLAYER_UTILITIES] ?: true,
            miniStyle = p[Keys.UI_MINI_STYLE] ?: MiniStyle.STANDARD,
            miniProgress = p[Keys.UI_MINI_PROGRESS] ?: MiniProgress.LINE,
            libraryColumns = p[Keys.UI_LIBRARY_COLUMNS] ?: 2,
            hiddenHomeSections = p[Keys.UI_HIDDEN_HOME] ?: emptySet(),
        )
    }

    val audioPrefs: Flow<AudioPrefs> = context.dataStore.data.map { p ->
        AudioPrefs(
            eqEnabled = p[Keys.EQ_ENABLED] ?: false,
            eqPreset = p[Keys.EQ_PRESET] ?: -1,
            eqBands = p[Keys.EQ_BANDS]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
            bassBoost = p[Keys.BASS_BOOST] ?: 0,
            virtualizer = p[Keys.VIRTUALIZER] ?: 0,
            loudnessGain = p[Keys.LOUDNESS] ?: 0,
            replayGain = p[Keys.REPLAY_GAIN] ?: 0,
            dspMode = p[Keys.DSP_MODE] ?: DspMode.SYSTEM,
            dspGraphicBands = p[Keys.DSP_GRAPHIC]?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: emptyList(),
            dspParametric = parseParametric(p[Keys.DSP_PARAMETRIC]),
            dspPreampDb = p[Keys.DSP_PREAMP] ?: 0f,
            dspBalance = p[Keys.DSP_BALANCE] ?: 0f,
            dspWidth = p[Keys.DSP_WIDTH] ?: 1f,
            dspCrossfeed = p[Keys.DSP_CROSSFEED] ?: 0f,
            dspLimiterEnabled = p[Keys.DSP_LIMITER] ?: true,
            dspLimiterCeilingDb = p[Keys.DSP_CEILING] ?: -0.3f,
            dspCompEnabled = p[Keys.DSP_COMP] ?: false,
            dspCompThreshDb = p[Keys.DSP_COMP_THRESH] ?: -18f,
            dspCompRatio = p[Keys.DSP_COMP_RATIO] ?: 2f,
            dspConvEnabled = p[Keys.DSP_CONV] ?: false,
            dspConvIrPath = p[Keys.DSP_CONV_PATH].orEmpty(),
            dspConvIrName = p[Keys.DSP_CONV_NAME].orEmpty(),
            dspConvMakeupDb = p[Keys.DSP_CONV_MAKEUP] ?: 0f,
            dspGraphicLayout = p[Keys.DSP_GRAPHIC_LAYOUT] ?: 0,
            dspSaturation = p[Keys.DSP_SATURATION] ?: 0f,
            dspDelayLeftMs = p[Keys.DSP_DELAY_L] ?: 0f,
            dspDelayRightMs = p[Keys.DSP_DELAY_R] ?: 0f,
            dspTrimLeftDb = p[Keys.DSP_TRIM_L] ?: 0f,
            dspTrimRightDb = p[Keys.DSP_TRIM_R] ?: 0f,
        )
    }

    private fun parseParametric(s: String?): List<ParamBand> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size < 3) return@mapNotNull null
            val f = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val g = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val q = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val t = parts.getOrNull(3)?.toIntOrNull() ?: BandType.PEAK
            ParamBand(f, g, q, t)
        }
    }

    val offlineMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.OFFLINE] ?: false }
    val lrclibEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LRCLIB] ?: true }
    /** Data saver: cap cellular streaming to a low bitrate (server backends only). */
    val dataSaver: Flow<Boolean> = context.dataStore.data.map { it[Keys.DATA_SAVER] ?: false }
    /** Private session: don't report playback to the server or scrobble to Last.fm. */
    val privateSession: Flow<Boolean> = context.dataStore.data.map { it[Keys.PRIVATE_SESSION] ?: false }

    /** Player gesture toggles (default on). */
    val gesturePrefs: Flow<GesturePrefs> = context.dataStore.data.map { p ->
        GesturePrefs(
            swipeArtwork = p[Keys.GESTURE_SWIPE_ART] ?: true,
            swipeDownDismiss = p[Keys.GESTURE_SWIPE_DISMISS] ?: true,
            doubleTapPause = p[Keys.GESTURE_DOUBLE_TAP] ?: true,
        )
    }
    /** Haptic feedback on meaningful taps (default off — opt-in). */
    val haptics: Flow<Boolean> = context.dataStore.data.map { it[Keys.HAPTICS] ?: false }

    /** Wake-to-music alarm settings. */
    val alarmPrefs: Flow<AlarmPrefs> = context.dataStore.data.map { p ->
        AlarmPrefs(
            enabled = p[Keys.ALARM_ENABLED] ?: false,
            hour = p[Keys.ALARM_HOUR] ?: 7,
            minute = p[Keys.ALARM_MINUTE] ?: 0,
        )
    }

    /** The user's own Spotify app Client ID (DIY setup). Persists across logout. */
    val spotifyClientId: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_CLIENT_ID] ?: "" }

    /** The user's own AcoustID application API key (for tag-editor "Auto-identify"). */
    val acoustIdKey: Flow<String> = context.dataStore.data.map { it[Keys.ACOUSTID_KEY] ?: "" }

    // distinctUntilChanged so the AutoEQ controller only reacts to real changes, not every settings
    // write (which would otherwise re-fire it and wipe a just-applied correction).
    /** AutoEQ per-output-device bindings. */
    val eqBindings: Flow<List<EqBinding>> = context.dataStore.data.map { parseBindings(it[Keys.EQ_BINDINGS]) }.distinctUntilChanged()
    /** Auto-apply the bound AutoEQ profile when its output device connects. */
    val autoEqAutoSwitch: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTOEQ_SWITCH] ?: false }.distinctUntilChanged()
    /** Display name of the currently-applied AutoEQ profile ("" = none). */
    val activeEqProfile: Flow<String> = context.dataStore.data.map { it[Keys.AUTOEQ_PROFILE] ?: "" }

    private fun parseBindings(json: String?): List<EqBinding> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<EqBinding>>(json, object : TypeToken<List<EqBinding>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** Pinned library items (persist across logout & server changes). */
    val pins: Flow<List<Pin>> = context.dataStore.data.map { p -> parsePins(p[Keys.PINS]) }

    private fun parsePins(json: String?): List<Pin> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Pin>>(json, object : TypeToken<List<Pin>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** Smart (rule-based) playlists — evaluated lazily against whatever library is active. */
    val smartPlaylists: Flow<List<SmartPlaylist>> = context.dataStore.data.map { p -> parseSmart(p[Keys.SMART_PLAYLISTS]) }

    private fun parseSmart(json: String?): List<SmartPlaylist> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<SmartPlaylist>>(json, object : TypeToken<List<SmartPlaylist>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** Create or update a smart playlist (upsert by id). */
    suspend fun saveSmartPlaylist(sp: SmartPlaylist) = context.dataStore.edit { p ->
        val cur = parseSmart(p[Keys.SMART_PLAYLISTS])
        val next = if (cur.any { it.id == sp.id }) cur.map { if (it.id == sp.id) sp else it } else cur + sp
        p[Keys.SMART_PLAYLISTS] = gson.toJson(next)
    }

    suspend fun deleteSmartPlaylist(id: String) = context.dataStore.edit { p ->
        p[Keys.SMART_PLAYLISTS] = gson.toJson(parseSmart(p[Keys.SMART_PLAYLISTS]).filterNot { it.id == id })
    }

    /** The user's favourited / custom-added internet-radio stations (6.3). */
    val radioFavorites: Flow<List<RadioStation>> = context.dataStore.data.map { p -> parseRadio(p[Keys.RADIO_FAVORITES]) }

    private fun parseRadio(json: String?): List<RadioStation> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<RadioStation>>(json, object : TypeToken<List<RadioStation>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** Favourite a station (upsert by uuid). */
    suspend fun saveRadioStation(s: RadioStation) = context.dataStore.edit { p ->
        val cur = parseRadio(p[Keys.RADIO_FAVORITES])
        val next = if (cur.any { it.uuid == s.uuid }) cur.map { if (it.uuid == s.uuid) s else it } else cur + s
        p[Keys.RADIO_FAVORITES] = gson.toJson(next)
    }

    suspend fun deleteRadioStation(uuid: String) = context.dataStore.edit { p ->
        p[Keys.RADIO_FAVORITES] = gson.toJson(parseRadio(p[Keys.RADIO_FAVORITES]).filterNot { it.uuid == uuid })
    }

    /** Podcast shows the user subscribed to (6.3). */
    val podcastSubs: Flow<List<Podcast>> = context.dataStore.data.map { p -> parsePodcasts(p[Keys.PODCAST_SUBS]) }

    private fun parsePodcasts(json: String?): List<Podcast> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Podcast>>(json, object : TypeToken<List<Podcast>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** Subscribe to a podcast (upsert by feed URL). */
    suspend fun savePodcast(p: Podcast) = context.dataStore.edit { prefs ->
        val cur = parsePodcasts(prefs[Keys.PODCAST_SUBS])
        val next = if (cur.any { it.feedUrl == p.feedUrl }) cur.map { if (it.feedUrl == p.feedUrl) p else it } else cur + p
        prefs[Keys.PODCAST_SUBS] = gson.toJson(next)
    }

    suspend fun deletePodcast(feedUrl: String) = context.dataStore.edit { p ->
        p[Keys.PODCAST_SUBS] = gson.toJson(parsePodcasts(p[Keys.PODCAST_SUBS]).filterNot { it.feedUrl == feedUrl })
    }

    /** Playlists the user liked. Subsonic has no playlist-star, so we persist these locally. */
    val likedPlaylists: Flow<Set<String>> = context.dataStore.data.map { it[Keys.LIKED_PLAYLISTS] ?: emptySet() }

    val session: Flow<Session?> = context.dataStore.data.map { p ->
        val s = Session(
            server = p[Keys.SERVER].orEmpty(),
            username = p[Keys.USERNAME].orEmpty(),
            salt = p[Keys.SALT].orEmpty(),
            token = p[Keys.TOKEN].orEmpty(),
            type = runCatching { ServerType.valueOf(p[Keys.SERVER_TYPE] ?: "SUBSONIC") }.getOrDefault(ServerType.SUBSONIC),
            userId = p[Keys.USER_ID].orEmpty(),
            imageUrl = p[Keys.USER_IMAGE].orEmpty(),
            clientToken = p[Keys.CLIENT_TOKEN].orEmpty(),
            clientVersion = p[Keys.CLIENT_VERSION].orEmpty(),
        )
        if (s.isValid) s else null
    }

    val playbackPrefs: Flow<PlaybackPrefs> = context.dataStore.data.map { p ->
        PlaybackPrefs(
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            crossfadeSec = p[Keys.CROSSFADE] ?: 0,
            gapless = p[Keys.GAPLESS] ?: true,
            defaultSpeed = p[Keys.DEFAULT_SPEED] ?: 1.0f,
            monoAudio = p[Keys.MONO] ?: false,
            streamWifi = p[Keys.STREAM_WIFI] ?: 0,
            streamCellular = p[Keys.STREAM_CELLULAR] ?: 0,
            downloadBitrate = p[Keys.DOWNLOAD_BITRATE] ?: 0,
            preferHighRes = p[Keys.PREFER_HIRES] ?: false,
            scrobble = p[Keys.SCROBBLE] ?: true,
            autoplayRadio = p[Keys.AUTOPLAY_RADIO] ?: false,
            bitPerfectUsb = p[Keys.BIT_PERFECT_USB] ?: false,
        )
    }

    val visualizerPrefs: Flow<VisualizerPrefs> = context.dataStore.data.map { p ->
        val d = VisualizerPrefs()
        VisualizerPrefs(
            style = p[Keys.VIZ_STYLE] ?: d.style,
            colorSource = p[Keys.VIZ_COLOR_SOURCE] ?: d.colorSource,
            primaryColor = p[Keys.VIZ_PRIMARY] ?: d.primaryColor,
            secondaryColor = p[Keys.VIZ_SECONDARY] ?: d.secondaryColor,
            background = p[Keys.VIZ_BACKGROUND] ?: d.background,
            barCount = p[Keys.VIZ_BAR_COUNT] ?: d.barCount,
            smoothing = p[Keys.VIZ_SMOOTHING] ?: d.smoothing,
            sensitivity = p[Keys.VIZ_SENSITIVITY] ?: d.sensitivity,
            minHz = p[Keys.VIZ_MIN_HZ] ?: d.minHz,
            maxHz = p[Keys.VIZ_MAX_HZ] ?: d.maxHz,
            peakHold = p[Keys.VIZ_PEAK_HOLD] ?: d.peakHold,
            mirror = p[Keys.VIZ_MIRROR] ?: d.mirror,
            fftSize = p[Keys.VIZ_FFT_SIZE] ?: d.fftSize,
            fpsCap = p[Keys.VIZ_FPS] ?: d.fpsCap,
            rotate = p[Keys.VIZ_ROTATE] ?: d.rotate,
            particleCount = p[Keys.VIZ_PARTICLES] ?: d.particleCount,
            showAlbumArt = p[Keys.VIZ_ALBUM_ART] ?: d.showAlbumArt,
            showTrackInfo = p[Keys.VIZ_TRACK_INFO] ?: d.showTrackInfo,
        )
    }

    /** Analyze new local/downloaded tracks for sonic similarity automatically (on app start). */
    val sonicAutoAnalyze: Flow<Boolean> = context.dataStore.data.map { it[Keys.SONIC_AUTO_ANALYZE] ?: false }
    suspend fun setSonicAutoAnalyze(v: Boolean) = context.dataStore.edit { it[Keys.SONIC_AUTO_ANALYZE] = v }

    /** Fetch artist bios + images from MusicBrainz/Wikipedia on the artist page (6.5). On by default;
     *  off keeps artist names from ever leaving the device. */
    val artistEnrichment: Flow<Boolean> = context.dataStore.data.map { it[Keys.ARTIST_ENRICHMENT] ?: true }
    suspend fun setArtistEnrichment(v: Boolean) = context.dataStore.edit { it[Keys.ARTIST_ENRICHMENT] = v }

    /** Best-source playback (7.1a): play a matching on-device/downloaded file instead of streaming. */
    val preferLocalSources: Flow<Boolean> = context.dataStore.data.map { it[Keys.PREFER_LOCAL] ?: true }
    suspend fun setPreferLocalSources(v: Boolean) = context.dataStore.edit { it[Keys.PREFER_LOCAL] = v }

    /** Ordered playback source priority (7.1b): subset/order of "local", "downloaded", "stream". */
    val sourcePriority: Flow<List<String>> = context.dataStore.data.map { p ->
        parseStringList(p[Keys.SOURCE_PRIORITY]).filter { it in DEFAULT_SOURCE_PRIORITY }
            .ifEmpty { DEFAULT_SOURCE_PRIORITY }
    }
    suspend fun setSourcePriority(order: List<String>) = context.dataStore.edit {
        it[Keys.SOURCE_PRIORITY] = gson.toJson(order.filter { t -> t in DEFAULT_SOURCE_PRIORITY }.distinct())
    }

    /** Unified library (7.1b): merge all included servers + local files into one browsable library. */
    val unifiedLibrary: Flow<Boolean> = context.dataStore.data.map { it[Keys.UNIFIED_LIBRARY] ?: false }
    suspend fun setUnifiedLibrary(v: Boolean) = context.dataStore.edit { it[Keys.UNIFIED_LIBRARY] = v }

    /** Account keys included in the unified library (empty = every eligible saved source). */
    val mergeSources: Flow<Set<String>> = context.dataStore.data.map { it[Keys.MERGE_SOURCES] ?: emptySet() }
    suspend fun setMergeSources(keys: Set<String>) = context.dataStore.edit { it[Keys.MERGE_SOURCES] = keys }

    /** Recent search queries, most-recent first, capped (7.3 search depth). */
    val recentSearches: Flow<List<String>> = context.dataStore.data.map { parseStringList(it[Keys.RECENT_SEARCHES]) }

    private fun parseStringList(json: String?): List<String> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** Add [query] to the top of the recents (de-duped, case-insensitively), capped at 12. */
    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.dataStore.edit { p ->
            val cur = parseStringList(p[Keys.RECENT_SEARCHES])
            val next = (listOf(q) + cur.filterNot { it.equals(q, ignoreCase = true) }).take(12)
            p[Keys.RECENT_SEARCHES] = gson.toJson(next)
        }
    }

    suspend fun removeRecentSearch(query: String) = context.dataStore.edit { p ->
        p[Keys.RECENT_SEARCHES] = gson.toJson(parseStringList(p[Keys.RECENT_SEARCHES]).filterNot { it.equals(query, ignoreCase = true) })
    }

    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }

    /** Live AutoEQ (7.2): the squig.link/CrinGraph instance + target curve to fetch from. */
    val squigBaseUrl: Flow<String> = context.dataStore.data.map { it[Keys.SQUIG_BASE]?.takeIf { u -> u.isNotBlank() } ?: DEFAULT_SQUIG_BASE }
    suspend fun setSquigBaseUrl(v: String) = context.dataStore.edit { it[Keys.SQUIG_BASE] = v.trim().trimEnd('/') }

    val squigTarget: Flow<String> = context.dataStore.data.map { it[Keys.SQUIG_TARGET]?.takeIf { t -> t.isNotBlank() } ?: DEFAULT_SQUIG_TARGET }
    suspend fun setSquigTarget(v: String) = context.dataStore.edit { it[Keys.SQUIG_TARGET] = v.trim() }

    suspend fun setVisualizer(v: VisualizerPrefs) = context.dataStore.edit { p ->
        p[Keys.VIZ_STYLE] = v.style
        p[Keys.VIZ_COLOR_SOURCE] = v.colorSource
        p[Keys.VIZ_PRIMARY] = v.primaryColor
        p[Keys.VIZ_SECONDARY] = v.secondaryColor
        p[Keys.VIZ_BACKGROUND] = v.background
        p[Keys.VIZ_BAR_COUNT] = v.barCount
        p[Keys.VIZ_SMOOTHING] = v.smoothing
        p[Keys.VIZ_SENSITIVITY] = v.sensitivity
        p[Keys.VIZ_MIN_HZ] = v.minHz
        p[Keys.VIZ_MAX_HZ] = v.maxHz
        p[Keys.VIZ_PEAK_HOLD] = v.peakHold
        p[Keys.VIZ_MIRROR] = v.mirror
        p[Keys.VIZ_FFT_SIZE] = v.fftSize
        p[Keys.VIZ_FPS] = v.fpsCap
        p[Keys.VIZ_ROTATE] = v.rotate
        p[Keys.VIZ_PARTICLES] = v.particleCount
        p[Keys.VIZ_ALBUM_ART] = v.showAlbumArt
        p[Keys.VIZ_TRACK_INFO] = v.showTrackInfo
    }

    suspend fun saveSession(session: Session) {
        context.dataStore.edit { p ->
            p[Keys.SERVER] = session.server
            p[Keys.USERNAME] = session.username
            p[Keys.SALT] = session.salt
            p[Keys.TOKEN] = session.token
            p[Keys.SERVER_TYPE] = session.type.name
            p[Keys.USER_ID] = session.userId
            p[Keys.USER_IMAGE] = session.imageUrl
            p[Keys.CLIENT_TOKEN] = session.clientToken
            p[Keys.CLIENT_VERSION] = session.clientVersion
        }
    }

    /** Update just the access token (used after an OAuth refresh). */
    suspend fun updateToken(token: String) = context.dataStore.edit { it[Keys.TOKEN] = token }

    /** Backfill/refresh the user's avatar URL (e.g. fetched from Spotify after the fact). */
    suspend fun updateUserImage(url: String) = context.dataStore.edit { it[Keys.USER_IMAGE] = url }

    suspend fun clearSession() {
        context.dataStore.edit { p ->
            p.remove(Keys.SERVER); p.remove(Keys.USERNAME); p.remove(Keys.SALT); p.remove(Keys.TOKEN)
            p.remove(Keys.SERVER_TYPE); p.remove(Keys.USER_ID); p.remove(Keys.USER_IMAGE)
            p.remove(Keys.CLIENT_TOKEN); p.remove(Keys.CLIENT_VERSION)
        }
    }

    // --- Saved logins (multi-account quick switch) ---------------------------

    /** Remembered logins (with tokens), so the user can switch servers without re-entering creds. */
    val savedSessions: Flow<List<Session>> = context.dataStore.data.map { parseSessions(it[Keys.SAVED_SESSIONS]) }

    private fun parseSessions(json: String?): List<Session> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Session>>(json, object : TypeToken<List<Session>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList()).filter { it.isValid }

    /** Stable identity for a login (so re-saving the same account refreshes rather than duplicates). */
    private fun Session.accountKey() = "${type.name}|$server|$username|$userId"

    /** Add or refresh a saved login (keeps the newest token for that account). */
    suspend fun addSavedSession(session: Session) = context.dataStore.edit { p ->
        if (!session.isValid) return@edit
        val cur = parseSessions(p[Keys.SAVED_SESSIONS])
        val next = cur.filterNot { it.accountKey() == session.accountKey() } + session
        p[Keys.SAVED_SESSIONS] = gson.toJson(next)
    }

    suspend fun removeSavedSession(session: Session) = context.dataStore.edit { p ->
        val cur = parseSessions(p[Keys.SAVED_SESSIONS])
        p[Keys.SAVED_SESSIONS] = gson.toJson(cur.filterNot { it.accountKey() == session.accountKey() })
    }

    suspend fun setSkipSilence(v: Boolean) = context.dataStore.edit { it[Keys.SKIP_SILENCE] = v }
    suspend fun setCrossfade(sec: Int) = context.dataStore.edit { it[Keys.CROSSFADE] = sec }
    suspend fun setGapless(v: Boolean) = context.dataStore.edit { it[Keys.GAPLESS] = v }
    suspend fun setDefaultSpeed(v: Float) = context.dataStore.edit { it[Keys.DEFAULT_SPEED] = v }
    suspend fun setMono(v: Boolean) = context.dataStore.edit { it[Keys.MONO] = v }
    suspend fun setStreamWifi(v: Int) = context.dataStore.edit { it[Keys.STREAM_WIFI] = v }
    suspend fun setStreamCellular(v: Int) = context.dataStore.edit { it[Keys.STREAM_CELLULAR] = v }
    suspend fun setDownloadBitrate(v: Int) = context.dataStore.edit { it[Keys.DOWNLOAD_BITRATE] = v }
    suspend fun setPreferHighRes(v: Boolean) = context.dataStore.edit { it[Keys.PREFER_HIRES] = v }
    suspend fun setBitPerfectUsb(v: Boolean) = context.dataStore.edit { it[Keys.BIT_PERFECT_USB] = v }
    suspend fun setScrobble(v: Boolean) = context.dataStore.edit { it[Keys.SCROBBLE] = v }
    suspend fun setAutoplayRadio(v: Boolean) = context.dataStore.edit { it[Keys.AUTOPLAY_RADIO] = v }
    suspend fun setOfflineMode(v: Boolean) = context.dataStore.edit { it[Keys.OFFLINE] = v }
    suspend fun setLrclibEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LRCLIB] = v }
    suspend fun setDataSaver(v: Boolean) = context.dataStore.edit { it[Keys.DATA_SAVER] = v }
    suspend fun setPrivateSession(v: Boolean) = context.dataStore.edit { it[Keys.PRIVATE_SESSION] = v }
    suspend fun setGestureSwipeArtwork(v: Boolean) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_ART] = v }
    suspend fun setGestureSwipeDismiss(v: Boolean) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_DISMISS] = v }
    suspend fun setGestureDoubleTap(v: Boolean) = context.dataStore.edit { it[Keys.GESTURE_DOUBLE_TAP] = v }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = v }
    suspend fun setAlarm(enabled: Boolean, hour: Int, minute: Int) = context.dataStore.edit {
        it[Keys.ALARM_ENABLED] = enabled; it[Keys.ALARM_HOUR] = hour; it[Keys.ALARM_MINUTE] = minute
    }
    suspend fun setSpotifyClientId(v: String) = context.dataStore.edit { it[Keys.SPOTIFY_CLIENT_ID] = v.trim() }

    suspend fun setAcoustIdKey(v: String) = context.dataStore.edit { it[Keys.ACOUSTID_KEY] = v.trim() }
    suspend fun setAutoEqAutoSwitch(v: Boolean) = context.dataStore.edit { it[Keys.AUTOEQ_SWITCH] = v }
    suspend fun setActiveEqProfile(name: String) = context.dataStore.edit { it[Keys.AUTOEQ_PROFILE] = name }

    /** Add/replace the binding for a device (keyed by deviceKey). */
    suspend fun upsertEqBinding(binding: EqBinding) = context.dataStore.edit { p ->
        val cur = parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == binding.deviceKey }
        p[Keys.EQ_BINDINGS] = gson.toJson(cur + binding)
    }

    suspend fun removeEqBinding(deviceKey: String) = context.dataStore.edit { p ->
        p[Keys.EQ_BINDINGS] = gson.toJson(parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == deviceKey })
    }

    /** Pin or unpin a library item; de-duped by (serverId, kind, id) so each connection has its own. */
    suspend fun togglePin(pin: Pin) = context.dataStore.edit { p ->
        val cur = parsePins(p[Keys.PINS])
        fun same(x: Pin) = x.id == pin.id && x.kind == pin.kind && x.serverId == pin.serverId
        val next = if (cur.any(::same)) cur.filterNot(::same) else cur + pin
        p[Keys.PINS] = gson.toJson(next)
    }

    suspend fun setPlaylistLiked(id: String, liked: Boolean) = context.dataStore.edit { p ->
        val set = (p[Keys.LIKED_PLAYLISTS] ?: emptySet()).toMutableSet()
        if (liked) set.add(id) else set.remove(id)
        p[Keys.LIKED_PLAYLISTS] = set
    }

    suspend fun setEqEnabled(v: Boolean) = context.dataStore.edit { it[Keys.EQ_ENABLED] = v }
    suspend fun setEqPreset(v: Int) = context.dataStore.edit { it[Keys.EQ_PRESET] = v }
    suspend fun setEqBands(v: List<Int>) = context.dataStore.edit { it[Keys.EQ_BANDS] = v.joinToString(",") }
    suspend fun setBassBoost(v: Int) = context.dataStore.edit { it[Keys.BASS_BOOST] = v }
    suspend fun setVirtualizer(v: Int) = context.dataStore.edit { it[Keys.VIRTUALIZER] = v }
    suspend fun setLoudness(v: Int) = context.dataStore.edit { it[Keys.LOUDNESS] = v }
    suspend fun setReplayGain(v: Int) = context.dataStore.edit { it[Keys.REPLAY_GAIN] = v }

    suspend fun setDspMode(v: Int) = context.dataStore.edit { it[Keys.DSP_MODE] = v }
    suspend fun setDspGraphicBands(v: List<Float>) = context.dataStore.edit { it[Keys.DSP_GRAPHIC] = v.joinToString(",") }
    suspend fun setDspParametric(v: List<ParamBand>) = context.dataStore.edit {
        it[Keys.DSP_PARAMETRIC] = v.joinToString(";") { b -> "${b.freqHz}:${b.gainDb}:${b.q}:${b.type}" }
    }
    suspend fun setDspPreamp(v: Float) = context.dataStore.edit { it[Keys.DSP_PREAMP] = v }
    suspend fun setDspBalance(v: Float) = context.dataStore.edit { it[Keys.DSP_BALANCE] = v }
    suspend fun setDspWidth(v: Float) = context.dataStore.edit { it[Keys.DSP_WIDTH] = v }
    suspend fun setDspCrossfeed(v: Float) = context.dataStore.edit { it[Keys.DSP_CROSSFEED] = v }
    suspend fun setDspLimiterEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_LIMITER] = v }
    suspend fun setDspCeiling(v: Float) = context.dataStore.edit { it[Keys.DSP_CEILING] = v }
    suspend fun setDspCompEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_COMP] = v }
    suspend fun setDspCompThresh(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_THRESH] = v }
    suspend fun setDspCompRatio(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_RATIO] = v }
    suspend fun setDspConvEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_CONV] = v }
    suspend fun setDspConvMakeup(v: Float) = context.dataStore.edit { it[Keys.DSP_CONV_MAKEUP] = v }
    suspend fun setDspConvIr(path: String, name: String) = context.dataStore.edit {
        it[Keys.DSP_CONV_PATH] = path; it[Keys.DSP_CONV_NAME] = name
    }
    suspend fun setDspGraphicLayout(v: Int) = context.dataStore.edit { it[Keys.DSP_GRAPHIC_LAYOUT] = v }
    suspend fun setDspSaturation(v: Float) = context.dataStore.edit { it[Keys.DSP_SATURATION] = v }
    suspend fun setDspDelayLeft(v: Float) = context.dataStore.edit { it[Keys.DSP_DELAY_L] = v }
    suspend fun setDspDelayRight(v: Float) = context.dataStore.edit { it[Keys.DSP_DELAY_R] = v }
    suspend fun setDspTrimLeft(v: Float) = context.dataStore.edit { it[Keys.DSP_TRIM_L] = v }
    suspend fun setDspTrimRight(v: Float) = context.dataStore.edit { it[Keys.DSP_TRIM_R] = v }

    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[Keys.UI_THEME_MODE] = v }
    suspend fun setAccentMode(v: Int) = context.dataStore.edit { it[Keys.UI_ACCENT_MODE] = v }
    suspend fun setAccentPreset(v: Int) = context.dataStore.edit { it[Keys.UI_ACCENT_PRESET] = v }
    suspend fun setAccentColor(v: Long) = context.dataStore.edit { it[Keys.UI_ACCENT_COLOR] = v }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.UI_FONT_SCALE] = v }
    suspend fun setCornerStyle(v: Int) = context.dataStore.edit { it[Keys.UI_CORNER_STYLE] = v }
    suspend fun setPlayerSeekStyle(v: Int) = context.dataStore.edit { it[Keys.UI_PLAYER_SEEK] = v }
    suspend fun setPlayerWaveBars(v: Int) = context.dataStore.edit { it[Keys.UI_PLAYER_WAVE_BARS] = v }
    suspend fun setPlayerArtSize(v: Float) = context.dataStore.edit { it[Keys.UI_PLAYER_ART] = v }
    suspend fun setPlayerGradient(v: Float) = context.dataStore.edit { it[Keys.UI_PLAYER_GRADIENT] = v }
    suspend fun setPlayerShowUtilities(v: Boolean) = context.dataStore.edit { it[Keys.UI_PLAYER_UTILITIES] = v }
    suspend fun setMiniStyle(v: Int) = context.dataStore.edit { it[Keys.UI_MINI_STYLE] = v }
    suspend fun setMiniProgress(v: Int) = context.dataStore.edit { it[Keys.UI_MINI_PROGRESS] = v }
    suspend fun setLibraryColumns(v: Int) = context.dataStore.edit { it[Keys.UI_LIBRARY_COLUMNS] = v }
    suspend fun setHomeSectionHidden(id: String, hidden: Boolean) = context.dataStore.edit { p ->
        val set = (p[Keys.UI_HIDDEN_HOME] ?: emptySet()).toMutableSet()
        if (hidden) set.add(id) else set.remove(id)
        p[Keys.UI_HIDDEN_HOME] = set
    }

    suspend fun saveLastfm(sessionKey: String, username: String, imageUrl: String) = context.dataStore.edit { p ->
        p[Keys.LASTFM_SK] = sessionKey
        p[Keys.LASTFM_USER] = username
        p[Keys.LASTFM_IMAGE] = imageUrl
        p[Keys.LASTFM_ENABLED] = true
    }

    suspend fun clearLastfm() = context.dataStore.edit { p ->
        p.remove(Keys.LASTFM_SK); p.remove(Keys.LASTFM_USER); p.remove(Keys.LASTFM_IMAGE)
    }

    suspend fun setLastfmEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LASTFM_ENABLED] = v }

    suspend fun setLastfmKeys(apiKey: String, secret: String) = context.dataStore.edit { p ->
        p[Keys.LASTFM_API_KEY] = apiKey.trim()
        p[Keys.LASTFM_SECRET] = secret.trim()
    }

    suspend fun saveListenBrainz(token: String, username: String) = context.dataStore.edit { p ->
        p[Keys.LISTENBRAINZ_TOKEN] = token
        p[Keys.LISTENBRAINZ_USER] = username
        p[Keys.LISTENBRAINZ_ENABLED] = true
    }
    suspend fun clearListenBrainz() = context.dataStore.edit { p ->
        p.remove(Keys.LISTENBRAINZ_TOKEN); p.remove(Keys.LISTENBRAINZ_USER)
    }
    suspend fun setListenBrainzEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LISTENBRAINZ_ENABLED] = v }

    suspend fun saveDiscord(token: String, username: String) = context.dataStore.edit { p ->
        p[Keys.DISCORD_TOKEN] = token
        p[Keys.DISCORD_USER] = username
        p[Keys.DISCORD_ENABLED] = true
    }

    suspend fun clearDiscord() = context.dataStore.edit { p ->
        p.remove(Keys.DISCORD_TOKEN); p.remove(Keys.DISCORD_USER)
    }

    suspend fun setDiscordEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DISCORD_ENABLED] = v }
    suspend fun setDiscordImgur(v: String) = context.dataStore.edit { it[Keys.DISCORD_IMGUR] = v.trim() }
    suspend fun setDiscordAppId(v: String) = context.dataStore.edit { it[Keys.DISCORD_APP_ID] = v.trim() }

    // Backup / restore: all DataStore prefs, typed so JSON round-trips losslessly.

    suspend fun exportPrefs(): PrefsBackup {
        val p = context.dataStore.data.first()
        val strings = HashMap<String, String>(); val ints = HashMap<String, Int>(); val longs = HashMap<String, Long>()
        val booleans = HashMap<String, Boolean>(); val floats = HashMap<String, Float>(); val sets = HashMap<String, List<String>>()
        for ((k, v) in p.asMap()) when (v) {
            is String -> strings[k.name] = v
            is Int -> ints[k.name] = v
            is Long -> longs[k.name] = v
            is Boolean -> booleans[k.name] = v
            is Float -> floats[k.name] = v
            is Set<*> -> sets[k.name] = v.filterIsInstance<String>()
        }
        return PrefsBackup(strings, ints, longs, booleans, floats, sets)
    }

    suspend fun importPrefs(b: PrefsBackup) = context.dataStore.edit { p ->
        b.strings.forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
        b.ints.forEach { (k, v) -> p[intPreferencesKey(k)] = v }
        b.longs.forEach { (k, v) -> p[longPreferencesKey(k)] = v }
        b.booleans.forEach { (k, v) -> p[booleanPreferencesKey(k)] = v }
        b.floats.forEach { (k, v) -> p[floatPreferencesKey(k)] = v }
        b.stringSets.forEach { (k, v) -> p[stringSetPreferencesKey(k)] = v.toSet() }
    }
}
