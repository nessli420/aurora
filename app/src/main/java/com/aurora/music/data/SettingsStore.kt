package com.aurora.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.aurora.music.data.tuning.TuningProject
import com.aurora.music.data.tuning.TuningProjectCodec
import com.aurora.music.data.tuning.TuningRackPlan
import com.aurora.music.data.tuning.TuningTarget
import com.aurora.music.data.tuning.TuningTargetCatalog
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.ir.ImpulseLibraryFiles
import com.aurora.music.data.ir.ImpulsePreparation
import com.aurora.music.data.routes.*
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aurora_settings")

val DEFAULT_SOURCE_PRIORITY = listOf("local", "downloaded", "stream")

// sentinel meaning no servers so empty can keep meaning all eligible
const val MERGE_NONE = "__none__"

enum class ServerType { SUBSONIC, JELLYFIN, SPOTIFY, LOCAL }

// subsonic keeps salt+token never the raw password jellyfin uses token as access token
data class Session(
    val server: String,
    val username: String,
    val salt: String,
    val token: String,
    val type: ServerType = ServerType.SUBSONIC,
    val userId: String = "",
    val imageUrl: String = "",
    // spotify web-player only client-token + app-version that /v1 requires alongside the bearer
    val clientToken: String = "",
    val clientVersion: String = "",
) {
    val isValid: Boolean get() = server.isNotBlank() && username.isNotBlank() && token.isNotBlank()

    val typeLabel: String get() = when (type) {
        ServerType.SPOTIFY -> "Spotify"
        ServerType.JELLYFIN -> "Jellyfin"
        ServerType.SUBSONIC -> "Navidrome"
        ServerType.LOCAL -> "On this device"
    }
}

fun Session.accountKey(): String = "${type.name}|$server|$username|$userId"

