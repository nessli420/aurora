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

// bitPerfect true only when samples reach output untouched float passthrough no dsp/mixing
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

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)
    val playHistory = PlayHistoryStore(appContext)

    val queueStore = QueueStore(appContext)

    val replayGainStore = ReplayGainStore(appContext)

    val localLibrary = LocalLibrary(appContext, gainProvider = { path -> replayGainStore.gainsFor(path) })
    private val localStore = LocalStore(appContext)

    val replayGainScanner = ReplayGainScanner(localLibrary, replayGainStore)

    val tagEditor = TagEditor(appContext)

    val backupManager = BackupManager(settingsStore, localStore, playHistory)

    val musicBrainz = com.aurora.music.data.remote.MusicBrainzClient()

    @Volatile private var acoustIdKeyValue: String = ""
    val acoustId = com.aurora.music.data.remote.AcoustIdClient(apiKeyProvider = { acoustIdKeyValue })

    val autoEq = AutoEqRepository(appContext)
    val autoEqController = AutoEqController(appContext, settingsStore, scope)

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

    @Volatile
    private var preferLocalSources: Boolean = true

    @Volatile
    private var sourcePriorityValue: List<String> = DEFAULT_SOURCE_PRIORITY

    @Volatile private var unifiedLibraryValue: Boolean = false
    @Volatile private var mergeSourceKeys: Set<String> = emptySet()
    @Volatile private var lastSession: Session? = null
    private val localMergeSession = Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL)

    @Volatile
    var backend: MediaBackend? = null
        private set

    // server base url stamped on downloads so they can be scoped per-server
    private fun currentServerId(): String = backend?.session?.server ?: ""

    val downloadManager = DownloadManager(
        appContext,
        streamUrlProvider = { id, bitrate, lossless -> backend?.streamUrl(id, bitrate, lossless) },
        downloadBitrateProvider = { downloadBitrate },
        currentServerIdProvider = { currentServerId() },
        resolveSentinel = ::resolveYtSentinel,
    )

    val sonicStore = SonicStore(appContext)
    val sonicEngine = SonicEngine(localLibrary, downloadManager, sonicStore)

    val radioBrowser = com.aurora.music.data.remote.RadioBrowserClient()
    val podcastClient = com.aurora.music.data.remote.PodcastClient()

    val artistInfoClient = com.aurora.music.data.remote.ArtistInfoClient()
    val artistInfoStore = ArtistInfoStore(appContext)

    private fun resolveYtSentinel(sentinel: String): String? {
        val uri = runCatching { android.net.Uri.parse(sentinel) }.getOrNull() ?: return null
        if (uri.scheme != "aurora-yt") return null
        return youtubeResolver.resolve(
            uri.host.orEmpty(),
            uri.getQueryParameter("q").orEmpty(),
            uri.getQueryParameter("dur")?.toIntOrNull() ?: 0,
        )
    }

    // rewrites only streamUrl/metadata id stays the server's so server features keep working
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

    // carry the file's replaygain + format so loudness/ui match what actually plays
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

    private fun buildActiveBackend(session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>): MediaBackend {
        if (!unified || session.type == ServerType.SPOTIFY) return buildBackend(session)
        // empty = all eligible servers MERGE_NONE sentinel = local files only
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

    private suspend fun rebuildBackend() {
        val session = lastSession
        backend = session?.let {
            val saved = runCatching { settingsStore.savedSessions.first() }.getOrDefault(emptyList())
            buildActiveBackend(it, unifiedLibraryValue, mergeSourceKeys, saved)
        }
    }

    @Volatile private var spotifyClientIdValue: String = ""
    val spotifyClientId: String get() = spotifyClientIdValue

    val isLocal: Boolean get() = backend?.session?.type == ServerType.LOCAL

    @Volatile private var hapticsEnabled = false
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    fun haptic() {
        if (!hapticsEnabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else @Suppress("DEPRECATION") v.vibrate(12)
        }
    }

    private val _spotifyRedirect = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val spotifyRedirect = _spotifyRedirect.asSharedFlow()
    fun emitSpotifyRedirect(code: String) { _spotifyRedirect.tryEmit(code) }

    val audioSessionId: Int = runCatching {
        (appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
    }.getOrDefault(0)

    val audioEffects = AudioEffectsController(audioSessionId, settingsStore, scope)

    val visualizer = com.aurora.music.playback.VisualizerController(scope)

    val lastfm = LastfmScrobbler(settingsStore, scope)

    val listenBrainz = ListenBrainzScrobbler(settingsStore, scope)

    val discord = DiscordRpc(settingsStore, scope)

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

    // 0 = system default
    val preferredAudioDeviceId = MutableStateFlow(0)

    val signalPath = MutableStateFlow(SignalPath())

    @Volatile
    private var offlineFlag: Boolean = false

    private val _sessionReady = MutableStateFlow<Boolean?>(null)
    val sessionReady: StateFlow<Boolean?> = _sessionReady.asStateFlow()

    // bumped only on a real account change not initial load or token refresh
    private val _accountEpoch = MutableStateFlow(0)
    val accountEpoch: StateFlow<Int> = _accountEpoch.asStateFlow()

    // reloads home/library without the playback-stopping semantics of an account change
    private val _libraryReload = MutableStateFlow(0)
    val libraryReload: StateFlow<Int> = _libraryReload.asStateFlow()
    @Volatile private var lastAccountKey: String? = null
    private fun accountKey(s: Session?): String = s?.accountKey() ?: ""

    fun currentAccountKey(): String = accountKey(backend?.session)

    private val _offline = MutableStateFlow(false)
    // effective offline manual toggle or no connectivity
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _noNetwork = MutableStateFlow(false)
    val noNetwork: StateFlow<Boolean> = _noNetwork.asStateFlow()

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
        // local-files mode never needs the network so it's never offline
        val local = backend?.session?.type == ServerType.LOCAL
        offlineFlag = !local && (offlineToggle || !networkUp)
        _offline.value = offlineFlag
        _noNetwork.value = !networkUp
    }

    private fun recomputeBitrate() {
        maxBitrate = if (onWifi) {
            streamWifi
        } else {
            if (dataSaver) (if (streamCellular == 0) DATA_SAVER_KBPS else minOf(streamCellular, DATA_SAVER_KBPS)) else streamCellular
        }
    }

    init {
        // scan() is idempotent only processes tracks not already in the vector store
        scope.launch {
            if (runCatching { settingsStore.sonicAutoAnalyze.first() }.getOrDefault(false)) sonicEngine.scan()
        }
        scope.launch {
            settingsStore.session.collect { session ->
                lastSession = session
                // keep a disk-restored session in the saved list so it shows up for switching
                session?.let { settingsStore.addSavedSession(it) }
                rebuildBackend()
                _sessionReady.value = session != null
                // ignore the first load so startup doesn't count as an account change
                val key = accountKey(session)
                if (lastAccountKey != null && lastAccountKey != key) _accountEpoch.value++
                lastAccountKey = key
                recomputeOffline()
            }
        }
        scope.launch {
            var first = true
            settingsStore.unifiedLibrary.collect {
                unifiedLibraryValue = it; rebuildBackend()
                if (!first) _libraryReload.value++
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
                // best-source matching needs the on-device index load it once when enabled
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
                // rebuild an active spotify backend so token refresh uses the up-to-date client id
                backend?.session?.let { if (it.type == ServerType.SPOTIFY) backend = buildBackend(it) }
            }
        }
        registerConnectivity()
    }

    private fun registerConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // require VALIDATED so wifi-without-real-internet counts as offline and we serve downloads
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
        settingsStore.addSavedSession(session)
        rebuildBackend()
        _sessionReady.value = true
    }

    suspend fun switchSession(session: Session) = applySession(session)

    suspend fun signOut() {
        lastSession = null
        backend = null
        _sessionReady.value = false
        settingsStore.clearSession()
    }

    suspend fun forgetSavedSession(session: Session) {
        settingsStore.removeSavedSession(session)
        if (backend?.session?.let { accountKey(it) } == accountKey(session)) signOut()
    }

    private companion object {
        const val DATA_SAVER_KBPS = 96
    }
}
