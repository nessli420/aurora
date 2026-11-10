package com.aurora.music.data

import kotlinx.coroutines.flow.debounce
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
import com.aurora.music.data.container.BackendFactory
import com.aurora.music.data.container.SessionCoordinator
import com.aurora.music.data.remote.appClientInfo
import com.aurora.music.data.remote.SpotifyClient
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    val http: okhttp3.OkHttpClient get() = com.aurora.music.data.remote.AuroraHttp.base

    val settingsStore = SettingsStore(appContext)
    // ui reads only, so a first frame starts from stored values; logic keeps reading settingsStore
    val uiPrefs = settingsStore.uiPrefs.stateIn(scope, SharingStarted.Eagerly, UiPrefs())
    val session = settingsStore.session.stateIn(scope, SharingStarted.Eagerly, null)
    val savedSessions = settingsStore.savedSessions.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val pins = settingsStore.pins.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val gesturePrefs = settingsStore.gesturePrefs.stateIn(scope, SharingStarted.Eagerly, GesturePrefs())
    val simpleMode = settingsStore.simpleMode.stateIn(scope, SharingStarted.Eagerly, false)
    val playbackReportingAllowed = kotlinx.coroutines.flow.combine(settingsStore.playbackPrefs, settingsStore.privateSession) { prefs, private ->
        prefs.scrobble && !private
    }.stateIn(scope, SharingStarted.Eagerly, false)
    val playbackReports = PlaybackReportDispatcher(scope, allowed = { playbackReportingAllowed.value }) {
        _sourceErrors.tryEmit(appContext.getString(com.aurora.music.R.string.playback_history_sync_failed))
    }

    val audioCache = com.aurora.music.data.cache.AudioCache(appContext, settingsStore, offline = { offlineFlag })
    val appUpdater = com.aurora.music.data.updates.AppUpdater(appContext, http)
    val extensions = com.aurora.music.extensions.ExtensionManager(appContext, settingsStore, scope)
    val listeningLevels = com.aurora.music.data.listening.ListeningLevelStore(
        java.io.File(appContext.noBackupFilesDir, "listening_levels.json"), settingsStore.processingRoutes)
    val networkOutput = com.aurora.music.playback.network.NetworkOutputManager(appContext)
    val profileImages = ProfileImages(appContext)
    val localProfileAppearance = settingsStore.localProfile.map(profileImages::appearance)
        .flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, ProfileAppearance())
    val playHistory = PlayHistoryStore(appContext.filesDir)
    val listeningDetails by lazy { DetailedListeningStore(appContext) }
    val placesStore by lazy { PlacesStore(appContext) }

    val queueStore = QueueStore(appContext.filesDir)
    val resumePoints = ResumePoints(appContext.filesDir)

    val replayGainStore = ReplayGainStore(appContext.filesDir)
    private val soundcloudPendingLikes = SoundCloudPendingLikes(appContext.filesDir)
    val guestLikes = com.aurora.music.data.GuestLikes(appContext.filesDir)

    val localLibrary = LocalLibrary(appContext, gainProvider = { path -> replayGainStore.gainsFor(path) },
        separatorsProvider = { settingsStore.artistSeparators.first() },
        trackCoversProvider = { settingsStore.trackCovers.first() },
        foldersProvider = { settingsStore.localFolders.first() to settingsStore.localExcludedFolders.first() })
    private val localStore = LocalStore(appContext.filesDir)
    private val localIndexRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    val replayGainScanner = ReplayGainScanner(localLibrary, replayGainStore)

    val tagEditor = TagEditor(appContext)

    val backupManager = BackupManager(settingsStore, localStore, playHistory, appContext.filesDir, appContext.cacheDir, listeningLevels)

    val musicBrainz by lazy { com.aurora.music.data.remote.MusicBrainzClient(http) }

    private val acoustIdKey = settingsStore.acoustIdKey.stateIn(scope, SharingStarted.Eagerly, "")
    val acoustId by lazy { com.aurora.music.data.remote.AcoustIdClient(apiKeyProvider = { acoustIdKey.value }, base = http) }

    val autoEq by lazy { AutoEqRepository(appContext, http) }
    val autoEqController = AutoEqController(settingsStore, scope)

    private val squigBase = settingsStore.squigBaseUrl.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SQUIG_BASE)
    private val squigTarget = settingsStore.squigTarget.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SQUIG_TARGET)
    val squigEq by lazy {
        SquigEqRepository(
            com.aurora.music.data.remote.SquigClient(http),
            baseProvider = { squigBase.value },
            targetProvider = { squigTarget.value },
        )
    }

    @Volatile
    private var maxBitrate: Int = 0

    @Volatile
    private var downloadBitrate: Int = 0

    @Volatile
    private var preferLocalSources: Boolean = true

    private val sourcePriority = settingsStore.sourcePriority.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SOURCE_PRIORITY)

    private val catalogueMatch = settingsStore.catalogueMatch.stateIn(scope, SharingStarted.Eagerly, CatalogueMatchPrefs())

    private val localMergeSession = Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL)

    @Volatile
    var backend: MediaBackend? = null
        private set

    // server base url stamped on downloads so they can be scoped per-server
    private fun currentServerId(): String = backend?.session?.server ?: ""

    private val downloadsDir = java.io.File(appContext.filesDir, "downloads")
    val downloadManager: DownloadManager = DownloadManager(
        downloadsDir, ::androidFileUri, appContext::openUri,
        streamUrlProvider = { id, bitrate, lossless -> backend?.streamUrl(id, bitrate, lossless) },
        downloadBitrateProvider = { downloadBitrate },
        currentServerIdProvider = { currentServerId() },
        resolveSentinel = ::resolveYtSentinel,
        playbackSourceProvider = { song -> backend?.playbackSourceIdentity(song) },
        playbackCollectionProvider = { kind, id, name -> repository.playbackCollectionIdentity(kind, id)?.copy(name = name) },
        copyExtension = extensions::copyAudio,
        copyCached = audioCache::copyTo,
        downloadUrlResolverProvider = { backend?.let { source -> { id, bitrate, lossless -> source.downloadUrl(id, bitrate, lossless) } } },
    )

    private val sourceLocalizer = SourceLocalizer(localMergeSession, downloadManager,
        preferLocal = { preferLocalSources }, priority = { sourcePriority.value },
        isLocalUrl = { it.startsWith("content://") || it.startsWith("file://") }, findLocal = ::findLocalMatch,
        catalogueMatch = { catalogueMatch.value })

    private fun requestLocalIndex() {
        if (!localLibrary.inUse && localIndexRequested.compareAndSet(false, true)) scope.launch { runCatching { localLibrary.ensureLoaded() } }
    }

    private fun findLocalMatch(artist: String, title: String, durationSec: Int): Song? {
        requestLocalIndex()
        return localLibrary.findMatch(artist, title, durationSec)
    }

    // best-source matching needs the on-device index only once a server session will look up local copies
    private suspend fun prefetchLocalIndex() {
        val session = settingsStore.session.first() ?: return
        val order = settingsStore.sourcePriority.first()
        val local = order.indexOf("local")
        val stream = order.indexOf("stream")
        if (session.type != ServerType.LOCAL && local >= 0 && (stream < 0 || local < stream) &&
            settingsStore.preferLocalSources.first()) requestLocalIndex()
    }

    val sonicStore = SonicStore(appContext)
    val mixStore = com.aurora.music.mix.MixStore(appContext)
    val mixController = com.aurora.music.mix.MixController()
    @OptIn(UnstableApi::class)
    val stemSeparator = com.aurora.music.mix.StemSeparator(appContext, ::resolveYtSentinel)
    val mixAnalyzer = com.aurora.music.mix.MixAnalyzer(appContext, ::resolveYtSentinel) { account, id, vector ->
        sonicStore.put(id, vector, account)
    }
    private val canStream: () -> Boolean = { !offlineToggle && networkUp && onWifi && !dataSaver }
    val sonicEngine by lazy { SonicEngine(sonicStore, { repository.allLibrarySongs(cap = Int.MAX_VALUE) },
        { settingsStore.session.first()?.accountKey().orEmpty() }, mixAnalyzer,
        libraryKey = { offline.value to libraryReload.value }, canStream = canStream, context = appContext) }

    val aiDjUnsupported: String? by lazy { AiDjDevice.unsupported(appContext) }
    val aiDjModels by lazy { com.aurora.music.ai.DjModelStore(java.io.File(appContext.filesDir, "ai-dj-model"), http) }
    private val aiDjLlm by lazy { com.aurora.music.ai.LocalLlm({ aiDjModels.model() }, AiDjDevice.threads()) }
    val aiDjMemory by lazy { com.aurora.music.ai.DjMemory(java.io.File(appContext.filesDir, "ai-dj-memory.json")) }
    private val aiDjDelegate = lazy {
        // the device check waits for a ready model so it never runs on main at startup
        com.aurora.music.ai.DjEngine(repository, playHistory, aiDjLlm,
            { aiDjModels.state.value == com.aurora.music.ai.DjModelState.Ready && aiDjUnsupported == null }, { sonicStore.snapshot() },
            { seed, count, pool -> if (sonicStore.get(seed.id) != null) sonicEngine.buildRadio(seed, count, pool) else emptyList() }, aiDjMemory)
    }
    val aiDj by aiDjDelegate
    fun downloadAiDjModel() = aiDjModels.download { !offlineToggle && networkUp && onWifi }
    suspend fun deleteAiDjModel() = aiDjModels.delete { aiDjLlm.release() }
    val aiDjVoiceModels by lazy { com.aurora.music.ai.DjModelStore(java.io.File(appContext.filesDir, "ai-dj-voice"), http, com.aurora.music.ai.DjModelStore.WHISPER) }
    private val aiDjWhisper by lazy { com.aurora.music.ai.LocalWhisper({ aiDjVoiceModels.model() }, AiDjDevice.threads(), idleMs = 0) }
    val aiDjSpeakerModels by lazy { com.aurora.music.ai.DjModelStore(java.io.File(appContext.filesDir, "ai-dj-speaker"), http, com.aurora.music.ai.DjModelStore.SPEAKER) }
    private val aiDjSpeaker by lazy { com.aurora.music.ai.DjSpeaker({ aiDjSpeakerModels.model().takeIf { aiDjUnsupported == null } }, AiDjDevice.threads()) }
    val aiDjVoice by lazy { AiDjSystemVoice(appContext, aiDjSpeaker) { settingsStore.aiDjSpoken.first() } }
    fun downloadAiDjSpeakerModel() = aiDjSpeakerModels.download { !offlineToggle && networkUp && onWifi }
    suspend fun deleteAiDjSpeakerModel() = aiDjSpeakerModels.delete { aiDjSpeaker.release() }
    val aiDjEars by lazy {
        com.aurora.music.ai.WhisperEars(AiDjMicrophone(), aiDjWhisper) { com.aurora.music.localization.AppStrings.context.resources.configuration.locales[0].language }
    }
    val aiDjMode by lazy { com.aurora.music.ai.DjMode(aiDj, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), aiDjVoice, aiDjEars) }
    // set while the playback service runs
    @Volatile var aiDjQueue: com.aurora.music.ai.DjMode.Queue? = null
    fun downloadAiDjVoiceModel() = aiDjVoiceModels.download { !offlineToggle && networkUp && onWifi }
    suspend fun deleteAiDjVoiceModel() = aiDjVoiceModels.delete { aiDjWhisper.release() }

    val radioBrowser by lazy { com.aurora.music.data.remote.RadioBrowserClient(http) }
    val podcastClient by lazy { com.aurora.music.data.remote.PodcastClient(http) }

    val artistInfoClient by lazy { com.aurora.music.data.remote.ArtistInfoClient(http) }
    val artistInfoStore = ArtistInfoStore(appContext.filesDir)

    // soundcloud previews and drm-only tracks play from a youtube match instead
    suspend fun resolveSoundCloud(item: androidx.media3.common.MediaItem): com.aurora.music.playback.YoutubeResolver.PlaybackStream? {
        val id = item.localConfiguration?.uri?.lastPathSegment?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val source = backend as? SoundCloudBackend ?: backends.catalogue(ServerType.SOUNDCLOUD) as? SoundCloudBackend
        source?.playbackUrl(id)?.let { return com.aurora.music.playback.YoutubeResolver.PlaybackStream(it) }
        val meta = item.mediaMetadata
        val query = java.net.URLEncoder.encode("${meta.title ?: ""} ${meta.artist ?: ""}".trim(), "UTF-8")
        val duration = meta.extras?.getInt("aurora.durationSec") ?: 0
        return youtubeResolver.resolveSentinel(android.net.Uri.parse("aurora-yt://sc$id?q=$query&dur=$duration"))
            ?.let { com.aurora.music.playback.YoutubeResolver.PlaybackStream(it) }
    }

    val playlistImporter by lazy { PlaylistImporter(repository, PlaylistLinks(http), backends::catalogue) }

    suspend fun verifySoundCloud(session: Session): Boolean = (backends.build(session) as? SoundCloudBackend)?.verify() == true
    fun soundCloudWaiting(session: Session): Int = soundcloudPendingLikes.waiting(session.userId)

    // adds the songs liked while signed out to the account that took the guest's place
    suspend fun moveGuestLikes(session: Session): Int = backends.build(session)?.let { guestLikes.moveTo(it) } ?: 0

    private fun resolveYtSentinel(sentinel: String): String? {
        val uri = runCatching { android.net.Uri.parse(sentinel) }.getOrNull() ?: return null
        return youtubeResolver.resolveSentinel(uri)
    }

    // the downloaded or on-device copy of a song, when it should play instead of the stream
    fun localCopy(song: Song): Song? = sourceLocalizer.localize(song).takeIf { it.streamUrl != song.streamUrl }

    private val backends = BackendFactory(appClientInfo, settingsStore, sourceLocalizer, localLibrary, localStore, localMergeSession,
        soundcloudPendingLikes, guestLikes = guestLikes, http = { http }, maxBitrate = { maxBitrate },
        youtubeMusicLogin = { youtubeMusicCredentials.read(it) }, signatureTimestamp = { youtubeResolver::signatureTimestamp },
        spotifyWeb = { cookie -> spotifyWeb.account(cookie) }, spotifyLogin = { spotifyCredentials.read(it) },
        soundcloudLogin = { soundcloudCredentials.read(it) }, saveSoundcloudLogin = { token, login -> soundcloudCredentials.update(token, login) },
        soundcloudBrowser = { soundcloudWriter }, reportError = { session, message -> if (sessions.isActive(session)) _sourceErrors.tryEmit(message) },
        platformBackend = ::platformBackend)

    private fun platformBackend(session: Session, localize: (Song) -> Song): MediaBackend? = when (session.type) {
        ServerType.SPOTIFY -> SpotifyBackend(
            SpotifyClient(session, spotifyClientIdValue, onTokenRefreshed = { tok -> scope.launch { settingsStore.updateToken(tok) } }),
            { maxBitrate }, localize,
        )
        ServerType.EXTENSION -> extensions.backend(session) { song ->
            val enabled = extensions.entries.value.any { it.component == session.userId && it.enabled }
            val downloaded = if (!enabled) downloadManager.getByOriginalId(song.id)
                ?.takeIf { java.io.File(it.audioPath).isFile } else null
            if (downloaded != null) sourceLocalizer.fromDownload(song, downloaded.toSong(downloadManager.fileUri)) else localize(song)
        }
        else -> null
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

    val listenBrainz = ListenBrainzScrobbler(settingsStore, scope, com.aurora.music.data.remote.ListenBrainzClient(
        http.newBuilder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()))

    val discord = DiscordRpc(appContext, settingsStore, scope)

    val trackChoices = TrackChoices(appContext.filesDir)
    val youtubeResolver by lazy { com.aurora.music.playback.YoutubeResolver(http) { trackChoices.matchFor(it)?.videoId } }
    val youtubeMusicCredentials = WebCredentials.youtubeMusic(appContext)
    val spotifyCredentials = WebCredentials.spotify(appContext)
    val soundcloudCredentials = WebCredentials.soundcloud(appContext)
    val soundcloudWriter by lazy { SoundCloudWebWriter(appContext) }
    val spotifyWeb by lazy { SpotifyWebBridge(appContext) }
    val youTubeDownloader by lazy {
        YouTubeDownloader(appContext, youtubeResolver, { settingsStore.youTubeDownloadPrefs.first() }, http) {
            settingsStore.savedSessions.first().firstOrNull { it.type == ServerType.YOUTUBE_MUSIC && !it.isGuest }?.let { session ->
                runCatching { com.aurora.music.data.remote.YouTubeMusicWebSession.decode(youtubeMusicCredentials.read(session.token)) }.getOrNull()
            }
        }
    }

    private val lrclibEnabled = settingsStore.lrclibEnabled.stateIn(scope, SharingStarted.Eagerly, true)

    val lyricsRepository by lazy {
        LyricsRepository(backendProvider = { backend }, lrclibEnabledProvider = { lrclibEnabled.value },
            separatorsProvider = { settingsStore.artistSeparators.first() },
            orderProvider = { settingsStore.lyricsSources.first() },
            spotifyAllowed = { type -> settingsStore.mergeFeatures.first().lyricsFor(type) },
            http = http.newBuilder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build())
    }

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

    val vocalAnalysis = VocalAnalysis(appContext, canStream) { uri ->
        youtubeResolver.resolveSentinel(android.net.Uri.parse(uri))
    }

    val lyricsTranslator = LyricsTranslator(appContext) { !offlineToggle && networkUp && onWifi && !dataSaver }

    // 0 = system default
    val preferredAudioDeviceId = MutableStateFlow(0)

    val signalPath = MutableStateFlow(SignalPath())
    val streamFormat = MutableStateFlow<StreamFormat?>(null)

    @Volatile
    private var offlineFlag: Boolean = false

    private val sessions: SessionCoordinator = SessionCoordinator(settingsStore, settingsStore, scope, backends::buildActive,
        backend = { backend }, setBackend = { backend = it },
        rememberRestored = { if (runCatching { settingsStore.savedSessions.first().lastOrNull() }.getOrNull() != it) settingsStore.addSavedSession(it) },
        rememberApplied = { settingsStore.addSavedSession(it) }, onAccountChanged = ::onAccountChanged, forgetLibrary = ::forgetAiDjLibrary,
        // single build so read the stored spotify client id instead of racing its collector
        beforeRebuild = { if (it?.type == ServerType.SPOTIFY) spotifyClientIdValue = settingsStore.spotifyClientId.first() })

    val sessionReady: StateFlow<Boolean?> = sessions.sessionReady
    val accountEpoch: StateFlow<Int> = sessions.accountEpoch
    val libraryReload: StateFlow<Int> = sessions.libraryReload

    private val mediaStoreChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val mediaStoreObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { mediaStoreChanges.tryEmit(Unit) }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun followMediaStore() {
        mediaStoreChanges.debounce(MEDIA_STORE_SETTLE_MS).collect {
            if (localLibrary.inUse && runCatching { localLibrary.syncWithMediaStore() }.getOrDefault(false)) sessions.reloadLibrary()
        }
    }

    suspend fun rescanLocalLibrary(full: Boolean = false, folder: String? = null, force: Boolean = false): LocalRescanResult? {
        val result = if (force || localLibrary.inUse) try { localLibrary.rescan(full, folder) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null } else null
        sessions.reloadLibrary()
        return result
    }
    private fun accountKey(s: Session?): String = s?.accountKey() ?: ""

    fun currentAccountKey(): String = accountKey(backend?.session)

    private val _offline = MutableStateFlow(false)
    // effective offline manual toggle or no connectivity
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _noNetwork = MutableStateFlow(false)
    val noNetwork: StateFlow<Boolean> = _noNetwork.asStateFlow()

    private val smartPlaylists = settingsStore.smartPlaylists.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val smartEngine = SmartPlaylistEngine(playHistory, downloadManager)

    private val artworkHttp = http.newBuilder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    val artworkRepository = com.aurora.music.data.artwork.ArtworkRepository(java.io.File(appContext.cacheDir, "metadata-artwork"),
        appContext::openUri, com.aurora.music.data.artwork.AndroidArtworkImages,
        offline = { offlineToggle || !networkUp }, enabled = { settingsStore.artworkLookupEnabled.first() },
        separators = { settingsStore.artistSeparators.first() },
        client = com.aurora.music.data.artwork.CoverArtClient(artworkHttp), http = artworkHttp)
    val animatedCovers = AnimatedCovers(appContext, settingsStore, scope)
    val storage by lazy { StorageInventory(appContext, this, downloadsDir) }

    val repository: MusicRepository = MusicRepository(
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { offlineFlag },
        currentServerIdProvider = { currentServerId() },
        smartPlaylistsProvider = { smartPlaylists.value },
        smartEngine = smartEngine,
        cachedSongsProvider = { audioCache.songs.value },
        coverUrl = com.aurora.music.data.artwork.ArtworkUrls::cover,
        likeSourcesProvider = { likeAccounts.of(backend, settingsStore.savedSessions.first()) },
        trackChoices = trackChoices,
    )

    // an oauth spotify login refreshes the active session's token, so only the web player one joins as a second account
    private val likeAccounts = LikeAccounts { s -> if (s.type == ServerType.SPOTIFY && s.salt != SpotifyWebBackend.WEB_PLAYER) null else backends.build(s) }

    private fun recomputeOffline() {
        // local-files mode never needs the network so it's never offline
        val local = backend?.session?.type == ServerType.LOCAL
        offlineFlag = !local && (offlineToggle || !networkUp)
        _offline.value = offlineFlag
        _noNetwork.value = !networkUp
    }

    private suspend fun onAccountChanged(session: Session?) {
        recomputeOffline()
        sonicEngine.cancel()
        withContext(Dispatchers.IO) { sonicStore.selectAccount(session?.accountKey().orEmpty()) }
        if (session != null) prefetchLocalIndex()
        if (session != null && settingsStore.sonicAutoAnalyze.first()) sonicEngine.scan()
    }

    private fun forgetAiDjLibrary() { if (aiDjDelegate.isInitialized()) aiDj.forgetLibrary() }

    private fun recomputeBitrate() {
        maxBitrate = if (onWifi) {
            streamWifi
        } else {
            if (dataSaver) (if (streamCellular == 0) DATA_SAVER_KBPS else minOf(streamCellular, DATA_SAVER_KBPS)) else streamCellular
        }
    }

    init {
        runCatching {
            appContext.contentResolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        }
        scope.launch { followMediaStore() }
        scope.launch {
            var first = true
            kotlinx.coroutines.flow.combine(settingsStore.artistSeparators, settingsStore.trackCovers, ::Pair).collect {
                if (!first) {
                    sessions.reloadLibrary()
                }
                first = false
            }
        }
        sessions.start()
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
            settingsStore.dataSaver.collect { dataSaver = it; recomputeBitrate() }
        }
        scope.launch {
            settingsStore.haptics.collect { hapticsEnabled = it }
        }
        scope.launch {
            settingsStore.preferLocalSources.collect { on ->
                if (on && !preferLocalSources) localIndexRequested.set(false)
                preferLocalSources = on
                if (on) prefetchLocalIndex()
            }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(settingsStore.localFolders, settingsStore.localExcludedFolders, ::Pair).drop(1).collect {
                if (localLibrary.inUse && runCatching { localLibrary.refresh() }.getOrDefault(false)) sessions.reloadLibrary()
            }
        }
        scope.launch {
            settingsStore.alarmPrefs.distinctUntilChanged().collect { com.aurora.music.playback.AlarmScheduler.apply(appContext, it) }
        }
        scope.launch {
            var first = true
            settingsStore.spotifyClientId.distinctUntilChanged().collect { id ->
                spotifyClientIdValue = id
                // rebuild an active spotify backend so token refresh uses the up-to-date client id
                if (!first && backend?.session?.type == ServerType.SPOTIFY) sessions.rebuildBackend()
                first = false
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

    suspend fun applySession(session: Session) = sessions.applySession(session)

    suspend fun switchSession(session: Session) = applySession(session)

    suspend fun signOut() = sessions.signOut()

    suspend fun forgetSavedSession(session: Session) {
        settingsStore.removeSavedSession(session)
        if (session.type == ServerType.YOUTUBE_MUSIC) youtubeMusicCredentials.remove(session.token)
        if (session.type == ServerType.SOUNDCLOUD) { backends.forgetSoundCloud(session.token); soundcloudCredentials.remove(session.token) }
        if (session.type == ServerType.SPOTIFY && session.salt == SpotifyWebBackend.WEB_PLAYER) {
            spotifyCredentials.remove(session.token)
            withContext(Dispatchers.Main) {
                spotifyWeb.reset()
                SpotifyWebPlayer.clearCookies()
            }
        }
        if (backend?.session?.let { accountKey(it) } == accountKey(session)) signOut()
    }

    private companion object {
        const val DATA_SAVER_KBPS = 96
        const val MEDIA_STORE_SETTLE_MS = 2500L
    }
}