data class PlaybackPrefs(
    val skipSilence: Boolean = false,
    val crossfadeSec: Int = 0,
    val crossfadeCurve: String = "SMOOTH",
    val crossfadeHeadroom: Boolean = true,
    val gapless: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val monoAudio: Boolean = false,
    val streamWifi: Int = 0,        // 0 = lossless original
    val streamCellular: Int = 0,
    val downloadBitrate: Int = 0,   // 0 = lossless original
    val preferHighRes: Boolean = false, // opt-in decoder-side precision processing; compatibility stays the default
    val scrobble: Boolean = true,
    val autoplayRadio: Boolean = false,
    val bitPerfectUsb: Boolean = false,
    val independentOutput: Boolean = false, // dont grab audio focus other apps keep playing through the speaker
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
    // ambient/textural set tuned for shoegaze cloud rap etc not just energetic music
    const val PLASMA = 26
    const val SILK_VEIL = 27
    const val NEBULA = 28
    const val HARMONOGRAPH = 29
    const val INK_BLOOM = 30
    const val count = 31
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
        PLASMA -> "Liquid chrome"; SILK_VEIL -> "Silk veil"; NEBULA -> "Nebula drift"
        HARMONOGRAPH -> "Harmonograph"; INK_BLOOM -> "Ink bloom"
        else -> "Spectrum bars"
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

// matches autoeq PK/LSC/HSC


object DspMode { const val SYSTEM = 0; const val CUSTOM = 1; const val OFF = 2 }

data class AudioPrefs(
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,                 // -1 = custom
    val eqBands: List<Int> = emptyList(),   // millibel gain per band
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudnessGain: Int = 0,
    val replayGain: Int = 0,                // 0=off 1=track 2=album
    val dspMode: Int = DspMode.SYSTEM,
    val dspGraphicBands: List<Float> = emptyList(),
    val dspParametric: List<ParamBand> = emptyList(),
    val dspPreampDb: Float = 0f,
    val dspBalance: Float = 0f,             // -1 L .. +1 R
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
    val dspGraphicLayout: Int = 0,          // index into DspCoeffBuilder.GRAPHIC_LAYOUTS
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

object AccentMode { const val PRESET = 0; const val CUSTOM = 1; const val MATERIAL_YOU = 2 }

object CornerStyle { const val SHARP = 0; const val DEFAULT = 1; const val ROUNDED = 2; const val PILL = 3 }

object SeekStyle { const val WAVEFORM = 0; const val BAR = 1 }

object MiniStyle { const val STANDARD = 0; const val COMPACT = 1; const val PROMINENT = 2 }

object MiniProgress { const val LINE = 0; const val BAR = 1; const val NONE = 2 }

object HomeSection {
    const val HERO = "hero"; const val RECENT = "recent"; const val PLAYLISTS = "playlists"
    const val FAVOURITE = "favourite"; const val MOST = "most"; const val ARTISTS = "artists"; const val NEW = "new"
}

data class UiPrefs(
    val themeMode: Int = ThemeMode.DARK,
    // Individual DataStore preference, not a Gson-serialized field.
    val themeStyle: Int = ThemeStyle.AURORA,
    val accentMode: Int = AccentMode.PRESET,
    val accentPreset: Int = 0,
    val accentColor: Long = 0xFFFF2E7EL,
    val fontScale: Float = 1f,
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
)

// scoped to serverId so a pin persists across logouts but only reappears on that connection
data class Pin(
    val id: String = "",
    val kind: String = "",        // album | playlist | artist
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

class SettingsStore(private val context: Context) {
    val processingRoutes = ProcessingRouteMonitor()

    private val gson = Gson()

    private object Keys {
        val PROCESSING_PRESETS = stringPreferencesKey("processing_presets_v1")
        val PROCESSING_RACK = stringPreferencesKey(ProcessingRackCodec.PREFERENCE_KEY)
        val TUNING_PROJECTS = stringPreferencesKey(TuningProjectCodec.PREFERENCE_KEY)
        val TUNING_TARGETS = stringPreferencesKey(TuningTargetCatalog.PREFERENCE_KEY)
        val IMPULSE_LIBRARY = stringPreferencesKey(ImpulseLibraryCodec.PREFERENCE_KEY)
        val PROCESSING_ROUTES = stringPreferencesKey(ProcessingRouteCodec.PREFERENCE_KEY)
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
        val CROSSFADE_CURVE = stringPreferencesKey("crossfade_curve")
        val CROSSFADE_HEADROOM = booleanPreferencesKey("crossfade_headroom")
        val GAPLESS = booleanPreferencesKey("gapless")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val MONO = booleanPreferencesKey("mono_audio")
        val STREAM_WIFI = intPreferencesKey("stream_wifi")
        val STREAM_CELLULAR = intPreferencesKey("stream_cellular")
        val DOWNLOAD_BITRATE = intPreferencesKey("download_bitrate")
        val PREFER_HIRES = booleanPreferencesKey("prefer_hires")
        val BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb")
        val INDEPENDENT_OUTPUT = booleanPreferencesKey("independent_output")
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
        val PINS = stringPreferencesKey("library_pins")   // not cleared on logout
        val SMART_PLAYLISTS = stringPreferencesKey("smart_playlists")  // not cleared on logout
        val RADIO_FAVORITES = stringPreferencesKey("radio_favorites")  // not cleared on logout
        val PODCAST_SUBS = stringPreferencesKey("podcast_subs")        // not cleared on logout
        val ARTIST_ENRICHMENT = booleanPreferencesKey("artist_enrichment")
        val PREFER_LOCAL = booleanPreferencesKey("prefer_local_sources")
        val SOURCE_PRIORITY = stringPreferencesKey("source_priority")
        val UNIFIED_LIBRARY = booleanPreferencesKey("unified_library")
        val MERGE_SOURCES = stringSetPreferencesKey("merge_sources")      // empty = all
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val SQUIG_BASE = stringPreferencesKey("squig_base_url")
        val SQUIG_TARGET = stringPreferencesKey("squig_target")
        val SAVED_SESSIONS = stringPreferencesKey("saved_sessions")
        val SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")  // survives logout
        val ACOUSTID_KEY = stringPreferencesKey("acoustid_key")           // survives logout
        val EQ_BINDINGS = stringPreferencesKey("eq_bindings")
        val AUTOEQ_SWITCH = booleanPreferencesKey("autoeq_autoswitch")
        val AUTOEQ_PROFILE = stringPreferencesKey("autoeq_active_profile")
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
        val UI_THEME_STYLE = intPreferencesKey("ui_theme_style")
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
        val LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
        val LASTFM_SECRET = stringPreferencesKey("lastfm_secret")
        val LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")
        val LISTENBRAINZ_USER = stringPreferencesKey("listenbrainz_user")
        val LISTENBRAINZ_ENABLED = booleanPreferencesKey("listenbrainz_enabled")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val DISCORD_USER = stringPreferencesKey("discord_user")
        val DISCORD_ENABLED = booleanPreferencesKey("discord_enabled")
        val DISCORD_IMGUR = stringPreferencesKey("discord_imgur")
        val DISCORD_APP_ID = stringPreferencesKey("discord_app_id")
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
            themeStyle = (p[Keys.UI_THEME_STYLE] ?: ThemeStyle.AURORA).coerceIn(ThemeStyle.AURORA, ThemeStyle.GLASS),
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

    val audioPrefs: Flow<AudioPrefs> = context.dataStore.data.map(::readAudioPrefs).distinctUntilChanged()

    private fun readAudioPrefs(p: Preferences): AudioPrefs =
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

    val processingSettings: Flow<ProcessingSettings> = context.dataStore.data.map { p ->
        val audio = readAudioPrefs(p)
        val playback = readPlaybackPrefs(p)
        ProcessingSettings(audio, playback, readProcessingRack(p, audio, playback.monoAudio))
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val processingSnapshot: Flow<ProcessingSnapshot> = context.dataStore.data.map { p ->
        val audio = readAudioPrefs(p)
        val playback = readPlaybackPrefs(p)
        ProcessingSnapshot(audio, ProcessingPlaybackPrefs.from(playback),
            p[Keys.AUTOEQ_PROFILE].orEmpty(), readProcessingRack(p, audio, playback.monoAudio))
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val processingRack: Flow<ProcessingRack> = processingSettings.map { it.rack }.distinctUntilChanged()

    private fun readProcessingRack(p: Preferences, audio: AudioPrefs, mono: Boolean): ProcessingRack =
        p[Keys.PROCESSING_RACK]?.let { ProcessingRackCodec.decode(it).getOrNull() } ?: ProcessingRack.legacy(audio, mono)

    suspend fun setProcessingRack(rack: ProcessingRack): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = ProcessingRackCodec.validate(rack)
            val encoded = ProcessingRackCodec.encode(validated)
            context.dataStore.edit { p ->
                p[Keys.PROCESSING_RACK] = encoded
                if (validated.enabled) p[Keys.DSP_MODE] = DspMode.CUSTOM
                holdManualRoute(p)
            }
            Unit
        }
    }

    /** Rebuild a disabled graph from current effective legacy settings in one transaction. */
    suspend fun migrateProcessingRack(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.dataStore.edit { p ->
                p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(
                    ProcessingRack.legacy(readAudioPrefs(p), readPlaybackPrefs(p).monoAudio))
                holdManualRoute(p)
            }
            Unit
        }
    }

    suspend fun resetProcessingRack(): Result<Unit> = migrateProcessingRack()

    val impulseLibrary: Flow<List<ImpulseLibraryEntry>> = context.dataStore.data
        .map { it[Keys.IMPULSE_LIBRARY] }.distinctUntilChanged()
        .map { ImpulseLibraryCodec.decodeLibrary(it).getOrThrow() }.flowOn(Dispatchers.IO)

    suspend fun importImpulse(input: InputStream, name: String): Result<ImpulseLibraryEntry> = withContext(Dispatchers.IO) {
        impulseResult {
            val file = newImpulseFile()
            var published = false
            try {
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytes += count
                        require(bytes <= 64L * 1024 * 1024) { "WAV exceeds 64 MiB." }
                        output.write(buffer, 0, count)
                    }
                }
                val entry = ImpulseLibraryFiles.importOriginal(file, name.substringBeforeLast('.').take(80).ifBlank { "Impulse response" },
                    name, System.currentTimeMillis()).getOrThrow()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    context.dataStore.edit { p ->
                        val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                        require(library.size < ImpulseLibraryCodec.MAX_ENTRIES) { "The library holds up to 32 impulse responses." }
                        p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library + entry)
                    }
                    published = true
                }
                entry
            } finally { if (!published) file.delete() }
        }
    }

    suspend fun importCurrentImpulse(): Result<ImpulseLibraryEntry> = withContext(Dispatchers.IO) {
        impulseResult {
            val audio = audioPrefs.first()
            require(audio.dspConvIrPath.isNotBlank()) { "No impulse response is selected." }
            File(audio.dspConvIrPath).inputStream().use {
                importImpulse(it, audio.dspConvIrName.ifBlank { "Impulse response.wav" }).getOrThrow()
            }
        }
    }

    suspend fun renameImpulse(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            context.dataStore.edit { p ->
                val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                val current = library.firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
                val renamed = ImpulseLibraryCodec.validate(current.copy(name = name))
                p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library.map {
                    if (it.id == id) renamed else it
                })
                val selectedPath = p[Keys.DSP_CONV_PATH]
                when {
                    selectedPath == current.sourcePath -> p[Keys.DSP_CONV_NAME] = renamed.name
                    current.prepared != null && selectedPath == current.prepared.path -> {
                        p[Keys.DSP_CONV_NAME] = "${renamed.name} · Variant"
                    }
                }
            }
            Unit
        }
    }

    suspend fun deleteImpulse(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            context.dataStore.edit { p ->
                val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                require(library.any { it.id == id }) { "Impulse response no longer exists." }
                // active playback and presets may still reference these files
                p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library.filterNot { it.id == id })
            }
            Unit
        }
    }

    suspend fun prepareImpulse(id: String, options: ImpulsePreparation): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val entry = impulseLibrary.first().firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
            val file = newImpulseFile()
            var published = false
            try {
                val prepared = ImpulseLibraryFiles.prepare(entry, file, options).getOrThrow()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    context.dataStore.edit { p ->
                        val library = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow()
                        val current = library.firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
                        require(current.sourcePath == entry.sourcePath && current.sourceSha256 == entry.sourceSha256) {
                            "The source changed. Open it again."
                        }
                        p[Keys.IMPULSE_LIBRARY] = ImpulseLibraryCodec.encodeLibrary(library.map {
                            if (it.id == id) it.copy(prepared = prepared.prepared) else it
                        })
                    }
                    published = true
                }
                Unit
            } finally { if (!published) file.delete() }
        }
    }

    suspend fun selectImpulse(id: String, prepared: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val entry = impulseLibrary.first().firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
            val file = ImpulseLibraryFiles.validateAsset(entry, prepared).getOrThrow()
            val metadata = if (prepared) requireNotNull(entry.prepared).metadata else entry.sourceMetadata
            require(ImpulseLibraryFiles.estimate(metadata, metadata.sampleRate).supported) { "Trim this response before selecting it." }
            context.dataStore.edit { p ->
                val current = ImpulseLibraryCodec.decodeLibrary(p[Keys.IMPULSE_LIBRARY]).getOrThrow().firstOrNull { it.id == id }
                    ?: error("Impulse response no longer exists.")
                require(if (prepared) current.prepared == entry.prepared else
                    current.sourcePath == entry.sourcePath && current.sourceSha256 == entry.sourceSha256) { "The response changed. Select it again." }
                p[Keys.DSP_CONV_PATH] = file.absolutePath
                p[Keys.DSP_CONV_NAME] = current.name + if (prepared) " · Variant" else ""
                holdManualRoute(p)
            }
            Unit
        }
    }

    suspend fun exportImpulse(id: String, prepared: Boolean, output: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val entry = impulseLibrary.first().firstOrNull { it.id == id } ?: error("Impulse response no longer exists.")
            val file = ImpulseLibraryFiles.validateAsset(entry, prepared).getOrThrow()
            file.inputStream().use { it.copyTo(output) }
            Unit
        }
    }

    private fun newImpulseFile(): File {
        val directory = File(context.filesDir, "impulse-library")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create impulse-response storage." }
        return File(directory, "${UUID.randomUUID()}.wav")
    }

    private suspend fun <T> impulseResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    val tuningTargets: Flow<List<TuningTarget>> = context.dataStore.data
        .map { it[Keys.TUNING_TARGETS] }.distinctUntilChanged()
        .map { TuningTargetCatalog.decodeLibrary(it).getOrThrow() }.flowOn(Dispatchers.Default)

    suspend fun saveTuningTarget(target: TuningTarget): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            context.dataStore.edit { p ->
                val library = TuningTargetCatalog.decodeLibrary(p[Keys.TUNING_TARGETS]).getOrThrow()
                p[Keys.TUNING_TARGETS] = TuningTargetCatalog.encodeLibrary(TuningTargetCatalog.upsert(library, target).getOrThrow())
            }
            Unit
        }
    }

    suspend fun deleteTuningTarget(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            context.dataStore.edit { p ->
                val library = TuningTargetCatalog.decodeLibrary(p[Keys.TUNING_TARGETS]).getOrThrow()
                p[Keys.TUNING_TARGETS] = TuningTargetCatalog.encodeLibrary(TuningTargetCatalog.delete(library, id).getOrThrow())
            }
            Unit
        }
    }

    suspend fun revertTuningAppend(expectedRack: ProcessingRack, previousRack: ProcessingRack): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val encoded = ProcessingRackCodec.encode(previousRack)
            context.dataStore.edit { p ->
                val current = readProcessingRack(p, readAudioPrefs(p), readPlaybackPrefs(p).monoAudio)
                require(current == expectedRack) { "The rack changed. Remove the correction stages manually." }
                p[Keys.PROCESSING_RACK] = encoded
                holdManualRoute(p)
            }
            Unit
        }
    }

    val tuningProjects: Flow<List<TuningProject>> = context.dataStore.data
        .map { it[Keys.TUNING_PROJECTS] }.distinctUntilChanged()
        .map { TuningProjectCodec.decodeLibrary(it).getOrThrow() }.flowOn(Dispatchers.Default)

    suspend fun saveTuningProject(project: TuningProject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = TuningProjectCodec.validate(project)
            context.dataStore.edit { p ->
                p[Keys.TUNING_PROJECTS] = TuningProjectCodec.encodeLibrary(TuningProjectCodec.upsert(
                    TuningProjectCodec.decodeLibrary(p[Keys.TUNING_PROJECTS]).getOrThrow(), validated).getOrThrow())
            }
            Unit
        }
    }

    suspend fun deleteTuningProject(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.dataStore.edit { p ->
                p[Keys.TUNING_PROJECTS] = TuningProjectCodec.encodeLibrary(TuningProjectCodec.delete(
                    TuningProjectCodec.decodeLibrary(p[Keys.TUNING_PROJECTS]).getOrThrow(), id).getOrThrow())
            }
            Unit
        }
    }

    suspend fun appendTuningProjectToRack(project: TuningProject): Result<Unit> =
        appendTuningProjectWithUndo(project).map { Unit }

    suspend fun appendTuningProjectWithUndo(project: TuningProject): Result<Pair<ProcessingRack, ProcessingRack>> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = TuningProjectCodec.validate(project)
            var snapshots: Pair<ProcessingRack, ProcessingRack>? = null
            context.dataStore.edit { p ->
                val rack = readProcessingRack(p, readAudioPrefs(p), readPlaybackPrefs(p).monoAudio)
                val updatedRack = TuningRackPlan.append(rack, validated)
                snapshots = rack to updatedRack
                val library = TuningProjectCodec.upsert(TuningProjectCodec.decodeLibrary(p[Keys.TUNING_PROJECTS]).getOrThrow(), validated).getOrThrow()
                p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(updatedRack)
                p[Keys.TUNING_PROJECTS] = TuningProjectCodec.encodeLibrary(library)
                holdManualRoute(p)
            }
            requireNotNull(snapshots)
        }
    }

    val processingPresetLibrary: Flow<ProcessingPresetLibrary> = context.dataStore.data
        .map { it[Keys.PROCESSING_PRESETS] }
        .distinctUntilChanged()
        .map(ProcessingPresetCodec::decode)
        .flowOn(Dispatchers.Default)

    private fun presetList(p: Preferences): List<ProcessingPreset> {
        val library = ProcessingPresetCodec.decode(p[Keys.PROCESSING_PRESETS])
        require(library.error == null) { library.error.orEmpty() }
        return library.presets
    }

    suspend fun saveProcessingPreset(name: String): Result<ProcessingPreset> = withContext(Dispatchers.IO) {
        runCatching {
            val title = ProcessingPresetCodec.name(name)
            var saved: ProcessingPreset? = null
            context.dataStore.edit { p ->
                val previous = presetList(p)
                require(previous.size < ProcessingPresetCodec.MAX_PRESETS) { "You can save up to 100 presets." }
                var audio = readAudioPrefs(p)
                val playback = readPlaybackPrefs(p)
                val rack = readProcessingRack(p, audio, playback.monoAudio)
                val needsImpulse = if (rack.enabled) rack.requiresImpulseResponse() else audio.dspConvEnabled
                var hash = ""
                if (audio.dspConvIrPath.isNotBlank()) {
                    val source = File(audio.dspConvIrPath)
                    if (source.isFile && source.canRead()) {
                        require(source.length() in 1..MAX_PRESET_IR_BYTES) { "Impulse response must be between 1 byte and 64 MB." }
                        val directory = File(context.filesDir, "processing-presets")
                        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preset storage." }
                        val destination = File(directory, "${UUID.randomUUID()}.wav")
                        // Keep this asset even if the edit subsequently fails or is cancelled:
                        // DataStore may have committed before the caller observes cancellation.
                        // Cleanup must eventually check durable and active references first.
                        source.inputStream().use { input -> destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_PRESET_IR_BYTES) { "Impulse response exceeds 64 MB." }
                                output.write(buffer, 0, count)
                            }
                        } }
                        hash = irHash(destination)
                        audio = audio.copy(dspConvIrPath = destination.absolutePath)
                    } else require(!needsImpulse) { "The selected impulse response is missing. Select it again before saving." }
                } else require(!needsImpulse) { "Select an impulse response before saving enabled convolution." }
                val preset = ProcessingPreset(UUID.randomUUID().toString(), title,
                    createdAtMs = System.currentTimeMillis(), audio = audio,
                    playback = ProcessingPlaybackPrefs.from(playback),
                    activeEqProfile = p[Keys.AUTOEQ_PROFILE].orEmpty(), irSha256 = hash, rack = rack)
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous + preset)
                saved = preset
            }
            requireNotNull(saved)
        }
    }

    val processingRouteRules: Flow<ProcessingRouteRules> = context.dataStore.data
        .map { ProcessingRouteCodec.decode(it[Keys.PROCESSING_ROUTES]).getOrThrow() }.distinctUntilChanged()

    suspend fun setRouteRulesEnabled(enabled: Boolean): Result<Unit> = editRouteRules { it.copy(enabled = enabled) }

    suspend fun holdCurrentRoute(hold: Boolean): Result<Unit> {
        val key = processingRoutes.current.route.key ?: return Result.failure(IllegalStateException("Output identity is unavailable."))
        return editRouteRules { it.copy(manual = if (hold) it.manual + key else it.manual - key) }
    }

    suspend fun chooseRouteHeadphones(key: String, headphones: String): Result<Unit> = editRouteRules { rules ->
        require(ProcessingRouteCodec.validKey(key)) { "Invalid output identity." }
        require(headphones.isEmpty() || rules.bindings.any { it.routeKey == key && it.headphones == headphones }) { "Headphones are not bound to this output." }
        rules.copy(headphones = rules.headphones + (key to headphones), manual = rules.manual - key)
    }

    suspend fun bindCurrentRoute(presetId: String, headphones: String,
        expected: RouteObservation = processingRoutes.current): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val route = expected.route
            val key = route.key ?: error("Output identity is unavailable.")
            require(route.kind == ProcessingRouteKind.ANDROID) { "This output bypasses local processing." }
            context.dataStore.edit { p ->
                require(processingRoutes.current == expected) { "The output changed. Try again." }
                require(presetList(p).any { it.id == presetId }) { "This preset no longer exists." }
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                val name = headphones.trim()
                val binding = RoutePresetBinding(key, route.label, name, presetId)
                p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(rules.copy(enabled = true,
                    bindings = rules.bindings.filterNot { it.routeKey == key && it.headphones == name } + binding,
                    headphones = rules.headphones + (key to name), manual = rules.manual - key))
            }
            Unit
        }
    }

    suspend fun removeRouteBinding(binding: RoutePresetBinding): Result<Unit> = editRouteRules { rules ->
        rules.copy(bindings = rules.bindings.filterNot { it == binding }, manual = rules.manual + binding.routeKey)
    }

    private suspend fun editRouteRules(change: (ProcessingRouteRules) -> ProcessingRouteRules): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            context.dataStore.edit { p ->
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(change(rules))
            }
            Unit
        }
    }

    suspend fun applyProcessingPreset(id: String): Result<ProcessingPresetApplyResult> = withContext(Dispatchers.IO) {
        impulseResult {
            var result: ProcessingPresetApplyResult? = null
            context.dataStore.edit { p ->
                val preset = presetList(p).firstOrNull { it.id == id } ?: error("This preset no longer exists.")
                result = applyPresetSnapshot(p, preset)
                holdManualRoute(p)
            }
            requireNotNull(result)
        }
    }

    suspend fun applyRoutePreset(expected: RouteObservation, presetId: String): Result<ProcessingPresetApplyResult?> = withContext(Dispatchers.IO) {
        impulseResult {
            var result: ProcessingPresetApplyResult? = null
            context.dataStore.edit { p ->
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                if (!RouteRuleDecision.mayApply(expected, processingRoutes.current, rules, presetId)) return@edit
                val preset = presetList(p).firstOrNull { it.id == presetId } ?: error("The bound preset was deleted.")
                validatePresetImpulse(preset)
                if (!RouteRuleDecision.mayApply(expected, processingRoutes.current, rules, presetId)) return@edit
                result = applyPresetSnapshot(p, preset, validateImpulse = false)
            }
            result
        }
    }

    private fun validatePresetImpulse(preset: ProcessingPreset) {
        if (!preset.requiresImpulseResponse()) return
        require(preset.audio.dspConvIrPath.isNotBlank() && preset.irSha256.isNotBlank()) { "This preset needs its saved impulse response." }
        val file = File(preset.audio.dspConvIrPath)
        require(file.isFile && file.canRead() && irHash(file) == preset.irSha256) { "The preset impulse response is missing or changed." }
    }

    private fun applyPresetSnapshot(p: MutablePreferences, preset: ProcessingPreset, validateImpulse: Boolean = true): ProcessingPresetApplyResult {
        if (validateImpulse) validatePresetImpulse(preset)
        val old = readPlaybackPrefs(p)
        val restart = old.preferHighRes != preset.playback.preferHighRes || old.bitPerfectUsb != preset.playback.bitPerfectUsb
        writeProcessingAudio(p, if (preset.rack.enabled) preset.audio.copy(dspMode = DspMode.CUSTOM) else preset.audio)
        writeProcessingPlayback(p, preset.playback)
        p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(preset.rack)
        p[Keys.AUTOEQ_PROFILE] = preset.activeEqProfile
        return ProcessingPresetApplyResult(preset.name, restart)
    }

    private fun holdManualRoute(p: MutablePreferences) {
        val route = processingRoutes.current.route
        val key = route.key ?: return
        if (route.kind != ProcessingRouteKind.ANDROID) return
        val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
        val fullBinding = rules.enabled && rules.bindings.any { it.routeKey == key }
        val legacyBinding = p[Keys.AUTOEQ_SWITCH] == true && parseBindings(p[Keys.EQ_BINDINGS]).any {
            it.deviceKey == key || it.deviceKey == route.legacyKey
        }
        if (fullBinding || legacyBinding) p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(rules.copy(manual = rules.manual + key))
    }

    private suspend fun editManualProcessing(change: (MutablePreferences) -> Unit) = context.dataStore.edit { p ->
        change(p)
        holdManualRoute(p)
    }
    suspend fun duplicateProcessingPreset(id: String, name: String): Result<ProcessingPreset> = withContext(Dispatchers.IO) {
        runCatching {
            val title = ProcessingPresetCodec.name(name)
            var duplicate: ProcessingPreset? = null
            context.dataStore.edit { p ->
                val previous = presetList(p)
                val original = previous.firstOrNull { it.id == id } ?: error("This preset no longer exists.")
                val copy = original.copy(id = UUID.randomUUID().toString(), name = title, createdAtMs = System.currentTimeMillis())
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous + copy)
                duplicate = copy
            }
            requireNotNull(duplicate)
        }
    }

    suspend fun renameProcessingPreset(id: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val title = ProcessingPresetCodec.name(name)
            context.dataStore.edit { p ->
                val previous = presetList(p)
                require(previous.any { it.id == id }) { "This preset no longer exists." }
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous.map { if (it.id == id) it.copy(name = title) else it })
            }
            Unit
        }
    }

    suspend fun deleteProcessingPreset(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.dataStore.edit { p ->
                val previous = presetList(p)
                require(previous.any { it.id == id }) { "This preset no longer exists." }
                p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous.filterNot { it.id == id })
            }
            // Retain immutable IRs: another preset, duplicate, or current processing can reference them.
            Unit
        }
    }

    /** Export one immutable snapshot. The caller owns and closes the destination stream. */
    suspend fun exportProcessingPreset(id: String, output: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val preset = presetList(context.dataStore.data.first()).firstOrNull { it.id == id }
                ?: error("This preset no longer exists.")
            val asset = preset.audio.dspConvIrPath.takeIf { preset.irSha256.isNotBlank() }?.let(::File)
            ProcessingPresetBundle.write(preset, asset, output)
        }
    }

    /** Validate every dependency before changing the library. Import never applies audio settings. */
    suspend fun importProcessingPreset(input: InputStream): Result<ProcessingPreset> = withContext(Dispatchers.IO) {
        runCatching {
            ProcessingPresetBundle.read(input, context.cacheDir).use { bundle ->
                var imported: ProcessingPreset? = null
                context.dataStore.edit { p ->
                    val previous = presetList(p)
                    require(previous.size < ProcessingPresetCodec.MAX_PRESETS) { "You can save up to 100 presets." }
                    var audio = bundle.preset.audio
                    bundle.impulseResponse?.let { source ->
                        val directory = File(context.filesDir, "processing-presets")
                        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preset storage." }
                        val destination = File(directory, "${UUID.randomUUID()}.wav")
                        source.copyTo(destination, overwrite = false)
                        // As with save, retain a durable asset if commit/cancellation status is
                        // uncertain. Live processing can still be loading an older reference.
                        audio = audio.copy(dspConvIrPath = destination.absolutePath)
                    }
                    val preset = bundle.preset.copy(id = UUID.randomUUID().toString(),
                        createdAtMs = System.currentTimeMillis(), audio = audio)
                    p[Keys.PROCESSING_PRESETS] = ProcessingPresetCodec.encode(previous + preset)
                    imported = preset
                }
                requireNotNull(imported)
            }
        }
    }

    suspend fun applyEqBindingIfEnabled(binding: EqBinding, expectedRoute: RouteObservation? = null): Boolean {
        var applied = false
        context.dataStore.edit { p ->
            if (p[Keys.AUTOEQ_SWITCH] != true || binding !in parseBindings(p[Keys.EQ_BINDINGS])) return@edit
            val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
            val observed = processingRoutes.current
            val key = observed.route.key
            if (expectedRoute != null && (expectedRoute != observed || key == null || observed.route.kind != ProcessingRouteKind.ANDROID)) return@edit
            if (key != null && (key in rules.manual || rules.enabled && rules.bindings.any { it.routeKey == key })) return@edit
            p[Keys.DSP_PARAMETRIC] = ParamBandCodec.encodePreference(binding.bands)
            p[Keys.DSP_PREAMP] = binding.preampDb
            p[Keys.DSP_MODE] = DspMode.CUSTOM
            p[Keys.AUTOEQ_PROFILE] = binding.profileName
            applied = true
        }
        return applied
    }

    private fun irHash(file: File): String {
        require(file.length() in 1..MAX_PRESET_IR_BYTES) { "Impulse response must be between 1 byte and 64 MB." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PRESET_IR_BYTES) { "Impulse response exceeds 64 MB." }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val MAX_PRESET_IR_BYTES = 64L * 1024L * 1024L }

    private fun writeProcessingAudio(p: MutablePreferences, a: AudioPrefs) {
        p[Keys.EQ_ENABLED] = a.eqEnabled
        p[Keys.EQ_PRESET] = a.eqPreset
        p[Keys.EQ_BANDS] = a.eqBands.joinToString(",")
        p[Keys.BASS_BOOST] = a.bassBoost
        p[Keys.VIRTUALIZER] = a.virtualizer
        p[Keys.LOUDNESS] = a.loudnessGain
        p[Keys.REPLAY_GAIN] = a.replayGain
        p[Keys.DSP_MODE] = a.dspMode
        p[Keys.DSP_GRAPHIC] = a.dspGraphicBands.joinToString(",")
        p[Keys.DSP_PREAMP] = a.dspPreampDb
        p[Keys.DSP_BALANCE] = a.dspBalance
        p[Keys.DSP_WIDTH] = a.dspWidth
        p[Keys.DSP_CROSSFEED] = a.dspCrossfeed
        p[Keys.DSP_LIMITER] = a.dspLimiterEnabled
        p[Keys.DSP_CEILING] = a.dspLimiterCeilingDb
        p[Keys.DSP_COMP] = a.dspCompEnabled
        p[Keys.DSP_COMP_THRESH] = a.dspCompThreshDb
        p[Keys.DSP_COMP_RATIO] = a.dspCompRatio
        p[Keys.DSP_CONV] = a.dspConvEnabled
        p[Keys.DSP_CONV_PATH] = a.dspConvIrPath
        p[Keys.DSP_CONV_NAME] = a.dspConvIrName
        p[Keys.DSP_CONV_MAKEUP] = a.dspConvMakeupDb
        p[Keys.DSP_GRAPHIC_LAYOUT] = a.dspGraphicLayout
        p[Keys.DSP_SATURATION] = a.dspSaturation
        p[Keys.DSP_DELAY_L] = a.dspDelayLeftMs
        p[Keys.DSP_DELAY_R] = a.dspDelayRightMs
        p[Keys.DSP_TRIM_L] = a.dspTrimLeftDb
        p[Keys.DSP_TRIM_R] = a.dspTrimRightDb
        p[Keys.DSP_PARAMETRIC] = ParamBandCodec.encodePreference(a.dspParametric)
    }

    private fun writeProcessingPlayback(p: MutablePreferences, a: ProcessingPlaybackPrefs) {
        p[Keys.SKIP_SILENCE] = a.skipSilence
        p[Keys.CROSSFADE] = a.crossfadeSec
        p[Keys.CROSSFADE_CURVE] = a.crossfadeCurve
        p[Keys.CROSSFADE_HEADROOM] = a.crossfadeHeadroom
        p[Keys.GAPLESS] = a.gapless
        p[Keys.DEFAULT_SPEED] = a.defaultSpeed
        p[Keys.MONO] = a.monoAudio
        p[Keys.PREFER_HIRES] = a.preferHighRes
        p[Keys.BIT_PERFECT_USB] = a.bitPerfectUsb
        p[Keys.INDEPENDENT_OUTPUT] = a.independentOutput
    }

    private fun parseParametric(s: String?): List<ParamBand> = ParamBandCodec.decodePreference(s)

    val offlineMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.OFFLINE] ?: false }
    val lrclibEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LRCLIB] ?: true }
    val dataSaver: Flow<Boolean> = context.dataStore.data.map { it[Keys.DATA_SAVER] ?: false }
    val privateSession: Flow<Boolean> = context.dataStore.data.map { it[Keys.PRIVATE_SESSION] ?: false }

    val gesturePrefs: Flow<GesturePrefs> = context.dataStore.data.map { p ->
        GesturePrefs(
            swipeArtwork = p[Keys.GESTURE_SWIPE_ART] ?: true,
            swipeDownDismiss = p[Keys.GESTURE_SWIPE_DISMISS] ?: true,
            doubleTapPause = p[Keys.GESTURE_DOUBLE_TAP] ?: true,
        )
    }
    val haptics: Flow<Boolean> = context.dataStore.data.map { it[Keys.HAPTICS] ?: false }

    val alarmPrefs: Flow<AlarmPrefs> = context.dataStore.data.map { p ->
        AlarmPrefs(
            enabled = p[Keys.ALARM_ENABLED] ?: false,
            hour = p[Keys.ALARM_HOUR] ?: 7,
            minute = p[Keys.ALARM_MINUTE] ?: 0,
        )
    }

    val spotifyClientId: Flow<String> = context.dataStore.data.map { it[Keys.SPOTIFY_CLIENT_ID] ?: "" }

    val acoustIdKey: Flow<String> = context.dataStore.data.map { it[Keys.ACOUSTID_KEY] ?: "" }

    // distinctUntilChanged so a settings write doesnt re-fire and wipe a just-applied correction
    val eqBindings: Flow<List<EqBinding>> = context.dataStore.data.map { parseBindings(it[Keys.EQ_BINDINGS]) }.distinctUntilChanged()
    val autoEqAutoSwitch: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTOEQ_SWITCH] ?: false }.distinctUntilChanged()
    val activeEqProfile: Flow<String> = context.dataStore.data.map { it[Keys.AUTOEQ_PROFILE] ?: "" }

    private fun parseBindings(json: String?): List<EqBinding> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<EqBinding>>(json, object : TypeToken<List<EqBinding>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    val pins: Flow<List<Pin>> = context.dataStore.data.map { p -> parsePins(p[Keys.PINS]) }

    private fun parsePins(json: String?): List<Pin> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Pin>>(json, object : TypeToken<List<Pin>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    val smartPlaylists: Flow<List<SmartPlaylist>> = context.dataStore.data.map { p -> parseSmart(p[Keys.SMART_PLAYLISTS]) }

    private fun parseSmart(json: String?): List<SmartPlaylist> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<SmartPlaylist>>(json, object : TypeToken<List<SmartPlaylist>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun saveSmartPlaylist(sp: SmartPlaylist) = context.dataStore.edit { p ->
        val cur = parseSmart(p[Keys.SMART_PLAYLISTS])
        val next = if (cur.any { it.id == sp.id }) cur.map { if (it.id == sp.id) sp else it } else cur + sp
        p[Keys.SMART_PLAYLISTS] = gson.toJson(next)
    }

    suspend fun deleteSmartPlaylist(id: String) = context.dataStore.edit { p ->
        p[Keys.SMART_PLAYLISTS] = gson.toJson(parseSmart(p[Keys.SMART_PLAYLISTS]).filterNot { it.id == id })
    }

    val radioFavorites: Flow<List<RadioStation>> = context.dataStore.data.map { p -> parseRadio(p[Keys.RADIO_FAVORITES]) }

    private fun parseRadio(json: String?): List<RadioStation> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<RadioStation>>(json, object : TypeToken<List<RadioStation>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun saveRadioStation(s: RadioStation) = context.dataStore.edit { p ->
        val cur = parseRadio(p[Keys.RADIO_FAVORITES])
        val next = if (cur.any { it.uuid == s.uuid }) cur.map { if (it.uuid == s.uuid) s else it } else cur + s
        p[Keys.RADIO_FAVORITES] = gson.toJson(next)
    }

    suspend fun deleteRadioStation(uuid: String) = context.dataStore.edit { p ->
        p[Keys.RADIO_FAVORITES] = gson.toJson(parseRadio(p[Keys.RADIO_FAVORITES]).filterNot { it.uuid == uuid })
    }

    val podcastSubs: Flow<List<Podcast>> = context.dataStore.data.map { p -> parsePodcasts(p[Keys.PODCAST_SUBS]) }

    private fun parsePodcasts(json: String?): List<Podcast> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Podcast>>(json, object : TypeToken<List<Podcast>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun savePodcast(p: Podcast) = context.dataStore.edit { prefs ->
        val cur = parsePodcasts(prefs[Keys.PODCAST_SUBS])
        val next = if (cur.any { it.feedUrl == p.feedUrl }) cur.map { if (it.feedUrl == p.feedUrl) p else it } else cur + p
        prefs[Keys.PODCAST_SUBS] = gson.toJson(next)
    }

    suspend fun deletePodcast(feedUrl: String) = context.dataStore.edit { p ->
        p[Keys.PODCAST_SUBS] = gson.toJson(parsePodcasts(p[Keys.PODCAST_SUBS]).filterNot { it.feedUrl == feedUrl })
    }

    // persisted locally because subsonic has no playlist-star
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

    val playbackPrefs: Flow<PlaybackPrefs> = context.dataStore.data.map(::readPlaybackPrefs).distinctUntilChanged()

    private fun readPlaybackPrefs(p: Preferences): PlaybackPrefs =
        PlaybackPrefs(
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            crossfadeSec = p[Keys.CROSSFADE] ?: 0,
            crossfadeCurve = p[Keys.CROSSFADE_CURVE] ?: "SMOOTH",
            crossfadeHeadroom = p[Keys.CROSSFADE_HEADROOM] ?: true,
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
            independentOutput = p[Keys.INDEPENDENT_OUTPUT] ?: false,
        )

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

    val sonicAutoAnalyze: Flow<Boolean> = context.dataStore.data.map { it[Keys.SONIC_AUTO_ANALYZE] ?: false }
    suspend fun setSonicAutoAnalyze(v: Boolean) = context.dataStore.edit { it[Keys.SONIC_AUTO_ANALYZE] = v }

    // off keeps artist names from ever leaving the device
    val artistEnrichment: Flow<Boolean> = context.dataStore.data.map { it[Keys.ARTIST_ENRICHMENT] ?: true }
    suspend fun setArtistEnrichment(v: Boolean) = context.dataStore.edit { it[Keys.ARTIST_ENRICHMENT] = v }

    val preferLocalSources: Flow<Boolean> = context.dataStore.data.map { it[Keys.PREFER_LOCAL] ?: true }
    suspend fun setPreferLocalSources(v: Boolean) = context.dataStore.edit { it[Keys.PREFER_LOCAL] = v }

    val sourcePriority: Flow<List<String>> = context.dataStore.data.map { p ->
        parseStringList(p[Keys.SOURCE_PRIORITY]).filter { it in DEFAULT_SOURCE_PRIORITY }
            .ifEmpty { DEFAULT_SOURCE_PRIORITY }
    }
    suspend fun setSourcePriority(order: List<String>) = context.dataStore.edit {
        it[Keys.SOURCE_PRIORITY] = gson.toJson(order.filter { t -> t in DEFAULT_SOURCE_PRIORITY }.distinct())
    }

    val unifiedLibrary: Flow<Boolean> = context.dataStore.data.map { it[Keys.UNIFIED_LIBRARY] ?: false }
    suspend fun setUnifiedLibrary(v: Boolean) = context.dataStore.edit { it[Keys.UNIFIED_LIBRARY] = v }

    // empty = every eligible saved source
    val mergeSources: Flow<Set<String>> = context.dataStore.data.map { it[Keys.MERGE_SOURCES] ?: emptySet() }
    suspend fun setMergeSources(keys: Set<String>) = context.dataStore.edit { it[Keys.MERGE_SOURCES] = keys }

    val recentSearches: Flow<List<String>> = context.dataStore.data.map { parseStringList(it[Keys.RECENT_SEARCHES]) }

    private fun parseStringList(json: String?): List<String> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

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

    suspend fun updateToken(token: String) = context.dataStore.edit { it[Keys.TOKEN] = token }

    suspend fun updateUserImage(url: String) = context.dataStore.edit { it[Keys.USER_IMAGE] = url }

    suspend fun clearSession() {
        context.dataStore.edit { p ->
            p.remove(Keys.SERVER); p.remove(Keys.USERNAME); p.remove(Keys.SALT); p.remove(Keys.TOKEN)
            p.remove(Keys.SERVER_TYPE); p.remove(Keys.USER_ID); p.remove(Keys.USER_IMAGE)
            p.remove(Keys.CLIENT_TOKEN); p.remove(Keys.CLIENT_VERSION)
        }
    }

    val savedSessions: Flow<List<Session>> = context.dataStore.data.map { parseSessions(it[Keys.SAVED_SESSIONS]) }

    private fun parseSessions(json: String?): List<Session> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Session>>(json, object : TypeToken<List<Session>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList()).filter { it.isValid }

    private fun Session.accountKey() = "${type.name}|$server|$username|$userId"

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

    suspend fun setSkipSilence(v: Boolean) = editManualProcessing { it[Keys.SKIP_SILENCE] = v }
    suspend fun setCrossfade(sec: Int) = editManualProcessing { it[Keys.CROSSFADE] = sec.coerceIn(0, 30) }
    suspend fun setCrossfadeCurve(curve: String) = editManualProcessing { it[Keys.CROSSFADE_CURVE] = curve }
    suspend fun setCrossfadeHeadroom(on: Boolean) = editManualProcessing { it[Keys.CROSSFADE_HEADROOM] = on }
    suspend fun setGapless(v: Boolean) = editManualProcessing { it[Keys.GAPLESS] = v }
    suspend fun setDefaultSpeed(v: Float) = editManualProcessing { it[Keys.DEFAULT_SPEED] = v }
    suspend fun setMono(v: Boolean) = editManualProcessing { it[Keys.MONO] = v }
    suspend fun setStreamWifi(v: Int) = context.dataStore.edit { it[Keys.STREAM_WIFI] = v }
    suspend fun setStreamCellular(v: Int) = context.dataStore.edit { it[Keys.STREAM_CELLULAR] = v }
    suspend fun setDownloadBitrate(v: Int) = context.dataStore.edit { it[Keys.DOWNLOAD_BITRATE] = v }
    suspend fun setPreferHighRes(v: Boolean) = editManualProcessing { it[Keys.PREFER_HIRES] = v }
    suspend fun setBitPerfectUsb(v: Boolean) = editManualProcessing { it[Keys.BIT_PERFECT_USB] = v }
    suspend fun setIndependentOutput(v: Boolean) = editManualProcessing { it[Keys.INDEPENDENT_OUTPUT] = v }
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
    suspend fun setActiveEqProfile(name: String) = editManualProcessing { it[Keys.AUTOEQ_PROFILE] = name }

    suspend fun upsertEqBinding(binding: EqBinding) = context.dataStore.edit { p ->
        val cur = parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == binding.deviceKey }
        p[Keys.EQ_BINDINGS] = gson.toJson(cur + binding)
    }

    suspend fun bindEqToCurrentOutput(profile: String, preampDb: Float, bands: List<ParamBand>): Result<Unit> = withContext(Dispatchers.IO) {
        impulseResult {
            val expected = processingRoutes.current
            val key = expected.route.key ?: error("Output identity is unavailable.")
            require(expected.route.kind == ProcessingRouteKind.ANDROID) { "This output bypasses local processing." }
            context.dataStore.edit { p ->
                require(processingRoutes.current == expected) { "The output changed. Try again." }
                val binding = EqBinding(key, expected.route.label, profile, preampDb, bands)
                p[Keys.EQ_BINDINGS] = gson.toJson(parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == key } + binding)
                p[Keys.AUTOEQ_SWITCH] = true
                val rules = ProcessingRouteCodec.decode(p[Keys.PROCESSING_ROUTES]).getOrThrow()
                p[Keys.PROCESSING_ROUTES] = ProcessingRouteCodec.encode(rules.copy(manual = rules.manual - key))
            }
            Unit
        }
    }

    suspend fun removeEqBinding(deviceKey: String) = context.dataStore.edit { p ->
        p[Keys.EQ_BINDINGS] = gson.toJson(parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == deviceKey })
    }

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

    suspend fun setEqEnabled(v: Boolean) = editManualProcessing { it[Keys.EQ_ENABLED] = v }
    suspend fun setEqPreset(v: Int) = editManualProcessing { it[Keys.EQ_PRESET] = v }
    suspend fun setEqBands(v: List<Int>) = editManualProcessing { it[Keys.EQ_BANDS] = v.joinToString(",") }
    suspend fun setBassBoost(v: Int) = editManualProcessing { it[Keys.BASS_BOOST] = v }
    suspend fun setVirtualizer(v: Int) = editManualProcessing { it[Keys.VIRTUALIZER] = v }
    suspend fun setLoudness(v: Int) = editManualProcessing { it[Keys.LOUDNESS] = v }
    suspend fun setReplayGain(v: Int) = editManualProcessing { it[Keys.REPLAY_GAIN] = v }

    suspend fun setDspMode(v: Int) = editManualProcessing { p ->
        p[Keys.DSP_MODE] = v
        if (v != DspMode.CUSTOM) {
            val rack = p[Keys.PROCESSING_RACK]?.let { ProcessingRackCodec.decode(it).getOrNull() }
            if (rack?.enabled == true) p[Keys.PROCESSING_RACK] = ProcessingRackCodec.encode(rack.copy(enabled = false))
        }
    }
    suspend fun setDspGraphicBands(v: List<Float>) = editManualProcessing { it[Keys.DSP_GRAPHIC] = v.joinToString(",") }
    suspend fun setDspParametric(v: List<ParamBand>) = editManualProcessing {
        it[Keys.DSP_PARAMETRIC] = ParamBandCodec.encodePreference(v)
    }
    suspend fun setDspPreamp(v: Float) = editManualProcessing { it[Keys.DSP_PREAMP] = v }
    suspend fun setDspBalance(v: Float) = editManualProcessing { it[Keys.DSP_BALANCE] = v }
    suspend fun setDspWidth(v: Float) = editManualProcessing { it[Keys.DSP_WIDTH] = v }
    suspend fun setDspCrossfeed(v: Float) = editManualProcessing { it[Keys.DSP_CROSSFEED] = v }
    suspend fun setDspLimiterEnabled(v: Boolean) = editManualProcessing { it[Keys.DSP_LIMITER] = v }
    suspend fun setDspCeiling(v: Float) = editManualProcessing { it[Keys.DSP_CEILING] = v }
    suspend fun setDspCompEnabled(v: Boolean) = editManualProcessing { it[Keys.DSP_COMP] = v }
    suspend fun setDspCompThresh(v: Float) = editManualProcessing { it[Keys.DSP_COMP_THRESH] = v }
    suspend fun setDspCompRatio(v: Float) = editManualProcessing { it[Keys.DSP_COMP_RATIO] = v }
    suspend fun setDspConvEnabled(v: Boolean) = editManualProcessing { it[Keys.DSP_CONV] = v }
    suspend fun setDspConvMakeup(v: Float) = editManualProcessing { it[Keys.DSP_CONV_MAKEUP] = v }
    suspend fun setDspConvIr(path: String, name: String) = editManualProcessing {
        it[Keys.DSP_CONV_PATH] = path; it[Keys.DSP_CONV_NAME] = name
    }
    suspend fun setDspGraphicLayout(v: Int) = editManualProcessing { it[Keys.DSP_GRAPHIC_LAYOUT] = v }
    suspend fun setDspSaturation(v: Float) = editManualProcessing { it[Keys.DSP_SATURATION] = v }
    suspend fun setDspDelayLeft(v: Float) = editManualProcessing { it[Keys.DSP_DELAY_L] = v }
    suspend fun setDspDelayRight(v: Float) = editManualProcessing { it[Keys.DSP_DELAY_R] = v }
    suspend fun setDspTrimLeft(v: Float) = editManualProcessing { it[Keys.DSP_TRIM_L] = v }
    suspend fun setDspTrimRight(v: Float) = editManualProcessing { it[Keys.DSP_TRIM_R] = v }

    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[Keys.UI_THEME_MODE] = v }
    suspend fun setThemeStyle(v: Int) = context.dataStore.edit { it[Keys.UI_THEME_STYLE] = v.coerceIn(ThemeStyle.AURORA, ThemeStyle.GLASS) }
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

    // typed so json round-trips losslessly
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

    /** Replace one validated backup atomically after its caller has restored/remapped all assets. */
    suspend fun restoreBackupPrefs(b: PrefsBackup): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val names = listOf(b.strings.keys, b.ints.keys, b.longs.keys, b.booleans.keys, b.floats.keys, b.stringSets.keys)
                .flatMap { it }
            require(names.distinct().size == names.size) { "Backup contains preference keys with conflicting types." }
            require(b.floats.values.all { it.isFinite() }) { "Backup contains invalid numeric preferences." }
            val replacement = mutablePreferencesOf()
            b.strings.forEach { (k, v) -> replacement[stringPreferencesKey(k)] = v }
            b.ints.forEach { (k, v) -> replacement[intPreferencesKey(k)] = v }
            b.longs.forEach { (k, v) -> replacement[longPreferencesKey(k)] = v }
            b.booleans.forEach { (k, v) -> replacement[booleanPreferencesKey(k)] = v }
            b.floats.forEach { (k, v) -> replacement[floatPreferencesKey(k)] = v }
            b.stringSets.forEach { (k, v) -> replacement[stringSetPreferencesKey(k)] = v.toSet() }
            validateBackupAudioText(replacement)
            val audio = ProcessingPresetCodec.validateAudio(readAudioPrefs(replacement))
            val playback = readPlaybackPrefs(replacement)
            ProcessingPresetCodec.validatePlayback(ProcessingPlaybackPrefs.from(playback))
            val library = ProcessingPresetCodec.decode(replacement[Keys.PROCESSING_PRESETS])
            require(library.error == null) { library.error.orEmpty() }
            val rack = replacement[Keys.PROCESSING_RACK]?.let { ProcessingRackCodec.decode(it).getOrThrow() }
                ?: ProcessingRack.legacy(audio, playback.monoAudio)
            TuningProjectCodec.decodeLibrary(replacement[Keys.TUNING_PROJECTS]).getOrThrow()
            TuningTargetCatalog.decodeLibrary(replacement[Keys.TUNING_TARGETS]).getOrThrow()
            ImpulseLibraryCodec.decodeLibrary(replacement[Keys.IMPULSE_LIBRARY]).getOrThrow()
            ProcessingRouteCodec.decode(replacement[Keys.PROCESSING_ROUTES]).getOrThrow()
            if (rack.enabled) replacement[Keys.DSP_MODE] = DspMode.CUSTOM
            // Asset ownership, hashes and remapping belong to the bundle reader before this call.
            // Keeping this transaction about preferences also permits restoring an exact snapshot
            // whose disabled historical entries already reference unavailable source files.
            context.dataStore.edit { p ->
                p.clear()
                replacement.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    p[key as Preferences.Key<Any>] = value
                }
            }
            Unit
        }
    }

    private fun validateBackupAudioText(p: Preferences) {
        p[Keys.EQ_BANDS]?.takeIf { it.isNotBlank() }?.split(",")?.forEach {
            require(it.toIntOrNull() != null) { "Backup contains an invalid system EQ band." }
        }
        p[Keys.DSP_GRAPHIC]?.takeIf { it.isNotBlank() }?.split(",")?.forEach {
            require(it.toFloatOrNull()?.isFinite() == true) { "Backup contains an invalid graphic EQ band." }
        }
        p[Keys.DSP_PARAMETRIC]?.takeIf { it.isNotBlank() }?.split(";")?.forEach { entry ->
            require(ParamBandCodec.decodePreference(entry).size == 1) { "Backup contains an invalid parametric EQ band." }
        }
    }
}
