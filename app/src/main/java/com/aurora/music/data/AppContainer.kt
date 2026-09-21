package com.aurora.music.data

import kotlinx.coroutines.flow.drop

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.SpotifyClient
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sourceErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sourceErrors = _sourceErrors.asSharedFlow()

    val settingsStore = SettingsStore(appContext)
    val audioCache = com.aurora.music.data.cache.AudioCache(appContext, settingsStore, offline = { offlineFlag })
    val appUpdater = com.aurora.music.data.updates.AppUpdater(appContext)
    val extensions = com.aurora.music.extensions.ExtensionManager(appContext, settingsStore, scope)
    val listeningLevels = com.aurora.music.data.listening.ListeningLevelStore(
        java.io.File(appContext.noBackupFilesDir, "listening_levels.json"), settingsStore.processingRoutes)
    val networkOutput = com.aurora.music.playback.network.NetworkOutputManager(appContext)
    val profileImages = ProfileImages(appContext)
    val localProfileAppearance = settingsStore.localProfile.map(profileImages::appearance)
        .flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, ProfileAppearance())
    val playHistory = PlayHistoryStore(appContext)

    val queueStore = QueueStore(appContext)

    val replayGainStore = ReplayGainStore(appContext)

    val localLibrary = LocalLibrary(appContext, gainProvider = { path -> replayGainStore.gainsFor(path) },
        separatorsProvider = { settingsStore.artistSeparators.first() })
    private val localStore = LocalStore(appContext)

    val replayGainScanner = ReplayGainScanner(localLibrary, replayGainStore)

    val tagEditor = TagEditor(appContext)

    val backupManager = BackupManager(settingsStore, localStore, playHistory, appContext, listeningLevels)

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

    val downloadManager: DownloadManager = DownloadManager(
        appContext,
        streamUrlProvider = { id, bitrate, lossless -> backend?.streamUrl(id, bitrate, lossless) },
        downloadBitrateProvider = { downloadBitrate },
        currentServerIdProvider = { currentServerId() },
        resolveSentinel = ::resolveYtSentinel,
        playbackSourceProvider = { song -> backend?.playbackSourceIdentity(song) },
        playbackCollectionProvider = { kind, id, name -> repository.playbackCollectionIdentity(kind, id)?.copy(name = name) },
        copyExtension = extensions::copyAudio,
        copyCached = audioCache::copyTo,
    )

    val sonicStore = SonicStore(appContext)
    val mixStore = com.aurora.music.mix.MixStore(appContext)
    val mixController = com.aurora.music.mix.MixController()
    @OptIn(UnstableApi::class)
    val stemSeparator = com.aurora.music.mix.StemSeparator(appContext, ::resolveYtSentinel)
    val mixAnalyzer = com.aurora.music.mix.MixAnalyzer(appContext, ::resolveYtSentinel) { account, id, vector ->
        sonicStore.put(id, vector, account)
    }
    val sonicEngine by lazy { SonicEngine(sonicStore, { repository.allLibrarySongs(cap = Int.MAX_VALUE) },
        { settingsStore.session.first()?.accountKey().orEmpty() }, mixAnalyzer) }

    val radioBrowser = com.aurora.music.data.remote.RadioBrowserClient()
    val podcastClient = com.aurora.music.data.remote.PodcastClient()

    val artistInfoClient = com.aurora.music.data.remote.ArtistInfoClient()
    val artistInfoStore = ArtistInfoStore(appContext)

    private fun resolveYtSentinel(sentinel: String): String? {
        val uri = runCatching { android.net.Uri.parse(sentinel) }.getOrNull() ?: return null
        return youtubeResolver.resolveSentinel(uri)
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
        playbackSource = PlaybackSourceIdentity.fromSession(localMergeSession, local.albumId,
            com.aurora.music.data.rules.RuleSource.LOCAL_FILE),
    )

    private fun localizedFromDownload(song: Song, local: Song): Song =
        song.copy(streamUrl = local.streamUrl, artworkUrl = local.artworkUrl.ifBlank { song.artworkUrl },
            suffix = local.suffix, bitrateKbps = local.bitrateKbps, sampleRateHz = local.sampleRateHz,
            bitDepth = local.bitDepth,
            playbackSource = local.playbackSource)

    private fun buildBackend(session: Session): MediaBackend = when (session.type) {
        ServerType.JELLYFIN -> JellyfinBackend(JellyfinClient(session), { maxBitrate }, ::localizeSong)
        ServerType.SUBSONIC -> SubsonicBackend(SubsonicClient(session), { maxBitrate }, ::localizeSong)
        ServerType.SPOTIFY -> SpotifyBackend(
            SpotifyClient(session, spotifyClientIdValue, onTokenRefreshed = { tok -> scope.launch { settingsStore.updateToken(tok) } }),
            { maxBitrate }, ::localizeSong,
        )
        ServerType.LOCAL -> LocalBackend(localLibrary, localStore, session)
        ServerType.YOUTUBE_MUSIC -> ReportingMediaBackend(YouTubeMusicBackend(session,
            com.aurora.music.data.remote.YouTubeMusicClient(
                session = { com.aurora.music.data.remote.YouTubeMusicWebSession.decode(youtubeMusicCredentials.read(session.token)) }))) { message ->
            if (lastSession?.accountKey() == session.accountKey() ||
                (unifiedLibraryValue && lastSession?.type?.supportsMergedLibrary == true &&
                    (mergeSourceKeys.isEmpty() || session.accountKey() in mergeSourceKeys))) _sourceErrors.tryEmit(message)
        }
        ServerType.EXTENSION -> extensions.backend(session) { song ->
            val enabled = extensions.entries.value.any { it.component == session.userId && it.enabled }
            val downloaded = if (!enabled) downloadManager.getByOriginalId(song.id)
                ?.takeIf { java.io.File(it.audioPath).isFile } else null
            if (downloaded != null) localizedFromDownload(song, downloaded.toSong()) else localizeSong(song)
        }
    }

    private fun buildActiveBackend(session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>): MediaBackend {
        if (!unified || !session.type.supportsMergedLibrary) return buildBackend(session)
        // empty = all eligible servers MERGE_NONE sentinel = local files only
        val serverSessions = if (mergeKeys == setOf(MERGE_NONE)) emptyList() else (saved + session)
            .filter { it.type.supportsMergedLibrary && it.type != ServerType.LOCAL }
            .filter { mergeKeys.isEmpty() || accountKey(it) in mergeKeys }
            .distinctBy { accountKey(it) }
        val sources = buildList {
            add(LocalBackend(localLibrary, localStore, localMergeSession))
            serverSessions.forEach { add(buildBackend(it)) }
        }
        return MergedBackend(sources, session,
            priority = { if (preferLocalSources) sourcePriorityValue else listOf("stream", "local", "downloaded") },
            downloads = { downloadManager.downloads.value.values.filter { java.io.File(it.audioPath).isFile }.map { it.toSong() } })
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

    val discord = DiscordRpc(appContext, settingsStore, scope)

    val youtubeResolver = com.aurora.music.playback.YoutubeResolver()
    val youtubeMusicCredentials = YouTubeMusicCredentials(appContext)

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

    val repository: MusicRepository = MusicRepository(
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { offlineFlag },
        currentServerIdProvider = { currentServerId() },
        smartPlaylistsProvider = { smartPlaylistsValue },
        smartEngine = smartEngine,
        cachedSongsProvider = { audioCache.songs.value },
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
        scope.launch {
            var first = true
            settingsStore.artistSeparators.collect {
                if (!first) {
                    _libraryReload.value++
                }
                first = false
            }
        }
        scope.launch {
            settingsStore.session.distinctUntilChanged().collect { session ->
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
                sonicEngine.cancel()
                kotlinx.coroutines.withContext(Dispatchers.IO) { sonicStore.selectAccount(session?.accountKey().orEmpty()) }
                if (session != null && settingsStore.sonicAutoAnalyze.first()) sonicEngine.scan()
            }
        }
        scope.launch {
            var first = true
            settingsStore.unifiedLibrary.distinctUntilChanged().collect {
                unifiedLibraryValue = it; rebuildBackend()
                if (!first) _libraryReload.value++
                first = false
            }
        }
        scope.launch {
            settingsStore.savedSessions.distinctUntilChanged().drop(1).collect {
                if (unifiedLibraryValue && lastSession?.type?.supportsMergedLibrary == true) {
                    rebuildBackend()
                    _libraryReload.value++
                }
            }
        }
        scope.launch {
            var first = true
            settingsStore.mergeSources.distinctUntilChanged().collect {
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
            settingsStore.alarmPrefs.distinctUntilChanged().collect { com.aurora.music.playback.AlarmScheduler.apply(appContext, it) }
        }
        scope.launch {
            settingsStore.spotifyClientId.distinctUntilChanged().collect { id ->
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
        if (session.type == ServerType.YOUTUBE_MUSIC) youtubeMusicCredentials.remove(session.token)
        if (backend?.session?.let { accountKey(it) } == accountKey(session)) signOut()
    }

    private companion object {
        const val DATA_SAVER_KBPS = 96
    }
}
