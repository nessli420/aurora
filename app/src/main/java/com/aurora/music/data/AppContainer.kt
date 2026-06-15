package com.aurora.music.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.SpotifyClient
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live audio signal-path status for the bit-perfect indicator: the format actually being played and
 * where it's going. [bitPerfect] is true only when samples reach the output untouched (float
 * passthrough, no DSP/mixing); [note] explains the verdict.
 */
data class SignalPath(
    val active: Boolean = false,
    val codec: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val output: String = "",
    val bitPerfect: Boolean = false,
    val note: String = "",
)

/**
 * Process-wide singletons. Holds the settings store, the authenticated Subsonic client,
 * the download manager + audio-effects controller, and the repository. Tracks "effective
 * offline" = the manual toggle OR no network connectivity, so downloaded music keeps
 * working with zero internet.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)
    val playHistory = PlayHistoryStore(appContext)

    /** Per-account playback queue persistence (restore after swipe-away / server switch). */
    val queueStore = QueueStore(appContext)

    // Offline-scanned ReplayGain: overlaid onto local songs by file path.
    val replayGainStore = ReplayGainStore(appContext)

    // Local-files mode (no server): scanned device library + on-device playlists/likes.
    val localLibrary = LocalLibrary(appContext, gainProvider = { path -> replayGainStore.gainsFor(path) })
    private val localStore = LocalStore(appContext)

    /** Offline ReplayGain scanner over the local library. */
    val replayGainScanner = ReplayGainScanner(localLibrary, replayGainStore)

    /** In-app tag editor for on-device files. */
    val tagEditor = TagEditor(appContext)

    /** Backup & restore of settings + local playlists/likes + history. */
    val backupManager = BackupManager(settingsStore, localStore, playHistory)

    /** MusicBrainz + Cover Art Archive metadata lookup. */
    val musicBrainz = com.aurora.music.data.remote.MusicBrainzClient()

    /** AcoustID fingerprint identification. The user supplies their own key in Integrations. */
    @Volatile private var acoustIdKeyValue: String = ""
    val acoustId = com.aurora.music.data.remote.AcoustIdClient(apiKeyProvider = { acoustIdKeyValue })

    // AutoEQ headphone correction: searchable bundled database + per-output auto-switching.
    val autoEq = AutoEqRepository(appContext)
    val autoEqController = AutoEqController(appContext, settingsStore, scope)

    /** Live per-IEM AutoEQ from squig.link (7.2): generates a correction on-device from raw FR. */
    @Volatile private var squigBaseValue: String = DEFAULT_SQUIG_BASE
    @Volatile private var squigTargetValue: String = DEFAULT_SQUIG_TARGET
    val squigEq = SquigEqRepository(
        com.aurora.music.data.remote.SquigClient(),
        baseProvider = { squigBaseValue },
        targetProvider = { squigTargetValue },
    )

    @Volatile
    private var maxBitrate: Int = 0

    @Volatile
    private var downloadBitrate: Int = 0

    /** 7.1a best-source playback: prefer a matching on-device/downloaded file over streaming. */
    @Volatile
    private var preferLocalSources: Boolean = true

    /** 7.1b configurable playback source priority (tiers: local / downloaded / stream). */
    @Volatile
    private var sourcePriorityValue: List<String> = DEFAULT_SOURCE_PRIORITY

    /** 7.1b unified library: merge included servers + local files into one browsable backend. */
    @Volatile private var unifiedLibraryValue: Boolean = false
    @Volatile private var mergeSourceKeys: Set<String> = emptySet()
    @Volatile private var lastSession: Session? = null
    private val localMergeSession = Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL)

    @Volatile
    var backend: MediaBackend? = null
        private set

    /** The active server's base URL — stamped on downloads so they can be scoped per-server. */
    private fun currentServerId(): String = backend?.session?.server ?: ""

    val downloadManager = DownloadManager(
        appContext,
        // Downloads fetch from whichever server is active; offline playback uses the local file.
        streamUrlProvider = { id, bitrate, lossless -> backend?.streamUrl(id, bitrate, lossless) },
        downloadBitrateProvider = { downloadBitrate },
        currentServerIdProvider = { currentServerId() },
        resolveSentinel = ::resolveYtSentinel,
    )

    /** On-device sonic-similarity engine (feature analysis + "sonically similar" radio). */
    val sonicStore = SonicStore(appContext)
    val sonicEngine = SonicEngine(localLibrary, downloadManager, sonicStore)

    /** Internet radio (Radio-Browser directory) + podcasts (iTunes directory + RSS) — both keyless. */
    val radioBrowser = com.aurora.music.data.remote.RadioBrowserClient()
    val podcastClient = com.aurora.music.data.remote.PodcastClient()

    /** Artist enrichment (bios + images from MusicBrainz/Wikipedia) + its on-disk cache. */
    val artistInfoClient = com.aurora.music.data.remote.ArtistInfoClient()
    val artistInfoStore = ArtistInfoStore(appContext)

    /** Resolve an `aurora-yt://<id>?q=...&dur=...` sentinel to a real YouTube audio URL (for downloads). */
    private fun resolveYtSentinel(sentinel: String): String? {
        val uri = runCatching { android.net.Uri.parse(sentinel) }.getOrNull() ?: return null
        if (uri.scheme != "aurora-yt") return null
        return youtubeResolver.resolve(
            uri.host.orEmpty(),
            uri.getQueryParameter("q").orEmpty(),
            uri.getQueryParameter("dur")?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Best-source substitution (7.1a/7.1b): play a track from the best available copy, trying the tiers
     * in the user's configured [sourcePriorityValue] (default on-device file > download > server
     * stream). Runs per mapped Song in every remote backend, rewriting only [Song.streamUrl]/metadata
     * (the id stays the server's). The "stream" tier is the terminal fallback (use the original URL).
     */
    private fun localizeSong(song: Song): Song {
        if (!preferLocalSources) return song
        val alreadyLocal = song.streamUrl.startsWith("content://") || song.streamUrl.startsWith("file://")
        for (tier in sourcePriorityValue) {
            when (tier) {
                "local" -> if (!alreadyLocal) {
                    localLibrary.findMatch(song.artist, song.title, song.durationSec)?.let { return localizedFromFile(song, it) }
                }
                "downloaded" -> downloadManager.getByOriginalId(song.id)?.let { return localizedFromDownload(song, it.toSong()) }
                "stream" -> return song
            }
        }
        return song
    }

    /** The on-device file actually plays — carry its ReplayGain + format so loudness/UI match the file. */
    private fun localizedFromFile(song: Song, local: Song): Song = song.copy(
        streamUrl = local.streamUrl,
        artworkUrl = song.artworkUrl.ifBlank { local.artworkUrl },
        replayGainTrack = local.replayGainTrack,
        replayGainAlbum = local.replayGainAlbum,
        suffix = local.suffix,
        bitrateKbps = local.bitrateKbps,
        sampleRateHz = local.sampleRateHz,
        bitDepth = local.bitDepth,
        path = local.path,
    )

    private fun localizedFromDownload(song: Song, local: Song): Song =
        song.copy(streamUrl = local.streamUrl, artworkUrl = local.artworkUrl.ifBlank { song.artworkUrl })

    private fun buildBackend(session: Session): MediaBackend = when (session.type) {
        ServerType.JELLYFIN -> JellyfinBackend(JellyfinClient(session), { maxBitrate }, ::localizeSong)
        ServerType.SUBSONIC -> SubsonicBackend(SubsonicClient(session), { maxBitrate }, ::localizeSong)
        ServerType.SPOTIFY -> SpotifyBackend(
            SpotifyClient(session, spotifyClientIdValue, onTokenRefreshed = { tok -> scope.launch { settingsStore.updateToken(tok) } }),
            { maxBitrate }, ::localizeSong,
        )
        ServerType.LOCAL -> LocalBackend(localLibrary, localStore, session)
    }

    /**
     * The backend that actually drives the app for [session]: just [buildBackend] normally, or a
     * [MergedBackend] over Local + every included Navidrome/Jellyfin login when the unified library is
     * on (7.1b). Spotify isn't merged yet, so a Spotify-active session stays single-source.
     */
    private fun buildActiveBackend(session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>): MediaBackend {
        if (!unified || session.type == ServerType.SPOTIFY) return buildBackend(session)
        // mergeKeys: empty = all eligible servers; the MERGE_NONE sentinel = local files only.
        val serverSessions = if (mergeKeys == setOf(MERGE_NONE)) emptyList() else saved
            .filter { it.type == ServerType.SUBSONIC || it.type == ServerType.JELLYFIN }
            .filter { mergeKeys.isEmpty() || accountKey(it) in mergeKeys }
            .distinctBy { accountKey(it) }
        val sources = buildList {
            add(LocalBackend(localLibrary, localStore, localMergeSession))
            serverSessions.forEach { add(buildBackend(it)) }
        }
        return if (sources.size <= 1) buildBackend(session) else MergedBackend(sources, session)
    }

    /** Rebuild [backend] from the current session + unified-library settings + saved logins. */
    private suspend fun rebuildBackend() {
        val session = lastSession
        backend = session?.let {
            val saved = runCatching { settingsStore.savedSessions.first() }.getOrDefault(emptyList())
            buildActiveBackend(it, unifiedLibraryValue, mergeSourceKeys, saved)
        }
    }

    /** The user's own Spotify app Client ID (DIY setup; PKCE, no secret). Redirect = aurora://spotify. */
    @Volatile private var spotifyClientIdValue: String = ""
    val spotifyClientId: String get() = spotifyClientIdValue

    /** The active mode is the local-files library (no server). Used to hide server-only UI. */
    val isLocal: Boolean get() = backend?.session?.type == ServerType.LOCAL

    // ---- Haptics ----
    @Volatile private var hapticsEnabled = false
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    /** Fire a short haptic tick for a meaningful action — no-op unless the user enabled haptics. */
    fun haptic() {
        if (!hapticsEnabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else @Suppress("DEPRECATION") v.vibrate(12)
        }
    }

    private val _spotifyRedirect = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits the OAuth `code` from the aurora://spotify redirect (caught in MainActivity). */
    val spotifyRedirect = _spotifyRedirect.asSharedFlow()
    fun emitSpotifyRedirect(code: String) { _spotifyRedirect.tryEmit(code) }

    /** A stable audio session id shared with the ExoPlayer so we can attach DSP effects. */
    val audioSessionId: Int = runCatching {
        (appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
    }.getOrDefault(0)

    val audioEffects = AudioEffectsController(audioSessionId, settingsStore, scope)

    /** Real-time audio analysis for the visualizer, fed by the playback layer's PCM taps. */
    val visualizer = com.aurora.music.playback.VisualizerController(scope)

    /**
     * Last.fm integration. The user supplies their own API key + shared secret in Integrations
     * (free, from https://www.last.fm/api/account/create); nothing is shipped hardcoded. Until a
     * key is entered, [LastfmScrobbler.configured] is false and the connect flow is disabled.
     */
    val lastfm = LastfmScrobbler(settingsStore, scope)

    /** ListenBrainz scrobbling, token-only, set by the user in Integrations. */
    val listenBrainz = ListenBrainzScrobbler(settingsStore, scope)

    /**
     * Discord Rich Presence. The user supplies their own Discord application id (and optional Imgur
     * client id for album art) in Integrations — nothing is shipped hardcoded.
     */
    val discord = DiscordRpc(settingsStore, scope)

    /** Resolves Spotify tracks → YouTube audio streams (used by the playback layer for aurora-yt:// URIs). */
    val youtubeResolver = com.aurora.music.playback.YoutubeResolver()

    @Volatile
    private var lrclibEnabled: Boolean = true

    val lyricsRepository = LyricsRepository(backendProvider = { backend }, lrclibEnabledProvider = { lrclibEnabled })

    @Volatile
    private var offlineToggle: Boolean = false

    @Volatile
    private var networkUp: Boolean = true

    @Volatile
    private var onWifi: Boolean = true

    @Volatile
    private var streamWifi: Int = 0

    @Volatile
    private var streamCellular: Int = 0

    @Volatile
    private var dataSaver: Boolean = false

    /** Preferred output device id (AudioDeviceInfo.id), 0 = system default. */
    val preferredAudioDeviceId = MutableStateFlow(0)

    /** Live signal-path / bit-perfect status, updated by the playback service. */
    val signalPath = MutableStateFlow(SignalPath())

    @Volatile
    private var offlineFlag: Boolean = false

    private val _sessionReady = MutableStateFlow<Boolean?>(null)
    val sessionReady: StateFlow<Boolean?> = _sessionReady.asStateFlow()

    // Bumped only when the active account genuinely changes (switch / logout / add) — NOT on the
    // initial load or a token refresh. Observers (player stop, library reload) react to changes.
    private val _accountEpoch = MutableStateFlow(0)
    val accountEpoch: StateFlow<Int> = _accountEpoch.asStateFlow()

    // Bumped when the unified-library settings change (rebuilds the backend) so Home/Library reload
    // WITHOUT the playback-stopping semantics of an account change.
    private val _libraryReload = MutableStateFlow(0)
    val libraryReload: StateFlow<Int> = _libraryReload.asStateFlow()
    @Volatile private var lastAccountKey: String? = null
    private fun accountKey(s: Session?): String = s?.accountKey() ?: ""

    /** The active account's stable key (for per-account queue persistence). "" when signed out. */
    fun currentAccountKey(): String = accountKey(backend?.session)

    private val _offline = MutableStateFlow(false)
    /** Effective offline: manual toggle OR no connectivity. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _noNetwork = MutableStateFlow(false)
    /** True only when there's genuinely no connectivity (distinct from the manual toggle). */
    val noNetwork: StateFlow<Boolean> = _noNetwork.asStateFlow()

    // Smart (rule-based) playlists: definitions live in settings, evaluation in the engine.
    @Volatile private var smartPlaylistsValue: List<SmartPlaylist> = emptyList()
    val smartEngine = SmartPlaylistEngine(playHistory, downloadManager)

    val repository = MusicRepository(
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { offlineFlag },
        currentServerIdProvider = { currentServerId() },
        smartPlaylistsProvider = { smartPlaylistsValue },
        smartEngine = smartEngine,
    )

    private fun recomputeOffline() {
        // Local-files mode never needs the network, so it's never "offline".
        val local = backend?.session?.type == ServerType.LOCAL
        offlineFlag = !local && (offlineToggle || !networkUp)
        _offline.value = offlineFlag
        _noNetwork.value = !networkUp
    }

    private fun recomputeBitrate() {
        maxBitrate = if (onWifi) {
            streamWifi
        } else {
            // Data saver caps cellular streaming to a low bitrate (server backends transcode to it).
            if (dataSaver) (if (streamCellular == 0) DATA_SAVER_KBPS else minOf(streamCellular, DATA_SAVER_KBPS)) else streamCellular
        }
    }

    init {
        // Auto-analyze new local/downloaded tracks for sonic similarity on startup when enabled.
        // scan() is idempotent — it only processes tracks not already in the vector store.
        scope.launch {
            if (runCatching { settingsStore.sonicAutoAnalyze.first() }.getOrDefault(false)) sonicEngine.scan()
        }
        scope.launch {
            settingsStore.session.collect { session ->
                lastSession = session
                // Keep the active login in the saved list (so an existing session shows up for
                // switching even though it was restored from disk, not via applySession).
                session?.let { settingsStore.addSavedSession(it) }
                rebuildBackend()
                _sessionReady.value = session != null
                // Detect a real account change (ignore the first load so startup doesn't count).
                val key = accountKey(session)
                if (lastAccountKey != null && lastAccountKey != key) _accountEpoch.value++
                lastAccountKey = key
                recomputeOffline()  // local vs server changes whether "offline" applies
            }
        }
        scope.launch {
            var first = true
            settingsStore.unifiedLibrary.collect {
                unifiedLibraryValue = it; rebuildBackend()
                if (!first) _libraryReload.value++   // refresh Home/Library on a real toggle
                first = false
            }
        }
        scope.launch {
            var first = true
            settingsStore.mergeSources.collect {
                mergeSourceKeys = it; rebuildBackend()
                if (!first) _libraryReload.value++
                first = false
            }
        }
        scope.launch {
            settingsStore.playbackPrefs.collect {
                streamWifi = it.streamWifi
                streamCellular = it.streamCellular
                downloadBitrate = it.downloadBitrate
                recomputeBitrate()
            }
        }
        scope.launch {
            settingsStore.offlineMode.collect { offlineToggle = it; recomputeOffline() }
        }
        scope.launch {
            settingsStore.lrclibEnabled.collect { lrclibEnabled = it }
        }
        scope.launch {
            settingsStore.dataSaver.collect { dataSaver = it; recomputeBitrate() }
        }
        scope.launch {
            settingsStore.haptics.collect { hapticsEnabled = it }
        }
        scope.launch {
            settingsStore.smartPlaylists.collect { smartPlaylistsValue = it }
        }
        scope.launch {
            settingsStore.acoustIdKey.collect { acoustIdKeyValue = it }
        }
        scope.launch {
            settingsStore.preferLocalSources.collect { on ->
                preferLocalSources = on
                // Best-source matching needs the on-device index; load it once when enabled.
                if (on) runCatching { localLibrary.ensureLoaded() }
            }
        }
        scope.launch {
            settingsStore.sourcePriority.collect { sourcePriorityValue = it }
        }
        scope.launch {
            settingsStore.squigBaseUrl.collect { squigBaseValue = it }
        }
        scope.launch {
            settingsStore.squigTarget.collect { squigTargetValue = it }
        }
        scope.launch {
            settingsStore.alarmPrefs.collect { com.aurora.music.playback.AlarmScheduler.apply(appContext, it) }
        }
        scope.launch {
            settingsStore.spotifyClientId.collect { id ->
                spotifyClientIdValue = id
                // Rebuild an active Spotify backend so token refresh uses the up-to-date client id.
                backend?.session?.let { if (it.type == ServerType.SPOTIFY) backend = buildBackend(it) }
            }
        }
        registerConnectivity()
    }

    private fun registerConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // "Has internet" = INTERNET capability AND it's actually VALIDATED — so "connected to Wi-Fi
        // but no real internet" correctly counts as offline (we serve downloads instead of hanging).
        fun hasInternet(caps: NetworkCapabilities?) = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            networkUp = hasInternet(caps)
            onWifi = caps?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } ?: true
        }
        recomputeOffline(); recomputeBitrate()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) { networkUp = false; recomputeOffline() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    networkUp = hasInternet(caps)
                    onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    recomputeOffline(); recomputeBitrate()
                }
            })
        }
    }

    suspend fun applySession(session: Session) {
        lastSession = session
        settingsStore.saveSession(session)
        settingsStore.addSavedSession(session)   // remember it for quick switching
        rebuildBackend()                          // honour unified-library mode if enabled
        _sessionReady.value = true
    }

    /** Switch to a previously-saved login (playback stop is handled reactively via [accountEpoch]). */
    suspend fun switchSession(session: Session) = applySession(session)

    /** Sign out of the active account. Saved logins are kept so the user can switch back. */
    suspend fun signOut() {
        lastSession = null
        backend = null
        _sessionReady.value = false
        settingsStore.clearSession()
    }

    /** Forget a saved login (and sign out if it's the one currently active). */
    suspend fun forgetSavedSession(session: Session) {
        settingsStore.removeSavedSession(session)
        if (backend?.session?.let { accountKey(it) } == accountKey(session)) signOut()
    }

    private companion object {
        // Data-saver cellular bitrate cap (kbps).
        const val DATA_SAVER_KBPS = 96
        // No third-party API credentials are hardcoded — Last.fm (key+secret), Discord (app id),
        // Imgur (client id), AcoustID (key) and Spotify (client id) are all entered by the user in
        // Settings → Integrations. See SettingsStore.
    }
}
